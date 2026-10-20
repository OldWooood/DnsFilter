package com.deatrg.dnsfilter.service

import android.app.Notification
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.deatrg.dnsfilter.AppLog
import com.deatrg.dnsfilter.ServiceLocator
import com.deatrg.dnsfilter.data.local.StatisticsBuffer
import com.deatrg.dnsfilter.data.remote.DomainFilter
import com.deatrg.dnsfilter.data.remote.DnsQueryExecutor
import com.deatrg.dnsfilter.data.remote.DnsQueryResult
import com.deatrg.dnsfilter.data.remote.DnsQuestion
import com.deatrg.dnsfilter.data.remote.DNS_RCODE_SERVFAIL
import com.deatrg.dnsfilter.data.remote.MutableDnsQuestion
import com.deatrg.dnsfilter.data.remote.minAnswerTtlSeconds
import com.deatrg.dnsfilter.data.remote.parseDnsQueryFromPacketInto
import com.deatrg.dnsfilter.data.remote.patchBlockedNxDomainResponse
import com.deatrg.dnsfilter.data.remote.patchDnsErrorResponse
import com.deatrg.dnsfilter.data.remote.patchDnsResponseForClient
import com.deatrg.dnsfilter.data.remote.patchDnsTruncatedResponse
import com.deatrg.dnsfilter.domain.model.DnsServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.util.concurrent.ArrayBlockingQueue

class DnsVpnService : VpnService() {

    companion object {
        const val TAG = "DnsVpnService"
        const val ACTION_START = "com.deatrg.dnsfilter.START_VPN"
        const val ACTION_STOP = "com.deatrg.dnsfilter.STOP_VPN"
        // TUN MTU: large enough for any realistic DNS answer. This link is
        // local-only, so a big MTU costs nothing on the wire and lets oversized
        // upstream answers (fetched via TCP fallback) reach the client in one
        // UDP datagram instead of a TC=1 the client cannot follow (no TCP
        // listener on the virtual DNS addresses).
        const val MTU = 8000
        private const val UPSTREAM_QUEUE_CAPACITY = 1024
        private const val DNS_HEADER_LENGTH = 12

        // 虚拟DNS服务器地址（与VPN接口地址不同）
        const val VPN_DNS_V4 = "10.10.10.10"
        const val VPN_DNS_V6 = "fd00::10"
    }

    private var vpnInterface: ParcelFileDescriptor? = null

    @Volatile
    private var isRunning = false

    private var domainFilter: DomainFilter? = null
    private var dnsQueryExecutor: DnsQueryExecutor? = null
    private var statisticsBuffer: StatisticsBuffer? = null
    private var vpnState: VpnStateHolder? = null

    @Volatile
    private var servers: List<DnsServer> = emptyList()

    // CNAME 链 blocklist 回调：每个查询在 reader/worker 都要用，提升为字段
    // 避免每次查询重新分配捕获 lambda。
    @Volatile
    private var cnameBlocklistCheck: ((String) -> Boolean)? = null

    // scope 与 service 实例同生命周期，stop 时只取消具体 Job，
    // 这样快速"关→开"时同一个实例可以直接重启 DNS 循环。
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 串行化 start/stop，避免快速切换时状态交错
    private val lifecycleMutex = Mutex()
    private var serversJob: Job? = null
    private var dnsLoopJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var activeNetwork: Network? = null

    // Upstream races suspend the worker (it releases its Dispatchers.IO permit
    // while awaiting), so this count bounds concurrent unique cache misses, not
    // live threads. 3x cores (12..32) keeps cold-cache bursts from queueing
    // behind a handful of slow races.
    private val slowWorkerCount: Int get() =
        (Runtime.getRuntime().availableProcessors() * 3).coerceIn(12, 32)

    // TUN writes are split: reader-generated fast-path responses (L2 hits,
    // blocked NXDOMAIN, TC=1, SERVFAIL) are written directly on the reader
    // thread, while worker responses still funnel through one writer coroutine
    // fed by responseQueue. Every write shares the short tunWriteLock monitor
    // in runDnsLoop, so responses never interleave while the hot path skips a
    // coroutine hand-off.
    private data class QueuedResponse(
        val packet: ByteArray,
        val length: Int
    )

