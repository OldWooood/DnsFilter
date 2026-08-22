package com.deatrg.dnsfilter.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketRewriterTest {

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    @Test
    fun ipv4HeaderChecksumMatchesKnownVector() {
        // 经典校验向量（Wikipedia IPv4 header 示例）：
        // 4500 0073 0000 4000 4011 0000 c0a8 0001 c0a8 00c7 -> checksum 0xb861
        val header = byteArrayOf(
            0x45, 0x00, 0x00, 0x73, 0x00, 0x00, 0x40, 0x00,
            0x40, 0x11, 0x00, 0x00, 0xC0.toByte(), 0xA8.toByte(), 0x00, 0x01,
            0xC0.toByte(), 0xA8.toByte(), 0x00, 0xC7.toByte()
        )

        val checksum = PacketRewriter.computeIpv4HeaderChecksum(header, header.size)

        assertEquals(0xB861, checksum)
    }

    @Test
    fun ipv4HeaderChecksumVerifiesToZeroWhenIncluded() {
        val header = byteArrayOf(
            0x45, 0x00, 0x00, 0x73, 0x00, 0x00, 0x40, 0x00,
            0x40, 0x11, 0x00, 0x00, 0xC0.toByte(), 0xA8.toByte(), 0x00, 0x01,
            0xC0.toByte(), 0xA8.toByte(), 0x00, 0xC7.toByte()
        )
        val checksum = PacketRewriter.computeIpv4HeaderChecksum(header, header.size)
        header[10] = ((checksum shr 8) and 0xFF).toByte()
        header[11] = (checksum and 0xFF).toByte()

        // 带上 checksum 字段重新求和，反码和应为 0xFFFF
        var sum = 0
        var i = 0
        while (i < header.size) {
            sum += u16(header, i)
            if (sum > 0xFFFF) sum = (sum and 0xFFFF) + 1
            i += 2
        }
        assertEquals(0xFFFF, sum)
    }

    @Test
    fun rewriteAsIpv4ResponseSwapsEndpointsAndSetsLengths() {
        val dnsPayload = ByteArray(17) { it.toByte() }
        val totalLength = 20 + 8 + dnsPayload.size
        val packet = ByteArray(totalLength)

        // IPv4 查询包：src=192.168.1.100:5555 -> dst=10.10.10.10:53
        packet[0] = 0x45
        packet[9] = 17
        val srcIp = byteArrayOf(192.toByte(), 168.toByte(), 1, 100)
        val dstIp = byteArrayOf(10, 10, 10, 10)
        srcIp.copyInto(packet, 12)
        dstIp.copyInto(packet, 16)
        packet[20] = ((5555 shr 8) and 0xFF).toByte()
        packet[21] = (5555 and 0xFF).toByte()
        packet[22] = 0
        packet[23] = PacketRewriter.DNS_PORT.toByte()
        dnsPayload.copyInto(packet, 28)

        val ctx = PacketContext(isIPv6 = false, ipHeaderLength = 20)
        val resultLength = PacketRewriter.rewriteAsResponse(packet, ctx, srcPort = 5555, dnsResponseLength = dnsPayload.size)

        assertEquals(totalLength, resultLength)
        assertEquals(totalLength, u16(packet, 2))
        assertArrayEquals(dstIp, packet.copyOfRange(12, 16))   // 原 dst 变为 src
        assertArrayEquals(srcIp, packet.copyOfRange(16, 20))   // 原 src 变为 dst
        assertEquals(PacketRewriter.DNS_PORT, u16(packet, 20))
        assertEquals(5555, u16(packet, 22))
        assertEquals(8 + dnsPayload.size, u16(packet, 24))     // UDP length
        assertEquals(0, u16(packet, 26))                       // UDP checksum 置零
        // 校验和字段应使包头自洽
        val stored = u16(packet, 10)
        packet[10] = 0
        packet[11] = 0
        assertEquals(stored, PacketRewriter.computeIpv4HeaderChecksum(packet, 20))
    }

    @Test
    fun rewriteAsIpv6ResponseProducesValidUdpChecksum() {
        val dnsPayload = ByteArray(24) { (it + 3).toByte() }
        val payloadLength = 8 + dnsPayload.size
        val totalLength = 40 + payloadLength
        val packet = ByteArray(totalLength)

        packet[0] = 0x60
        packet[6] = 17
        // src=fd00::1 (client), dst=fd00::10 (virtual DNS)
        val srcAddr = ByteArray(16)
        val dstAddr = ByteArray(16).also { it[15] = 0x10 }
        srcAddr.copyInto(packet, 8)
        dstAddr.copyInto(packet, 24)
        packet[40] = ((5555 shr 8) and 0xFF).toByte()
        packet[41] = (5555 and 0xFF).toByte()
        packet[42] = 0
        packet[43] = PacketRewriter.DNS_PORT.toByte()
        dnsPayload.copyInto(packet, 48)

        val ctx = PacketContext(isIPv6 = true, ipHeaderLength = 40)
        val resultLength = PacketRewriter.rewriteAsResponse(packet, ctx, srcPort = 5555, dnsResponseLength = dnsPayload.size)

        assertEquals(totalLength, resultLength)
        assertEquals(payloadLength, u16(packet, 4))
        assertArrayEquals(dstAddr, packet.copyOfRange(8, 24))  // 地址已交换
        assertArrayEquals(srcAddr, packet.copyOfRange(24, 40))
        assertEquals(PacketRewriter.DNS_PORT, u16(packet, 40))
        assertEquals(5555, u16(packet, 42))

        // 按 RFC 2460 伪头部方式独立复算 UDP 校验和
        val storedChecksum = u16(packet, 46)
        packet[46] = 0
        packet[47] = 0
        var sum = 0
        fun addWord(value: Int) {
            sum += value
            if (sum > 0xFFFF) sum = (sum and 0xFFFF) + 1
        }
        for (i in 8 until 40 step 2) addWord(u16(packet, i))
        addWord(payloadLength)
        addWord(17)
        var i = 40
        while (i < totalLength) {
            addWord(u16(packet, i))
            i += 2
        }
        assertEquals(storedChecksum, sum.inv() and 0xFFFF)
    }

    @Test
    fun ipv6PseudoHeaderTrickRestoresOriginalBytes() {
        val packet = ByteArray(48)
        val originalFirst8 = byteArrayOf(0x60, 1, 2, 3, 4, 5, 6, 7)
        originalFirst8.copyInto(packet)

        PacketRewriter.computeIpv6UdpChecksum(packet, 48)

        // 计算过程中临时改写的前 8 字节必须被恢复
        assertArrayEquals(originalFirst8, packet.copyOfRange(0, 8))
    }
}
