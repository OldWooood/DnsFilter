package com.deatrg.dnsfilter.data.remote

import android.util.Base64
import com.deatrg.dnsfilter.AppLog
import com.deatrg.dnsfilter.domain.model.DnsServer
import com.deatrg.dnsfilter.domain.model.DnsServerType
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import okhttp3.*
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.LinkedHashMap

class DnsQueryExecutor(
    private val okHttpClient: OkHttpClient,
    private val socketProtector: ((DatagramSocket) -> Unit)? = null
) {

    companion object {
        private const val TAG = "DnsQueryExecutor"
        private const val DNS_CACHE_SIZE = 4096
        private const val DNS_CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // DNS 响应缓存（LRU，最大 4096 条）
    private val dnsCache = object : LinkedHashMap<String, CachedDnsResult>(DNS_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedDnsResult>?): Boolean {
            return size > DNS_CACHE_SIZE
        }
    }

    private data class CachedDnsResult(
        val responseIp: String,
        val timestamp: Long
    )

    private fun getCacheKey(domain: String, qtype: Int): String = "$domain:$qtype"

    private fun getFromCache(domain: String, qtype: Int): String? {
        val key = getCacheKey(domain, qtype)
        synchronized(dnsCache) {
            val cached = dnsCache[key]
            if (cached != null && System.currentTimeMillis() - cached.timestamp < DNS_CACHE_TTL_MS) {
                return cached.responseIp
            }
            dnsCache.remove(key)
        }
        return null
    }

    private fun putToCache(domain: String, qtype: Int, responseIp: String) {
        val key = getCacheKey(domain, qtype)
        synchronized(dnsCache) {
            dnsCache[key] = CachedDnsResult(responseIp, System.currentTimeMillis())
        }
    }

    suspend fun query(
        domain: String,
        servers: List<DnsServer>,
        qtype: Int = 1,
        timeoutMs: Long = 3000
    ): DnsQueryResult = withContext(Dispatchers.IO) {
        // 先检查缓存
        getFromCache(domain, qtype)?.let { cachedIp ->
            AppLog.d(TAG, "DNS cache hit: domain=$domain qtype=$qtype ip=$cachedIp")
            return@withContext DnsQueryResult(
                success = true,
                responseIp = cachedIp,
                responseTime = 0,
                error = null,
                fromCache = true
            )
        }

        val activeServers = servers.filter { it.isEnabled }
        if (activeServers.isEmpty()) {
            return@withContext DnsQueryResult(
                success = false,
                responseIp = null,
                responseTime = 0,
                error = "No DNS servers configured"
            )
        }

        coroutineScope {
            // Query servers concurrently and return the first successful result
            val deferreds = activeServers.map { server ->
                async {
                    val startTime = System.currentTimeMillis()
                    val result = queryServer(domain, server, qtype, timeoutMs)
                    Pair(result, System.currentTimeMillis() - startTime)
                }
            }.toMutableList()

            var firstError: String? = null
            while (deferreds.isNotEmpty()) {
                val completed = select<Pair<Deferred<Pair<DnsQueryResult, Long>>, Pair<DnsQueryResult, Long>>> {
                    deferreds.forEach { deferred ->
                        deferred.onAwait { result -> Pair(deferred, result) }
                    }
                }
                val result = completed.second
                if (result.first.success) {
                    deferreds.forEach { it.cancel() }
                    // 缓存结果
                    result.first.responseIp?.let { putToCache(domain, qtype, it) }
                    AppLog.d(TAG, "DNS success: domain=$domain qtype=$qtype ip=${result.first.responseIp} time=${result.second}ms")
                    return@coroutineScope DnsQueryResult(
                        success = true,
                        responseIp = result.first.responseIp,
                        responseTime = result.second,
                        error = null,
                        fromCache = false
                    )
                } else if (firstError == null) {
                    firstError = result.first.error
                }
                deferreds.remove(completed.first)
            }

            AppLog.e(TAG, "DNS failed: domain=$domain qtype=$qtype error=$firstError")
            return@coroutineScope DnsQueryResult(
                success = false,
                responseIp = null,
                responseTime = 0,
                error = firstError ?: "All DNS queries failed"
            )
        }
    }

    private suspend fun queryServer(
        domain: String,
        server: DnsServer,
        qtype: Int,
        timeoutMs: Long
    ): DnsQueryResult = when (server.type) {
        DnsServerType.PLAIN -> queryPlainDns(domain, server.address, qtype, timeoutMs)
        DnsServerType.DOH -> queryDoH(domain, server.address, qtype, timeoutMs)
        DnsServerType.DOT -> queryDoT(domain, server.address, qtype, timeoutMs)
    }

    private suspend fun queryPlainDns(
        domain: String,
        serverAddress: String,
        qtype: Int,
        timeoutMs: Long
    ): DnsQueryResult = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socketProtector?.invoke(socket)
            socket.soTimeout = timeoutMs.toInt()

            val request = buildDnsQuery(domain, qtype)
            val address = InetAddress.getByName(serverAddress)
            val requestPacket = DatagramPacket(request, request.size, address, 53)
            socket.send(requestPacket)

            val responseBuffer = ByteArray(512)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(responsePacket)

            // Log raw DNS response for debugging
            AppLog.d(TAG, "Raw DNS response from $serverAddress, length=${responsePacket.length}")
            AppLog.d(TAG, "DNS response bytes: ${responsePacket.data.take(minOf(32, responsePacket.length)).joinToString { String.format("%02X", it) }}")

            val responseIp = parseDnsResponse(responsePacket.data, responsePacket.length, qtype)
            if (responseIp != null) {
                DnsQueryResult(
                    success = true,
                    responseIp = responseIp,
                    responseTime = 0,
                    error = null
                )
            } else {
                DnsQueryResult(
                    success = false,
                    responseIp = null,
                    responseTime = 0,
                    error = "Failed to parse DNS response"
                )
            }
        } catch (e: SocketTimeoutException) {
            DnsQueryResult(success = false, responseIp = null, responseTime = 0, error = "Timeout")
        } catch (e: Exception) {
            DnsQueryResult(success = false, responseIp = null, responseTime = 0, error = e.message)
        } finally {
            socket?.close()
        }
    }

    private suspend fun queryDoH(
        domain: String,
        url: String,
        qtype: Int,
        timeoutMs: Long
    ): DnsQueryResult = suspendCancellableCoroutine { continuation ->
        val request = buildDnsQuery(domain, qtype)
        // Use URL_SAFE_NO_WRAP for DoH
        val encodedQuery = Base64.encodeToString(request, Base64.URL_SAFE or Base64.NO_WRAP)

        val dohUrl = if (url.contains("?")) {
            "$url&dns=$encodedQuery"
        } else {
            "$url?dns=$encodedQuery"
        }

        val requestBuilder = Request.Builder()
            .url(dohUrl)
            .addHeader("Accept", "application/dns-message")
            .get()

        val call = okHttpClient.newCall(requestBuilder.build())

        continuation.invokeOnCancellation {
            call.cancel()
        }

        try {
            call.execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.bytes()
                    if (body != null) {
                        val responseIp = parseDnsResponse(body, body.size, qtype)
                        if (responseIp != null) {
                            continuation.resumeWith(Result.success(
                                DnsQueryResult(
                                    success = true,
                                    responseIp = responseIp,
                                    responseTime = 0,
                                    error = null
                                )
                            ))
                        } else {
                            continuation.resumeWith(Result.success(
                                DnsQueryResult(
                                    success = false,
                                    responseIp = null,
                                    responseTime = 0,
                                    error = "Failed to parse DoH response"
                                )
                            ))
                        }
                    } else {
                        continuation.resumeWith(Result.success(
                            DnsQueryResult(
                                success = false,
                                responseIp = null,
                                responseTime = 0,
                                error = "Empty response"
                            )
                        ))
                    }
                } else {
                    continuation.resumeWith(Result.success(
                        DnsQueryResult(
                            success = false,
                            responseIp = null,
                            responseTime = 0,
                            error = "HTTP ${response.code}"
                        )
                    ))
                }
            }
        } catch (e: IOException) {
            continuation.resumeWith(Result.success(
                DnsQueryResult(
                    success = false,
                    responseIp = null,
                    responseTime = 0,
                    error = e.message
                )
            ))
        }
    }

    private suspend fun queryDoT(
        domain: String,
        serverAddress: String,
        qtype: Int,
        timeoutMs: Long
    ): DnsQueryResult = withContext(Dispatchers.IO) {
        DnsQueryResult(
            success = false,
            responseIp = null,
            responseTime = 0,
            error = "DoT not yet implemented"
        )
    }

    private fun buildDnsQuery(domain: String, qtype: Int): ByteArray {
        val transactionId = byteArrayOf(0x00, 0x01)
        val flags = byteArrayOf(0x01, 0x00)
        val questions = byteArrayOf(0x00, 0x01)
        val answerRRs = byteArrayOf(0x00, 0x00)
        val authorityRRs = byteArrayOf(0x00, 0x00)
        val additionalRRs = byteArrayOf(0x00, 0x00)

        val header = transactionId + flags + questions + answerRRs + authorityRRs + additionalRRs

        val domainParts = domain.split(".")
        val qdlist = domainParts.flatMap { part ->
            listOf(part.length.toByte()) + part.toByteArray().toList()
        }
        val qdcount = qdlist.toByteArray() + byteArrayOf(0x00)

        val queryType = byteArrayOf(((qtype shr 8) and 0xFF).toByte(), (qtype and 0xFF).toByte())
        val queryClass = byteArrayOf(0x00, 0x01)

        return header + qdcount + queryType + queryClass
    }

    private fun parseDnsResponse(data: ByteArray, length: Int, expectedType: Int): String? {
        if (length < 12) return null
        if (expectedType != 1 && expectedType != 28) return null

        val answerCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        if (answerCount == 0) return null

        // Read QNAME
        val questionNameResult = readDnsName(data, 12, length)
        val questionName = questionNameResult?.first ?: return null
        var offset = questionNameResult.second

        // Skip QTYPE (2 bytes) and QCLASS (2 bytes)
        if (offset + 4 > length) return null
        offset += 4

        var currentName = questionName

        // Parse answer section
        for (i in 0 until answerCount) {
            if (offset >= length) break

            val nameResult = readDnsName(data, offset, length) ?: return null
            val name = nameResult.first
            offset = nameResult.second

            if (offset + 10 > length) break

            // TYPE (2 bytes)
            val type = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            offset += 2

            // CLASS (2 bytes)
            offset += 2

            // TTL (4 bytes)
            offset += 4

            // RDLENGTH (2 bytes)
            if (offset + 2 > length) break
            val rdLength = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            offset += 2

            if (offset + rdLength > length) break

            if (type == 5) { // CNAME
                val cnameResult = readDnsName(data, offset, length)
                if (cnameResult != null && name.equals(currentName, ignoreCase = true)) {
                    currentName = cnameResult.first
                }
            } else if (type == expectedType && name.equals(currentName, ignoreCase = true)) {
                if (type == 1 && rdLength == 4) {
                    return "${data[offset].toInt() and 0xFF}.${data[offset + 1].toInt() and 0xFF}.${data[offset + 2].toInt() and 0xFF}.${data[offset + 3].toInt() and 0xFF}"
                }
                if (type == 28 && rdLength == 16) {
                    val addr = data.copyOfRange(offset, offset + 16)
                    return InetAddress.getByAddress(addr).hostAddress
                }
            }

            offset += rdLength
        }

        return null
    }

    private fun readDnsName(data: ByteArray, offset: Int, length: Int): Pair<String, Int>? {
        val parts = mutableListOf<String>()
        var idx = offset
        var jumped = false
        var nextOffset = offset
        var jumps = 0

        while (idx < length) {
            val len = data[idx].toInt() and 0xFF
            if (len == 0) {
                if (!jumped) {
                    nextOffset = idx + 1
                }
                break
            }
            if ((len and 0xC0) == 0xC0) {
                if (idx + 1 >= length) return null
                val pointer = ((len and 0x3F) shl 8) or (data[idx + 1].toInt() and 0xFF)
                if (!jumped) {
                    nextOffset = idx + 2
                }
                idx = pointer
                jumped = true
                jumps++
                if (jumps > 8) return null
                continue
            }
            idx++
            if (idx + len > length) return null
            parts.add(String(data, idx, len))
            idx += len
            if (!jumped) {
                nextOffset = idx
            }
        }

        if (parts.isEmpty()) return null
        return Pair(parts.joinToString("."), nextOffset)
    }

    fun shutdown() {
        scope.cancel()
    }
}

data class DnsQueryResult(
    val success: Boolean,
    val responseIp: String?,
    val responseTime: Long,
    val error: String?,
    val fromCache: Boolean = false
)
