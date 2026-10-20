package com.deatrg.dnsfilter.data.remote

import android.os.SystemClock
import com.deatrg.dnsfilter.AppLog
import com.deatrg.dnsfilter.domain.model.DnsServer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap

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
        private const val UDP_SOCKET_POOL_SIZE = 64
        private const val PREWARM_SOCKETS_PER_SERVER = 8
        private val STALE_WINDOW_MS = STALE_DNS_MAX_WINDOW_SECONDS * 1000L
        private const val PREFETCH_GUARD_MS = 30_000L
        /** Default per-server upstream timeout for foreground queries and prefetch refreshes. */
        const val DEFAULT_QUERY_TIMEOUT_MS = 3000L
        // Blocked upstream receives park a thread each (workers x servers in
        // flight). IO views are elastic (they may exceed the shared 64-thread
        // default view on demand), so this bound sizes the worst-case thread
        // count: keep it above slowWorkerCount × enabled servers plus headroom
        // for refresh/prefetch races (32 × 3 = 96, +32 headroom).
        private const val UPSTREAM_IO_PARALLELISM = 128
        /**
         * Fast cancel-poll slice right after dispatch: races usually resolve
         * inside [CANCEL_FAST_POLL_WINDOW_MS], so losing legs release their
         * sockets promptly.
         */
        private const val CANCEL_CHECK_INTERVAL_MS = 50L
        /**
         * Idle slice after [CANCEL_FAST_POLL_WINDOW_MS]: a healthy leg waiting
         * for a slow upstream must not wake up (and construct a
         * SocketTimeoutException) 20x/s. A leg that loses the race later still
         * exits within this slice.
         */
        private const val IDLE_CHECK_INTERVAL_MS = 200L
        /** How long a leg polls at [CANCEL_CHECK_INTERVAL_MS] before backing off. */
        private const val CANCEL_FAST_POLL_WINDOW_MS = 500L

        /** [serveCachedResponseForClient] result: no fresh or stale cache entry (miss). */
        const val CACHED_SERVE_MISS = -1
        /** [serveCachedResponseForClient] result: cached payload exceeds destCapacity. */
        const val CACHED_SERVE_OVERFLOW = -2

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

    /** 解析后的上游端点：地址 + 端口（支持 `ip:port` / `[ipv6]:port` / hostname）。 */
    private data class ServerEndpoint(val address: InetAddress, val port: Int)

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
    @Volatile
    private var isShutdown = false
    private val serverEndpoints = ConcurrentHashMap<String, ServerEndpoint>()
    private val inFlightQueries = ConcurrentHashMap<String, CompletableDeferred<DnsQueryResult>>()
    private val responseCache = DnsResponseCache(DNS_RESPONSE_CACHE_SIZE, ::monotonicNowMs)
    private val refreshScope = CoroutineScope(upstreamIo + SupervisorJob())
    // 竞速腿的宿主：必须与调用方的结构化作用域解耦——输家阻塞在 socket 读里时，
    // coroutineScope 会等它完成才返回，导致首个成功响应被最慢的腿拖住。
    // 调用方在 finally 主动 cancel 自己的腿；腿在下一个轮询切片退出并归还 socket。
    private val raceScope = CoroutineScope(upstreamIo + SupervisorJob())
    private val lastRefreshMs = ConcurrentHashMap<String, Long>()
    private val refreshGuardLock = Any()

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

    /**
     * Reader-thread L2 fast path for FRESH and RFC 8767 STALE entries: copies
     * the cached response into [dest] at [destOffset] — normally the client
     * packet buffer, right where its DNS message lives — so serving a hit costs
     * ONE copy. TTLs are aged and the client's transaction ID / RD bit restored
     * in place. A hot near-expiry entry triggers Unbound-style prefetch, and a
     * stale entry triggers a background refresh (both evaluated in the same
     * cache-lock acquisition and before [dest] is overwritten since dest may be
     * the query itself). Stale answers are stamped to a 30s TTL; negative
     * stale answers also stamp the SOA MINIMUM so RFC 2308 downstream caching
     * still honors that window.
     *
     * @return the DNS payload length written into [dest], [CACHED_SERVE_MISS]
     *   when there is no fresh or stale entry, or [CACHED_SERVE_OVERFLOW] when
     *   the cached payload does not fit [destCapacity] ([dest] left untouched;
     *   the caller answers TC=1).
     *
     * 前置条件由调用方保证并以 require 快速判定：
     * query/reference 窗口至少 3 字节（读 TXID/RD），dest 窗口足以容纳 destCapacity；
     * 当前唯一调用方 processPacket 已保证 length >= dnsStart + 12。
     */
    fun serveCachedResponseForClient(
        key: String,
        query: ByteArray,
        queryOffset: Int,
        queryLength: Int,
        dest: ByteArray,
        destOffset: Int,
        destCapacity: Int,
        domain: String,
        servers: List<DnsServer>,
        qtype: Int,
        cnameBlocklistCheck: ((String) -> Boolean)? = null
    ): Int {
        require(queryOffset >= 0 && query.size - queryOffset >= 3) {
            "query window too small: offset=$queryOffset size=${query.size}"
        }
        require(destOffset in 0..dest.size && destCapacity in 0..(dest.size - destOffset)) {
            "dest window too small: offset=$destOffset capacity=$destCapacity size=${dest.size}"
        }
        val cached = responseCache.getOrStale(key, STALE_WINDOW_MS) ?: return CACHED_SERVE_MISS
        val payload = cached.response

        // Prefetch or stale refresh BEFORE the overflow exit: the old reader path
        // prefetched on every hit regardless of answer size, and the refresh
        // snapshot-copies the request bytes synchronously before any possible dest overwrite.
        if (cached.stale || cached.prefetch) {
            AppLog.d(TAG) { "DNS background refresh (stale=${cached.stale}): domain=$domain qtype=$qtype" }
            refreshInBackground(key, domain, servers, query, queryOffset, queryLength,
                qtype, DEFAULT_QUERY_TIMEOUT_MS, cnameBlocklistCheck)
        }

        if (payload.size > destCapacity) return CACHED_SERVE_OVERFLOW

        // Save the client header fields first: dest === query on the reader path.
        val transactionId0 = query[queryOffset]
        val transactionId1 = query[queryOffset + 1]
        val recursionDesired = query[queryOffset + 2].toInt() and 0x01

        payload.copyInto(dest, destinationOffset = destOffset)
        if (cached.kind == DnsResponseCache.EntryKind.NEGATIVE) {
            ageNegativeDnsTtlsInPlace(dest, cached.ageSeconds, destOffset, payload.size)
        } else {
            agePositiveDnsTtlsInPlace(dest, cached.ageSeconds, destOffset, payload.size)
        }
        if (cached.stale) {
            stampStaleTtlsInPlace(
                dest,
                STALE_DNS_TTL_SECONDS,
                destOffset,
                payload.size,
                stampSoaMinimum = cached.kind == DnsResponseCache.EntryKind.NEGATIVE
            )
        }
        dest[destOffset] = transactionId0
        dest[destOffset + 1] = transactionId1
        dest[destOffset + 2] = ((dest[destOffset + 2].toInt() and 0xFE) or recursionDesired).toByte()
        return payload.size
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
            val hit = responseCache.get(queryKey)
            if (hit != null) {
                AppLog.d(TAG) { "DNS L2 cache hit: domain=$domain qtype=$qtype" }
                // Unbound-style prefetch for hot near-expiry entries; the hint
                // came back inside the same cache-lock acquisition as the hit.
                if (hit.prefetch) {
                    refreshInBackground(queryKey, domain, activeServers, query, queryOffset,
                        queryLength, qtype, timeoutMs, cnameBlocklistCheck)
                }
                val response = patchResponseForClient(hit.response, query, queryOffset)
                if (hit.kind == DnsResponseCache.EntryKind.NEGATIVE) {
                    ageNegativeDnsTtlsInPlace(response, hit.ageSeconds)
                } else {
                    agePositiveDnsTtlsInPlace(response, hit.ageSeconds)
                }
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
            // Single copy: patch TXID/RD while copying hit.response, then age and
            // stamp stale TTLs in place on that copy.
            val response = patchResponseForClient(hit.response, query, queryOffset)
            if (hit.kind == DnsResponseCache.EntryKind.NEGATIVE) {
                ageNegativeDnsTtlsInPlace(response, hit.ageSeconds)
            } else {
                agePositiveDnsTtlsInPlace(response, hit.ageSeconds)
            }
            stampStaleTtlsInPlace(
                response,
                stampSoaMinimum = hit.kind == DnsResponseCache.EntryKind.NEGATIVE
            )
            return DnsQueryResult(
                success = true,
                responseBytes = response,
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
        // Atomic check-and-set: two concurrent stale/prefetch hits must not
        // both pass the guard window and launch duplicate refreshes.
        val now = monotonicNowMs()
        synchronized(refreshGuardLock) {
            if (now - (lastRefreshMs[queryKey] ?: 0L) < PREFETCH_GUARD_MS) return
            lastRefreshMs[queryKey] = now
        }
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
            // 停机后清空的池不要被迟到的预热重新填上。
            if (isShutdown) return@withContext
            servers.distinctBy { it.address }.forEach { server ->
                try {
                    val endpoint = resolveServerEndpoint(server.address)
                    val pool = udpSocketPools.getOrPut(server.address) { UdpSocketPool() }
                    repeat((perServer - pool.sockets.size).coerceAtLeast(0)) {
                        val wrapper = createUdpSocket(endpoint)
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
    ): DnsQueryResult {
        val cacheGeneration = responseCache.generation()
        val outcomes = Channel<ServerQueryOutcome>(capacity = servers.size)

        // 竞速腿跑在 executor 自己的 raceScope 上，而不是调用方的结构化作用域：
        // 输家阻塞在 socket.receive 时，coroutineScope 必须等它完成才肯返回，
        // 会把"首个成功响应"拖到输家超时/迟到为止。这里 finally 主动取消腿，
        // 腿在下一次取消轮询切片（前 500ms 内 ≤50ms，之后 ≤200ms）退出并归还 socket。
        val legs = servers.map { server ->
            raceScope.launch {
                val startTime = monotonicNowMs()
                // 单个服务器失败（地址非法/无法解析/连接错误）转成该服务器的失败结果，
                // 不炸掉整个竞速，让其余服务器继续参与。
                val result = try {
                    val udpResult = queryPlainDns(query, queryOffset, queryLength, server.address, timeoutMs)
                    if (udpResult.success && udpResult.responseBytes != null &&
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DnsQueryResult(false, null, 0, e.message ?: "Upstream error")
                }
                outcomes.trySend(ServerQueryOutcome(server, result, monotonicNowMs() - startTime))
            }
        }

        try {
            var firstError: String? = null
            var pending = legs.size
            while (pending > 0) {
                val outcome = outcomes.receive()
                pending--
                val responseBytes = outcome.result.responseBytes

                if (!outcome.result.success || responseBytes == null) {
                    if (firstError == null) {
                        firstError = outcome.result.error
                    }
                    continue
                }

                val rcode = dnsResponseRcode(responseBytes)
                val answerCount = dnsResponseAnswerCount(responseBytes)
                if (rcode == null || answerCount == null) {
                    if (firstError == null) firstError = "Malformed DNS response"
                    continue
                }
                when {
                    rcode == 0 && answerCount > 0 -> {
                        if (cnameBlocklistCheck != null &&
                            anyCnameTargetMatches(responseBytes, predicate = cnameBlocklistCheck)
                        ) {
                            AppLog.d(TAG) { "DNS CNAME-cloaked block: domain=$domain qtype=$qtype" }
                            return DnsQueryResult(
                                success = true,
                                responseBytes = responseBytes,
                                responseTime = outcome.elapsedMs,
                                error = null,
                                blocked = true
                            )
                        }
                        val ttlSeconds = clampPositiveDnsTtlsInPlace(responseBytes, qtype)
                        if (ttlSeconds != null) {
                            responseCache.put(queryKey, responseBytes, ttlSeconds, cacheGeneration)
                        }
                        AppLog.d(TAG) {
                            "DNS success: domain=$domain qtype=$qtype server=${outcome.server.name} time=${outcome.elapsedMs}ms"
                        }
                        return DnsQueryResult(
                            success = true,
                            responseBytes = responseBytes,
                            responseTime = outcome.elapsedMs,
                            error = null
                        )
                    }
                    (rcode == 3 && answerCount == 0) || (rcode == 0 && answerCount == 0) -> {
                        // NXDOMAIN / NODATA: cache by SOA MINIMUM (RFC 2308).
                        extractNegativeTtlSeconds(responseBytes)?.let { negativeTtl ->
                            responseCache.putNegative(queryKey, responseBytes, negativeTtl, cacheGeneration)
                        }
                        AppLog.d(TAG) {
                            "DNS negative: domain=$domain qtype=$qtype rcode=$rcode server=${outcome.server.name}"
                        }
                        return DnsQueryResult(
                            success = true,
                            responseBytes = responseBytes,
                            responseTime = outcome.elapsedMs,
                            error = null
                        )
                    }
                    rcode == 2 -> {
                        // Upstream SERVFAIL: hand the real answer to this client but
                        // short-circuit repeats briefly (RFC 9520).
                        responseCache.putServfail(queryKey, SERVFAIL_CACHE_TTL_SECONDS)
                        return DnsQueryResult(
                            success = true,
                            responseBytes = responseBytes,
                            responseTime = outcome.elapsedMs,
                            error = null
                        )
                    }
                    else -> {
                        if (firstError == null) firstError = "Upstream rcode=$rcode"
                    }
                }
            }

            AppLog.e(TAG) { "DNS failed: domain=$domain qtype=$qtype error=$firstError" }
            responseCache.putServfail(queryKey, SERVFAIL_CACHE_TTL_SECONDS)
            return DnsQueryResult(
                success = false,
                responseBytes = null,
                responseTime = 0,
                error = firstError ?: "All DNS queries failed"
            )
        } finally {
            // 赢家已定（或全部失败）：取消剩余腿；它们在下一个切片退出并归还 socket。
            legs.forEach { it.cancel() }
            outcomes.close()
        }
    }

    private suspend fun queryPlainDns(
        request: ByteArray,
        requestOffset: Int,
        requestLength: Int,
        serverAddress: String,
        timeoutMs: Long
    ): DnsQueryResult = withContext(upstreamIo) {
        val endpoint = resolveServerEndpoint(serverAddress)
        val wrapper = acquireUdpSocket(serverAddress, endpoint)
        // Cancellation signal for the blocked receive: this withContext job is
        // a child of the race leg, so it turns inactive when the leg loses.
        val raceJob = currentCoroutineContext()[Job]

        try {
            wrapper.requestPacket.setData(request, requestOffset, requestLength)
            wrapper.socket.send(wrapper.requestPacket)

            receiveMatchingResponse(
                wrapper, request, requestOffset, requestLength, endpoint, timeoutMs, raceJob
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            wrapper.isValid = false
            closeUdpSocket(wrapper)
            // 失效端点缓存：hostname 型服务器换地址后，下一次查询会重新解析。
            invalidateServerEndpoint(serverAddress)
            DnsQueryResult(false, null, 0, e.message)
        } finally {
            // Lost the race or completed normally: do NOT close a valid pooled socket.
            // The blocked receive returns at its next cancel-poll slice and hands the
            // socket back to the pool; any late datagram is drained by the next receive
            // loop via question-section matching.
            releaseUdpSocket(serverAddress, wrapper)
        }
    }

    /** True when a losing (cancelled) race leg should stop waiting. */
    private fun isRaceCancelled(raceJob: Job?): Boolean = raceJob?.isActive == false

    /**
     * Drain-receive loop for a pooled connected socket. Skips stale datagrams
     * left by previously cancelled queries and waits until this query's own
     * response arrives or the overall deadline hits. Timeouts do NOT poison
     * the socket, so it stays in the pool.
     *
     * Waits in slices: [CANCEL_CHECK_INTERVAL_MS] during the first
     * [CANCEL_FAST_POLL_WINDOW_MS], then [IDLE_CHECK_INTERVAL_MS], so a losing
     * leg still returns promptly without making a healthy slow query build a
     * timeout exception 20x/s. Once the race is lost ([raceJob] inactive) the
     * loop returns immediately, releasing the thread and the socket without
     * waiting out the full deadline. A late datagram is drained by the next
     * receive loop and rejected by question matching.
     */
    private fun receiveMatchingResponse(
        wrapper: ReusableUdpSocket,
        request: ByteArray,
        requestOffset: Int,
        requestLength: Int,
        endpoint: ServerEndpoint,
        timeoutMs: Long,
        raceJob: Job?
    ): DnsQueryResult {
        val buffer = wrapper.responseBuffer
        val responsePacket = wrapper.responsePacket
        val startMs = monotonicNowMs()
        val deadlineMs = startMs + timeoutMs
        while (true) {
            if (isRaceCancelled(raceJob)) {
                return DnsQueryResult(false, null, 0, "Cancelled")
            }
            val now = monotonicNowMs()
            val remainingMs = (deadlineMs - now).coerceAtLeast(1L)
            val sliceMs = if (now - startMs < CANCEL_FAST_POLL_WINDOW_MS) {
                CANCEL_CHECK_INTERVAL_MS
            } else {
                IDLE_CHECK_INTERVAL_MS
            }
            wrapper.socket.soTimeout = remainingMs.coerceAtMost(sliceMs).toInt()
            responsePacket.setData(buffer, 0, buffer.size)
            responsePacket.length = buffer.size
            try {
                wrapper.socket.receive(responsePacket)
            } catch (_: SocketTimeoutException) {
                if (monotonicNowMs() >= deadlineMs) {
                    return DnsQueryResult(false, null, 0, "Timeout")
                }
                continue
            }
            if (!isExpectedResponseSource(responsePacket, endpoint)) {
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
     *
     * Blocking reads run in adaptive slices ([CANCEL_CHECK_INTERVAL_MS], then
     * [IDLE_CHECK_INTERVAL_MS]) tied to a single overall deadline, so a losing
     * TCP leg observes cancellation shortly after the race ends instead of
     * pinning its thread for the full timeout.
     */
    private suspend fun queryTcpDns(
        request: ByteArray,
        requestOffset: Int,
        requestLength: Int,
        serverAddress: String,
        timeoutMs: Long
    ): DnsQueryResult = withContext(upstreamIo) {
        var socket: Socket? = null
        val raceJob = currentCoroutineContext()[Job]
        try {
            val endpoint = resolveServerEndpoint(serverAddress)
            socket = Socket()
            tcpSocketProtector?.invoke(socket)
            socket.connect(InetSocketAddress(endpoint.address, endpoint.port), timeoutMs.toInt())

            val startMs = monotonicNowMs()
            val deadlineMs = startMs + timeoutMs
            val out = socket.getOutputStream()
            out.write((requestLength shr 8) and 0xFF)
            out.write(requestLength and 0xFF)
            out.write(request, requestOffset, requestLength)
            out.flush()

            val input = socket.getInputStream()
            val lengthBytes = ByteArray(2)
            if (!readTcpFully(input, lengthBytes, 0, 2, socket, startMs, deadlineMs, raceJob)) {
                return@withContext DnsQueryResult(
                    false, null, 0, tcpReadFailure("TCP framing", raceJob, deadlineMs)
                )
            }
            val responseLength =
                ((lengthBytes[0].toInt() and 0xFF) shl 8) or (lengthBytes[1].toInt() and 0xFF)
            if (responseLength < 12 || responseLength > 65535) {
                return@withContext DnsQueryResult(false, null, 0, "Bad TCP DNS length")
            }
            val responseBytes = ByteArray(responseLength)
            if (!readTcpFully(input, responseBytes, 0, responseLength, socket, startMs, deadlineMs, raceJob)) {
                return@withContext DnsQueryResult(
                    false, null, 0, tcpReadFailure("TCP body", raceJob, deadlineMs)
                )
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

    /** Error label for a failed sliced TCP read. */
    private fun tcpReadFailure(part: String, raceJob: Job?, deadlineMs: Long): String = when {
        isRaceCancelled(raceJob) -> "TCP cancelled"
        monotonicNowMs() >= deadlineMs -> "TCP timeout"
        else -> "$part EOF"
    }

    /**
     * Reads exactly [length] bytes into [target], polling for cancellation on
     * adaptive slices ([startMs] anchors the fast window) and respecting
     * [deadlineMs]. Returns false on EOF, deadline expiry, or cancellation.
     */
    private fun readTcpFully(
        input: InputStream,
        target: ByteArray,
        offset: Int,
        length: Int,
        socket: Socket,
        startMs: Long,
        deadlineMs: Long,
        raceJob: Job?
    ): Boolean {
        var read = 0
        while (read < length) {
            if (isRaceCancelled(raceJob)) return false
            val now = monotonicNowMs()
            val remainingMs = deadlineMs - now
            if (remainingMs <= 0L) return false
            val sliceMs = if (now - startMs < CANCEL_FAST_POLL_WINDOW_MS) {
                CANCEL_CHECK_INTERVAL_MS
            } else {
                IDLE_CHECK_INTERVAL_MS
            }
            socket.soTimeout = minOf(remainingMs, sliceMs).toInt()
            val n = try {
                input.read(target, offset + read, length - read)
            } catch (_: SocketTimeoutException) {
                continue
            }
            if (n < 0) return false
            read += n
        }
        return true
    }

    /**
     * 解析并缓存上游端点。连接/发送失败时由 [invalidateServerEndpoint] 失效，
     * hostname 型服务器换地址后下一次查询会重新解析。
     */
    private fun resolveServerEndpoint(serverAddress: String): ServerEndpoint {
        return serverEndpoints[serverAddress] ?: buildServerEndpoint(serverAddress).also {
            serverEndpoints[serverAddress] = it
        }
    }

    private fun buildServerEndpoint(serverAddress: String): ServerEndpoint {
        val (host, port) = DnsServer.parseAddress(serverAddress)
            ?: throw IllegalArgumentException("Invalid DNS server address: $serverAddress")
        return ServerEndpoint(InetAddress.getByName(host), port)
    }

    /** 失效端点缓存：下次查询重新解析（仅 hostname 型会真正重新走 DNS）。 */
    private fun invalidateServerEndpoint(serverAddress: String) {
        serverEndpoints.remove(serverAddress)
    }

    private fun isExpectedResponseSource(
        responsePacket: DatagramPacket,
        endpoint: ServerEndpoint
    ): Boolean {
        return responsePacket.port == endpoint.port && responsePacket.address == endpoint.address
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

    private fun acquireUdpSocket(serverAddress: String, endpoint: ServerEndpoint): ReusableUdpSocket {
        val pool = udpSocketPools.getOrPut(serverAddress) { UdpSocketPool() }
        return pool.sockets.poll() ?: createUdpSocket(endpoint)
    }

    private fun releaseUdpSocket(serverAddress: String, wrapper: ReusableUdpSocket) {
        if (!wrapper.isValid) return

        // 停机后归还的 socket（排水复用让竞速输家在 shutdown 之后才结束 receive）：
        // 直接关闭，不要用 getOrPut 重建已被清空的池子把 socket 停进去。
        if (isShutdown) {
            closeUdpSocket(wrapper)
            return
        }

        val pool = udpSocketPools.getOrPut(serverAddress) { UdpSocketPool() }
        if (!pool.sockets.offer(wrapper)) {
            closeUdpSocket(wrapper)
        }
    }

    private fun createUdpSocket(endpoint: ServerEndpoint): ReusableUdpSocket {
        val socket = DatagramSocket()
        socketProtector?.invoke(socket)
        socket.connect(endpoint.address, endpoint.port)
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
        // 先置位：排水复用下，竞速输家的 socket 会在 shutdown 之后才结束 receive
        // 并归还，releaseUdpSocket 需要知道直接关闭而不是回池。
        isShutdown = true
        refreshScope.cancel()
        // raceScope 故意不取消：stop 过程中 runDnsLoop 的 NonCancellable 排水仍会
        // 处理排队查询并调用 query()，腿必须还能运行，否则 worker join 永久挂起。
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
