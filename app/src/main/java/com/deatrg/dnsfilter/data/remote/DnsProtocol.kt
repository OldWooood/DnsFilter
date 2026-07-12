package com.deatrg.dnsfilter.data.remote

data class DnsQuestion(
    val domain: String,
    val qtype: Int,
    val qclass: Int,
    val questionEndOffset: Int = 0
)

internal const val POSITIVE_DNS_MIN_TTL_SECONDS = 60L * 60L
internal const val POSITIVE_DNS_MAX_TTL_SECONDS = 6L * 60L * 60L
internal const val BLOCKED_DOMAIN_TTL_SECONDS = 24L * 60L * 60L

private const val DNS_HEADER_SIZE = 12
private const val DNS_TYPE_OPT = 41
private const val DNS_TYPE_TKEY = 249
private const val DNS_TYPE_TSIG = 250
private const val DNS_TYPE_SOA = 6
private const val DNS_CLASS_IN = 1

private val BLOCKED_SOA_MNAME = byteArrayOf(
    9, 'd'.code.toByte(), 'n'.code.toByte(), 's'.code.toByte(), 'f'.code.toByte(),
    'i'.code.toByte(), 'l'.code.toByte(), 't'.code.toByte(), 'e'.code.toByte(), 'r'.code.toByte(),
    7, 'i'.code.toByte(), 'n'.code.toByte(), 'v'.code.toByte(), 'a'.code.toByte(),
    'l'.code.toByte(), 'i'.code.toByte(), 'd'.code.toByte(), 0
)

private val BLOCKED_SOA_RNAME = byteArrayOf(
    10, 'h'.code.toByte(), 'o'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(),
    'm'.code.toByte(), 'a'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(),
    'e'.code.toByte(), 'r'.code.toByte(),
    9, 'd'.code.toByte(), 'n'.code.toByte(), 's'.code.toByte(), 'f'.code.toByte(),
    'i'.code.toByte(), 'l'.code.toByte(), 't'.code.toByte(), 'e'.code.toByte(), 'r'.code.toByte(),
    7, 'i'.code.toByte(), 'n'.code.toByte(), 'v'.code.toByte(), 'a'.code.toByte(),
    'l'.code.toByte(), 'i'.code.toByte(), 'd'.code.toByte(), 0
)

private fun readUInt16(data: ByteArray, offset: Int): Int {
    return ((data[offset].toInt() and 0xFF) shl 8) or
        (data[offset + 1].toInt() and 0xFF)
}

private fun writeUInt16(data: ByteArray, offset: Int, value: Int) {
    data[offset] = ((value ushr 8) and 0xFF).toByte()
    data[offset + 1] = (value and 0xFF).toByte()
}

private fun readUInt32(data: ByteArray, offset: Int): Long {
    return ((data[offset].toLong() and 0xFF) shl 24) or
        ((data[offset + 1].toLong() and 0xFF) shl 16) or
        ((data[offset + 2].toLong() and 0xFF) shl 8) or
        (data[offset + 3].toLong() and 0xFF)
}

private fun writeUInt32(data: ByteArray, offset: Int, value: Long) {
    data[offset] = ((value ushr 24) and 0xFF).toByte()
    data[offset + 1] = ((value ushr 16) and 0xFF).toByte()
    data[offset + 2] = ((value ushr 8) and 0xFF).toByte()
    data[offset + 3] = (value and 0xFF).toByte()
}

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
 * Applies the speed-first Android Resolver TTL policy to every real resource record.
 * OPT uses the TTL-shaped field for EDNS metadata and must never be rewritten.
 */
internal fun clampPositiveDnsTtlsInPlace(response: ByteArray): Boolean {
    if (response.size < DNS_HEADER_SIZE) return false
    val flags = readUInt16(response, 2)
    val rcode = flags and 0x0F
    val answerCount = readUInt16(response, 6)
    if (rcode != 0 || answerCount == 0) return false

    return clampDnsTtlsInPlace(response)
}

