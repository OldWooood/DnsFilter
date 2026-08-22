package com.deatrg.dnsfilter.service

import android.app.Notification
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.deatrg.dnsfilter.AppLog
import com.deatrg.dnsfilter.ServiceLocator
import com.deatrg.dnsfilter.data.local.StatisticsBuffer
import com.deatrg.dnsfilter.data.remote.DomainFilter
import com.deatrg.dnsfilter.data.remote.DnsQueryExecutor
import com.deatrg.dnsfilter.data.remote.DnsQueryResult
import com.deatrg.dnsfilter.data.remote.DnsQuestion
import com.deatrg.dnsfilter.data.remote.DNS_RCODE_SERVFAIL
import com.deatrg.dnsfilter.data.remote.parseDnsQueryFromPacket
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
        const val MTU = 1500
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

    // Upstream DNS queries mostly wait on IO, so keep this worker count modest.
    private val slowWorkerCount: Int get() = Runtime.getRuntime().availableProcessors().coerceIn(4, 16) * 2

    // Serialize writes to the VPN descriptor without blocking dispatcher threads.
    private val outputMutex = Mutex()

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
        val ctx: PacketContext
    )

    override fun onCreate() {
        super.onCreate()
        VpnNotifications.createChannel(this)
        domainFilter = ServiceLocator.provideDomainFilter()
        statisticsBuffer = ServiceLocator.provideStatisticsBuffer()
        vpnState = ServiceLocator.provideVpnStateHolder()
        dnsQueryExecutor = DnsQueryExecutor { socket ->
            protect(socket)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.d(TAG) { "onStartCommand action=${intent?.action}" }
        when (intent?.action) {
            ACTION_START -> launchLifecycle { startVpn() }
            ACTION_STOP -> launchLifecycle { stopVpn() }
            // START_STICKY 重启时 intent 为 null，恢复之前的运行状态
            null -> launchLifecycle { startVpn() }
        }
        return START_STICKY
    }

    private fun launchLifecycle(block: suspend () -> Unit) {
        scope.launch {
            lifecycleMutex.withLock { block() }
        }
    }

    private suspend fun startVpn() {
        if (isRunning || vpnInterface != null) return
        AppLog.d(TAG, "Starting VPN service")

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
            AppLog.e(TAG, "Failed to establish VPN interface", e)
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
        AppLog.d(TAG, "VPN established successfully")
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
        AppLog.d(TAG, "VPN stopped")
    }

    private suspend fun runDnsLoop(vpn: ParcelFileDescriptor) {
        val inputStream = FileInputStream(vpn.fileDescriptor)
        val outputStream = FileOutputStream(vpn.fileDescriptor)
        val upstreamQueue = Channel<UpstreamTask>(capacity = UPSTREAM_QUEUE_CAPACITY)

        val slowWorkers = List(slowWorkerCount) {
            scope.launch(Dispatchers.IO) {
                for (task in upstreamQueue) {
                    try {
                        val result = queryUpstream(task)
                        deliverUpstreamResult(task, result, outputStream)
                    } catch (e: Exception) {
                        AppLog.e(TAG) { "Error in upstream worker: ${e.message}" }
                        // 让客户端立即收到 SERVFAIL 而不是等到超时
                        runCatching { writeErrorResponse(task, outputStream, DNS_RCODE_SERVFAIL) }
                    } finally {
                        recyclePacket(task.packet)
                    }
                }
            }
        }

        AppLog.d(TAG, "DNS loop started with $slowWorkerCount upstream workers")

        try {
            while (isRunning) {
                val packet = obtainPacket()
                val length = inputStream.read(packet)
                if (length <= 0) {
                    recyclePacket(packet)
                    if (length == 0) {
                        AppLog.w(TAG, "read() returned 0, no data available")
                    }
                    continue
                }

                var handled = true
                try {
                    handled = processPacket(packet, length, outputStream, upstreamQueue)
                } catch (e: Exception) {
                    AppLog.e(TAG) { "Error while processing packet: ${e.message}" }
                }
                // false 表示包已移交给上游队列，所有权归 worker
                if (handled) recyclePacket(packet)
            }
        } catch (e: InterruptedIOException) {
            AppLog.d(TAG, "DNS loop interrupted by stop")
        } catch (e: Exception) {
            AppLog.e(TAG, "Error in DNS loop", e)
        } finally {
            // stopVpn 可能已取消本协程；清理必须继续执行完
            withContext(NonCancellable) {
                upstreamQueue.close()
                slowWorkers.joinAll()
                runCatching { inputStream.close() }
                runCatching { outputStream.close() }
                packetPool.clear()
            }
            AppLog.d(TAG, "DNS loop stopped")
        }
    }

    /**
     * 统一处理 IPv4/IPv6 DNS 查询包。
     * @return true 表示包的所有权仍在调用方（缓冲区可回收）；false 表示已移交上游队列。
     */
    private suspend fun processPacket(
        packet: ByteArray,
        length: Int,
        outputStream: FileOutputStream,
        upstreamQueue: Channel<UpstreamTask>
    ): Boolean {
        val ctx = probeIpHeader(packet, length) ?: return true
        val udpStart = ctx.ipHeaderLength
        if (length < udpStart + PacketRewriter.UDP_HEADER_LENGTH) return true

        val srcPort = ((packet[udpStart].toInt() and 0xFF) shl 8) or (packet[udpStart + 1].toInt() and 0xFF)
        val dstPort = ((packet[udpStart + 2].toInt() and 0xFF) shl 8) or (packet[udpStart + 3].toInt() and 0xFF)
        if (dstPort != PacketRewriter.DNS_PORT) return true

        val dnsStart = udpStart + PacketRewriter.UDP_HEADER_LENGTH
        if (length < dnsStart + DNS_HEADER_LENGTH) return true
        val question = parseDnsQueryFromPacket(packet, dnsStart, length) ?: return true

        val task = UpstreamTask(
            packet = packet,
            dnsStart = dnsStart,
            dnsLength = length - dnsStart,
            question = question,
            srcPort = srcPort,
            ctx = ctx
        )

        // 检查拦截；24 小时 SOA TTL 交由 Android Resolver 负缓存。
        if (domainFilter?.isDomainBlocked(question.domain) == true) {
            AppLog.d(TAG) { "Domain ${question.domain} is blocked" }
            statisticsBuffer?.recordQuery(blocked = true, responseTime = 0, includeInAvg = false)
            val dnsResponseLength = patchBlockedNxDomainResponse(packet, dnsStart, question.questionEndOffset)
            sendPrebuiltResponse(task, dnsResponseLength, outputStream)
            return true
        }

        dnsQueryExecutor?.getCachedResponseForClient(
            domain = question.domain,
            qtype = question.qtype,
            qclass = question.qclass,
            query = packet,
            queryOffset = dnsStart
        )?.let { cachedResponse ->
            AppLog.d(TAG) { "DNS L2 cache hit: ${question.domain}" }
            statisticsBuffer?.recordQuery(blocked = false, responseTime = 0, includeInAvg = false)
            sendDnsPayload(task, cachedResponse, outputStream)
            return true
        }

        if (!upstreamQueue.trySend(task).isSuccess) {
            // Queue full: return SERVFAIL immediately instead of blocking the main loop
            statisticsBuffer?.recordQuery(blocked = false, responseTime = 0, includeInAvg = false)
            val dnsResponseLength = patchDnsErrorResponse(
                packet, dnsStart, question.questionEndOffset, DNS_RCODE_SERVFAIL
            )
            sendPrebuiltResponse(task, dnsResponseLength, outputStream)
            return true
        }
        return false
    }

    /** 解析 IP 通用头；非 UDP 或长度不足时返回 null。 */
    private fun probeIpHeader(packet: ByteArray, length: Int): PacketContext? {
        if (length < 20) return null
        return when (packet[0].toInt() shr 4) {
            4 -> {
                if ((packet[9].toInt() and 0xFF) != 17) return null
                val ihl = (packet[0].toInt() and 0x0F) * 4
                if (ihl < 20 || length < ihl) return null
                PacketContext(isIPv6 = false, ipHeaderLength = ihl)
            }
            6 -> {
                if (length < PacketRewriter.IPV6_HEADER_LENGTH) return null
                // 不处理 IPv6 扩展头，仅接受紧邻的 UDP
                if ((packet[6].toInt() and 0xFF) != 17) return null
                PacketContext(isIPv6 = true, ipHeaderLength = PacketRewriter.IPV6_HEADER_LENGTH)
            }
            else -> null
        }
    }

    private suspend fun queryUpstream(task: UpstreamTask): DnsQueryResult {
        if (servers.isEmpty()) {
            AppLog.e(TAG, "No DNS servers available")
            return DnsQueryResult(false, null, 0, "No DNS servers available")
        }

        // 相同 domain:qtype 的并发请求由 DnsQueryExecutor 的 in-flight 表合并为一次上游查询
        return dnsQueryExecutor?.query(
            domain = task.question.domain,
            servers = servers,
            query = task.packet,
            queryOffset = task.dnsStart,
            queryLength = task.dnsLength,
            qtype = task.question.qtype,
            qclass = task.question.qclass
        ) ?: DnsQueryResult(false, null, 0, "Executor not initialized")
    }

    private suspend fun deliverUpstreamResult(
        task: UpstreamTask,
        result: DnsQueryResult,
        outputStream: FileOutputStream
    ) {
        val responseBytes = result.responseBytes
        if (result.success && responseBytes != null) {
            AppLog.d(TAG) { "DNS response: ${task.question.domain} (${result.responseTime}ms)" }
            statisticsBuffer?.recordQuery(
                blocked = false,
                responseTime = result.responseTime,
                includeInAvg = !result.fromCache
            )
            sendDnsPayload(task, responseBytes, outputStream)
        } else {
            AppLog.e(TAG) { "DNS query failed: ${result.error}" }
            statisticsBuffer?.recordQuery(blocked = false, responseTime = 0, includeInAvg = false)
            writeErrorResponse(task, outputStream, DNS_RCODE_SERVFAIL)
        }
    }

    /**
     * 把 DNS 响应负载写回客户端：复制进包内、还原事务 ID/RD 位、改写 IP/UDP 头。
     * 响应超出 MTU 时改发 TC=1 的截断应答，让客户端改走 TCP。
     */
    private suspend fun sendDnsPayload(
        task: UpstreamTask,
        dnsPayload: ByteArray,
        outputStream: FileOutputStream
    ) {
        val maxDnsResponseLength = task.packet.size - task.dnsStart
        if (dnsPayload.size > maxDnsResponseLength) {
            val truncatedLength = patchDnsTruncatedResponse(
                task.packet, task.dnsStart, task.question.questionEndOffset
            )
            sendPrebuiltResponse(task, truncatedLength, outputStream)
            return
        }

        val transactionId0 = task.packet[task.dnsStart]
        val transactionId1 = task.packet[task.dnsStart + 1]
        val recursionDesired = task.packet[task.dnsStart + 2].toInt() and 0x01

        dnsPayload.copyInto(task.packet, destinationOffset = task.dnsStart)
        patchDnsResponseForClient(task.packet, task.dnsStart, transactionId0, transactionId1, recursionDesired)
        sendPrebuiltResponse(task, dnsPayload.size, outputStream)
    }

    private suspend fun writeErrorResponse(
        task: UpstreamTask,
        outputStream: FileOutputStream,
        errorCode: Int
    ) {
        val dnsResponseLength = patchDnsErrorResponse(
            task.packet, task.dnsStart, task.question.questionEndOffset, errorCode
        )
        sendPrebuiltResponse(task, dnsResponseLength, outputStream)
    }

    /** 改写 IP/UDP 头并写入 TUN。 */
    private suspend fun sendPrebuiltResponse(
        task: UpstreamTask,
        dnsResponseLength: Int,
        outputStream: FileOutputStream
    ) {
        val responseLength = PacketRewriter.rewriteAsResponse(
            task.packet, task.ctx, task.srcPort, dnsResponseLength
        )
        outputMutex.withLock {
            try {
                outputStream.write(task.packet, 0, responseLength)
                AppLog.d(TAG) {
                    "Sent ${if (task.ctx.isIPv6) "IPv6" else "IPv4"} DNS response to port=${task.srcPort}, length: $responseLength"
                }
            } catch (e: Exception) {
                AppLog.e(TAG) { "Failed to send DNS response: ${e.message}" }
            }
        }
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
                    invalidateResponseCache("default network changed")
                }
            }

            override fun onLost(network: Network) {
                if (activeNetwork == network) {
                    activeNetwork = null
                    invalidateResponseCache("default network lost")
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

    private fun isSupportedDnsServer(server: DnsServer): Boolean {
        return server.isEnabled
    }
}
