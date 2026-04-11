package com.deatrg.dnsfilter.service

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.deatrg.dnsfilter.ServiceLocator
import com.deatrg.dnsfilter.data.local.StatisticsBuffer
import com.deatrg.dnsfilter.data.remote.DomainFilter
import com.deatrg.dnsfilter.data.remote.DnsQueryExecutor
import com.deatrg.dnsfilter.domain.model.DnsServer
import com.deatrg.dnsfilter.domain.model.DnsServerType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

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
    
    // 限制并发处理数量，防止创建过多协程
    private val processingSemaphore = Semaphore(1024)

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
        Log.d(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return
        Log.d(TAG, "Starting VPN service")

        // Load servers synchronously BEFORE starting VPN
        runBlocking {
            val prefsManager = ServiceLocator.providePreferencesManager()
            servers = prefsManager.dnsServers.first().filter { it.isEnabled }
            Log.d(TAG, "Loaded ${servers.size} DNS servers")
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
            Log.d(TAG, "VPN established successfully")
        } else {
            isRunning = false
            isServiceRunning = false
            Log.e(TAG, "Failed to establish VPN interface")
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
                Log.e(TAG, "Failed to flush statistics", e)
            }
        }

        scope.cancel()
        dnsQueryExecutor?.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "VPN stopped")
    }

    private fun runDnsLoop() {
        val vpnInterface = this.vpnInterface ?: return
        val inputStream = FileInputStream(vpnInterface.fileDescriptor)
        val outputStream = FileOutputStream(vpnInterface.fileDescriptor)
        val packet = ByteArray(MTU)

        Log.d(TAG, "DNS loop started")

        try {
            while (isRunning) {
                val length = inputStream.read(packet)
                if (length > 0) {
                    val version = packet[0].toInt() shr 4
                    val protocol = packet[9].toInt() and 0xFF
                    Log.d(TAG, "Packet received: version=$version, protocol=$protocol, length=$length")

                    val packetCopy = packet.copyOf(length)
                    if (version != 4 && version != 6) {
                        Log.d(TAG, "Unknown packet version=$version, byte0=${String.format("%02X", packet[0])}, byte1=${String.format("%02X", packet[1])}")
                    }
                    // 使用 Semaphore 限制并发处理数量
                    scope.launch {
                        processingSemaphore.withPermit {
                            try {
                                processPacket(packetCopy, length, outputStream)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing packet: ${e.message}")
                            }
                        }
                    }
                } else if (length == 0) {
                    // In blocking mode, read should block until data is available
                    // If it returns 0, it means immediate return with no data
                    Log.w(TAG, "read() returned 0, no data available")
                }
            }
        } catch (e: InterruptedIOException) {
            // 正常停止，vpnInterface.close() 会中断 read()
            Log.d(TAG, "DNS loop interrupted by stop")
        } catch (e: Exception) {
            Log.e(TAG, "Error in DNS loop", e)
        } finally {
            Log.d(TAG, "DNS loop stopped")
        }
    }

    private suspend fun processPacket(packet: ByteArray, length: Int, outputStream: FileOutputStream) {
        // Check minimum length for IP header
        if (length < 40) {
            Log.d(TAG, "Packet too short: $length bytes")
            return
        }

        // Get IP version from first nibble
        val version = packet[0].toInt() shr 4

        when (version) {
            4 -> processIPv4Packet(packet, length, outputStream)
            6 -> processIPv6Packet(packet, length, outputStream)
            else -> Log.d(TAG, "Unknown IP version: $version, skipping")
        }
    }

    private suspend fun processIPv4Packet(packet: ByteArray, length: Int, outputStream: FileOutputStream) {
        // Get protocol (byte 9) - 17 = UDP
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) {
            Log.d(TAG, "Non-UDP IPv4 packet, protocol=$protocol, skipping")
            return
        }

        // Calculate IP header length
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (length < ihl + 8) {
            Log.d(TAG, "Packet too short for UDP header")
            return
        }

        // Get UDP ports
        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)

        // Only handle DNS (port 53)
        if (dstPort != DNS_PORT) {
            Log.d(TAG, "Non-DNS UDP packet, dstPort=$dstPort, skipping")
            return
        }

        // Extract source and destination IPs from IP header
        val srcIp = "${packet[12].toInt() and 0xFF}.${packet[13].toInt() and 0xFF}.${packet[14].toInt() and 0xFF}.${packet[15].toInt() and 0xFF}"
        val dstIp = "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}.${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}"

        // DNS payload starts after IP header + UDP header
        val dnsPayload = packet.copyOfRange(ihl + 8, length)

        if (dnsPayload.isEmpty()) return

        Log.d(TAG, "IPv4 DNS query from $srcIp:$srcPort to $dstIp, DNS length: ${dnsPayload.size}")

        // Process DNS query
        val response = processDnsQuery(dnsPayload, srcIp, srcPort)

        if (response != null) {
            // Build the full IP/UDP packet
            val fullResponse = buildIPv4FullResponse(packet, ihl, srcPort, response)
            // Log response details for debugging
            Log.d(TAG, "Response packet: src=${fullResponse[12].toInt() and 0xFF}.${fullResponse[13].toInt() and 0xFF}.${fullResponse[14].toInt() and 0xFF}.${fullResponse[15].toInt() and 0xFF}, " +
                    "dst=${fullResponse[16].toInt() and 0xFF}.${fullResponse[17].toInt() and 0xFF}.${fullResponse[18].toInt() and 0xFF}.${fullResponse[19].toInt() and 0xFF}, " +
                    "UDP src=${((fullResponse[20].toInt() and 0xFF) shl 8) or (fullResponse[21].toInt() and 0xFF)}, " +
                    "UDP dst=${((fullResponse[22].toInt() and 0xFF) shl 8) or (fullResponse[23].toInt() and 0xFF)}")
            synchronized(outputStream) {
                try {
                    outputStream.write(fullResponse)
                    outputStream.flush()
                    Log.d(TAG, "Successfully sent IPv4 DNS response to $srcIp:$srcPort, length: ${fullResponse.size}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send DNS response: ${e.message}")
                }
            }
        } else {
            Log.w(TAG, "No DNS response generated")
        }
    }

    private suspend fun processIPv6Packet(packet: ByteArray, length: Int, outputStream: FileOutputStream) {
        // In IPv6, byte 6 is "Next Header" - 17 = UDP
        val nextHeader = packet[6].toInt() and 0xFF
        if (nextHeader != 17) {
            Log.d(TAG, "Non-UDP IPv6 packet, nextHeader=$nextHeader, skipping")
            return
        }

        // IPv6 fixed header is 40 bytes
        val ipv6HeaderLength = 40
        if (length < ipv6HeaderLength + 8) {
            Log.d(TAG, "Packet too short for IPv6 UDP header")
            return
        }

        // Get UDP ports (after IPv6 fixed header)
        val srcPort = ((packet[ipv6HeaderLength].toInt() and 0xFF) shl 8) or (packet[ipv6HeaderLength + 1].toInt() and 0xFF)
        val dstPort = ((packet[ipv6HeaderLength + 2].toInt() and 0xFF) shl 8) or (packet[ipv6HeaderLength + 3].toInt() and 0xFF)

        // Only handle DNS (port 53)
        if (dstPort != DNS_PORT) {
            Log.d(TAG, "Non-DNS IPv6 UDP packet, dstPort=$dstPort, skipping")
            return
        }

        // Extract source and destination IPv6 addresses
        val srcIp = formatIPv6(packet, 8)
        val dstIp = formatIPv6(packet, 24)

        // DNS payload starts after IPv6 header + UDP header
        val dnsPayload = packet.copyOfRange(ipv6HeaderLength + 8, length)

        if (dnsPayload.isEmpty()) return

        Log.d(TAG, "IPv6 DNS query from $srcIp:$srcPort to $dstIp, DNS length: ${dnsPayload.size}")

        // Process DNS query
        val response = processDnsQuery(dnsPayload, srcIp, srcPort)

        if (response != null) {
            // Build the full IPv6/UDP packet
            val fullResponse = buildIPv6FullResponse(packet, srcPort, response)
            synchronized(outputStream) {
                outputStream.write(fullResponse)
                outputStream.flush()
            }
            Log.d(TAG, "Sent IPv6 DNS response to $srcIp:$srcPort, length: ${fullResponse.size}")
        } else {
            Log.w(TAG, "No DNS response generated")
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
            Log.e(TAG, "Failed to parse DNS query")
            return null
        }

        Log.d(TAG, "Query for domain: ${question.domain}, qtype=${question.qtype}, servers=${servers.size}")

        // Check if domain is blocked
        val blockResult = domainFilter?.isDomainBlocked(question.domain)
        if (blockResult?.isBlocked == true) {
            Log.d(TAG, "Domain ${question.domain} is blocked: ${blockResult.reason}")
            
            // Record blocked query log
            val responseTime = System.currentTimeMillis() - startTime
            // Update statistics（使用内存缓冲，无磁盘 I/O）
            statisticsBuffer?.recordQuery(blocked = true, responseTime = responseTime)
            
            return buildBlockedDnsResponse(dnsPayload)
        }

        // Ensure servers are available
        if (servers.isEmpty()) {
            Log.e(TAG, "No DNS servers available")
            return buildErrorDnsResponse(dnsPayload, 0x0002) // SERVFAIL
        }

        // Forward to upstream DNS
        val result = dnsQueryExecutor?.query(question.domain, servers, question.qtype)
        val responseTime = System.currentTimeMillis() - startTime

        if (result?.success == true && result.responseIp != null) {
            Log.d(TAG, "DNS response: ${question.domain} -> ${result.responseIp} (${result.responseTime}ms)")

            // Update statistics（使用内存缓冲，无磁盘 I/O）
            statisticsBuffer?.recordQuery(blocked = false, responseTime = responseTime)
            
            return buildDnsResponse(dnsPayload, question.qtype, result.responseIp)
        }

        Log.e(TAG, "DNS query failed: ${result?.error}")
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

    private fun buildIPv4FullResponse(originalPacket: ByteArray, ipHeaderLength: Int, srcPort: Int, dnsResponse: ByteArray): ByteArray {
        // Response IP header is always exactly 20 bytes (standard header, no options)
        val responseIpHeaderLength = 20
        val totalLength = responseIpHeaderLength + 8 + dnsResponse.size
        val packet = ByteBuffer.allocate(totalLength)

        // Build IP header
        // Version (4) and IHL (5 = 20 bytes)
        packet.put(0x45.toByte())
        // TOS
        packet.put(0x00.toByte())
        // Total length
        packet.put(((totalLength shr 8) and 0xFF).toByte())
        packet.put((totalLength and 0xFF).toByte())
        // ID (copy from original)
        packet.put(originalPacket[4])
        packet.put(originalPacket[5])
        // Flags and Fragment (copy from original)
        packet.put(originalPacket[6])
        packet.put(originalPacket[7])
        // TTL (64 is a reasonable default)
        packet.put(64.toByte())
        // Protocol (UDP = 17)
        packet.put(17.toByte())
        // Header checksum (computed later)
        packet.put(0x00.toByte())
        packet.put(0x00.toByte())

        // 交换源和目的IP地址
        // 原始包：src=客户端IP (bytes 12-15), dst=VPN_DNS_V4 (bytes 16-19)
        // 响应包：src=VPN_DNS_V4, dst=客户端IP
        val vpnDnsIpBytes = VPN_DNS_V4.split(".").map { it.toInt().toByte() }.toByteArray()
        
        // 源IP（响应包中应该是虚拟DNS地址）
        for (i in 0 until 4) {
            packet.put(vpnDnsIpBytes[i])
        }
        // 目的IP（响应包中应该是客户端IP）
        for (i in 0 until 4) {
            packet.put(originalPacket[12 + i]) // 原始包的源IP
        }

        // UDP header
        // Source port = 53 (DNS)
        packet.put(((DNS_PORT shr 8) and 0xFF).toByte())
        packet.put((DNS_PORT and 0xFF).toByte())
        // Destination port = original source port
        packet.put(((srcPort shr 8) and 0xFF).toByte())
        packet.put((srcPort and 0xFF).toByte())
        // UDP length
        val udpLength = 8 + dnsResponse.size
        packet.put(((udpLength shr 8) and 0xFF).toByte())
        packet.put((udpLength and 0xFF).toByte())
        // UDP checksum (computed later)
        packet.put(0x00.toByte())
        packet.put(0x00.toByte())

        // DNS payload
        packet.put(dnsResponse)

        val result = ByteArray(packet.position())
        packet.flip()
        packet.get(result)

        // Compute IPv4 header checksum and write it back
        val checksum = computeIpv4HeaderChecksum(result, responseIpHeaderLength)
        result[10] = ((checksum shr 8) and 0xFF).toByte()
        result[11] = (checksum and 0xFF).toByte()

        // Compute UDP checksum and write it back
        val udpChecksumValue = computeUdpChecksum(result, responseIpHeaderLength)
        val udpChecksumOffset = responseIpHeaderLength + 6
        result[udpChecksumOffset] = ((udpChecksumValue shr 8) and 0xFF).toByte()
        result[udpChecksumOffset + 1] = (udpChecksumValue and 0xFF).toByte()
        return result
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

    private fun computeUdpChecksum(packet: ByteArray, ipHeaderLength: Int): Int {
        val udpOffset = ipHeaderLength
        val udpLength = ((packet[udpOffset + 4].toInt() and 0xFF) shl 8) or (packet[udpOffset + 5].toInt() and 0xFF)

        var sum = 0

        // Pseudo-header: source IP, dest IP, zero, protocol, UDP length
        for (i in 12 until 20 step 2) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
        }
        sum += 17 // Protocol UDP
        sum += udpLength

        // UDP header and payload
        var i = udpOffset
        val end = udpOffset + udpLength
        while (i + 1 < end) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            if (sum > 0xFFFF) {
                sum = (sum and 0xFFFF) + 1
            }
            i += 2
        }
        if (i < end) {
            // Odd byte padding
            val word = (packet[i].toInt() and 0xFF) shl 8
            sum += word
            if (sum > 0xFFFF) {
                sum = (sum and 0xFFFF) + 1
            }
        }

        return sum.inv() and 0xFFFF
    }

    private fun buildIPv6FullResponse(originalPacket: ByteArray, srcPort: Int, dnsResponse: ByteArray): ByteArray {
        // IPv6 header is 40 bytes, UDP header is 8 bytes
        val totalLength = 40 + 8 + dnsResponse.size
        val packet = ByteBuffer.allocate(totalLength)

        // IPv6 Header
        // Version (6) + Traffic Class (8 bits) + Flow Label (20 bits) = first 4 bytes
        packet.put(0x60.toByte()) // Version 6, traffic class = 0
        packet.put(0x00.toByte())
        packet.put(0x00.toByte())
        packet.put(0x00.toByte())
        // Payload Length (UDP header + DNS response)
        val payloadLength = 8 + dnsResponse.size
        packet.put(((payloadLength shr 8) and 0xFF).toByte())
        packet.put((payloadLength and 0xFF).toByte())
        // Next Header (UDP = 17)
        packet.put(17.toByte())
        // Hop Limit
        packet.put(64.toByte())

        // 源地址应该是虚拟DNS地址 fd00::10
        val srcAddr = byteArrayOf(
            0xfd.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10
        )
        packet.put(srcAddr)

        // Destination address (from original packet, bytes 24-39) - 16 bytes
        packet.put(originalPacket, 24, 16)

        // UDP header
        // Source port = 53 (DNS)
        packet.put(((DNS_PORT shr 8) and 0xFF).toByte())
        packet.put((DNS_PORT and 0xFF).toByte())
        // Destination port = original source port
        packet.put(((srcPort shr 8) and 0xFF).toByte())
        packet.put((srcPort and 0xFF).toByte())
        // UDP length
        val udpLength = 8 + dnsResponse.size
        packet.put(((udpLength shr 8) and 0xFF).toByte())
        packet.put((udpLength and 0xFF).toByte())
        // UDP checksum (computed later) - placeholder
        packet.put(0x00.toByte())
        packet.put(0x00.toByte())

        // DNS payload
        packet.put(dnsResponse)

        val result = ByteArray(packet.position())
        packet.flip()
        packet.get(result)

        // Compute UDP checksum for IPv6
        val udpChecksum = computeIpv6UdpChecksum(result, srcAddr, 24)
        result[42] = ((udpChecksum shr 8) and 0xFF).toByte()
        result[43] = (udpChecksum and 0xFF).toByte()

        return result
    }

    private fun computeIpv6UdpChecksum(packet: ByteArray, srcAddr: ByteArray, dstAddrOffset: Int): Int {
        // IPv6 pseudo-header: src IP (16) + dst IP (16) + UDP length (4) + Next Header (3 bytes padded)
        var sum = 0

        // Source address
        for (i in srcAddr.indices step 2) {
            val word = ((srcAddr[i].toInt() and 0xFF) shl 8) or (srcAddr[i + 1].toInt() and 0xFF)
            sum += word
        }
        // Destination address
        for (i in 0 until 16 step 2) {
            val word = ((packet[24 + i].toInt() and 0xFF) shl 8) or (packet[24 + i + 1].toInt() and 0xFF)
            sum += word
        }
        // UDP length
        val udpLength = ((packet[42].toInt() and 0xFF) shl 8) or (packet[43].toInt() and 0xFF)
        sum += udpLength
        // Next header (17 = UDP)
        sum += 17

        // UDP header + payload
        var i = 40 // Start of UDP header
        val end = 40 + udpLength
        while (i + 1 < end) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            if (sum > 0xFFFF) {
                sum = (sum and 0xFFFF) + 1
            }
            i += 2
        }
        if (i < end) {
            // Odd byte padding
            val word = (packet[i].toInt() and 0xFF) shl 8
            sum += word
            if (sum > 0xFFFF) {
                sum = (sum and 0xFFFF) + 1
            }
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

        return Notification.Builder(this, "dnsfilter_channel")
            .setContentTitle("DnsFilter Active")
            .setContentText("DNS filtering is enabled")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "dnsfilter_channel",
                "DnsFilter Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "DNS filtering service notification"
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
