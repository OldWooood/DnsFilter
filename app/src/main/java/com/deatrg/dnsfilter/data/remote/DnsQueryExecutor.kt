package com.deatrg.dnsfilter.data.remote

import android.os.SystemClock
import com.deatrg.dnsfilter.AppLog
import com.deatrg.dnsfilter.domain.model.DnsServer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class DnsQueryExecutor(
    private val socketProtector: ((DatagramSocket) -> Unit)? = null
) {

    companion object {
        private const val TAG = "DnsQueryExecutor"
        private const val DNS_RESPONSE_BUFFER_SIZE = 2048
        private const val UDP_SOCKET_POOL_SIZE = 16
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

    private val udpSocketPools = ConcurrentHashMap<String, UdpSocketPool>()
    private val serverAddressCache = ConcurrentHashMap<String, InetAddress>()
    private val inFlightQueries = ConcurrentHashMap<String, CompletableDeferred<DnsQueryResult>>()

    private fun monotonicNowMs(): Long = SystemClock.elapsedRealtime()

    private fun getQueryKey(domain: String, qtype: Int, qclass: Int): String {
        // domain is already lowercased by parseDnsQueryFromPacket
        return "$domain:$qtype:$qclass"
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
        val requestStart = monotonicNowMs()
        val queryKey = getQueryKey(domain, qtype, qclass)
        val activeServers = servers

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
                domain = domain,
                servers = activeServers,
                query = query,
                queryOffset = queryOffset,
                queryLength = queryLength,
                qtype = qtype,
                timeoutMs = timeoutMs
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

    private suspend fun queryUpstream(
        domain: String,
        servers: List<DnsServer>,
        query: ByteArray,
        queryOffset: Int,
        queryLength: Int,
        qtype: Int,
        timeoutMs: Long
    ): DnsQueryResult = coroutineScope {
        val deferreds = servers.map { server ->
            async {
                val startTime = monotonicNowMs()
                val result = queryPlainDns(query, queryOffset, queryLength, server.address, timeoutMs)
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

            if (result.result.success) {
                deferreds.forEach { it.cancel() }
                result.result.responseBytes?.let(::clampPositiveDnsTtlsInPlace)
                AppLog.d(TAG) {
                    "DNS success: domain=$domain qtype=$qtype server=${result.server.name} time=${result.elapsedMs}ms"
                }
                return@coroutineScope DnsQueryResult(
                    success = true,
                    responseBytes = result.result.responseBytes,
                    responseTime = result.elapsedMs,
                    error = null
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

    private suspend fun queryPlainDns(
        request: ByteArray,
        requestOffset: Int,
        requestLength: Int,
        serverAddress: String,
        timeoutMs: Long
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

                wrapper.requestPacket.setData(request, requestOffset, requestLength)
                wrapper.socket.send(wrapper.requestPacket)

                wrapper.responsePacket.setData(wrapper.responseBuffer, 0, wrapper.responseBuffer.size)
                wrapper.responsePacket.length = wrapper.responseBuffer.size
                val responsePacket = wrapper.responsePacket
                wrapper.socket.receive(responsePacket)

                if (!isExpectedResponseSource(responsePacket, expectedAddress)) {
                    wrapper.isValid = false
                    closeUdpSocket(wrapper)
                    DnsQueryResult(false, null, 0, "Unexpected DNS response source")
                } else {
                    val responseBytes = responsePacket.data.copyOfRange(0, responsePacket.length)
                    if (!isValidDnsResponse(request, requestOffset, responseBytes)) {
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
        response: ByteArray
    ): Boolean {
        if (request.size - requestOffset < 12 || response.size < 12) return false

        // Transaction ID match + connected UDP socket (kernel filters source) is sufficient
        if (response[0] != request[requestOffset] || response[1] != request[requestOffset + 1]) {
            return false
        }

        val responseFlags = ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)
        val qrBit = (responseFlags shr 15) and 1
        if (qrBit != 1) return false

        return true
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
        return copy(
            responseBytes = patchResponseForClient(response, query, queryOffset),
            responseTime = responseTime
        )
    }

    fun shutdown() {
        inFlightQueries.values.forEach { it.cancel() }
        inFlightQueries.clear()
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
    val error: String?
)
