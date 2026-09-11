package com.deatrg.dnsfilter.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsProtocolCacheTest {

    @Test
    fun positiveTtlsAreClampedForAndroidResolver() {
        val response = buildPositiveResponse()
        val firstAnswerTtlOffset = 35
        val secondAnswerTtlOffset = 51
        val optMetadataOffset = 78

        assertEquals(POSITIVE_DNS_MIN_TTL_SECONDS, clampPositiveDnsTtlsInPlace(response))
        assertEquals(POSITIVE_DNS_MIN_TTL_SECONDS, readUInt32(response, firstAnswerTtlOffset))
        assertEquals(POSITIVE_DNS_MAX_TTL_SECONDS, readUInt32(response, secondAnswerTtlOffset))
        assertEquals(0x8000L, readUInt32(response, optMetadataOffset))

        assertTrue(agePositiveDnsTtlsInPlace(response, ageSeconds = 60))
        assertEquals(POSITIVE_DNS_MIN_TTL_SECONDS - 60, readUInt32(response, firstAnswerTtlOffset))
        assertEquals(POSITIVE_DNS_MAX_TTL_SECONDS - 60, readUInt32(response, secondAnswerTtlOffset))
        assertEquals(0x8000L, readUInt32(response, optMetadataOffset))
    }

    @Test
    fun blockedResponseContainsCacheableTwentyFourHourSoa() {
        val packet = ByteArray(512)
        val dnsStart = 28
        packet[dnsStart] = 0x12
        packet[dnsStart + 1] = 0x34
        packet[dnsStart + 2] = 0x01 // RD
        packet[dnsStart + 3] = 0x10 // CD
        writeUInt16(packet, dnsStart + 4, 1)
        val question = exampleQuestion()
        question.copyInto(packet, destinationOffset = dnsStart + 12)
        val questionEnd = dnsStart + 12 + question.size

        val dnsLength = patchBlockedNxDomainResponse(packet, dnsStart, questionEnd)

        assertTrue(dnsLength > questionEnd - dnsStart)
        assertEquals(0x81, packet[dnsStart + 2].toInt() and 0xFF)
        assertEquals(0x93, packet[dnsStart + 3].toInt() and 0xFF)
        assertEquals(1, readUInt16(packet, dnsStart + 4))
        assertEquals(0, readUInt16(packet, dnsStart + 6))
        assertEquals(1, readUInt16(packet, dnsStart + 8))
        assertEquals(0, readUInt16(packet, dnsStart + 10))

        val authorityOffset = questionEnd
        assertEquals(0xC00C, readUInt16(packet, authorityOffset))
        assertEquals(6, readUInt16(packet, authorityOffset + 2))
        assertEquals(1, readUInt16(packet, authorityOffset + 4))
        assertEquals(BLOCKED_DOMAIN_TTL_SECONDS, readUInt32(packet, authorityOffset + 6))
        val rdataLength = readUInt16(packet, authorityOffset + 10)
        val soaMinimumOffset = authorityOffset + 12 + rdataLength - 4
        assertEquals(BLOCKED_DOMAIN_TTL_SECONDS, readUInt32(packet, soaMinimumOffset))
    }

    @Test
    fun malformedDnsMessageIsNotRewritten() {
        assertNull(clampPositiveDnsTtlsInPlace(ByteArray(11)))
    }

    @Test
    fun perQtypeTtlBoundsApplyToVolatileTypes() {
        val response = buildPositiveResponse()
        val firstAnswerTtlOffset = 35

        // A/AAAA keep the aggressive 1-6h window (default path unchanged).
        assertEquals(POSITIVE_DNS_MIN_TTL_SECONDS, clampPositiveDnsTtlsInPlace(response, DNS_TYPE_A))

        val https = buildPositiveResponse()
        assertEquals(600L, clampPositiveDnsTtlsInPlace(https, DNS_TYPE_HTTPS))
        assertEquals(600L, readUInt32(https, firstAnswerTtlOffset))
    }

    @Test
    fun truncatedBitIsDetected() {
        val response = buildPositiveResponse()
        assertEquals(false, isTruncatedResponse(response))
        response[2] = (response[2].toInt() or 0x02).toByte()
        assertEquals(true, isTruncatedResponse(response))
    }

    @Test
    fun negativeTtlComesFromSoaMinimum() {
        val nxdomain = buildNxDomainResponse(soaTtl = 86_400, soaMinimum = 300)
        assertEquals(300L, extractNegativeTtlSeconds(nxdomain))

        val noSoa = buildNxDomainResponseWithoutAuthority()
        assertEquals(NEGATIVE_DNS_DEFAULT_TTL_SECONDS, extractNegativeTtlSeconds(noSoa))

        val positive = buildPositiveResponse()
        assertNull(extractNegativeTtlSeconds(positive))
    }

    @Test
    fun cnameTargetsAreExtractedLowercased() {
        val response = buildCnameResponse()
        assertEquals(listOf("target.example.com"), extractCnameTargets(response))

        val positive = buildPositiveResponse()
        assertEquals(emptyList<String>(), extractCnameTargets(positive))
    }

    @Test
    fun staleStampCapsAllRecordTtls() {
        val response = buildPositiveResponse()
        val firstAnswerTtlOffset = 35
        val secondAnswerTtlOffset = 51
        val optMetadataOffset = 78

        assertTrue(stampStaleTtlsInPlace(response, 30))
        assertEquals(30L, readUInt32(response, firstAnswerTtlOffset))
        assertEquals(30L, readUInt32(response, secondAnswerTtlOffset))
        // OPT metadata untouched.
        assertEquals(0x8000L, readUInt32(response, optMetadataOffset))
    }

    @Test
    fun minAnswerTtlReadsWithoutClamping() {
        val response = buildPositiveResponse()
        assertEquals(30L, minAnswerTtlSeconds(response))
    }

    private fun buildPositiveResponse(): ByteArray {
        val question = exampleQuestion()
        val response = ByteArray(84)
        response[0] = 0x12
        response[1] = 0x34
        response[2] = 0x81.toByte()
        response[3] = 0x80.toByte()
        writeUInt16(response, 4, 1)
        writeUInt16(response, 6, 2)
        writeUInt16(response, 8, 0)
        writeUInt16(response, 10, 1)
        question.copyInto(response, destinationOffset = 12)

        var offset = 12 + question.size
        offset = writeAnswer(response, offset, type = 1, ttl = 30, rdata = byteArrayOf(1, 2, 3, 4))
        offset = writeAnswer(response, offset, type = 28, ttl = 86_400, rdata = ByteArray(16) { it.toByte() })
        response[offset++] = 0 // OPT root owner
        writeUInt16(response, offset, 41)
        offset += 2
        writeUInt16(response, offset, 1232)
        offset += 2
        writeUInt32(response, offset, 0x8000)
        offset += 4
        writeUInt16(response, offset, 0)
        offset += 2
        assertEquals(response.size, offset)
        return response
    }

    private fun exampleQuestion(): ByteArray {
        return byteArrayOf(
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
            'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0,
            0, 1, 0, 1
        )
    }

    private fun buildNxDomainResponse(soaTtl: Long, soaMinimum: Long): ByteArray {
        val question = exampleQuestion()
        // SOA rdata: mname(1,'a',0) + rname(1,'b',0) + 5 x u32.
        val soaRdata = byteArrayOf(1, 'a'.code.toByte(), 0, 1, 'b'.code.toByte(), 0) +
            u32(1) + u32(3600) + u32(600) + u32(604800) + u32(soaMinimum)
        val response = ByteArray(12 + question.size + 2 + 10 + soaRdata.size)
        response[0] = 0x12
        response[1] = 0x34
        response[2] = 0x81.toByte()
        response[3] = 0x83.toByte() // RCODE=3 NXDOMAIN
        writeUInt16(response, 4, 1)
        writeUInt16(response, 6, 0)
        writeUInt16(response, 8, 1)
        writeUInt16(response, 10, 0)
        question.copyInto(response, destinationOffset = 12)
        var offset = 12 + question.size
        writeUInt16(response, offset, 0xC00C)
        offset += 2
        writeUInt16(response, offset, 6) // SOA
        offset += 2
        writeUInt16(response, offset, 1)
        offset += 2
        writeUInt32(response, offset, soaTtl)
        offset += 4
        writeUInt16(response, offset, soaRdata.size)
        offset += 2
        soaRdata.copyInto(response, destinationOffset = offset)
        return response
    }

    private fun buildNxDomainResponseWithoutAuthority(): ByteArray {
        val question = exampleQuestion()
        val response = ByteArray(12 + question.size)
        response[0] = 0x12
        response[1] = 0x34
        response[2] = 0x81.toByte()
        response[3] = 0x83.toByte()
        writeUInt16(response, 4, 1)
        writeUInt16(response, 6, 0)
        writeUInt16(response, 8, 0)
        writeUInt16(response, 10, 0)
        question.copyInto(response, destinationOffset = 12)
        return response
    }

    private fun buildCnameResponse(): ByteArray {
        val question = exampleQuestion()
        val target = byteArrayOf(
            6, 'T'.code.toByte(), 'A'.code.toByte(), 'R'.code.toByte(),
            'G'.code.toByte(), 'E'.code.toByte(), 'T'.code.toByte(),
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
            'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0
        )
        val response = ByteArray(12 + question.size + 2 + 10 + target.size)
        response[0] = 0x12
        response[1] = 0x34
        response[2] = 0x81.toByte()
        response[3] = 0x80.toByte()
        writeUInt16(response, 4, 1)
        writeUInt16(response, 6, 1)
        writeUInt16(response, 8, 0)
        writeUInt16(response, 10, 0)
        question.copyInto(response, destinationOffset = 12)
        var offset = 12 + question.size
        writeUInt16(response, offset, 0xC00C)
        offset += 2
        writeUInt16(response, offset, 5) // CNAME
        offset += 2
        writeUInt16(response, offset, 1)
        offset += 2
        writeUInt32(response, offset, 3600)
        offset += 4
        writeUInt16(response, offset, target.size)
        offset += 2
        target.copyInto(response, destinationOffset = offset)
        return response
    }

    private fun u32(value: Long): ByteArray {
        val out = ByteArray(4)
        writeUInt32(out, 0, value)
        return out
    }

    private fun writeAnswer(data: ByteArray, start: Int, type: Int, ttl: Long, rdata: ByteArray): Int {
        var offset = start
        writeUInt16(data, offset, 0xC00C)
        offset += 2
        writeUInt16(data, offset, type)
        offset += 2
        writeUInt16(data, offset, 1)
        offset += 2
        writeUInt32(data, offset, ttl)
        offset += 4
        writeUInt16(data, offset, rdata.size)
        offset += 2
        rdata.copyInto(data, destinationOffset = offset)
        return offset + rdata.size
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private fun readUInt32(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }

    private fun writeUInt16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeUInt32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = ((value ushr 24) and 0xFF).toByte()
        data[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }
}
