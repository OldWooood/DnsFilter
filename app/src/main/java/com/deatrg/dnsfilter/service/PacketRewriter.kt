package com.deatrg.dnsfilter.service

/**
 * 将拦截到的出站 DNS 查询包原地改写为响应包：
 * 交换 src/dst 地址与端口、更新长度字段并重算校验和。
 */
internal object PacketRewriter {

    /**
     * 统一入口：按 IP 版本改写包头。
     * @param isIPv6 true 为 IPv6 包
     * @param ipHeaderLength IPv4 的 IHL（字节），IPv6 固定 40
     * @param dnsResponseLength DNS 报文长度（不含 IP/UDP 头）
     * @return 完整响应包长度
     */
    fun rewriteAsResponse(
        packet: ByteArray,
        isIPv6: Boolean,
        ipHeaderLength: Int,
        srcPort: Int,
        dnsResponseLength: Int
    ): Int {
        return if (isIPv6) {
            rewriteIpv6(packet, srcPort, dnsResponseLength)
        } else {
            rewriteIpv4(packet, ipHeaderLength, srcPort, dnsResponseLength)
        }
    }

    private fun rewriteIpv4(packet: ByteArray, ipHeaderLength: Int, srcPort: Int, dnsResponseLength: Int): Int {
        val totalLength = ipHeaderLength + UDP_HEADER_LENGTH + dnsResponseLength
        val udpLength = UDP_HEADER_LENGTH + dnsResponseLength

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

        val udpOff = ipHeaderLength

        // Swap UDP src/dst port: src=53, dst=原始 srcPort
        packet[udpOff] = ((DNS_PORT shr 8) and 0xFF).toByte()
        packet[udpOff + 1] = (DNS_PORT and 0xFF).toByte()
        packet[udpOff + 2] = ((srcPort shr 8) and 0xFF).toByte()
        packet[udpOff + 3] = (srcPort and 0xFF).toByte()

        // Update UDP length (bytes udpOff+4..5)
        packet[udpOff + 4] = ((udpLength shr 8) and 0xFF).toByte()
        packet[udpOff + 5] = (udpLength and 0xFF).toByte()

        // Skip UDP checksum for IPv4 (RFC 768: zero means "not computed", receiver should not verify)
        packet[udpOff + 6] = 0
        packet[udpOff + 7] = 0

        return totalLength
    }

    private fun rewriteIpv6(packet: ByteArray, srcPort: Int, dnsResponseLength: Int): Int {
        val payloadLength = UDP_HEADER_LENGTH + dnsResponseLength
        val totalLength = IPV6_HEADER_LENGTH + payloadLength

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

        // Swap UDP src/dst port
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

        return totalLength
    }

    /**
     * IPv6 UDP 校验和：直接累加 RFC 2460 伪头部的字序列——
     * [udpLength 一个字] + [nextHeader(17) 一个字] + src/dst 地址（bytes 8..39）
     * + UDP 头与载荷（bytes IPV6_HEADER_LENGTH..totalLength）。
     * 与旧的"临时改写包头前 8 字节再恢复"方式逐字等价，但不再每包分配
     * saved ByteArray、也不做两轮 copyInto，且天然线程安全。
     */
    internal fun computeIpv6UdpChecksum(packet: ByteArray, totalLength: Int): Int {
        val udpLength = totalLength - IPV6_HEADER_LENGTH

        // 旧伪头部字序列 [0,0,lenHi,lenLo,0,0,0,17] 等价于这两个字。
        // MTU <= 8000，32 位 Int 累加全部 16 位字绝不会溢出，循环内无需进位分支，
        // 仅在末尾一次性折叠高 16 位进位。
        var sum = (udpLength and 0xFFFF) + 17

        // src + dst（bytes 8..39）与 UDP 头 + 载荷（bytes 40..totalLength）连续，合并单循环遍历
        var i = 8
        while (i + 1 < totalLength) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < totalLength) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }

        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }

        val checksum = sum.inv() and 0xFFFF
        // UDP 校验和的 0x0000 表示"未计算"，计算结果为 0 时按标准发送 0xFFFF。
        return if (checksum == 0) 0xFFFF else checksum
    }

    internal fun computeIpv4HeaderChecksum(packet: ByteArray, headerLength: Int): Int {
        var sum = 0
        var i = 0
        while (i < headerLength) {
            if (i != 10) {
                sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            }
            i += 2
        }
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }

    internal const val IPV6_HEADER_LENGTH = 40
    internal const val UDP_HEADER_LENGTH = 8
    internal const val DNS_PORT = 53
}
