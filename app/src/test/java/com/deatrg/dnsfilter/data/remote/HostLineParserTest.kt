package com.deatrg.dnsfilter.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostLineParserTest {

    @Test
    fun parsesZeroIpHostsLine() {
        assertEquals("ads.example.com", parseHostLine("0.0.0.0 ads.example.com"))
    }

    @Test
    fun parsesLoopbackHostsLine() {
        assertEquals("tracker.example.com", parseHostLine("127.0.0.1 tracker.example.com"))
    }

    @Test
    fun parsesTabSeparatedHostsLine() {
        assertEquals("ads.example.com", parseHostLine("0.0.0.0\tads.example.com"))
    }

    @Test
    fun parsesMultipleSpacesBetweenIpAndDomain() {
        assertEquals("ads.example.com", parseHostLine("0.0.0.0    ads.example.com"))
    }

    @Test
    fun ignoresTrailingCommentAfterDomain() {
        assertEquals("ads.example.com", parseHostLine("0.0.0.0 ads.example.com # ad network"))
    }

    @Test
    fun lowercasesDomain() {
        assertEquals("ads.example.com", parseHostLine("0.0.0.0 ADS.Example.COM"))
    }

    @Test
    fun trimsTrailingDotFromFqdn() {
        assertEquals("ads.example.com", parseHostLine("0.0.0.0 ads.example.com."))
    }

    @Test
    fun parsesPlainDomainLine() {
        assertEquals("ads.example.com", parseHostLine("ads.example.com"))
    }

    @Test
    fun plainDomainLineIsLowercasedAndDotTrimmed() {
        assertEquals("ads.example.com", parseHostLine("ADS.Example.COM."))
    }

    @Test
    fun rejectsCommentLine() {
        assertNull(parseHostLine("# 0.0.0.0 ads.example.com"))
    }

    @Test
    fun rejectsBlankLine() {
        assertNull(parseHostLine("   "))
    }

    @Test
    fun rejectsUnmappedIpAddress() {
        assertNull(parseHostLine("192.168.1.1 ads.example.com"))
    }

    @Test
    fun rejectsIpv6LoopbackHostsLine() {
        assertNull(parseHostLine("::1 localhost"))
    }

    @Test
    fun rejectsSingleLabelWithoutDot() {
        assertNull(parseHostLine("localhost"))
    }

    @Test
    fun rejectsIpOnlyLineWithoutDomain() {
        assertNull(parseHostLine("0.0.0.0"))
    }

    @Test
    fun rejectsEmptyDomainAfterIp() {
        assertNull(parseHostLine("0.0.0.0 "))
    }
}
