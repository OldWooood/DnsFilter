package com.deatrg.dnsfilter.data.remote

import com.deatrg.dnsfilter.AppLog
import com.deatrg.dnsfilter.domain.model.DnsServer
import com.deatrg.dnsfilter.domain.model.DnsServerType
import com.deatrg.dnsfilter.data.remote.parseDnsQuestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DnsQueryExecutor(
    private val okHttpClient: OkHttpClient,
    private val socketProtector: ((DatagramSocket) -> Unit)? = null
) {

    companion object {
        private const val TAG = "DnsQueryExecutor"
        private const val DNS_CACHE_SIZE = 16384
        private const val DNS_CACHE_TTL_MS = 60 * 60 * 1000L // 1 hour
        private const val DNS_RESPONSE_BUFFER_SIZE = 2048
        private const val UDP_SOCKET_POOL_SIZE = 4
        private const val DEFAULT_SERVER_RTT_MS = 180L
        private const val FAILURE_PENALTY_MS = 300L
        private val DNS_MESSAGE_MEDIA_TYPE = "application/dns-message".toMediaType()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 每个服务器复用多个 UDP socket，减少重复创建/protect 开销，同时允许冷查询并发。
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

    private data class CachedDnsResponse(
        val response: ByteArray,
        val timestamp: Long
    )

    private data class ServerStats(
        var ewmaRttMs: Double = DEFAULT_SERVER_RTT_MS.toDouble(),
        var consecutiveFailures: Int = 0
    )

    private data class ServerQueryOutcome(
        val server: DnsServer,
        val result: DnsQueryResult,
        val elapsedMs: Long
    )

    private val udpSocketPools = ConcurrentHashMap<String, UdpSocketPool>()
    private val serverAddressCache = ConcurrentHashMap<String, InetAddress>()
    private val serverStats = ConcurrentHashMap<String, ServerStats>()

    // DNS 响应缓存（最大 16384 条，TTL 10 分钟）
    // 用 ConcurrentHashMap 替代 LinkedHashMap + synchronized，读操作完全无锁
    private val dnsCache = ConcurrentHashMap<String, CachedDnsResponse>()

    init {
        scope.launch {
            while (true) {
                delay(600_000L)
                val now = System.currentTimeMillis()
                dnsCache.entries.removeIf { now - it.value.timestamp > DNS_CACHE_TTL_MS }
            }
        }
    }

    private fun getCacheKey(domain: String, qtype: Int, qclass: Int): String = "$domain:$qtype:$qclass"

    fun queryCache(
        domain: String,
        qtype: Int,
        qclass: Int,
        query: ByteArray
    ): ByteArray? {
        return getFromCache(domain, qtype, qclass, query, 0)
    }

    fun getCachedResponseRaw(
        domain: String,
        qtype: Int,
        qclass: Int
    ): ByteArray? {
        val key = getCacheKey(domain, qtype, qclass)
        val cached = dnsCache[key] ?: return null
        if (System.currentTimeMillis() - cached.timestamp < DNS_CACHE_TTL_MS) {
            return cached.response
        }
        dnsCache.remove(key)
        return null
    }

    private fun getFromCache(
        domain: String,
        qtype: Int,
        qclass: Int,
        query: ByteArray,
        queryOffset: Int
    ): ByteArray? {
        val key = getCacheKey(domain, qtype, qclass)
        val cached = dnsCache[key]
        if (cached != null) {
            if (System.currentTimeMillis() - cached.timestamp < DNS_CACHE_TTL_MS) {
                return patchResponseForClient(cached.response, query, queryOffset)
            }
            dnsCache.remove(key)
        }
        return null
    }

    private fun putToCache(
        domain: String,
        qtype: Int,
        qclass: Int,
        response: ByteArray
    ) {
        val key = getCacheKey(domain, qtype, qclass)
        if (dnsCache.size > DNS_CACHE_SIZE) {
            val now = System.currentTimeMillis()
            dnsCache.entries.removeIf { now - it.value.timestamp > DNS_CACHE_TTL_MS }
        }
        dnsCache[key] = CachedDnsResponse(response.copyOf(), System.currentTimeMillis())
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
    ): DnsQueryResult = withContext(Dispatchers.IO) {
        getFromCache(domain, qtype, qclass, query, queryOffset)?.let { cachedResponse ->
            AppLog.d(TAG) { "DNS cache hit: domain=$domain qtype=$qtype" }
            return@withContext DnsQueryResult(
                success = true,
                responseBytes = cachedResponse,
                responseTime = 0,
                error = null,
                fromCache = true
            )
        }

        val activeServers = servers.filter { it.isEnabled }
        if (activeServers.isEmpty()) {
            return@withContext DnsQueryResult(
                success = false,
                responseBytes = null,
                responseTime = 0,
                error = "No DNS servers configured"
            )
        }

        coroutineScope {
            val deferreds = activeServers.map { server ->
                async {
                    val startTime = System.currentTimeMillis()
                    val result = queryServer(query, queryOffset, queryLength, server, timeoutMs, domain, qtype, qclass)
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
                recordServerResult(result.server, result.elapsedMs, result.result.success)

                if (result.result.success) {
                    deferreds.forEach { it.cancel() }
                    result.result.responseBytes?.let { putToCache(domain, qtype, qclass, it) }
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
    }

    private suspend fun queryServer(
        request: ByteArray,
        requestOffset: Int,
        requestLength: Int,
        server: DnsServer,
        timeoutMs: Long,
        expectedDomain: String,
        expectedQtype: Int,
        expectedQclass: Int
    ): DnsQueryResult = when (server.type) {
        DnsServerType.PLAIN -> queryPlainDns(request, requestOffset, requestLength, server.address, timeoutMs, expectedDomain, expectedQtype, expectedQclass)
        DnsServerType.DOH -> queryDoH(request, requestOffset, requestLength, server.address, timeoutMs, expectedDomain, expectedQtype, expectedQclass)
        DnsServerType.DOT -> queryDoT(server.address, timeoutMs)
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
    ): DnsQueryResult {
        val expectedAddress = getServerAddress(serverAddress)
        val wrapper = acquireUdpSocket(serverAddress, expectedAddress)

        return try {
            wrapper.socket.soTimeout = timeoutMs.toInt()

            val requestPacket = DatagramPacket(request, requestOffset, requestLength)
            wrapper.socket.send(requestPacket)

            val responsePacket = DatagramPacket(wrapper.responseBuffer, wrapper.responseBuffer.size)
            wrapper.socket.receive(responsePacket)

            if (!isExpectedResponseSource(responsePacket, expectedAddress)) {
                wrapper.isValid = false
                closeUdpSocket(wrapper)
                return DnsQueryResult(false, null, 0, "Unexpected DNS response source")
            }

            val responseBytes = responsePacket.data.copyOfRange(0, responsePacket.length)
            if (!isValidDnsResponse(request, requestOffset, responseBytes, expectedDomain, expectedQtype, expectedQclass)) {
                wrapper.isValid = false
                closeUdpSocket(wrapper)
                return DnsQueryResult(false, null, 0, "Mismatched DNS response")
            }

            DnsQueryResult(
                success = true,
                responseBytes = responseBytes,
                responseTime = 0,
                error = null
            )
        } catch (e: SocketTimeoutException) {
            wrapper.isValid = false
            closeUdpSocket(wrapper)
            DnsQueryResult(false, null, 0, "Timeout")
        } catch (e: Exception) {
            wrapper.isValid = false
            closeUdpSocket(wrapper)
            DnsQueryResult(false, null, 0, e.message)
        } finally {
            releaseUdpSocket(serverAddress, wrapper)
        }
    }

    private suspend fun queryDoH(
        request: ByteArray,
        requestOffset: Int,
        requestLength: Int,
        url: String,
        timeoutMs: Long = 3000,
        expectedDomain: String,
        expectedQtype: Int,
        expectedQclass: Int
    ): DnsQueryResult = withContext(Dispatchers.IO) {
        val httpRequest = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/dns-message")
            .addHeader("Content-Type", "application/dns-message")
            .post(request.toRequestBody(DNS_MESSAGE_MEDIA_TYPE, requestOffset, requestLength))
            .build()

        try {
            val call = okHttpClient.newCall(httpRequest)
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext DnsQueryResult(false, null, 0, "HTTP ${response.code}")
                }

                val body = response.body?.bytes()
                    ?: return@withContext DnsQueryResult(false, null, 0, "Empty response")

                if (!isValidDnsResponse(request, requestOffset, body, expectedDomain, expectedQtype, expectedQclass)) {
                    return@withContext DnsQueryResult(false, null, 0, "Mismatched DoH response")
                }

                DnsQueryResult(
                    success = true,
                    responseBytes = body,
                    responseTime = 0,
                    error = null
                )
            }
        } catch (e: IOException) {
            DnsQueryResult(false, null, 0, e.message)
        }
    }

    private suspend fun queryDoT(
        serverAddress: String,
        timeoutMs: Long
    ): DnsQueryResult = withContext(Dispatchers.IO) {
        DnsQueryResult(
            success = false,
            responseBytes = null,
            responseTime = 0,
            error = "DoT not yet implemented for $serverAddress within ${timeoutMs}ms"
        )
    }

    private fun getServerAddress(serverAddress: String): InetAddress {
        return serverAddressCache.getOrPut(serverAddress) {
            InetAddress.getByName(serverAddress)
        }
    }

    private fun recordServerResult(server: DnsServer, elapsedMs: Long, success: Boolean) {
        val stats = serverStats.getOrPut(server.address) { ServerStats() }
        synchronized(stats) {
            if (success) {
                val measured = elapsedMs.coerceAtLeast(1).toDouble()
                stats.ewmaRttMs = stats.ewmaRttMs * 0.7 + measured * 0.3
                stats.consecutiveFailures = 0
            } else {
                stats.consecutiveFailures = (stats.consecutiveFailures + 1).coerceAtMost(8)
                stats.ewmaRttMs = (stats.ewmaRttMs + FAILURE_PENALTY_MS).coerceAtMost(2_000.0)
            }
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

        return expectedDomain.equals(responseQuestion.domain, ignoreCase = true) &&
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

    fun shutdown() {
        scope.cancel()
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
