package com.deatrg.dnsfilter.data.remote

data class DnsQuestion(
    val domain: String,
    val qtype: Int,
    val qclass: Int
)

fun readDnsName(data: ByteArray, offset: Int, length: Int): Pair<String, Int>? {
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
    val parts = mutableListOf<String>()
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
        parts.add(String(data, idx, len))
        idx += len
        if (!jumped) {
            nextOffset = idx
        }
    }

    if (parts.isEmpty()) return null
    return Pair(parts.joinToString("."), nextOffset)
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
    val domain = nameResult.first
    val offset = nameResult.second
    if (offset + 4 > packetLength) return null

    val qtype = ((packet[offset].toInt() and 0xFF) shl 8) or
            (packet[offset + 1].toInt() and 0xFF)
    val qclass = ((packet[offset + 2].toInt() and 0xFF) shl 8) or
            (packet[offset + 3].toInt() and 0xFF)
    return DnsQuestion(domain, qtype, qclass)
}

fun parseDnsQuestion(data: ByteArray): DnsQuestion? {
    if (data.size < 12) return null

    val questionCount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
    if (questionCount < 1) return null

    val nameResult = readDnsName(data, 12, data.size) ?: return null
    val domain = nameResult.first
    val offset = nameResult.second
    if (offset + 4 > data.size) return null

    val qtype = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    val qclass = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
    return DnsQuestion(domain, qtype, qclass)
}
