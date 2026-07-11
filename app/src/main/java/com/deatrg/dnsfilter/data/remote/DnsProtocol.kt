package com.deatrg.dnsfilter.data.remote

data class DnsQuestion(
    val domain: String,
    val qtype: Int,
    val qclass: Int,
    val questionEndOffset: Int = 0
)

private fun appendLowercaseAscii(builder: StringBuilder, data: ByteArray, offset: Int, length: Int) {
    for (i in 0 until length) {
        val value = data[offset + i].toInt() and 0xFF
        val lower = if (value in 65..90) value + 32 else value
        builder.append(lower.toChar())
    }
}

fun skipDnsName(data: ByteArray, offset: Int, length: Int): Int? {
    var idx = offset
    var jumps = 0

    while (idx < length) {
        val len = data[idx].toInt() and 0xFF
        if (len == 0) return idx + 1
        if ((len and 0xC0) == 0xC0) {
            if (idx + 1 >= length) return null
            jumps++
            if (jumps > 8) return null
            return idx + 2
        }
        idx++
        if (idx + len > length) return null
        idx += len
    }

    return null
}

/**
 * Parse DNS name from a raw IP packet buffer where DNS compression pointers
 * are relative to the start of the DNS message (not the packet).
 * @param data  full IP packet buffer
 * @param dnsStart  offset within data where the DNS message starts
 * @param startOffset  offset within data where the name label begins
 * @param packetLength  total length of the packet buffer
 */
fun readDnsNameFromPacket(
    data: ByteArray,
    dnsStart: Int,
    startOffset: Int,
    packetLength: Int
): Pair<String, Int>? {
    val name = StringBuilder(64)
    var idx = startOffset
    var jumped = false
    var nextOffset = startOffset
    var jumps = 0

    while (idx < packetLength) {
        val len = data[idx].toInt() and 0xFF
        if (len == 0) {
            if (!jumped) {
                nextOffset = idx + 1
            }
            break
        }
        if ((len and 0xC0) == 0xC0) {
            if (idx + 1 >= packetLength) return null
            val pointer = ((len and 0x3F) shl 8) or (data[idx + 1].toInt() and 0xFF)
            if (!jumped) {
                nextOffset = idx + 2
            }
            idx = dnsStart + pointer
            jumped = true
            jumps++
            if (jumps > 8) return null
            continue
        }
        idx++
        if (idx + len > packetLength) return null
        if (name.isNotEmpty()) name.append('.')
        appendLowercaseAscii(name, data, idx, len)
        idx += len
        if (!jumped) {
            nextOffset = idx
        }
    }

    if (name.isEmpty()) return null
    return Pair(name.toString(), nextOffset)
}

fun parseDnsQueryFromPacket(
    packet: ByteArray,
    dnsStart: Int,
    packetLength: Int
): DnsQuestion? {
    if (packetLength - dnsStart < 12) return null

    val flags = ((packet[dnsStart + 2].toInt() and 0xFF) shl 8) or
            (packet[dnsStart + 3].toInt() and 0xFF)
    val qrBit = (flags shr 15) and 1
    if (qrBit != 0) return null

    val nameResult = readDnsNameFromPacket(packet, dnsStart, dnsStart + 12, packetLength) ?: return null
    // DNS names are normalized while parsing, so callers don't need to re-lowercase.
    val domain = nameResult.first
    val offset = nameResult.second
    if (offset + 4 > packetLength) return null

    val qtype = ((packet[offset].toInt() and 0xFF) shl 8) or
            (packet[offset + 1].toInt() and 0xFF)
    val qclass = ((packet[offset + 2].toInt() and 0xFF) shl 8) or
            (packet[offset + 3].toInt() and 0xFF)
    return DnsQuestion(domain, qtype, qclass, offset + 4)
}
