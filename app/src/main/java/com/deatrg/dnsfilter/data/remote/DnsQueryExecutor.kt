package com.deatrg.dnsfilter.data.remote

import com.deatrg.dnsfilter.AppLog
import com.deatrg.dnsfilter.domain.model.DnsServer
import com.deatrg.dnsfilter.domain.model.DnsServerType
import com.deatrg.dnsfilter.data.remote.DnsQuestion
import com.deatrg.dnsfilter.data.remote.readDnsName
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.util.LinkedHashMap
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
        private const val DEFAULT_SERVER_RTT_MS = 180L
        private const val FAILURE_PENALTY_MS = 300L
        private val DNS_MESSAGE_MEDIA_TYPE = "application/dns-message".toMediaType()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 每个服务器复用一个 UDP socket，减少重复创建和 VPN protect 开销
    private class ReusableUdpSocket(
        val socket: DatagramSocket,
        val lock: Mutex = Mutex()
    ) {
        @Volatile
        var isValid = true
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

    private val udpSockets = ConcurrentHashMap<String, ReusableUdpSocket>()
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
        return getFromCache(domain, qtype, qclass, query)
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
        query: ByteArray
    ): ByteArray? {
        val key = getCacheKey(domain, qtype, qclass)
        val cached = dnsCache[key]
        if (cached != null) {
            if (System.currentTimeMillis() - cached.timestamp < DNS_CACHE_TTL_MS) {
                return patchResponseForClient(cached.response, query)
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
    ): DnsQueryResult = withContext(Dispatchers.IO) {
        getFromCache(domain, qtype, qclass, query)?.let { cachedResponse ->
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
                    val result = queryServer(query, server, timeoutMs)
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
        server: DnsServer,
        timeoutMs: Long
    ): DnsQueryResult = when (server.type) {
        DnsServerType.PLAIN -> queryPlainDns(request, server.address, timeoutMs)
        DnsServerType.DOH -> queryDoH(request, server.address, timeoutMs)
        DnsServerType.DOT -> queryDoT(server.address, timeoutMs)
    }

    private suspend fun queryPlainDns(
        request: ByteArray,
        serverAddress: String,
        timeoutMs: Long
    ): DnsQueryResult {
        val expectedAddress = getServerAddress(serverAddress)
        val wrapper = udpSockets.getOrPut(serverAddress) {
            val socket = DatagramSocket()
            socketProtector?.invoke(socket)
            socket.connect(expectedAddress, 53)
            ReusableUdpSocket(socket)
        }

        return wrapper.lock.withLock {
            if (!wrapper.isValid) {
                udpSockets.remove(serverAddress, wrapper)
                return@withLock queryPlainDnsFresh(request, expectedAddress, timeoutMs)
            }

            try {
                wrapper.socket.soTimeout = timeoutMs.toInt()

                val requestPacket = DatagramPacket(request, request.size)
                wrapper.socket.send(requestPacket)

                val responseBuffer = ByteArray(2048)
                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                wrapper.socket.receive(responsePacket)

                if (!isExpectedResponseSource(responsePacket, expectedAddress)) {
                    wrapper.isValid = false
                    try {
                        wrapper.socket.close()
                    } catch (_: Exception) {
                    }
                    udpSockets.remove(serverAddress, wrapper)
                    return@withLock DnsQueryResult(false, null, 0, "Unexpected DNS response source")
                }

                val responseBytes = responsePacket.data.copyOfRange(0, responsePacket.length)
                if (!isValidDnsResponse(request, responseBytes)) {
                    wrapper.isValid = false
                    try {
                        wrapper.socket.close()
                    } catch (_: Exception) {
                    }
                    udpSockets.remove(serverAddress, wrapper)
                    return@withLock DnsQueryResult(false, null, 0, "Mismatched DNS response")
                }

                DnsQueryResult(
                    success = true,
                    responseBytes = responseBytes,
                    responseTime = 0,
                    error = null
                )
            } catch (e: SocketTimeoutException) {
                wrapper.isValid = false
                try {
                    wrapper.socket.close()
                } catch (_: Exception) {
                }
                udpSockets.remove(serverAddress, wrapper)
                DnsQueryResult(false, null, 0, "Timeout")
            } catch (e: Exception) {
                wrapper.isValid = false
                try {
                    wrapper.socket.close()
                } catch (_: Exception) {
                }
                udpSockets.remove(serverAddress, wrapper)
                DnsQueryResult(false, null, 0, e.message)
            }
        }
    }

    private fun queryPlainDnsFresh(
        request: ByteArray,
        expectedAddress: InetAddress,
        timeoutMs: Long
    ): DnsQueryResult {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socketProtector?.invoke(socket)
            socket.connect(expectedAddress, 53)
            socket.soTimeout = timeoutMs.toInt()

            val requestPacket = DatagramPacket(request, request.size)
            socket.send(requestPacket)

            val responseBuffer = ByteArray(2048)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(responsePacket)

            if (!isExpectedResponseSource(responsePacket, expectedAddress)) {
                return DnsQueryResult(false, null, 0, "Unexpected DNS response source")
            }

            val responseBytes = responsePacket.data.copyOfRange(0, responsePacket.length)
            return if (isValidDnsResponse(request, responseBytes)) {
                DnsQueryResult(true, responseBytes, 0, null)
            } else {
                DnsQueryResult(false, null, 0, "Mismatched DNS response")
            }
        } catch (e: SocketTimeoutException) {
            return DnsQueryResult(false, null, 0, "Timeout")
        } catch (e: Exception) {
            return DnsQueryResult(false, null, 0, e.message)
        } finally {
            socket?.close()
        }
    }

    private suspend fun queryDoH(
        request: ByteArray,
        url: String,
        timeoutMs: Long = 3000
    ): DnsQueryResult = withContext(Dispatchers.IO) {
        val httpRequest = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/dns-message")
            .addHeader("Content-Type", "application/dns-message")
            .post(request.toRequestBody(DNS_MESSAGE_MEDIA_TYPE))
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

                if (!isValidDnsResponse(request, body)) {
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
        response: ByteArray
    ): Boolean {
        if (request.size < 12 || response.size < 12) return false

        if (response[0] != request[0] || response[1] != request[1]) {
            return false
        }

        val responseFlags = ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)
        val qrBit = (responseFlags shr 15) and 1
        if (qrBit != 1) return false

        val requestQuestion = parseDnsQuestion(request) ?: return false
        val responseQuestion = parseDnsQuestion(response) ?: return false

        return requestQuestion.domain.equals(responseQuestion.domain, ignoreCase = true) &&
            requestQuestion.qtype == responseQuestion.qtype &&
            requestQuestion.qclass == responseQuestion.qclass
    }

    private fun patchResponseForClient(
        response: ByteArray,
        query: ByteArray
    ): ByteArray {
        val patched = response.copyOf()
        if (patched.size >= 2 && query.size >= 2) {
            patched[0] = query[0]
            patched[1] = query[1]
        }
        if (patched.size > 2 && query.size > 2) {
            patched[2] = ((patched[2].toInt() and 0xFE) or (query[2].toInt() and 0x01)).toByte()
        }
        return patched
    }

    fun shutdown() {
        scope.cancel()
        udpSockets.values.forEach {
            try {
                it.socket.close()
            } catch (_: Exception) {
            }
        }
        udpSockets.clear()
    }
}

data class DnsQueryResult(
    val success: Boolean,
    val responseBytes: ByteArray?,
    val responseTime: Long,
    val error: String?,
    val fromCache: Boolean = false
)
