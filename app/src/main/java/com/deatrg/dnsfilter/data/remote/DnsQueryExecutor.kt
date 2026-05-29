package com.deatrg.dnsfilter.data.remote

import com.deatrg.dnsfilter.AppLog
import com.deatrg.dnsfilter.domain.model.DnsServer
import com.deatrg.dnsfilter.data.remote.parseDnsQuestion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

class DnsQueryExecutor(
    private val socketProtector: ((DatagramSocket) -> Unit)? = null
) {

    companion object {
        private const val TAG = "DnsQueryExecutor"
        private const val DNS_CACHE_SIZE = 16384
        private const val DNS_CACHE_MIN_TTL_MS = 30 * 1000L
        private const val DNS_CACHE_MAX_TTL_MS = 60 * 60 * 1000L
        private const val DNS_CACHE_STALE_MS = 5 * 60 * 1000L
        private const val DNS_NEGATIVE_CACHE_MIN_TTL_MS = 30 * 1000L
        private const val DNS_NEGATIVE_CACHE_MAX_TTL_MS = 2 * 60 * 1000L
        private const val DNS_NEGATIVE_CACHE_STALE_MS = 30 * 1000L
        private const val PREFETCH_MIN_HITS = 3
        private const val PREFETCH_REMAINING_TTL_MS = 10 * 1000L
        private const val PREFETCH_COOLDOWN_MS = 30 * 1000L
        private const val DNS_RESPONSE_BUFFER_SIZE = 2048
        private const val UDP_SOCKET_POOL_SIZE = 4
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Each server reuses a small UDP socket pool to avoid repeated create/protect cost.
    private class ReusableUdpSocket(
        val socket: DatagramSocket,
        val responseBuffer: ByteArray = ByteArray(DNS_RESPONSE_BUFFER_SIZE)
    ) {
        @Volatile
        var isValid = true
    }

    private class UdpSocketPool {
        val sockets = ArrayBlockingQueue<ReusableUdpSocket>(UDP_SOCKET_POOL_SIZE)
    }

    private class CachedDnsResponse(
        val response: ByteArray,
        val expiresAtMs: Long,
        val staleUntilMs: Long,
        val isNegative: Boolean
    ) {
        val hitCount = AtomicInteger(0)

        @Volatile
        var lastRefreshStartedAtMs: Long = 0
    }

    private data class CacheLookup(
        val response: ByteArray,
        val isStale: Boolean,
        val cached: CachedDnsResponse
    )

    private data class CachePolicy(
        val ttlMs: Long,
        val staleMs: Long,
        val isNegative: Boolean
    )

    private data class DnsRecordHeader(
        val type: Int,
        val ttlSeconds: Long,
        val rdataOffset: Int,
        val rdataLength: Int,
        val nextOffset: Int
    )

    private data class ServerQueryOutcome(
        val server: DnsServer,
        val result: DnsQueryResult,
        val elapsedMs: Long
    )

    private val udpSocketPools = ConcurrentHashMap<String, UdpSocketPool>()
    private val serverAddressCache = ConcurrentHashMap<String, InetAddress>()

    // Response cache uses upstream TTLs with bounded stale fallback.
    // ConcurrentHashMap keeps hot-path reads lock-free.
    private val dnsCache = ConcurrentHashMap<String, CachedDnsResponse>()
    private val inFlightQueries = ConcurrentHashMap<String, CompletableDeferred<DnsQueryResult>>()
    private val staleRefreshes = ConcurrentHashMap.newKeySet<String>()

    init {
        scope.launch {
            while (true) {
                delay(600_000L)
                pruneCache(System.currentTimeMillis())
            }
        }
    }

    private fun normalizeDomain(domain: String): String {
        return domain.lowercase(Locale.ROOT).trimEnd('.')
    }

    private fun getCacheKey(domain: String, qtype: Int, qclass: Int): String {
        return "${normalizeDomain(domain)}:$qtype:$qclass"
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private fun readUInt32(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }

    fun getCachedResponseRaw(
        domain: String,
        qtype: Int,
        qclass: Int,
        servers: List<DnsServer> = emptyList(),
        query: ByteArray? = null,
        queryOffset: Int = 0,
        queryLength: Int = 0,
        timeoutMs: Long = 3000
    ): ByteArray? {
        val key = getCacheKey(domain, qtype, qclass)
        val cached = dnsCache[key] ?: return null
        val now = System.currentTimeMillis()
        if (now < cached.expiresAtMs) {
            cached.hitCount.incrementAndGet()
            if (query != null && queryLength > 0) {
                maybeStartPrefetch(
                    cacheKey = key,
                    domain = domain,
                    cached = cached,
                    servers = servers.filter { it.isEnabled },
                    query = query,
                    queryOffset = queryOffset,
                    queryLength = queryLength,
                    qtype = qtype,
                    qclass = qclass,
                    timeoutMs = timeoutMs,
                    now = now
                )
            }
            return cached.response
        }
        if (now > cached.staleUntilMs && dnsCache.remove(key, cached)) {
            staleRefreshes.remove(key)
        }
        return null
    }

    private fun getCacheLookup(
        domain: String,
        qtype: Int,
        qclass: Int,
        query: ByteArray,
        queryOffset: Int
    ): CacheLookup? {
        val key = getCacheKey(domain, qtype, qclass)
        val cached = dnsCache[key] ?: return null
        val now = System.currentTimeMillis()
        if (now < cached.expiresAtMs) {
            cached.hitCount.incrementAndGet()
            return CacheLookup(patchResponseForClient(cached.response, query, queryOffset), isStale = false, cached)
        }
        if (now <= cached.staleUntilMs) {
            cached.hitCount.incrementAndGet()
            return CacheLookup(patchResponseForClient(cached.response, query, queryOffset), isStale = true, cached)
        }
        if (dnsCache.remove(key, cached)) {
            staleRefreshes.remove(key)
        }
        return null
    }

    private fun putToCache(
        cacheKey: String,
        response: ByteArray
    ) {
        val policy = getCachePolicy(response) ?: return
        val now = System.currentTimeMillis()
        dnsCache[cacheKey] = CachedDnsResponse(
            response = response.copyOf(),
            expiresAtMs = now + policy.ttlMs,
            staleUntilMs = now + policy.ttlMs + policy.staleMs,
            isNegative = policy.isNegative
        )
        staleRefreshes.remove(cacheKey)
        if (dnsCache.size > DNS_CACHE_SIZE) {
            pruneCache(now)
        }
    }

    private fun pruneCache(now: Long) {
        dnsCache.entries.removeIf { now > it.value.staleUntilMs }

        val overflow = dnsCache.size - DNS_CACHE_SIZE
        if (overflow <= 0) return

        dnsCache.entries
            .sortedBy { it.value.expiresAtMs }
            .take(overflow)
            .forEach { entry ->
                if (dnsCache.remove(entry.key, entry.value)) {
                    staleRefreshes.remove(entry.key)
                }
            }
    }

    private fun getCachePolicy(response: ByteArray): CachePolicy? {
        if (response.size < 12) return null

        val flags = readUInt16(response, 2)
        val rcode = flags and 0x0F
        if (rcode != 0 && rcode != 3) return null

        val questionCount = readUInt16(response, 4)
        val answerCount = readUInt16(response, 6)
        val authorityCount = readUInt16(response, 8)
        val recordsOffset = skipQuestionSection(response, questionCount) ?: return null

        if (rcode == 3 || answerCount == 0) {
            val ttlSeconds = findNegativeTtlSeconds(response, recordsOffset, answerCount, authorityCount)
                ?: TimeUnit.MILLISECONDS.toSeconds(DNS_NEGATIVE_CACHE_MIN_TTL_MS)
            val ttlMs = TimeUnit.SECONDS.toMillis(ttlSeconds)
                .coerceIn(DNS_NEGATIVE_CACHE_MIN_TTL_MS, DNS_NEGATIVE_CACHE_MAX_TTL_MS)
            return CachePolicy(ttlMs, DNS_NEGATIVE_CACHE_STALE_MS, isNegative = true)
        }

        val ttlSeconds = findMinAnswerTtlSeconds(response, recordsOffset, answerCount) ?: return null
        val ttlMs = TimeUnit.SECONDS.toMillis(ttlSeconds)
            .coerceIn(DNS_CACHE_MIN_TTL_MS, DNS_CACHE_MAX_TTL_MS)
        return CachePolicy(ttlMs, DNS_CACHE_STALE_MS, isNegative = false)
    }

    private fun skipQuestionSection(response: ByteArray, questionCount: Int): Int? {
        var offset = 12
        repeat(questionCount) {
            val name = readDnsName(response, offset, response.size) ?: return null
            offset = name.second + 4
            if (offset > response.size) return null
        }
        return offset
    }

    private fun findMinAnswerTtlSeconds(
        response: ByteArray,
        recordsOffset: Int,
        answerCount: Int
    ): Long? {
        var offset = recordsOffset
        var minTtl: Long? = null
        repeat(answerCount) {
            val record = readDnsRecordHeader(response, offset) ?: return null
            minTtl = minOfNullable(minTtl, record.ttlSeconds)
            offset = record.nextOffset
        }
        return minTtl
    }

    private fun findNegativeTtlSeconds(
        response: ByteArray,
        recordsOffset: Int,
        answerCount: Int,
        authorityCount: Int
    ): Long? {
        var offset = recordsOffset
        repeat(answerCount) {
            val record = readDnsRecordHeader(response, offset) ?: return null
            offset = record.nextOffset
        }

        var minTtl: Long? = null
        repeat(authorityCount) {
            val record = readDnsRecordHeader(response, offset) ?: return null
            if (record.type == 6) {
                val soaMinimum = readSoaMinimumTtl(response, record)
                minTtl = minOfNullable(minTtl, minOf(record.ttlSeconds, soaMinimum ?: record.ttlSeconds))
            }
            offset = record.nextOffset
        }
        return minTtl
    }

    private fun readDnsRecordHeader(response: ByteArray, offset: Int): DnsRecordHeader? {
        val name = readDnsName(response, offset, response.size) ?: return null
        val headerOffset = name.second
        if (headerOffset + 10 > response.size) return null

        val type = readUInt16(response, headerOffset)
        val ttlSeconds = readUInt32(response, headerOffset + 4)
        val rdataLength = readUInt16(response, headerOffset + 8)
        val rdataOffset = headerOffset + 10
        val nextOffset = rdataOffset + rdataLength
        if (nextOffset > response.size) return null

        return DnsRecordHeader(
            type = type,
            ttlSeconds = ttlSeconds,
            rdataOffset = rdataOffset,
            rdataLength = rdataLength,
            nextOffset = nextOffset
        )
    }

    private fun readSoaMinimumTtl(response: ByteArray, record: DnsRecordHeader): Long? {
        val rdataEnd = record.rdataOffset + record.rdataLength
        val mname = readDnsName(response, record.rdataOffset, response.size) ?: return null
        if (mname.second > rdataEnd) return null
        val rname = readDnsName(response, mname.second, response.size) ?: return null
        if (rname.second > rdataEnd) return null

        val minimumOffset = rname.second + 16
        if (minimumOffset + 4 > rdataEnd) return null
        return readUInt32(response, minimumOffset)
    }

    private fun minOfNullable(current: Long?, candidate: Long): Long {
        return current?.let { minOf(it, candidate) } ?: candidate
    }

    suspend fun query(
        domain: String,
        servers: List<DnsServer>,
        query: ByteArray,
        qtype: Int = 1,
        qclass: Int = 1,
        timeoutMs: Long = 3000
    ): DnsQueryResult {
        return query(
            domain = domain,
            servers = servers,
            query = query,
            queryOffset = 0,
            queryLength = query.size,
            qtype = qtype,
            qclass = qclass,
            timeoutMs = timeoutMs
        )
    }

    suspend fun query(
        domain: String,
        servers: List<DnsServer>,
        query: ByteArray,
        queryOffset: Int,
        queryLength: Int,
        qtype: Int = 1,
        qclass: Int = 1,
        timeoutMs: Long = 3000
    ): DnsQueryResult {
        val requestStart = System.currentTimeMillis()
        val cacheKey = getCacheKey(domain, qtype, qclass)
        val activeServers = servers.filter { it.isEnabled }

        val cacheLookup = getCacheLookup(domain, qtype, qclass, query, queryOffset)
        if (cacheLookup?.isStale == false) {
            maybeStartPrefetch(
                cacheKey = cacheKey,
                domain = domain,
                cached = cacheLookup.cached,
                servers = activeServers,
                query = query,
                queryOffset = queryOffset,
                queryLength = queryLength,
                qtype = qtype,
                qclass = qclass,
                timeoutMs = timeoutMs,
                now = System.currentTimeMillis()
            )
            AppLog.d(TAG) { "DNS cache hit: domain=$domain qtype=$qtype" }
            return DnsQueryResult(
                success = true,
                responseBytes = cacheLookup.response,
                responseTime = 0,
                error = null,
                fromCache = true
            )
        }

        if (cacheLookup?.isStale == true) {
            if (activeServers.isNotEmpty()) {
                startStaleRefresh(
                    cacheKey = cacheKey,
                    domain = domain,
                    servers = activeServers,
                    query = query,
                    queryOffset = queryOffset,
                    queryLength = queryLength,
                    qtype = qtype,
                    qclass = qclass,
                    timeoutMs = timeoutMs
                )
            }
            AppLog.d(TAG) { "DNS stale cache hit: domain=$domain qtype=$qtype" }
            return DnsQueryResult(
                success = true,
                responseBytes = cacheLookup.response,
                responseTime = 0,
                error = null,
                fromCache = true
            )
        }

        if (activeServers.isEmpty()) {
            return DnsQueryResult(
                success = false,
                responseBytes = null,
                responseTime = 0,
                error = "No DNS servers configured"
            )
        }

        val requestBytes = query.copyOfRange(queryOffset, queryOffset + queryLength)
        val newQuery = CompletableDeferred<DnsQueryResult>()

        val runningQuery = inFlightQueries.putIfAbsent(cacheKey, newQuery)
        if (runningQuery != null) {
            AppLog.d(TAG) { "DNS in-flight hit: domain=$domain qtype=$qtype" }
            return runningQuery.await().forClient(
                query = query,
                queryOffset = queryOffset,
                responseTime = System.currentTimeMillis() - requestStart
            )
        }

        try {
            val upstreamResult = queryUpstream(
                cacheKey = cacheKey,
                domain = domain,
                servers = activeServers,
                query = requestBytes,
                queryOffset = 0,
                queryLength = requestBytes.size,
                qtype = qtype,
                qclass = qclass,
                timeoutMs = timeoutMs
            )
            newQuery.complete(upstreamResult)
            return upstreamResult.forClient(
                query = query,
                queryOffset = queryOffset,
                responseTime = System.currentTimeMillis() - requestStart
            )
        } catch (e: Throwable) {
            newQuery.completeExceptionally(e)
            throw e
        } finally {
            inFlightQueries.remove(cacheKey, newQuery)
        }
    }

    private suspend fun queryUpstream(
        cacheKey: String,
        domain: String,
        servers: List<DnsServer>,
        query: ByteArray,
        queryOffset: Int,
        queryLength: Int,
        qtype: Int,
        qclass: Int,
        timeoutMs: Long
    ): DnsQueryResult = coroutineScope {
        val deferreds = servers.map { server ->
            async {
                val startTime = System.currentTimeMillis()
                val result = queryPlainDns(query, queryOffset, queryLength, server.address, timeoutMs, domain, qtype, qclass)
                ServerQueryOutcome(server, result, System.currentTimeMillis() - startTime)
            }
        }.toMutableList()

        var firstError: String? = null
        while (deferreds.isNotEmpty()) {
            val completed = select<Pair<kotlinx.coroutines.Deferred<ServerQueryOutcome>, ServerQueryOutcome>> {
                deferreds.forEach { deferred ->
                    deferred.onAwait { result -> Pair(deferred, result) }
                }
            }
            val result = completed.second

            if (result.result.success) {
                deferreds.forEach { it.cancel() }
                result.result.responseBytes?.let { putToCache(cacheKey, it) }
                AppLog.d(TAG) {
                    "DNS success: domain=$domain qtype=$qtype server=${result.server.name} time=${result.elapsedMs}ms"
                }
                return@coroutineScope DnsQueryResult(
                    success = true,
                    responseBytes = result.result.responseBytes,
                    responseTime = result.elapsedMs,
                    error = null,
                    fromCache = false
                )
            } else if (firstError == null) {
                firstError = result.result.error
            }
            deferreds.remove(completed.first)
        }

        AppLog.e(TAG) { "DNS failed: domain=$domain qtype=$qtype error=$firstError" }
        return@coroutineScope DnsQueryResult(
            success = false,
            responseBytes = null,
            responseTime = 0,
            error = firstError ?: "All DNS queries failed"
        )
    }

    private fun startStaleRefresh(
        cacheKey: String,
        domain: String,
        servers: List<DnsServer>,
        query: ByteArray,
        queryOffset: Int,
        queryLength: Int,
        qtype: Int,
        qclass: Int,
        timeoutMs: Long
    ) {
        if (!staleRefreshes.add(cacheKey)) return

        val requestBytes = query.copyOfRange(queryOffset, queryOffset + queryLength)
        scope.launch {
            try {
                queryUpstream(
                    cacheKey = cacheKey,
                    domain = domain,
                    servers = servers,
                    query = requestBytes,
                    queryOffset = 0,
                    queryLength = requestBytes.size,
                    qtype = qtype,
                    qclass = qclass,
                    timeoutMs = timeoutMs
                )
            } finally {
                staleRefreshes.remove(cacheKey)
            }
        }
    }

    private fun maybeStartPrefetch(
        cacheKey: String,
        domain: String,
        cached: CachedDnsResponse,
        servers: List<DnsServer>,
        query: ByteArray,
        queryOffset: Int,
        queryLength: Int,
        qtype: Int,
        qclass: Int,
        timeoutMs: Long,
        now: Long
    ) {
        if (cached.isNegative || servers.isEmpty()) return
        if (cached.hitCount.get() < PREFETCH_MIN_HITS) return
        if (cached.expiresAtMs - now > PREFETCH_REMAINING_TTL_MS) return
        if (now - cached.lastRefreshStartedAtMs < PREFETCH_COOLDOWN_MS) return

        cached.lastRefreshStartedAtMs = now
        startStaleRefresh(
            cacheKey = cacheKey,
            domain = domain,
            servers = servers,
            query = query,
            queryOffset = queryOffset,
            queryLength = queryLength,
            qtype = qtype,
            qclass = qclass,
            timeoutMs = timeoutMs
        )
    }

    private suspend fun queryPlainDns(
        request: ByteArray,
        requestOffset: Int,
        requestLength: Int,
        serverAddress: String,
        timeoutMs: Long,
        expectedDomain: String,
        expectedQtype: Int,
        expectedQclass: Int
    ): DnsQueryResult = withContext(Dispatchers.IO) {
        val expectedAddress = getServerAddress(serverAddress)
        val wrapper = acquireUdpSocket(serverAddress, expectedAddress)

        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) {
                    wrapper.isValid = false
                    closeUdpSocket(wrapper)
                }
            }

            val result = try {
                wrapper.socket.soTimeout = timeoutMs.toInt()

                val requestPacket = DatagramPacket(request, requestOffset, requestLength)
                wrapper.socket.send(requestPacket)

                val responsePacket = DatagramPacket(wrapper.responseBuffer, wrapper.responseBuffer.size)
                wrapper.socket.receive(responsePacket)

                if (!isExpectedResponseSource(responsePacket, expectedAddress)) {
                    wrapper.isValid = false
                    closeUdpSocket(wrapper)
                    DnsQueryResult(false, null, 0, "Unexpected DNS response source")
                } else {
                    val responseBytes = responsePacket.data.copyOfRange(0, responsePacket.length)
                    if (!isValidDnsResponse(request, requestOffset, responseBytes, expectedDomain, expectedQtype, expectedQclass)) {
                        wrapper.isValid = false
                        closeUdpSocket(wrapper)
                        DnsQueryResult(false, null, 0, "Mismatched DNS response")
                    } else {
                        DnsQueryResult(
                            success = true,
                            responseBytes = responseBytes,
                            responseTime = 0,
                            error = null
                        )
                    }
                }
            } catch (e: SocketTimeoutException) {
                wrapper.isValid = false
                closeUdpSocket(wrapper)
                DnsQueryResult(false, null, 0, "Timeout")
            } catch (e: Exception) {
                wrapper.isValid = false
                closeUdpSocket(wrapper)
                DnsQueryResult(false, null, 0, e.message)
            }

            if (completed.compareAndSet(false, true)) {
                releaseUdpSocket(serverAddress, wrapper)
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
        }
    }

    private fun getServerAddress(serverAddress: String): InetAddress {
        return serverAddressCache.getOrPut(serverAddress) {
            InetAddress.getByName(serverAddress)
        }
    }

    private fun isExpectedResponseSource(
        responsePacket: DatagramPacket,
        expectedAddress: InetAddress
    ): Boolean {
        return responsePacket.port == 53 && responsePacket.address == expectedAddress
    }

    private fun isValidDnsResponse(
        request: ByteArray,
        requestOffset: Int,
        response: ByteArray,
        expectedDomain: String,
        expectedQtype: Int,
        expectedQclass: Int
    ): Boolean {
        if (request.size - requestOffset < 12 || response.size < 12) return false

        if (response[0] != request[requestOffset] || response[1] != request[requestOffset + 1]) {
            return false
        }

        val responseFlags = ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)
        val qrBit = (responseFlags shr 15) and 1
        if (qrBit != 1) return false

        val responseQuestion = parseDnsQuestion(response) ?: return false

        return normalizeDomain(expectedDomain) == normalizeDomain(responseQuestion.domain) &&
            expectedQtype == responseQuestion.qtype &&
            expectedQclass == responseQuestion.qclass
    }

    private fun acquireUdpSocket(serverAddress: String, expectedAddress: InetAddress): ReusableUdpSocket {
        val pool = udpSocketPools.getOrPut(serverAddress) { UdpSocketPool() }
        return pool.sockets.poll() ?: createUdpSocket(expectedAddress)
    }

    private fun releaseUdpSocket(serverAddress: String, wrapper: ReusableUdpSocket) {
        if (!wrapper.isValid) return

        val pool = udpSocketPools.getOrPut(serverAddress) { UdpSocketPool() }
        if (!pool.sockets.offer(wrapper)) {
            closeUdpSocket(wrapper)
        }
    }

    private fun createUdpSocket(expectedAddress: InetAddress): ReusableUdpSocket {
        val socket = DatagramSocket()
        socketProtector?.invoke(socket)
        socket.connect(expectedAddress, 53)
        return ReusableUdpSocket(socket)
    }

    private fun closeUdpSocket(wrapper: ReusableUdpSocket) {
        try {
            wrapper.socket.close()
        } catch (_: Exception) {
        }
    }

    private fun patchResponseForClient(
        response: ByteArray,
        query: ByteArray,
        queryOffset: Int
    ): ByteArray {
        val patched = response.copyOf()
        if (patched.size >= 2 && query.size - queryOffset >= 2) {
            patched[0] = query[queryOffset]
            patched[1] = query[queryOffset + 1]
        }
        if (patched.size > 2 && query.size - queryOffset > 2) {
            patched[2] = ((patched[2].toInt() and 0xFE) or (query[queryOffset + 2].toInt() and 0x01)).toByte()
        }
        return patched
    }

    private fun DnsQueryResult.forClient(
        query: ByteArray,
        queryOffset: Int,
        responseTime: Long
    ): DnsQueryResult {
        val response = responseBytes
        if (!success || response == null) {
            return copy(responseTime = responseTime)
        }
        return copy(
            responseBytes = patchResponseForClient(response, query, queryOffset),
            responseTime = responseTime
        )
    }

    fun shutdown() {
        scope.cancel()
        inFlightQueries.values.forEach { it.cancel() }
        inFlightQueries.clear()
        staleRefreshes.clear()
        udpSocketPools.values.forEach { pool ->
            pool.sockets.forEach(::closeUdpSocket)
            pool.sockets.clear()
        }
        udpSocketPools.clear()
    }
}

data class DnsQueryResult(
    val success: Boolean,
    val responseBytes: ByteArray?,
    val responseTime: Long,
    val error: String?,
    val fromCache: Boolean = false
)
