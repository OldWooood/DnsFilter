package com.deatrg.dnsfilter.service

import android.app.*
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.deatrg.dnsfilter.AppLog
import com.deatrg.dnsfilter.R
import com.deatrg.dnsfilter.ServiceLocator
import com.deatrg.dnsfilter.data.local.StatisticsBuffer
import com.deatrg.dnsfilter.data.remote.DomainFilter
import com.deatrg.dnsfilter.data.remote.DnsQueryExecutor
import com.deatrg.dnsfilter.domain.model.DnsServer
import com.deatrg.dnsfilter.domain.model.DnsServerType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue

class DnsVpnService : VpnService() {

    companion object {
        const val TAG = "DnsVpnService"
        const val ACTION_START = "com.deatrg.dnsfilter.START_VPN"
        const val ACTION_STOP = "com.deatrg.dnsfilter.STOP_VPN"
        const val NOTIFICATION_ID = 1
        const val MTU = 1500
        const val DNS_PORT = 53
        private const val PACKET_QUEUE_CAPACITY = 256
        // 虚拟DNS服务器地址（应该与VPN接口地址不同）
        const val VPN_DNS_V4 = "10.10.10.10"
        const val VPN_DNS_V6 = "fd00::10"
        
        // 跟踪VPN服务实际运行状态
        @Volatile
        var isServiceRunning: Boolean = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false

    private var domainFilter: DomainFilter? = null
    private var dnsQueryExecutor: DnsQueryExecutor? = null
    private var okHttpClient: OkHttpClient? = null
    private var statisticsBuffer: StatisticsBuffer? = null
    @Volatile
    private var servers: List<DnsServer> = emptyList()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serversJob: Job? = null

    // Fast path: 解析、拦截、缓存命中 —— 纯内存操作，Worker 数可多一些
    // 直接用 Dispatchers.IO，避免 limitedParallelism 的额外调度开销
    private val fastWorkerCount = (Runtime.getRuntime().availableProcessors() * 2).coerceIn(4, 16)

    // Slow path: 上游 DNS 查询 —— 主要是 IO 等待，不需要太多 Worker
    private val slowWorkerCount = 4

    // 协程友好的输出锁，synchronized 会让线程 BLOCKED 而占用 dispatcher 槽位
    private val outputMutex = kotlinx.coroutines.sync.Mutex()

    // Packet buffer 对象池，减少高并发时的内存分配压力
    private val packetPool = ArrayBlockingQueue<ByteArray>(256)

    private fun obtainPacket(): ByteArray = packetPool.poll() ?: ByteArray(MTU)
    private fun recyclePacket(packet: ByteArray) { packetPool.offer(packet) }

    private data class DnsQuestion(
        val domain: String,
        val qtype: Int,
        val qclass: Int
    )

    private data class PacketTask(
        val packet: ByteArray,
        val length: Int
    )

    private data class UpstreamTask(
        val packet: ByteArray,
        val length: Int,
        val dnsPayload: ByteArray,
        val question: DnsQuestion,
        val srcPort: Int,
        val isIPv6: Boolean,
        val ipHeaderLength: Int
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initializeComponents()
    }

    private fun initializeComponents() {
        okHttpClient = ServiceLocator.provideOkHttpClient()
        domainFilter = ServiceLocator.provideDomainFilter()
        statisticsBuffer = ServiceLocator.provideStatisticsBuffer()

        // Create DnsQueryExecutor with socket protection callback
        dnsQueryExecutor = DnsQueryExecutor(okHttpClient!!) { socket ->
            protect(socket)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.d(TAG) { "onStartCommand action=${intent?.action}" }
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return
        AppLog.d(TAG, "Starting VPN service")

        val prefsManager = ServiceLocator.providePreferencesManager()
        runBlocking {
            servers = prefsManager.dnsServers.first().filter(::isSupportedDnsServer)
            AppLog.d(TAG) { "Loaded ${servers.size} supported DNS servers" }
        }

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
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            // May fail on some devices
        }

        vpnInterface = builder.establish()

        if (vpnInterface != null) {
            isRunning = true
            isServiceRunning = true
            startDnsServerTracking(prefsManager)
            startForeground(NOTIFICATION_ID, createNotification())
            scope.launch { runDnsLoop() }
            AppLog.d(TAG, "VPN established successfully")
        } else {
            isRunning = false
            isServiceRunning = false
            AppLog.e(TAG, "Failed to establish VPN interface")
        }
    }

    private fun stopVpn() {
        isRunning = false
        isServiceRunning = false
        serversJob?.cancel()
        serversJob = null
        vpnInterface?.close()
        vpnInterface = null

        try {
            runBlocking {
                withContext(NonCancellable + Dispatchers.IO) {
                    statisticsBuffer?.flush()
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to flush statistics", e)
        }

        scope.cancel()
        dnsQueryExecutor?.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        AppLog.d(TAG, "VPN stopped")
    }

    private suspend fun runDnsLoop() {
        val vpnInterface = this.vpnInterface ?: return
        val inputStream = FileInputStream(vpnInterface.fileDescriptor)
        val outputStream = FileOutputStream(vpnInterface.fileDescriptor)
        val packetQueue = Channel<PacketTask>(capacity = PACKET_QUEUE_CAPACITY)
        val upstreamQueue = Channel<UpstreamTask>(capacity = PACKET_QUEUE_CAPACITY)

        val fastWorkers = List(fastWorkerCount) {
            scope.launch(Dispatchers.IO) {
                for (task in packetQueue) {
                    try {
                        val handled = processPacketFast(task.packet, task.length, outputStream, upstreamQueue)
                        if (handled) {
                            recyclePacket(task.packet)
                        }
                    } catch (e: Exception) {
                        AppLog.e(TAG) { "Error in fast path: ${e.message}" }
                        recyclePacket(task.packet)
                    }
                }
            }
        }

        val slowWorkers = List(slowWorkerCount) {
            scope.launch(Dispatchers.IO) {
                for (task in upstreamQueue) {
                    try {
                        processUpstreamTask(task, outputStream)
                    } catch (e: Exception) {
                        AppLog.e(TAG) { "Error in slow path: ${e.message}" }
                    } finally {
                        recyclePacket(task.packet)
                    }
                }
            }
        }

        AppLog.d(TAG, "DNS loop started with $fastWorkerCount fast + $slowWorkerCount slow workers")

        try {
            while (isRunning) {
                val packet = obtainPacket()
                val length = inputStream.read(packet)
                if (length > 0) {
                    val version = packet[0].toInt() shr 4
                    val protocol = packet[9].toInt() and 0xFF
                    AppLog.d(TAG) { "Packet received: version=$version, protocol=$protocol, length=$length" }

                    if (version != 4 && version != 6) {
                        AppLog.d(TAG) {
                            "Unknown packet version=$version, byte0=${String.format("%02X", packet[0])}, byte1=${String.format("%02X", packet[1])}"
                        }
                    }
                    val result = packetQueue.trySend(PacketTask(packet, length))
                    if (!result.isSuccess) {
                        packetQueue.send(PacketTask(packet, length))
                    }
                } else {
                    recyclePacket(packet)
                    if (length == 0) {
                        AppLog.w(TAG, "read() returned 0, no data available")
                    }
                }
            }
        } catch (e: InterruptedIOException) {
            AppLog.d(TAG, "DNS loop interrupted by stop")
        } catch (e: Exception) {
            AppLog.e(TAG, "Error in DNS loop", e)
        } finally {
            packetQueue.close()
            upstreamQueue.close()
            fastWorkers.joinAll()
            slowWorkers.joinAll()
            try {
                inputStream.close()
            } catch (_: Exception) {
            }
            try {
                outputStream.close()
            } catch (_: Exception) {
            }
            packetPool.clear()
            AppLog.d(TAG, "DNS loop stopped")
        }
    }

    private suspend fun processPacketFast(
        packet: ByteArray,
        length: Int,
        outputStream: FileOutputStream,
        upstreamQueue: Channel<UpstreamTask>
    ): Boolean {
        if (length < 40) {
            AppLog.d(TAG) { "Packet too short: $length bytes" }
            return true
        }

        val version = packet[0].toInt() shr 4
        return when (version) {
            4 -> processIPv4PacketFast(packet, length, outputStream, upstreamQueue)
            6 -> processIPv6PacketFast(packet, length, outputStream, upstreamQueue)
            else -> {
                AppLog.d(TAG) { "Unknown IP version: $version, skipping" }
                true
            }
        }
    }

    private suspend fun processIPv4PacketFast(
        packet: ByteArray,
        length: Int,
        outputStream: FileOutputStream,
        upstreamQueue: Channel<UpstreamTask>
    ): Boolean {
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) {
            AppLog.d(TAG) { "Non-UDP IPv4 packet, protocol=$protocol, skipping" }
            return true
        }

        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (length < ihl + 8) {
            AppLog.d(TAG) { "Packet too short for UDP header" }
            return true
        }

        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)

        if (dstPort != DNS_PORT) {
            AppLog.d(TAG) { "Non-DNS UDP packet, dstPort=$dstPort, skipping" }
            return true
        }

        val dnsPayload = packet.copyOfRange(ihl + 8, length)
        if (dnsPayload.isEmpty()) return true

        AppLog.d(TAG) {
            val srcIp = "${packet[12].toInt() and 0xFF}.${packet[13].toInt() and 0xFF}.${packet[14].toInt() and 0xFF}.${packet[15].toInt() and 0xFF}"
            val dstIp = "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}.${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}"
            "IPv4 DNS query from $srcIp:$srcPort to $dstIp, DNS length: ${dnsPayload.size}"
        }

        val question = parseDnsQuery(dnsPayload) ?: return true

        // 1. 检查拦截
        val blockResult = domainFilter?.isDomainBlocked(question.domain)
        if (blockResult?.isBlocked == true) {
            AppLog.d(TAG) { "Domain ${question.domain} is blocked: ${blockResult.reason}" }
            statisticsBuffer?.recordQuery(blocked = true, responseTime = 0, includeInAvg = false)
            val response = buildBlockedDnsResponse(dnsPayload)
            if (ihl + 8 + response.size <= packet.size) {
                response.copyInto(packet, destinationOffset = ihl + 8)
                patchIPv4Response(packet, ihl, srcPort, response.size)
                val responseLength = ihl + 8 + response.size
                outputMutex.withLock {
                    outputStream.write(packet, 0, responseLength)
                }
            }
            return true
        }

        // 2. 检查缓存
        val cachedResponse = dnsQueryExecutor?.queryCache(
            domain = question.domain,
            qtype = question.qtype,
            qclass = question.qclass,
            query = dnsPayload
        )
        if (cachedResponse != null) {
            AppLog.d(TAG) { "DNS cache hit: ${question.domain}" }
            statisticsBuffer?.recordQuery(blocked = false, responseTime = 0, includeInAvg = false)
            if (ihl + 8 + cachedResponse.size <= packet.size) {
                cachedResponse.copyInto(packet, destinationOffset = ihl + 8)
                patchIPv4Response(packet, ihl, srcPort, cachedResponse.size)
                val responseLength = ihl + 8 + cachedResponse.size
                outputMutex.withLock {
                    outputStream.write(packet, 0, responseLength)
                }
            }
            return true
        }

        // 3. 需要上游查询，发送到 slow path
        val task = UpstreamTask(
            packet = packet,
            length = length,
            dnsPayload = dnsPayload,
            question = question,
            srcPort = srcPort,
            isIPv6 = false,
            ipHeaderLength = ihl
        )
        val result = upstreamQueue.trySend(task)
        if (!result.isSuccess) {
            upstreamQueue.send(task)
        }
        return false
    }

    private suspend fun processIPv6PacketFast(
        packet: ByteArray,
        length: Int,
        outputStream: FileOutputStream,
        upstreamQueue: Channel<UpstreamTask>
    ): Boolean {
        val nextHeader = packet[6].toInt() and 0xFF
        if (nextHeader != 17) {
            AppLog.d(TAG) { "Non-UDP IPv6 packet, nextHeader=$nextHeader, skipping" }
            return true
        }

        val ipv6HeaderLength = 40
        if (length < ipv6HeaderLength + 8) {
            AppLog.d(TAG) { "Packet too short for IPv6 UDP header" }
            return true
        }

        val srcPort = ((packet[ipv6HeaderLength].toInt() and 0xFF) shl 8) or (packet[ipv6HeaderLength + 1].toInt() and 0xFF)
        val dstPort = ((packet[ipv6HeaderLength + 2].toInt() and 0xFF) shl 8) or (packet[ipv6HeaderLength + 3].toInt() and 0xFF)

        if (dstPort != DNS_PORT) {
            AppLog.d(TAG) { "Non-DNS IPv6 UDP packet, dstPort=$dstPort, skipping" }
            return true
        }

        val dnsPayload = packet.copyOfRange(ipv6HeaderLength + 8, length)
        if (dnsPayload.isEmpty()) return true

        AppLog.d(TAG) {
            val srcIp = formatIPv6(packet, 8)
            val dstIp = formatIPv6(packet, 24)
            "IPv6 DNS query from $srcIp:$srcPort to $dstIp, DNS length: ${dnsPayload.size}"
        }

        val question = parseDnsQuery(dnsPayload) ?: return true

        // 1. 检查拦截
        val blockResult = domainFilter?.isDomainBlocked(question.domain)
        if (blockResult?.isBlocked == true) {
            AppLog.d(TAG) { "Domain ${question.domain} is blocked: ${blockResult.reason}" }
            statisticsBuffer?.recordQuery(blocked = true, responseTime = 0, includeInAvg = false)
            val response = buildBlockedDnsResponse(dnsPayload)
            if (ipv6HeaderLength + 8 + response.size <= packet.size) {
                response.copyInto(packet, destinationOffset = ipv6HeaderLength + 8)
                patchIPv6Response(packet, srcPort, response.size)
                val responseLength = ipv6HeaderLength + 8 + response.size
                outputMutex.withLock {
                    outputStream.write(packet, 0, responseLength)
                }
            }
            return true
        }

        // 2. 检查缓存
        val cachedResponse = dnsQueryExecutor?.queryCache(
            domain = question.domain,
            qtype = question.qtype,
            qclass = question.qclass,
            query = dnsPayload
        )
        if (cachedResponse != null) {
            AppLog.d(TAG) { "DNS cache hit: ${question.domain}" }
            statisticsBuffer?.recordQuery(blocked = false, responseTime = 0, includeInAvg = false)
            if (ipv6HeaderLength + 8 + cachedResponse.size <= packet.size) {
                cachedResponse.copyInto(packet, destinationOffset = ipv6HeaderLength + 8)
                patchIPv6Response(packet, srcPort, cachedResponse.size)
                val responseLength = ipv6HeaderLength + 8 + cachedResponse.size
                outputMutex.withLock {
                    outputStream.write(packet, 0, responseLength)
                }
            }
            return true
        }

        // 3. 需要上游查询，发送到 slow path
        val task = UpstreamTask(
            packet = packet,
            length = length,
            dnsPayload = dnsPayload,
            question = question,
            srcPort = srcPort,
            isIPv6 = true,
            ipHeaderLength = ipv6HeaderLength
        )
        val result = upstreamQueue.trySend(task)
        if (!result.isSuccess) {
            upstreamQueue.send(task)
        }
        return false
    }

    private fun formatIPv6(packet: ByteArray, offset: Int): String {
        val sb = StringBuilder()
        for (i in 0 until 16 step 2) {
            if (i > 0) sb.append(":")
            val word = ((packet[offset + i].toInt() and 0xFF) shl 8) or (packet[offset + i + 1].toInt() and 0xFF)
            sb.append(String.format("%x", word))
        }
        return sb.toString()
    }

    private suspend fun processUpstreamTask(
        task: UpstreamTask,
        outputStream: FileOutputStream
    ) {
        if (servers.isEmpty()) {
            AppLog.e(TAG, "No DNS servers available")
            statisticsBuffer?.recordQuery(blocked = false, responseTime = 0, includeInAvg = false)
            writeResponseAndPatch(task, buildErrorDnsResponse(task.dnsPayload, 0x0002), outputStream)
            return
        }

        val result = dnsQueryExecutor?.query(
            domain = task.question.domain,
            servers = servers,
            query = task.dnsPayload,
            qtype = task.question.qtype,
            qclass = task.question.qclass
        )

        if (result?.success == true && result.responseBytes != null) {
            AppLog.d(TAG) { "DNS response: ${task.question.domain} (${result.responseTime}ms)" }
            val includeInAvg = !result.fromCache
            val recordedTime = if (result.fromCache) 0 else result.responseTime
            statisticsBuffer?.recordQuery(blocked = false, responseTime = recordedTime, includeInAvg = includeInAvg)
            writeResponseAndPatch(task, result.responseBytes, outputStream)
        } else {
            AppLog.e(TAG) { "DNS query failed: ${result?.error}" }
            statisticsBuffer?.recordQuery(blocked = false, responseTime = 0, includeInAvg = false)
            writeResponseAndPatch(task, buildErrorDnsResponse(task.dnsPayload, 0x0002), outputStream)
        }
    }

    private fun writeResponseAndPatch(
        task: UpstreamTask,
        dnsResponse: ByteArray,
        outputStream: FileOutputStream
    ) {
        if (task.isIPv6) {
            if (40 + 8 + dnsResponse.size <= task.packet.size) {
                dnsResponse.copyInto(task.packet, destinationOffset = 40 + 8)
                patchIPv6Response(task.packet, task.srcPort, dnsResponse.size)
                val responseLength = 40 + 8 + dnsResponse.size
                synchronized(outputStream) {
                    try {
                        outputStream.write(task.packet, 0, responseLength)
                        AppLog.d(TAG) { "Sent IPv6 DNS response to port=${task.srcPort}, length: $responseLength" }
                    } catch (e: Exception) {
                        AppLog.e(TAG) { "Failed to send IPv6 DNS response: ${e.message}" }
                    }
                }
            }
        } else {
            val ihl = task.ipHeaderLength
            if (ihl + 8 + dnsResponse.size <= task.packet.size) {
                dnsResponse.copyInto(task.packet, destinationOffset = ihl + 8)
                patchIPv4Response(task.packet, ihl, task.srcPort, dnsResponse.size)
                val responseLength = ihl + 8 + dnsResponse.size
                synchronized(outputStream) {
                    try {
                        outputStream.write(task.packet, 0, responseLength)
                        AppLog.d(TAG) { "Successfully sent IPv4 DNS response to port=${task.srcPort}, length: $responseLength" }
                    } catch (e: Exception) {
                        AppLog.e(TAG) { "Failed to send DNS response: ${e.message}" }
                    }
                }
            }
        }
    }

    private fun parseDnsQuery(data: ByteArray): DnsQuestion? {
        if (data.size < 12) return null

        // Check it's a standard query (QR bit = 0)
        val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val qrBit = (flags shr 15) and 1
        if (qrBit != 0) return null // Not a query

        val nameResult = readDnsName(data, 12, data.size) ?: return null
        val domain = nameResult.first
        val offset = nameResult.second
        if (offset + 4 > data.size) return null
        val qtype = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        val qclass = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)

        return DnsQuestion(domain, qtype, qclass)
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

    private fun buildBlockedDnsResponse(query: ByteArray): ByteArray {
        // Build DNS response with NXDOMAIN for blocked domains
        val response = ByteBuffer.allocate(512)

        // Transaction ID (copy from query)
        response.put(query[0])
        response.put(query[1])

        // Flags: Response, NXDOMAIN (rcode = 3), preserve RD
        val rdBit = (query[2].toInt() and 0x01)
        response.put((0x80 or rdBit).toByte())
        response.put((0x80 or 0x03).toByte())

        // Question count = 1
        response.put(0x00.toByte())
        response.put(0x01.toByte())

        // Answer count = 0
        response.put(0x00.toByte())
        response.put(0x00.toByte())

        // Authority count = 0
        response.put(0x00.toByte())
        response.put(0x00.toByte())

        // Additional count = 0
        response.put(0x00.toByte())
        response.put(0x00.toByte())

        // Copy question section from query
        var offset = 12
        while (offset < query.size) {
            val len = query[offset].toInt() and 0xFF
            response.put(query[offset])
            if (len == 0) {
                offset++
                break
            }
            if ((len and 0xC0) == 0xC0) {
                response.put(query[offset + 1])
                offset += 2
                break
            }
            for (i in 0 until len) {
                response.put(query[offset + 1 + i])
            }
            offset += 1 + len
        }

        // Copy QTYPE and QCLASS (4 bytes)
        if (offset + 4 <= query.size) {
            for (i in 0 until 4) {
                response.put(query[offset + i])
            }
        }

        val responseLen = response.position()
        val result = ByteArray(responseLen)
        response.flip()
        response.get(result)
        return result
    }

    private fun buildErrorDnsResponse(query: ByteArray, errorCode: Int): ByteArray {
        val response = ByteBuffer.allocate(512)

        // Transaction ID
        response.put(query[0])
        response.put(query[1])

        // Flags: Response, preserve RD, error code
        val rdBit = (query[2].toInt() and 0x01)
        response.put((0x80 or rdBit).toByte())
        response.put((0x80 or (errorCode and 0x0F)).toByte())

        // Question count = 1, others = 0
        response.put(0x00.toByte())
        response.put(0x01.toByte())
        response.put(0x00.toByte())
        response.put(0x00.toByte())
        response.put(0x00.toByte())
        response.put(0x00.toByte())

        // Copy question section
        var offset = 12
        while (offset < query.size) {
            val len = query[offset].toInt() and 0xFF
            response.put(query[offset])
            if (len == 0) {
                offset++
                break
            }
            if ((len and 0xC0) == 0xC0) {
                response.put(query[offset + 1])
                offset += 2
                break
            }
            for (i in 0 until len) {
                response.put(query[offset + 1 + i])
            }
            offset += 1 + len
        }

        // Copy QTYPE and QCLASS
        if (offset + 4 <= query.size) {
            for (i in 0 until 4) {
                response.put(query[offset + i])
            }
        }

        val responseLen = response.position()
        val result = ByteArray(responseLen)
        response.flip()
        response.get(result)
        return result
    }

    private fun patchIPv4Response(packet: ByteArray, ipHeaderLength: Int, srcPort: Int, dnsResponseLength: Int) {
        // 在原始 packet 上直接 patch 为响应包：交换 src/dst IP、更新 checksum、交换 UDP port
        val totalLength = ipHeaderLength + 8 + dnsResponseLength
        val udpLength = 8 + dnsResponseLength

        // Update IP total length (bytes 2-3)
        packet[2] = ((totalLength shr 8) and 0xFF).toByte()
        packet[3] = (totalLength and 0xFF).toByte()

        // Swap src/dst IP: bytes 12-15 <-> bytes 16-19
        for (i in 0 until 4) {
            val tmp = packet[12 + i]
            packet[12 + i] = packet[16 + i]
            packet[16 + i] = tmp
        }

        // Update TTL (byte 8)
        packet[8] = 64.toByte()

        // Zero IP checksum (bytes 10-11) then recalculate
        packet[10] = 0
        packet[11] = 0
        val ipChecksum = computeIpv4HeaderChecksum(packet, ipHeaderLength)
        packet[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // UDP header at offset ipHeaderLength
        val udpOff = ipHeaderLength

        // Swap UDP src/dst port: src=53, dst=原始 srcPort
        packet[udpOff]     = ((DNS_PORT shr 8) and 0xFF).toByte()
        packet[udpOff + 1] = (DNS_PORT and 0xFF).toByte()
        packet[udpOff + 2] = ((srcPort shr 8) and 0xFF).toByte()
        packet[udpOff + 3] = (srcPort and 0xFF).toByte()

        // Update UDP length (bytes udpOff+4..5)
        packet[udpOff + 4] = ((udpLength shr 8) and 0xFF).toByte()
        packet[udpOff + 5] = (udpLength and 0xFF).toByte()

        // Zero UDP checksum then recalculate using personalDnsfilter approach
        packet[udpOff + 6] = 0
        packet[udpOff + 7] = 0

        // personalDnsfilter trick: temporarily modify IPv4 header bytes 8-11 to pseudo-header
        val saved8 = packet[8]
        val saved9 = packet[9]
        val saved10 = packet[10]
        val saved11 = packet[11]

        packet[8] = 0      // zero field in pseudo header
        packet[9] = 17     // protocol = UDP
        packet[10] = ((udpLength shr 8) and 0xFF).toByte()
        packet[11] = (udpLength and 0xFF).toByte()

        val udpChecksum = computeGenericChecksum(packet, 8, totalLength - 8)

        packet[8] = saved8
        packet[9] = saved9
        packet[10] = saved10
        packet[11] = saved11

        packet[udpOff + 6] = ((udpChecksum shr 8) and 0xFF).toByte()
        packet[udpOff + 7] = (udpChecksum and 0xFF).toByte()
    }

    private fun patchIPv6Response(packet: ByteArray, srcPort: Int, dnsResponseLength: Int) {
        // 在原始 packet 上直接 patch 为 IPv6 响应包
        val payloadLength = 8 + dnsResponseLength
        val totalLength = 40 + payloadLength

        // Update Payload Length (bytes 4-5)
        packet[4] = ((payloadLength shr 8) and 0xFF).toByte()
        packet[5] = (payloadLength and 0xFF).toByte()

        // Update Hop Limit (byte 7)
        packet[7] = 64.toByte()

        // Swap src/dst IPv6: bytes 8-23 <-> bytes 24-39
        for (i in 0 until 16) {
            val tmp = packet[8 + i]
            packet[8 + i] = packet[24 + i]
            packet[24 + i] = tmp
        }

        // UDP header at offset 40
        // Swap src/dst port
        packet[40] = ((DNS_PORT shr 8) and 0xFF).toByte()
        packet[41] = (DNS_PORT and 0xFF).toByte()
        packet[42] = ((srcPort shr 8) and 0xFF).toByte()
        packet[43] = (srcPort and 0xFF).toByte()

        // Update UDP length (bytes 44-45)
        packet[44] = ((payloadLength shr 8) and 0xFF).toByte()
        packet[45] = (payloadLength and 0xFF).toByte()

        // Zero UDP checksum then recalculate
        packet[46] = 0
        packet[47] = 0
        val udpChecksum = computeIpv6UdpChecksum(packet, totalLength)
        packet[46] = ((udpChecksum shr 8) and 0xFF).toByte()
        packet[47] = (udpChecksum and 0xFF).toByte()
    }

    /**
     * 参考 personalDnsfilter 的 IPv6 UDP checksum 计算方式：
     * 临时把 IPv6 header 前 8 bytes 改写成伪头部格式，
     * 然后对整个 packet（从 offset 0 开始，长度 totalLength）计算 checksum，
     * 最后恢复原 header。
     */
    private fun computeIpv6UdpChecksum(packet: ByteArray, totalLength: Int): Int {
        // 保存 IPv6 header 前 8 bytes
        val saved = ByteArray(8)
        for (i in 0 until 8) saved[i] = packet[i]

        val udpLength = totalLength - 40

        // 临时构造伪头部到前 8 bytes（big-endian int 0 + int 1）
        // int 0 (bytes 0-3) = UDP length
        // int 1 (bytes 4-7) = 17 (protocol)
        packet[0] = 0
        packet[1] = 0
        packet[2] = ((udpLength shr 8) and 0xFF).toByte()
        packet[3] = (udpLength and 0xFF).toByte()
        packet[4] = 0
        packet[5] = 0
        packet[6] = 0
        packet[7] = 17.toByte()

        val checksum = computeGenericChecksum(packet, 0, totalLength)

        // 恢复原 header
        for (i in 0 until 8) packet[i] = saved[i]

        return if (checksum == 0) 0xFFFF else checksum
    }

    private fun computeGenericChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = 0
        while (i + 1 < length) {
            val word = ((data[offset + i].toInt() and 0xFF) shl 8) or (data[offset + i + 1].toInt() and 0xFF)
            sum += word
            if (sum > 0xFFFF) {
                sum = (sum and 0xFFFF) + 1
            }
            i += 2
        }
        if (i < length) {
            val word = (data[offset + i].toInt() and 0xFF) shl 8
            sum += word
            if (sum > 0xFFFF) {
                sum = (sum and 0xFFFF) + 1
            }
        }
        return sum.inv() and 0xFFFF
    }
    private fun computeIpv4HeaderChecksum(packet: ByteArray, headerLength: Int): Int {
        var sum = 0
        var i = 0
        while (i < headerLength) {
            if (i == 10) {
                i += 2
                continue
            }
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            if (sum > 0xFFFF) {
                sum = (sum and 0xFFFF) + 1
            }
            i += 2
        }
        return sum.inv() and 0xFFFF
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, com.deatrg.dnsfilter.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, DnsVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_pause),
            getString(R.string.notification_action_stop),
            stopPendingIntent
        ).build()

        return Notification.Builder(this, "dnsfilter_channel")
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .addAction(stopAction)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "dnsfilter_channel",
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        isServiceRunning = false
        stopVpn()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): android.os.IBinder? {
        return super.onBind(intent)
    }

    private fun startDnsServerTracking(preferencesManager: com.deatrg.dnsfilter.data.local.PreferencesManager) {
        serversJob?.cancel()
        serversJob = scope.launch {
            preferencesManager.dnsServers.collect { updatedServers ->
                servers = updatedServers.filter(::isSupportedDnsServer)
                AppLog.d(TAG) { "Updated active DNS servers: ${servers.size}" }
            }
        }
    }

    private fun isSupportedDnsServer(server: DnsServer): Boolean {
        return server.isEnabled && server.type != DnsServerType.DOT
    }
}