    // CNAME-cloaking verdicts: QNAME -> expiresAtMs (monotonic). Lets repeat
    // queries for cloaked ad domains answer NXDOMAIN without another upstream
    // round-trip. Bounded LRU; TTL-capped like ordinary DNS cache entries.
    private val cnameVerdictLock = Any()
    @Volatile
    private var hasCnameVerdicts = false
    private val cnameBlockedVerdicts =
        object : LinkedHashMap<String, Long>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > 1024
            }
        }

    // 单 Reader 线程复用解析输出槽，避免每次拦截或 L2 命中分配 Pair 与 DnsQuestion。
    private val reusableQuestion = MutableDnsQuestion()

    private fun isCnameBlockedCached(domain: String): Boolean {
        if (!hasCnameVerdicts) return false
        val nowMs = SystemClock.elapsedRealtime()
        return synchronized(cnameVerdictLock) {
            val expiresAt = cnameBlockedVerdicts[domain] ?: return false
            if (nowMs >= expiresAt) {
                cnameBlockedVerdicts.remove(domain)
                if (cnameBlockedVerdicts.isEmpty()) hasCnameVerdicts = false
                return false
            }
            true
        }
    }

    private fun rememberCnameBlocked(domain: String, ttlSeconds: Long, nowMs: Long) {
        synchronized(cnameVerdictLock) {
            cnameBlockedVerdicts[domain] = nowMs + ttlSeconds.coerceIn(60L, 7200L) * 1000L
            hasCnameVerdicts = true
        }
    }

    // Reuse packet buffers to reduce allocations during DNS bursts.
    private val packetPool = ArrayBlockingQueue<ByteArray>(256)

    private fun obtainPacket(): ByteArray = packetPool.poll() ?: ByteArray(MTU)
    private fun recyclePacket(packet: ByteArray) { packetPool.offer(packet) }

    private data class UpstreamTask(
        val packet: ByteArray,
        val dnsStart: Int,
        val dnsLength: Int,
        val question: DnsQuestion,
        val srcPort: Int,
        // IP 头信息直接内联：省去每包一个 PacketContext 分配。
        val isIPv6: Boolean,
        val ipHeaderLength: Int,
        // Reader 线程已算好的 L2/in-flight key，worker 复用以避免重复拼串。
        val cacheKeyHint: String? = null
    )

    override fun onCreate() {
        super.onCreate()
        VpnNotifications.createChannel(this)
        domainFilter = ServiceLocator.provideDomainFilter()
        cnameBlocklistCheck = domainFilter?.let { f -> { domain: String -> f.isDomainBlocked(domain) } }
        statisticsBuffer = ServiceLocator.provideStatisticsBuffer()
        vpnState = ServiceLocator.provideVpnStateHolder()
        dnsQueryExecutor = DnsQueryExecutor(
            socketProtector = { socket -> protect(socket) },
            tcpSocketProtector = { socket -> protect(socket) }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.d(TAG) { "onStartCommand action=${intent?.action}" }
        when (intent?.action) {
            ACTION_START -> launchLifecycle { startVpn() }
            ACTION_STOP -> launchLifecycle { stopVpn() }
            // START_STICKY 重启时 intent 为 null，恢复之前的运行状态
            // （always-on 模式下系统重启后也会以 null intent 拉起，走同一路径）
            null -> launchLifecycle { startVpn() }
        }
        return START_STICKY
    }

    /**
     * 系统撤销 VPN 授权（用户切换到其他 VPN、在系统设置里关闭/忘记本 VPN、
     * always-on 被关掉等）。走与 ACTION_STOP 相同的串行化停止路径，
     * 保证通知、状态流、socket 池全部完整清理，而不是默认的 stopSelf()。
     */
    override fun onRevoke() {
        AppLog.d(TAG) { "VPN authorization revoked by system" }
        launchLifecycle { stopVpn() }
    }

    private fun launchLifecycle(block: suspend () -> Unit) {
        scope.launch {
            lifecycleMutex.withLock { block() }
        }
    }

    private suspend fun startVpn() {
        if (isRunning || vpnInterface != null) return
        AppLog.d(TAG) { "Starting VPN service" }

        val prefsManager = ServiceLocator.providePreferencesManager()
        // 挂起式读取配置（原实现为 runBlocking 阻塞主线程）
        servers = prefsManager.dnsServers.first().filter { it.isEnabled }
        AppLog.d(TAG) { "Loaded ${servers.size} supported DNS servers" }

        val builder = Builder()
            .setSession("DnsFilter VPN")
            // VPN接口地址
            .addAddress("10.10.10.1", 24)
            .addAddress("fd00::1", 48)
            // 设置虚拟DNS服务器地址（系统会发送DNS查询到这些地址）
            .addDnsServer(VPN_DNS_V4)
            .addDnsServer(VPN_DNS_V6)
            // 分隧道模式：只路由发送到虚拟DNS地址的流量
            // 这样只有DNS查询会进入VPN，其他流量走正常网络
            .addRoute(VPN_DNS_V4, 32)
            .addRoute(VPN_DNS_V6, 128)
            .setMtu(MTU)
            .setBlocking(true)

        // minSdk = 29，可直接使用
        builder.setMetered(false)

        // Exclude ourselves from VPN to avoid routing loops
        runCatching { builder.addDisallowedApplication(packageName) }

        // establish() 在权限被撤销时抛 SecurityException，返回 null 表示其他失败
        val vpn = try {
            builder.establish()
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to establish VPN interface" }
            null
        }
        if (vpn == null) {
            vpnState?.reportStartFailed()
            stopSelf()
            return
        }

        vpnInterface = vpn
        isRunning = true
        vpnState?.setRunning(true)
        registerNetworkCallback()
        startDnsServerTracking(prefsManager)
        startForeground(VpnNotifications.NOTIFICATION_ID, createNotification())
        dnsLoopJob = scope.launch { runDnsLoop(vpn) }
        // Warm connected UDP sockets now so the first real queries skip
        // the create + protect() cost (P1-1).
        scope.launch { dnsQueryExecutor?.prewarm(servers) }
        AppLog.d(TAG) { "VPN established successfully" }
    }

    private fun stopVpn() {
        if (!isRunning && vpnInterface == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        isRunning = false
        vpnState?.setRunning(false)
        serversJob?.cancel()
        serversJob = null
        unregisterNetworkCallback()
        // 先关闭接口解除 read() 阻塞，再取消循环协程
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        dnsLoopJob?.cancel()
        dnsLoopJob = null
        dnsQueryExecutor?.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        AppLog.d(TAG) { "VPN stopped" }
    }

    private suspend fun runDnsLoop(vpn: ParcelFileDescriptor) {
        val inputStream = FileInputStream(vpn.fileDescriptor)
        val outputStream = FileOutputStream(vpn.fileDescriptor)
        val upstreamQueue = Channel<UpstreamTask>(capacity = UPSTREAM_QUEUE_CAPACITY)
        val responseQueue = Channel<QueuedResponse>(capacity = UPSTREAM_QUEUE_CAPACITY)

        // All TUN writes share this short monitor, which wraps only the write
        // call (~ns uncontended). Reader fast-path responses write here
        // directly; worker responses go through the single writer coroutine.
        // The lock keeps concurrent producers from interleaving if the stream
        // ever splits one write into multiple syscalls.
        val tunWriteLock = Any()
        val writePacket: (ByteArray, Int) -> Unit = { buffer, writeLength ->
            synchronized(tunWriteLock) { outputStream.write(buffer, 0, writeLength) }
        }

        // Single writer for worker responses; the reader never enqueues here.
        val writerJob = scope.launch(Dispatchers.IO) {
            for (queued in responseQueue) {
                try {
                    writePacket(queued.packet, queued.length)
                } catch (e: Exception) {
                    AppLog.e(TAG) { "Failed to send DNS response: ${e.message}" }
                } finally {
                    recyclePacket(queued.packet)
                }
            }
        }

        val slowWorkers = List(slowWorkerCount) {
            scope.launch(Dispatchers.IO) {
                for (task in upstreamQueue) {
                    // Buffer ownership: each path below enqueues task.packet into
                    // responseQueue exactly once; recycle locally only when nothing
                    // was enqueued (e.g. send threw during shutdown).
                    var enqueued = false
                    try {
                        val result = queryUpstream(task)
                        deliverUpstreamResult(task, result, responseQueue)
                        enqueued = true
                    } catch (e: Exception) {
                        AppLog.e(TAG) { "Error in upstream worker: ${e.message}" }
                        // 让客户端立即收到 SERVFAIL 而不是等到超时
                        enqueued = runCatching {
                            writeErrorResponse(task, responseQueue, DNS_RCODE_SERVFAIL)
                        }.isSuccess
                    } finally {
                        if (!enqueued) recyclePacket(task.packet)
                    }
                }
            }
        }

        AppLog.d(TAG) { "DNS loop started with $slowWorkerCount upstream workers" }

        try {
            while (isRunning) {
                val packet = obtainPacket()
                val length = inputStream.read(packet)
                if (length <= 0) {
                    recyclePacket(packet)
                    if (length == 0) {
                        AppLog.w(TAG) { "read() returned 0, no data available" }
                    }
                    continue
                }

                var handled = true
                try {
                    handled = processPacket(packet, length, upstreamQueue, writePacket)
                } catch (e: Exception) {
                    AppLog.e(TAG) { "Error while processing packet: ${e.message}" }
                }
                // false 表示包已移交给上游队列，所有权归 worker
                if (handled) recyclePacket(packet)
            }
        } catch (e: InterruptedIOException) {
            AppLog.d(TAG) { "DNS loop interrupted by stop" }
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Error in DNS loop" }
        } finally {
            // stopVpn 可能已取消本协程；清理必须继续执行完。
            // 关闭顺序：先停上游队列让 workers 排空并把响应送入
            // responseQueue，再关响应队列让 writer 排空，最后关流。
            withContext(NonCancellable) {
                upstreamQueue.close()
                slowWorkers.joinAll()
                responseQueue.close()
                writerJob.join()
                runCatching { inputStream.close() }
                runCatching { outputStream.close() }
                packetPool.clear()
            }
            AppLog.d(TAG) { "DNS loop stopped" }
        }
    }

    /**
     * 统一处理 IPv4/IPv6 DNS 查询包。reader 侧生成的响应（拦截、CNAME 判决、
     * L2 命中、队列满 SERVFAIL）直接写 TUN，不再经过 writer 协程。
     * @return true 表示包的所有权仍在调用方（缓冲区可回收）；false 表示已移交上游队列。
     */
    private fun processPacket(
        packet: ByteArray,
        length: Int,
        upstreamQueue: Channel<UpstreamTask>,
        writePacket: (ByteArray, Int) -> Unit
    ): Boolean {
        // probeIpHeader 返回 IP 头长度：正数 = IPv4 的 IHL，负数 = IPv6 固定 40，
        // 0 表示非 UDP 载体（丢弃）；版本由返回值符号区分，不再每包分配 PacketContext。
        val ipHeaderLength = probeIpHeader(packet, length)
        if (ipHeaderLength == 0) return true
        val isIPv6 = ipHeaderLength < 0
        val headerLength = if (isIPv6) -ipHeaderLength else ipHeaderLength
        val udpStart = headerLength
        if (length < udpStart + PacketRewriter.UDP_HEADER_LENGTH) return true

        val srcPort = ((packet[udpStart].toInt() and 0xFF) shl 8) or (packet[udpStart + 1].toInt() and 0xFF)
        val dstPort = ((packet[udpStart + 2].toInt() and 0xFF) shl 8) or (packet[udpStart + 3].toInt() and 0xFF)
        if (dstPort != PacketRewriter.DNS_PORT) return true

        val dnsStart = udpStart + PacketRewriter.UDP_HEADER_LENGTH
        if (length < dnsStart + DNS_HEADER_LENGTH) return true
        val parsed = reusableQuestion
        if (!parseDnsQueryFromPacketInto(packet, dnsStart, length, parsed)) return true
        val domain = parsed.domain
        val qtype = parsed.qtype
        val qclass = parsed.qclass
        val questionEndOffset = parsed.questionEndOffset

        // 检查拦截；24 小时 SOA TTL 交由 Android Resolver 负缓存。
        if (domainFilter?.isDomainBlocked(domain) == true) {
            AppLog.d(TAG) { "Domain $domain is blocked" }
            statisticsBuffer?.recordQuery(blocked = true, responseTime = 0, includeInAvg = false)
            val dnsResponseLength = patchBlockedNxDomainResponse(packet, dnsStart, questionEndOffset)
            writePrebuiltResponseDirect(packet, isIPv6, headerLength, srcPort, dnsResponseLength, writePacket)
            return true
        }

        // CNAME-cloaking 判决缓存：命中则直接 NXDOMAIN，跳过上游（无条目时无锁无系统调用快速跳过）。
        if (isCnameBlockedCached(domain)) {
            AppLog.d(TAG) { "Domain $domain is CNAME-cloak blocked (cached)" }
            statisticsBuffer?.recordQuery(blocked = true, responseTime = 0, includeInAvg = false)
            val dnsResponseLength = patchBlockedNxDomainResponse(packet, dnsStart, questionEndOffset)
            writePrebuiltResponseDirect(packet, isIPv6, headerLength, srcPort, dnsResponseLength, writePacket)
            return true
        }

        // Cache key 只算一次，miss 时复用给 executor，避免重复拼串与二次 L2 查询。
        val executor = dnsQueryExecutor
        val cacheKey = executor?.cacheKeyFor(domain, qtype, qclass)
        if (executor != null && cacheKey != null) {
            // L2 命中（含 Serve-Stale）快路径：应答单次拷贝就地写进 packet（TTL aging/stale stamp
            // + TXID/RD 还原在副本上完成），热点临期或 stale 条目同时触发后台刷新。
            val servedLength = executor.serveCachedResponseForClient(
                key = cacheKey,
                query = packet,
                queryOffset = dnsStart,
                queryLength = length - dnsStart,
                dest = packet,
                destOffset = dnsStart,
                destCapacity = packet.size - dnsStart,
                domain = domain,
                servers = servers,
                qtype = qtype,
                cnameBlocklistCheck = cnameBlocklistCheck
            )
            if (servedLength != DnsQueryExecutor.CACHED_SERVE_MISS) {
                statisticsBuffer?.recordQuery(blocked = false, responseTime = 0, includeInAvg = false)
                if (servedLength == DnsQueryExecutor.CACHED_SERVE_OVERFLOW) {
                    // 缓存应答塞不进包缓冲：与 sendDnsPayload 一致，回 TC=1。
                    AppLog.d(TAG) { "DNS L2 cache hit too large: $domain" }
                    val truncatedLength = patchDnsTruncatedResponse(
                        packet, dnsStart, questionEndOffset
                    )
                    writePrebuiltResponseDirect(packet, isIPv6, headerLength, srcPort, truncatedLength, writePacket)
                } else {
                    AppLog.d(TAG) { "DNS L2 cache hit: $domain" }
                    writePrebuiltResponseDirect(packet, isIPv6, headerLength, srcPort, servedLength, writePacket)
                }
                return true
            }
        }

        // 仅在 L2 Miss 需送往上游队列时才分配 DnsQuestion 与 UpstreamTask。
        val task = UpstreamTask(
            packet = packet,
            dnsStart = dnsStart,
            dnsLength = length - dnsStart,
            question = DnsQuestion(domain, qtype, qclass, questionEndOffset),
            srcPort = srcPort,
            isIPv6 = isIPv6,
            ipHeaderLength = headerLength,
            cacheKeyHint = cacheKey
        )
        if (!upstreamQueue.trySend(task).isSuccess) {
            // Queue full: return SERVFAIL immediately instead of blocking the main loop
            statisticsBuffer?.recordQuery(blocked = false, responseTime = 0, includeInAvg = false)
            val dnsResponseLength = patchDnsErrorResponse(
                packet, dnsStart, questionEndOffset, DNS_RCODE_SERVFAIL
            )
            writePrebuiltResponseDirect(packet, isIPv6, headerLength, srcPort, dnsResponseLength, writePacket)
            return true
        }
        return false
    }

    /**
     * 解析 IP 通用头；非 UDP 或长度不足时返回 0，否则返回 IP 头长度，
     * 版本由返回值符号区分：正数 = IPv4 的 IHL，负数 = IPv6（固定 [PacketRewriter.IPV6_HEADER_LENGTH]）。
     * 免去每包分配 PacketContext。
     */
    private fun probeIpHeader(packet: ByteArray, length: Int): Int {
        if (length < 20) return 0
        return when (packet[0].toInt() shr 4) {
            4 -> {
                if ((packet[9].toInt() and 0xFF) != 17) return 0
                val ihl = (packet[0].toInt() and 0x0F) * 4
                if (ihl < 20 || length < ihl) 0 else ihl
            }
            6 -> {
                if (length < PacketRewriter.IPV6_HEADER_LENGTH) return 0
                // 不处理 IPv6 扩展头，仅接受紧邻的 UDP
                if ((packet[6].toInt() and 0xFF) != 17) 0 else -PacketRewriter.IPV6_HEADER_LENGTH
            }
            else -> 0
        }
    }

    private suspend fun queryUpstream(task: UpstreamTask): DnsQueryResult {
        if (servers.isEmpty()) {
            AppLog.e(TAG) { "No DNS servers available" }
            return DnsQueryResult(false, null, 0, "No DNS servers available")
        }

        // Reader 已做过一次 L2 fresh 查询，这里跳过以避免双重查找；stale/
        // SERVFAIL 标记与 in-flight 合并仍由 executor 处理。CNAME 链检查通过
        // 回调完成，命中时 executor 直接返回 blocked verdict 且不污染缓存。
        return dnsQueryExecutor?.query(
            domain = task.question.domain,
            servers = servers,
            query = task.packet,
            queryOffset = task.dnsStart,
            queryLength = task.dnsLength,
            qtype = task.question.qtype,
            qclass = task.question.qclass,
            skipCacheLookup = true,
            cacheKeyHint = task.cacheKeyHint,
            cnameBlocklistCheck = cnameBlocklistCheck
        ) ?: DnsQueryResult(false, null, 0, "Executor not initialized")
    }

    private suspend fun deliverUpstreamResult(
        task: UpstreamTask,
        result: DnsQueryResult,
        responseQueue: Channel<QueuedResponse>
    ) {
        val responseBytes = result.responseBytes
        if (result.success && responseBytes != null) {
            if (result.blocked) {
                // CNAME 链命中 blocklist：回 NXDOMAIN 并记住判决，后续同 QNAME
                // 直接拦截不再走上游。
                val verdictTtl = minAnswerTtlSeconds(responseBytes) ?: 3600L
                rememberCnameBlocked(
                    task.question.domain, verdictTtl, SystemClock.elapsedRealtime()
                )
                AppLog.d(TAG) { "DNS CNAME-cloak blocked: ${task.question.domain}" }
                statisticsBuffer?.recordQuery(blocked = true, responseTime = 0, includeInAvg = false)
                val dnsResponseLength = patchBlockedNxDomainResponse(
                    task.packet, task.dnsStart, task.question.questionEndOffset
                )
                sendPrebuiltResponse(task, dnsResponseLength, responseQueue)
                return
            }
            AppLog.d(TAG) { "DNS response: ${task.question.domain} (${result.responseTime}ms)" }
            statisticsBuffer?.recordQuery(
                blocked = false,
                responseTime = result.responseTime,
                includeInAvg = !result.fromCache && !result.stale
            )
            sendDnsPayload(task, responseBytes, responseQueue)
        } else {
            AppLog.e(TAG) { "DNS query failed: ${result.error}" }
            statisticsBuffer?.recordQuery(blocked = false, responseTime = 0, includeInAvg = false)
            writeErrorResponse(task, responseQueue, DNS_RCODE_SERVFAIL)
        }
    }

    /**
     * 把 DNS 响应负载写回客户端：复制进包内、还原事务 ID/RD 位、改写 IP/UDP 头。
     * 上游 TCP fallback 已保证大包完整；这里仅当响应仍塞不进 MTU 时才发 TC=1。
     */
    private suspend fun sendDnsPayload(
        task: UpstreamTask,
        dnsPayload: ByteArray,
        responseQueue: Channel<QueuedResponse>
    ) {
        val maxDnsResponseLength = task.packet.size - task.dnsStart
        if (dnsPayload.size > maxDnsResponseLength) {
            val truncatedLength = patchDnsTruncatedResponse(
                task.packet, task.dnsStart, task.question.questionEndOffset
            )
            sendPrebuiltResponse(task, truncatedLength, responseQueue)
            return
        }

        val transactionId0 = task.packet[task.dnsStart]
        val transactionId1 = task.packet[task.dnsStart + 1]
        val recursionDesired = task.packet[task.dnsStart + 2].toInt() and 0x01

        dnsPayload.copyInto(task.packet, destinationOffset = task.dnsStart)
        patchDnsResponseForClient(task.packet, task.dnsStart, transactionId0, transactionId1, recursionDesired)
        sendPrebuiltResponse(task, dnsPayload.size, responseQueue)
    }

    private suspend fun writeErrorResponse(
        task: UpstreamTask,
        responseQueue: Channel<QueuedResponse>,
        errorCode: Int
    ) {
        val dnsResponseLength = patchDnsErrorResponse(
            task.packet, task.dnsStart, task.question.questionEndOffset, errorCode
        )
        sendPrebuiltResponse(task, dnsResponseLength, responseQueue)
    }

    /** 改写 IP/UDP 头并送入单 writer 队列，由 writer 串行写入 TUN。 */
    private suspend fun sendPrebuiltResponse(
        task: UpstreamTask,
        dnsResponseLength: Int,
        responseQueue: Channel<QueuedResponse>
    ) {
        val responseLength = PacketRewriter.rewriteAsResponse(
            task.packet, task.isIPv6, task.ipHeaderLength, task.srcPort, dnsResponseLength
        )
        responseQueue.send(QueuedResponse(task.packet, responseLength))
    }

    /**
     * reader 线程直写版本：改写 IP/UDP 头后立即写入 TUN，省去 writer 协程的
     * 一次调度唤醒，且无需构造 [UpstreamTask]；缓冲区仍归 reader 所有，由调用方回收。
     */
    private fun writePrebuiltResponseDirect(
        packet: ByteArray,
        isIPv6: Boolean,
        ipHeaderLength: Int,
        srcPort: Int,
        dnsResponseLength: Int,
        writePacket: (ByteArray, Int) -> Unit
    ) {
        val responseLength = PacketRewriter.rewriteAsResponse(
            packet, isIPv6, ipHeaderLength, srcPort, dnsResponseLength
        )
        writePacket(packet, responseLength)
    }

    private fun createNotification(): Notification {
        return VpnNotifications.buildServiceNotification(this)
    }

    override fun onDestroy() {
        // 系统直接销毁服务时的同步清理路径，不能依赖协程调度。
        isRunning = false
        vpnState?.setRunning(false)
        serversJob?.cancel()
        serversJob = null
        unregisterNetworkCallback()
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        dnsLoopJob?.cancel()
        dnsLoopJob = null
        super.onDestroy()
    }

    private fun startDnsServerTracking(preferencesManager: com.deatrg.dnsfilter.data.local.PreferencesManager) {
        serversJob?.cancel()
        serversJob = scope.launch {
            preferencesManager.dnsServers.collect { updatedServers ->
                val newServers = updatedServers.filter(::isSupportedDnsServer)
                if (servers != newServers) {
                    servers = newServers
                    invalidateResponseCache("upstream DNS servers changed")
                    // New addresses start with empty socket pools: warm them in
                    // the background so first queries skip create+protect.
                    scope.launch { dnsQueryExecutor?.prewarm(newServers) }
                }
                AppLog.d(TAG) { "Updated active DNS servers: ${servers.size}" }
            }
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        activeNetwork = connectivityManager.activeNetwork
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val previous = activeNetwork
                activeNetwork = network
                if (previous != network) {
                    softenResponseCacheForNetwork("default network changed")
                }
            }

            override fun onLost(network: Network) {
                if (activeNetwork == network) {
                    activeNetwork = null
                    softenResponseCacheForNetwork("default network lost")
                }
            }
        }

        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            activeNetwork = null
            AppLog.w(TAG) { "Failed to register network callback: ${e.message}" }
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        activeNetwork = null
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }

    private fun invalidateResponseCache(reason: String) {
        dnsQueryExecutor?.clearResponseCache()
        AppLog.d(TAG) { "DNS L2 cache cleared: $reason" }
    }

    /**
     * 切网时只丢弃 in-flight 合并（旧网络上的抓包不能再被 join），L2 条目
     * 保留：过期行以 serve-stale 先行应答并后台刷新，避免冷启动风暴。
     */
    private fun softenResponseCacheForNetwork(reason: String) {
        dnsQueryExecutor?.onDefaultNetworkChanged()
        scope.launch { dnsQueryExecutor?.prewarm(servers) }
        AppLog.d(TAG) { "DNS L2 cache kept for stale-serve: $reason" }
    }

    private fun isSupportedDnsServer(server: DnsServer): Boolean {
        return server.isEnabled
    }
}