private fun clampDnsTtlsInPlace(data: ByteArray): Boolean {
    val dnsEnd = data.size
    val questionCount = readUInt16(data, 4)
    val recordCount = readUInt16(data, 6) +
        readUInt16(data, 8) +
        readUInt16(data, 10)

    var offset = DNS_HEADER_SIZE
    repeat(questionCount) {
        offset = (skipDnsName(data, offset, dnsEnd) ?: return false) + 4
        if (offset > dnsEnd) return false
    }

    repeat(recordCount) {
        val headerOffset = skipDnsName(data, offset, dnsEnd) ?: return false
        if (headerOffset + 10 > dnsEnd) return false
        val type = readUInt16(data, headerOffset)
        val ttlOffset = headerOffset + 4
        val rdataLength = readUInt16(data, headerOffset + 8)
        val nextOffset = headerOffset + 10 + rdataLength
        if (nextOffset > dnsEnd) return false

        if (type != DNS_TYPE_OPT && type != DNS_TYPE_TKEY && type != DNS_TYPE_TSIG) {
            val originalTtl = readUInt32(data, ttlOffset)
            val resolverTtl = originalTtl.coerceIn(POSITIVE_DNS_MIN_TTL_SECONDS, POSITIVE_DNS_MAX_TTL_SECONDS)
            writeUInt32(data, ttlOffset, resolverTtl)
        }
        offset = nextOffset
    }
    return true
}

/**
 * Builds an RFC 2308-cacheable NXDOMAIN response with a 24-hour SOA TTL.
 * Returns the DNS message length, or a question-only NXDOMAIN if the packet
 * buffer cannot hold the authority record.
 */
internal fun patchBlockedNxDomainResponse(
    packet: ByteArray,
    dnsStart: Int,
    questionEndOffset: Int
): Int {
    if (dnsStart < 0 || questionEndOffset < dnsStart + DNS_HEADER_SIZE || questionEndOffset > packet.size) {
        return 0
    }

    val rdBit = packet[dnsStart + 2].toInt() and 0x01
    val opcode = packet[dnsStart + 2].toInt() and 0x78
    val checkingDisabled = packet[dnsStart + 3].toInt() and 0x10
    packet[dnsStart + 2] = (0x80 or opcode or rdBit).toByte()
    packet[dnsStart + 3] = (0x80 or checkingDisabled or 0x03).toByte()
    writeUInt16(packet, dnsStart + 4, 1)
    writeUInt16(packet, dnsStart + 6, 0)
    writeUInt16(packet, dnsStart + 8, 0)
    writeUInt16(packet, dnsStart + 10, 0)

    val rdataLength = BLOCKED_SOA_MNAME.size + BLOCKED_SOA_RNAME.size + 20
    val recordLength = 2 + 10 + rdataLength
    if (questionEndOffset > packet.size - recordLength) {
        return questionEndOffset - dnsStart
    }

    writeUInt16(packet, dnsStart + 8, 1)
    var offset = questionEndOffset
    // The SOA owner is the queried name at DNS message offset 12.
    packet[offset++] = 0xC0.toByte()
    packet[offset++] = 0x0C
    writeUInt16(packet, offset, DNS_TYPE_SOA)
    offset += 2
    writeUInt16(packet, offset, DNS_CLASS_IN)
    offset += 2
    writeUInt32(packet, offset, BLOCKED_DOMAIN_TTL_SECONDS)
    offset += 4
    writeUInt16(packet, offset, rdataLength)
    offset += 2
    BLOCKED_SOA_MNAME.copyInto(packet, destinationOffset = offset)
    offset += BLOCKED_SOA_MNAME.size
    BLOCKED_SOA_RNAME.copyInto(packet, destinationOffset = offset)
    offset += BLOCKED_SOA_RNAME.size
    writeUInt32(packet, offset, 1) // Serial
    offset += 4
    writeUInt32(packet, offset, BLOCKED_DOMAIN_TTL_SECONDS) // Refresh
    offset += 4
    writeUInt32(packet, offset, 60L * 60L) // Retry
    offset += 4
    writeUInt32(packet, offset, 7L * 24L * 60L * 60L) // Expire
    offset += 4
    writeUInt32(packet, offset, BLOCKED_DOMAIN_TTL_SECONDS) // Negative cache TTL
    offset += 4
    return offset - dnsStart
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
