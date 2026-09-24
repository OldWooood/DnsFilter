package com.deatrg.dnsfilter.data.remote

import android.os.SystemClock
import com.deatrg.dnsfilter.AppLog
import com.deatrg.dnsfilter.domain.model.DnsServer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class DnsQueryExecutor(
    private val socketProtector: ((DatagramSocket) -> Unit)? = null,
    private val tcpSocketProtector: ((Socket) -> Unit)? = null
) {

    companion object {
        private const val TAG = "DnsQueryExecutor"
        private const val DNS_RESPONSE_CACHE_SIZE = 4096
        // 4096: covers any answer the client could have advertised via EDNS;
        // anything filling it completely is treated as suspect-truncated and
        // retried over TCP (the kernel truncates oversized datagrams silently).
        private const val DNS_RESPONSE_BUFFER_SIZE = 4096
        private const val UDP_SOCKET_POOL_SIZE = 32
        private const val PREWARM_SOCKETS_PER_SERVER = 2
        private val STALE_WINDOW_MS = STALE_DNS_MAX_WINDOW_SECONDS * 1000L
        private const val PREFETCH_GUARD_MS = 30_000L
        /** Default per-server upstream timeout for foreground queries and prefetch refreshes. */
        const val DEFAULT_QUERY_TIMEOUT_MS = 3000L
        // Blocked upstream receives park a thread each (workers x servers in
        // flight); give them dedicated permits instead of borrowing from the
        // shared 64-thread Dispatchers.IO pool.
        private const val UPSTREAM_IO_PARALLELISM = 256

        @OptIn(ExperimentalCoroutinesApi::class)
        private val upstreamIo = Dispatchers.IO.limitedParallelism(UPSTREAM_IO_PARALLELISM)
    }

    // Each server reuses a small UDP socket pool to avoid repeated create/protect cost.
    private class ReusableUdpSocket(
        val socket: DatagramSocket,
        val responseBuffer: ByteArray = ByteArray(DNS_RESPONSE_BUFFER_SIZE)
    ) {
        val requestPacket: DatagramPacket = DatagramPacket(ByteArray(0), 0)
        val responsePacket: DatagramPacket = DatagramPacket(responseBuffer, responseBuffer.size)

        @Volatile
        var isValid = true
    }

    private class UdpSocketPool {
        val sockets = ArrayBlockingQueue<ReusableUdpSocket>(UDP_SOCKET_POOL_SIZE)
    }

    private data class ServerQueryOutcome(
        val server: DnsServer,
        val result: DnsQueryResult,
        val elapsedMs: Long
    )

    private data class RefreshRequest(
        val key: String,
        val domain: String,
        val servers: List<DnsServer>,
        val query: ByteArray,
        val queryOffset: Int,
        val queryLength: Int,
        val qtype: Int,
        val timeoutMs: Long,
        val cnameCheck: ((String) -> Boolean)?
    )

    private val udpSocketPools = ConcurrentHashMap<String, UdpSocketPool>()
    private val serverAddressCache = ConcurrentHashMap<String, InetAddress>()
    private val inFlightQueries = ConcurrentHashMap<String, CompletableDeferred<DnsQueryResult>>()
    private val responseCache = DnsResponseCache(DNS_RESPONSE_CACHE_SIZE, ::monotonicNowMs)
    private val refreshScope = CoroutineScope(upstreamIo + SupervisorJob())
    private val lastRefreshMs = ConcurrentHashMap<String, Long>()

    private fun monotonicNowMs(): Long = SystemClock.elapsedRealtime()

    /**
     * Cache/in-flight key. The reader thread computes it once per packet and
     * passes it down as [cacheKeyHint] so the key string and the L2 lookup are
     * not built twice on the hot path.
     */
    fun cacheKeyFor(domain: String, qtype: Int, qclass: Int): String {
        // domain is already lowercased by parseDnsQueryFromPacket
        return "$domain:$qtype:$qclass"
    }

    fun getCachedResponseForClient(
        domain: String,
        qtype: Int,
        qclass: Int,
        query: ByteArray,
        queryOffset: Int
    ): ByteArray? = getCachedResponseForClientWithKey(
        key = cacheKeyFor(domain, qtype, qclass),
        query = query,
        queryOffset = queryOffset
    )

    fun getCachedResponseForClientWithKey(
        key: String,
        query: ByteArray,
        queryOffset: Int
    ): ByteArray? {
        val cached = responseCache.get(key) ?: return null
        return patchResponseForClient(cached.response, query, queryOffset).also { response ->
            if (cached.kind == DnsResponseCache.EntryKind.NEGATIVE) {
                ageNegativeDnsTtlsInPlace(response, cached.ageSeconds)
            } else {
                agePositiveDnsTtlsInPlace(response, cached.ageSeconds)
            }
        }
    }

    suspend fun query(
        domain: String,
        servers: List<DnsServer>,
        query: ByteArray,
        queryOffset: Int,
        queryLength: Int,
        qtype: Int = 1,
        qclass: Int = 1,
        timeoutMs: Long = DEFAULT_QUERY_TIMEOUT_MS,
        skipCacheLookup: Boolean = false,
        cacheKeyHint: String? = null,
        cnameBlocklistCheck: ((String) -> Boolean)? = null
    ): DnsQueryResult {
        val requestStart = monotonicNowMs()
        val queryKey = cacheKeyHint ?: cacheKeyFor(domain, qtype, qclass)
        val activeServers = servers

        if (!skipCacheLookup) {
            getCachedResponseForClientWithKey(queryKey, query, queryOffset)?.let { response ->
                AppLog.d(TAG) { "DNS L2 cache hit: domain=$domain qtype=$qtype" }
                maybePrefetch(queryKey, domain, activeServers, query, queryOffset, queryLength,
                    qtype, timeoutMs, cnameBlocklistCheck)
                return DnsQueryResult(
                    success = true,
                    responseBytes = response,
                    responseTime = 0,
                    error = null,
                    fromCache = true
                )
            }
        }

        // Serve stale (RFC 8767) while a background refresh runs: hides
        // network transitions and upstream blips instead of stalling the client.
        responseCache.getStale(queryKey, STALE_WINDOW_MS)?.let { hit ->
            AppLog.d(TAG) { "DNS stale hit: domain=$domain qtype=$qtype" }
            refreshInBackground(queryKey, domain, activeServers, query, queryOffset, queryLength,
                qtype, timeoutMs, cnameBlocklistCheck)
            val response = hit.response.copyOf()
            if (hit.kind == DnsResponseCache.EntryKind.NEGATIVE) {
                ageNegativeDnsTtlsInPlace(response, hit.ageSeconds)
            } else {
                agePositiveDnsTtlsInPlace(response, hit.ageSeconds)
            }
            stampStaleTtlsInPlace(response)
            val patched = patchResponseForClient(response, query, queryOffset)
            return DnsQueryResult(
                success = true,
                responseBytes = patched,
                responseTime = monotonicNowMs() - requestStart,
                error = null,
                fromCache = true,
                stale = true
            )
        }

        // Recent upstream failure (RFC 9520): fail fast instead of hammering.
        if (responseCache.isServfailCached(queryKey)) {
            return DnsQueryResult(
                success = false,
                responseBytes = null,
                responseTime = 0,
                error = "Recent upstream failure (cached)"
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

        val newQuery = CompletableDeferred<DnsQueryResult>()

        val runningQuery = inFlightQueries.putIfAbsent(queryKey, newQuery)
        if (runningQuery != null) {
            AppLog.d(TAG) { "DNS in-flight hit: domain=$domain qtype=$qtype" }
            return runningQuery.await().forClient(
                query = query,
                queryOffset = queryOffset,
                responseTime = monotonicNowMs() - requestStart,
                patchResponse = true
            )
        }

        try {
            val upstreamResult = queryUpstream(
                queryKey = queryKey,
                domain = domain,
                servers = activeServers,
                query = query,
                queryOffset = queryOffset,
                queryLength = queryLength,
                qtype = qtype,
                timeoutMs = timeoutMs,
                cnameBlocklistCheck = cnameBlocklistCheck
            )
            newQuery.complete(upstreamResult)
            return upstreamResult.forClient(
                query = query,
                queryOffset = queryOffset,
                responseTime = monotonicNowMs() - requestStart,
                patchResponse = false
            )
        } catch (e: Throwable) {
            newQuery.completeExceptionally(e)
            throw e
        } finally {
            inFlightQueries.remove(queryKey, newQuery)
        }
    }

    /**
     * Reader-thread hook after an L2 fresh hit: kicks off an Unbound-style
     * background refresh for hot near-expiry entries while serving from cache.
     * Cheap (one lock + map ops + a small copy); safe on the packet-reader
     * hot path. The worker path always calls [query] with skipCacheLookup, so
     * this is the only place prefetch triggers on real VPN traffic.
     */
    fun maybePrefetch(
        queryKey: String,
        domain: String,
        servers: List<DnsServer>,
        query: ByteArray,
        queryOffset: Int,
        queryLength: Int,
        qtype: Int,
        timeoutMs: Long = DEFAULT_QUERY_TIMEOUT_MS,
        cnameBlocklistCheck: ((String) -> Boolean)? = null
    ) {
        if (!responseCache.prefetchHint(queryKey)) return
        AppLog.d(TAG) { "DNS prefetch: domain=$domain qtype=$qtype" }
        refreshInBackground(queryKey, domain, servers, query, queryOffset, queryLength,
            qtype, timeoutMs, cnameBlocklistCheck)
    }

    private fun refreshInBackground(
        queryKey: String,
        domain: String,
        servers: List<DnsServer>,
        query: ByteArray,
        queryOffset: Int,
        queryLength: Int,
        qtype: Int,
        timeoutMs: Long,
        cnameBlocklistCheck: ((String) -> Boolean)?
    ) {
        if (servers.isEmpty()) return
        if (inFlightQueries.containsKey(queryKey)) return
        val now = monotonicNowMs()
        if (now - (lastRefreshMs[queryKey] ?: 0L) < PREFETCH_GUARD_MS) return
        lastRefreshMs[queryKey] = now
        // The caller's packet buffer is recycled; copy the DNS question for the refresh.
        val bytes = query.copyOfRange(queryOffset, queryOffset + queryLength)
        val request = RefreshRequest(queryKey, domain, servers, bytes, 0, bytes.size,
            qtype, timeoutMs, cnameBlocklistCheck)
        refreshScope.launch {
            try {
                queryUpstream(
                    queryKey = request.key,
                    domain = request.domain,
                    servers = request.servers,
                    query = request.query,
                    queryOffset = request.queryOffset,
                    queryLength = request.queryLength,
                    qtype = request.qtype,
                    timeoutMs = request.timeoutMs,
                    cnameBlocklistCheck = request.cnameCheck
                )
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Warms one connected socket per server so the first real queries skip the
     * create + protect() cost. Best-effort; safe to call on every VPN start and
     * every default-network change.
     */
    suspend fun prewarm(servers: List<DnsServer>, perServer: Int = PREWARM_SOCKETS_PER_SERVER) =
        withContext(Dispatchers.IO) {
            servers.distinctBy { it.address }.forEach { server ->
                try {
                    val expected = getServerAddress(server.address)
                    val pool = udpSocketPools.getOrPut(server.address) { UdpSocketPool() }
                    repeat((perServer - pool.sockets.size).coerceAtLeast(0)) {
                        val wrapper = createUdpSocket(expected)
                        if (!pool.sockets.offer(wrapper)) {
                            closeUdpSocket(wrapper)
                        }
                    }
                } catch (e: Exception) {
                    AppLog.w(TAG) { "Prewarm failed for ${server.address}: ${e.message}" }
                }
            }
        }

    private suspend fun queryUpstream(
        queryKey: String,
        domain: String,
        servers: List<DnsServer>,
        query: ByteArray,
        queryOffset: Int,
        queryLength: Int,
        qtype: Int,
        timeoutMs: Long,
        cnameBlocklistCheck: ((String) -> Boolean)? = null
    ): DnsQueryResult = coroutineScope {
        val cacheGeneration = responseCache.generation()
        val deferreds = servers.map { server ->
            async {
                val startTime = monotonicNowMs()
                val udpResult = queryPlainDns(query, queryOffset, queryLength, server.address, timeoutMs)
                val result = if (udpResult.success && udpResult.responseBytes != null &&
                    (isTruncatedResponse(udpResult.responseBytes) || udpResult.udpBufferSaturated)
                ) {
                    // UDP truncated (TC=1, RFC 7766) or receive buffer completely
                    // filled (kernel truncates oversized datagrams silently):
                    // retry the same server over TCP.
                    val tcpResult = queryTcpDns(
                        query, queryOffset, queryLength, server.address, DNS_TCP_TIMEOUT_MS.toLong()
                    )
                    if (tcpResult.success) tcpResult
                    else DnsQueryResult(false, null, 0, "Truncated, TCP fallback failed")
                } else {
                    udpResult
                }
                ServerQueryOutcome(server, result, monotonicNowMs() - startTime)
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
            val responseBytes = result.result.responseBytes

            if (result.result.success && responseBytes != null) {
                deferreds.forEach { it.cancel() }
                val rcode = dnsResponseRcode(responseBytes)
                val answerCount = dnsResponseAnswerCount(responseBytes)
                when {
                    rcode == null || answerCount == null -> {
                        if (firstError == null) firstError = "Malformed DNS response"
                    }
                    rcode == 0 && answerCount > 0 -> {
                        if (cnameBlocklistCheck != null &&
                            extractCnameTargets(responseBytes).any { cnameBlocklistCheck(it) }
                        ) {
                            AppLog.d(TAG) { "DNS CNAME-cloaked block: domain=$domain qtype=$qtype" }
                            return@coroutineScope DnsQueryResult(
                                success = true,
                                responseBytes = responseBytes,
                                responseTime = result.elapsedMs,
                                error = null,
                                blocked = true
                            )
                        }
                        val ttlSeconds = clampPositiveDnsTtlsInPlace(responseBytes, qtype)
                        if (ttlSeconds != null) {
                            responseCache.put(queryKey, responseBytes, ttlSeconds, cacheGeneration)
                        }
                        AppLog.d(TAG) {
                            "DNS success: domain=$domain qtype=$qtype server=${result.server.name} time=${result.elapsedMs}ms"
                        }
                        return@coroutineScope DnsQueryResult(
                            success = true,
                            responseBytes = responseBytes,
                            responseTime = result.elapsedMs,
                            error = null
                        )
                    }
                    (rcode == 3 && answerCount == 0) || (rcode == 0 && answerCount == 0) -> {
                        // NXDOMAIN / NODATA: cache by SOA MINIMUM (RFC 2308).
                        extractNegativeTtlSeconds(responseBytes)?.let { negativeTtl ->
                            responseCache.putNegative(queryKey, responseBytes, negativeTtl, cacheGeneration)
                        }
                        AppLog.d(TAG) {
                            "DNS negative: domain=$domain qtype=$qtype rcode=$rcode server=${result.server.name}"
                        }
                        return@coroutineScope DnsQueryResult(
                            success = true,
                            responseBytes = responseBytes,
                            responseTime = result.elapsedMs,
                            error = null
                        )
                    }
                    rcode == 2 -> {
                        // Upstream SERVFAIL: hand the real answer to this client but
                        // short-circuit repeats briefly (RFC 9520).
                        responseCache.putServfail(queryKey, SERVFAIL_CACHE_TTL_SECONDS)
                        return@coroutineScope DnsQueryResult(
                            success = true,
                            responseBytes = responseBytes,
                            responseTime = result.elapsedMs,
                            error = null
                        )
                    }
                    else -> {
                        if (firstError == null) firstError = "Upstream rcode=$rcode"
                    }
                }
                if (rcode == null || answerCount == null || (rcode != 0 && rcode != 2 && rcode != 3)) {
                    deferreds.remove(completed.first)
                    continue
                }
                // rcode 2/negative paths already returned above; this is unreachable
                // except for defensive fall-through.
                deferreds.remove(completed.first)
            } else {
                if (firstError == null) {
                    firstError = result.result.error
                }
                deferreds.remove(completed.first)
            }
        }

        AppLog.e(TAG) { "DNS failed: domain=$domain qtype=$qtype error=$firstError" }
        responseCache.putServfail(queryKey, SERVFAIL_CACHE_TTL_SECONDS)
        return@coroutineScope DnsQueryResult(
            success = false,
            responseBytes = null,
            responseTime = 0,
            error = firstError ?: "All DNS queries failed"
        )
    }

    private suspend fun queryPlainDns(
        request: ByteArray,
        requestOffset: Int,
        requestLength: Int,
        serverAddress: String,
        timeoutMs: Long
    ): DnsQueryResult = withContext(upstreamIo) {
        val expectedAddress = getServerAddress(serverAddress)
        val wrapper = acquireUdpSocket(serverAddress, expectedAddress)

        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            continuation.invokeOnCancellation {
                // Lost the race: do NOT close the pooled socket. The blocked
                // receive below finishes on its own (this query's late response
                // or the deadline) and hands the socket back to the pool; any
                // leftover datagram is drained by the next receive loop via
                // question-section matching.
                completed.set(true)
            }

            val result = try {
                wrapper.requestPacket.setData(request, requestOffset, requestLength)
                wrapper.socket.send(wrapper.requestPacket)

                receiveMatchingResponse(
                    wrapper, request, requestOffset, requestLength, expectedAddress, timeoutMs
                )
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
            } else {
                // Cancelled while blocked, but the socket survived: hand it back.
                releaseUdpSocket(serverAddress, wrapper)
            }
        }
    }

    /**
     * Drain-receive loop for a pooled connected socket. Skips stale datagrams
     * left by previously cancelled queries and waits until this query's own
     * response arrives or the overall deadline hits. Timeouts do NOT poison
     * the socket, so it stays in the pool.
     */
    private fun receiveMatchingResponse(
        wrapper: ReusableUdpSocket,
        request: ByteArray,
        requestOffset: Int,
        requestLength: Int,
        expectedAddress: InetAddress,
        timeoutMs: Long
    ): DnsQueryResult {
        val buffer = wrapper.responseBuffer
        val responsePacket = wrapper.responsePacket
        val deadlineMs = monotonicNowMs() + timeoutMs
        while (true) {
            val remainingMs = (deadlineMs - monotonicNowMs()).coerceAtLeast(1L)
            wrapper.socket.soTimeout = remainingMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            responsePacket.setData(buffer, 0, buffer.size)
            responsePacket.length = buffer.size
            try {
                wrapper.socket.receive(responsePacket)
            } catch (_: SocketTimeoutException) {
                return DnsQueryResult(false, null, 0, "Timeout")
            }
            if (!isExpectedResponseSource(responsePacket, expectedAddress)) {
                continue
            }
            val length = responsePacket.length
            val responseBytes = buffer.copyOfRange(0, length)
            if (!isValidDnsResponse(request, requestOffset, requestLength, responseBytes)) {
                continue
            }
            return DnsQueryResult(
                success = true,
                responseBytes = responseBytes,
                responseTime = 0,
                error = null,
                udpBufferSaturated = length >= buffer.size
            )
        }
    }

    /**
     * DNS over TCP with the 2-byte length framing from RFC 7766. Used when a
     * UDP response arrives truncated (TC=1); not part of the hot path.
     */
    private suspend fun queryTcpDns(
        request: ByteArray,
        requestOffset: Int,
        requestLength: Int,
        serverAddress: String,
        timeoutMs: Long
    ): DnsQueryResult = withContext(upstreamIo) {
        var socket: Socket? = null
        try {
            val expectedAddress = getServerAddress(serverAddress)
            socket = Socket()
            tcpSocketProtector?.invoke(socket)
            socket.connect(InetSocketAddress(expectedAddress, 53), timeoutMs.toInt())
            socket.soTimeout = timeoutMs.toInt()

            val out = socket.getOutputStream()
            out.write((requestLength shr 8) and 0xFF)
            out.write(requestLength and 0xFF)
            out.write(request, requestOffset, requestLength)
            out.flush()

            val input = socket.getInputStream()
            val hi = input.read()
            val lo = input.read()
            if (hi < 0 || lo < 0) {
                return@withContext DnsQueryResult(false, null, 0, "TCP framing EOF")
            }
            val responseLength = (hi shl 8) or lo
            if (responseLength < 12 || responseLength > 65535) {
                return@withContext DnsQueryResult(false, null, 0, "Bad TCP DNS length")
            }
            val responseBytes = ByteArray(responseLength)
            var read = 0
            while (read < responseLength) {
                val n = input.read(responseBytes, read, responseLength - read)
                if (n < 0) throw EOFException("TCP DNS body truncated")
                read += n
            }
            if (!isValidDnsResponse(request, requestOffset, requestLength, responseBytes)) {
                return@withContext DnsQueryResult(false, null, 0, "Mismatched DNS response")
            }
            DnsQueryResult(success = true, responseBytes = responseBytes, responseTime = 0, error = null)
        } catch (e: SocketTimeoutException) {
            DnsQueryResult(false, null, 0, "TCP timeout")
        } catch (e: Exception) {
            DnsQueryResult(false, null, 0, e.message)
        } finally {
            runCatching { socket?.close() }
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
        requestLength: Int,
        response: ByteArray
    ): Boolean {
        if (requestLength < 12 || response.size < 12) return false

        // Transaction ID match + connected UDP socket (kernel filters source) is sufficient
        if (response[0] != request[requestOffset] || response[1] != request[requestOffset + 1]) {
            return false
        }

        val responseFlags = ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)
        val qrBit = (responseFlags shr 15) and 1
        if (qrBit != 1) return false

        // The question section must match this query: pooled sockets serve many
        // queries, and a stale datagram from a cancelled query can share the
        // 16-bit transaction ID.
        return dnsQuestionSectionMatches(request, requestOffset, requestLength, response)
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
        responseTime: Long,
        patchResponse: Boolean
    ): DnsQueryResult {
        val response = responseBytes
        if (!success || response == null) {
            return copy(responseTime = responseTime)
        }
        if (!patchResponse) {
            return copy(responseTime = responseTime)
        }
        // In-flight coalescing shares one upstream response; CNAME-cloaked hits
        // must stay flagged so every waiter answers NXDOMAIN instead of caching.
        if (blocked) {
            return copy(responseTime = responseTime)
        }
        return copy(
            responseBytes = patchResponseForClient(response, query, queryOffset),
            responseTime = responseTime
        )
    }

    fun shutdown() {
        refreshScope.cancel()
        lastRefreshMs.clear()
        responseCache.clear()
        inFlightQueries.values.forEach { it.cancel() }
        inFlightQueries.clear()
        udpSocketPools.values.forEach { pool ->
            pool.sockets.forEach(::closeUdpSocket)
            pool.sockets.clear()
        }
        udpSocketPools.clear()
    }

    fun clearResponseCache() {
        responseCache.clear()
        // Requests started on the old network must not be joined by new callers.
        inFlightQueries.clear()
    }

    /**
     * Soft invalidation for default-network transitions: in-flight joins are
     * dropped, but L2 entries are KEPT so expired rows can serve stale
     * (RFC 8767) while a background refresh repopulates the cache.
     */
    fun onDefaultNetworkChanged() {
        inFlightQueries.clear()
    }
}

data class DnsQueryResult(
    val success: Boolean,
    val responseBytes: ByteArray?,
    val responseTime: Long,
    val error: String?,
    val fromCache: Boolean = false,
    val stale: Boolean = false,
    /** Positive answer whose CNAME chain hits the blocklist: answer NXDOMAIN. */
    val blocked: Boolean = false,
    /**
     * UDP datagram filled the entire receive buffer: the kernel may have
     * silently truncated it, so the caller retries the same server over TCP.
     */
    val udpBufferSaturated: Boolean = false
)
