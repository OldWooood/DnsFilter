package com.deatrg.dnsfilter.service

/**
 * 描述一个已解析的 IP 包头中与改写响应相关的信息。
 */
internal data class PacketContext(
    val isIPv6: Boolean,
    val ipHeaderLength: Int
)

/**
 * 将拦截到的出站 DNS 查询包原地改写为响应包：
 * 交换 src/dst 地址与端口、更新长度字段并重算校验和。
 */
internal object PacketRewriter {

    /**
     * 统一入口：按 IP 版本改写包头。
     * @param dnsResponseLength DNS 报文长度（不含 IP/UDP 头）
     * @return 完整响应包长度
     */
    fun rewriteAsResponse(
        packet: ByteArray,
        ctx: PacketContext,
        srcPort: Int,
        dnsResponseLength: Int
    ): Int {
        return if (ctx.isIPv6) {
            rewriteIpv6(packet, srcPort, dnsResponseLength)
        } else {
            rewriteIpv4(packet, ctx.ipHeaderLength, srcPort, dnsResponseLength)
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
     * 参考 personalDnsfilter 的 IPv6 UDP checksum 计算方式：
     * 临时把 IPv6 header 前 8 bytes 改写成伪头部格式，
     * 然后对整个 packet（从 offset 0 开始，长度 totalLength）计算 checksum，
     * 最后恢复原 header。
     */
    internal fun computeIpv6UdpChecksum(packet: ByteArray, totalLength: Int): Int {
        // 保存 IPv6 header 前 8 bytes
        val saved = ByteArray(8)
        packet.copyInto(saved, destinationOffset = 0, startIndex = 0, endIndex = 8)

        val udpLength = totalLength - IPV6_HEADER_LENGTH

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
        saved.copyInto(packet, destinationOffset = 0)

        return if (checksum == 0) 0xFFFF else checksum
    }

    internal fun computeIpv4HeaderChecksum(packet: ByteArray, headerLength: Int): Int {
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

    internal const val IPV6_HEADER_LENGTH = 40
    internal const val UDP_HEADER_LENGTH = 8
    internal const val DNS_PORT = 53
}
