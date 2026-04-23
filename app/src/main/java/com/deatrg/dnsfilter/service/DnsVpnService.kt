package com.deatrg.dnsfilter.service

import android.app.*
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
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
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
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
    private var servers: List<DnsServer> = emptyList()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 固定并发度 dispatcher，避免每个包都创建协程并竞争 Semaphore
    private val processingDispatcher = Dispatchers.IO.limitedParallelism(4)

    // Packet buffer 对象池，减少高并发时的内存分配压力
    private val packetPool = ArrayBlockingQueue<ByteArray>(256)

    private fun obtainPacket(): ByteArray = packetPool.poll() ?: ByteArray(MTU)
    private fun recyclePacket(packet: ByteArray) { packetPool.offer(packet) }

    private data class DnsQuestion(
        val domain: String,
        val qtype: Int,
        val qclass: Int
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
        AppLog.d(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return
        AppLog.d(TAG, "Starting VPN service")

        // Load servers synchronously BEFORE starting VPN
        runBlocking {
            val prefsManager = ServiceLocator.providePreferencesManager()
            servers = prefsManager.dnsServers.first().filter { it.isEnabled }
            AppLog.d(TAG, "Loaded ${servers.size} DNS servers")
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
            .setMtu(3000)
            .setBlocking(true)

        // Android Q+ 默认将VPN视为计费网络，设置为false让系统从底层网络继承计费状态
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

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
        vpnInterface?.close()
        vpnInterface = null

        // 刷新统计信息到磁盘（异步，不阻塞主线程）
        scope.launch {
            try {
                statisticsBuffer?.flush()
            } catch (e: Exception) {
                AppLog.e(TAG, "Failed to flush statistics", e)
            }
        }

        scope.cancel()
        dnsQueryExecutor?.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        AppLog.d(TAG, "VPN stopped")
    }

    private fun runDnsLoop() {
        val vpnInterface = this.vpnInterface ?: return
        val inputStream = FileInputStream(vpnInterface.fileDescriptor)
        val outputStream = FileOutputStream(vpnInterface.fileDescriptor)

        AppLog.d(TAG, "DNS loop started")

        try {
            while (isRunning) {
                val packet = obtainPacket()
                val length = inputStream.read(packet)
                if (length > 0) {
                    val version = packet[0].toInt() shr 4
                    val protocol = packet[9].toInt() and 0xFF
                    AppLog.d(TAG, "Packet received: version=$version, protocol=$protocol, length=$length")

                    if (version != 4 && version != 6) {
                        AppLog.d(TAG, "Unknown packet version=$version, byte0=${String.format("%02X", packet[0])}, byte1=${String.format("%02X", packet[1])}")
                    }
                    scope.launch(processingDispatcher) {
                        try {
                            processPacket(packet, length, outputStream)
                        } catch (e: Exception) {
                            AppLog.e(TAG, "Error processing packet: ${e.message}")
                        } finally {
                            recyclePacket(packet)
                        }
                    }
                } else {
                    recyclePacket(packet)
                    if (length == 0) {
                        // In blocking mode, read should block until data is available
                        // If it returns 0, it means immediate return with no data
                        AppLog.w(TAG, "read() returned 0, no data available")
                    }
                }
            }
        } catch (e: InterruptedIOException) {
            // 正常停止，vpnInterface.close() 会中断 read()
            AppLog.d(TAG, "DNS loop interrupted by stop")
        } catch (e: Exception) {
            AppLog.e(TAG, "Error in DNS loop", e)
        } finally {
            packetPool.clear()
            AppLog.d(TAG, "DNS loop stopped")
        }
    }

    private suspend fun processPacket(packet: ByteArray, length: Int, outputStream: FileOutputStream) {
        // Check minimum length for IP header
        if (length < 40) {
            AppLog.d(TAG, "Packet too short: $length bytes")
            return
        }

        // Get IP version from first nibble
        val version = packet[0].toInt() shr 4

        when (version) {
            4 -> processIPv4Packet(packet, length, outputStream)
            6 -> processIPv6Packet(packet, length, outputStream)
            else -> AppLog.d(TAG, "Unknown IP version: $version, skipping")
        }
    }

    private suspend fun processIPv4Packet(packet: ByteArray, length: Int, outputStream: FileOutputStream) {
        // Get protocol (byte 9) - 17 = UDP
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) {
            AppLog.d(TAG, "Non-UDP IPv4 packet, protocol=$protocol, skipping")
            return
        }

        // Calculate IP header length
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (length < ihl + 8) {
            AppLog.d(TAG, "Packet too short for UDP header")
            return
        }

        // Get UDP ports
        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)

        // Only handle DNS (port 53)
        if (dstPort != DNS_PORT) {
            AppLog.d(TAG, "Non-DNS UDP packet, dstPort=$dstPort, skipping")
            return
        }

        // Extract source and destination IPs from IP header
        val srcIp = "${packet[12].toInt() and 0xFF}.${packet[13].toInt() and 0xFF}.${packet[14].toInt() and 0xFF}.${packet[15].toInt() and 0xFF}"
        val dstIp = "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}.${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}"

        // DNS payload starts after IP header + UDP header
        val dnsPayload = packet.copyOfRange(ihl + 8, length)

        if (dnsPayload.isEmpty()) return

        AppLog.d(TAG, "IPv4 DNS query from $srcIp:$srcPort to $dstIp, DNS length: ${dnsPayload.size}")

        // Process DNS query
        val response = processDnsQuery(dnsPayload, srcIp, srcPort)

        if (response != null) {
            // 复用原始 packet 数组：把 DNS 响应写回 payload 位置，patch IP/UDP header
            response.copyInto(packet, destinationOffset = ihl + 8)
            patchIPv4Response(packet, ihl, srcPort, response.size)
            val responseLength = ihl + 8 + response.size
            synchronized(outputStream) {
                try {
                    outputStream.write(packet, 0, responseLength)
                    AppLog.d(TAG, "Successfully sent IPv4 DNS response to $srcIp:$srcPort, length: $responseLength")
                } catch (e: Exception) {
                    AppLog.e(TAG, "Failed to send DNS response: ${e.message}")
                }
            }
        } else {
            AppLog.w(TAG, "No DNS response generated")
        }
    }

    private suspend fun processIPv6Packet(packet: ByteArray, length: Int, outputStream: FileOutputStream) {
        // In IPv6, byte 6 is "Next Header" - 17 = UDP
        val nextHeader = packet[6].toInt() and 0xFF
        if (nextHeader != 17) {
            AppLog.d(TAG, "Non-UDP IPv6 packet, nextHeader=$nextHeader, skipping")
            return
        }

        // IPv6 fixed header is 40 bytes
        val ipv6HeaderLength = 40
        if (length < ipv6HeaderLength + 8) {
            AppLog.d(TAG, "Packet too short for IPv6 UDP header")
            return
        }

        // Get UDP ports (after IPv6 fixed header)
        val srcPort = ((packet[ipv6HeaderLength].toInt() and 0xFF) shl 8) or (packet[ipv6HeaderLength + 1].toInt() and 0xFF)
        val dstPort = ((packet[ipv6HeaderLength + 2].toInt() and 0xFF) shl 8) or (packet[ipv6HeaderLength + 3].toInt() and 0xFF)

        // Only handle DNS (port 53)
        if (dstPort != DNS_PORT) {
            AppLog.d(TAG, "Non-DNS IPv6 UDP packet, dstPort=$dstPort, skipping")
            return
        }

        // Extract source and destination IPv6 addresses
        val srcIp = formatIPv6(packet, 8)
        val dstIp = formatIPv6(packet, 24)

        // DNS payload starts after IPv6 header + UDP header
        val dnsPayload = packet.copyOfRange(ipv6HeaderLength + 8, length)

        if (dnsPayload.isEmpty()) return

        AppLog.d(TAG, "IPv6 DNS query from $srcIp:$srcPort to $dstIp, DNS length: ${dnsPayload.size}")

        // Process DNS query
        val response = processDnsQuery(dnsPayload, srcIp, srcPort)

        if (response != null) {
            // 复用原始 packet 数组：把 DNS 响应写回 payload 位置，patch IPv6/UDP header
            response.copyInto(packet, destinationOffset = ipv6HeaderLength + 8)
            patchIPv6Response(packet, srcPort, response.size)
            val responseLength = ipv6HeaderLength + 8 + response.size
            synchronized(outputStream) {
                try {
                    outputStream.write(packet, 0, responseLength)
                    AppLog.d(TAG, "Sent IPv6 DNS response to $srcIp:$srcPort, length: $responseLength")
                } catch (e: Exception) {
                    AppLog.e(TAG, "Failed to send IPv6 DNS response: ${e.message}")
                }
            }
        } else {
            AppLog.w(TAG, "No DNS response generated")
        }
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

    private suspend fun processDnsQuery(dnsPayload: ByteArray, clientIp: String, clientPort: Int): ByteArray? {
        val startTime = System.currentTimeMillis()
        
        // Parse domain from DNS query
        val question = parseDnsQuery(dnsPayload) ?: run {
            AppLog.e(TAG, "Failed to parse DNS query")
            return null
        }

        AppLog.d(TAG, "Query for domain: ${question.domain}, qtype=${question.qtype}, servers=${servers.size}")

        // Check if domain is blocked
        val blockResult = domainFilter?.isDomainBlocked(question.domain)
        if (blockResult?.isBlocked == true) {
            AppLog.d(TAG, "Domain ${question.domain} is blocked: ${blockResult.reason}")
            
            // Update statistics（被拦截的请求不计入平均响应时间）
            statisticsBuffer?.recordQuery(blocked = true, responseTime = 0, includeInAvg = false)
            
            return buildBlockedDnsResponse(dnsPayload)
        }

        // Ensure servers are available
        if (servers.isEmpty()) {
            AppLog.e(TAG, "No DNS servers available")
            return buildErrorDnsResponse(dnsPayload, 0x0002) // SERVFAIL
        }

        // Forward to upstream DNS
        val result = dnsQueryExecutor?.query(question.domain, servers, question.qtype)

        if (result?.success == true && result.responseIp != null) {
            AppLog.d(TAG, "DNS response: ${question.domain} -> ${result.responseIp} (${result.responseTime}ms)")

            // 只有真实发送的上游请求才计入平均响应时间，缓存命中不计入
            val includeInAvg = !result.fromCache
            val recordedTime = if (result.fromCache) 0 else result.responseTime
            statisticsBuffer?.recordQuery(blocked = false, responseTime = recordedTime, includeInAvg = includeInAvg)
            
            return buildDnsResponse(dnsPayload, question.qtype, result.responseIp)
        }

        AppLog.e(TAG, "DNS query failed: ${result?.error}")
        return buildErrorDnsResponse(dnsPayload, 0x0002) // SERVFAIL
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

    private fun buildDnsResponse(query: ByteArray, qtype: Int, responseIp: String): ByteArray {
        val response = ByteBuffer.allocate(512)

        // Transaction ID
        response.put(query[0])
        response.put(query[1])

        // Flags: Standard response, No error, preserve RD
        val rdBit = (query[2].toInt() and 0x01)
        response.put((0x80 or rdBit).toByte())
        response.put(0x80.toByte())

        // Question count = 1
        response.put(0x00.toByte())
        response.put(0x01.toByte())

        // Answer count = 1
        response.put(0x00.toByte())
        response.put(0x01.toByte())

        // Authority and Additional = 0
        response.put(0x00.toByte())
        response.put(0x00.toByte())
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

        // Answer section
        // Pointer to question name (0xC00C = offset 12)
        response.put(0xC0.toByte())
        response.put(0x0C.toByte())

        // TYPE
        response.put(((qtype shr 8) and 0xFF).toByte())
        response.put((qtype and 0xFF).toByte())

        // CLASS IN (1)
        response.put(0x00.toByte())
        response.put(0x01.toByte())

        // TTL: 300 seconds
        response.put(0x00.toByte())
        response.put(0x00.toByte())
        response.put(0x01.toByte())
        response.put(0x2C.toByte())

        if (qtype == 28) {
            // RDLENGTH: 16 bytes (IPv6)
            response.put(0x00.toByte())
            response.put(0x10.toByte())
            val addr = InetAddress.getByName(responseIp).address
            if (addr.size != 16) return buildErrorDnsResponse(query, 0x0002)
            response.put(addr)
        } else {
            // Default to A record
            response.put(0x00.toByte())
            response.put(0x04.toByte())
            val ipParts = responseIp.split(".")
            if (ipParts.size != 4) return buildErrorDnsResponse(query, 0x0002)
            for (part in ipParts) {
                response.put(part.toIntOrNull()?.toByte() ?: 0)
            }
        }

        val responseLen = response.position()
        val dnsResponse = ByteArray(responseLen)
        response.flip()
        response.get(dnsResponse)
        return dnsResponse
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
        val intent = Intent(this, DnsVpnService::class.java)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
    }

    override fun onDestroy() {
        isServiceRunning = false
        stopVpn()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): android.os.IBinder? {
        return super.onBind(intent)
    }
}
