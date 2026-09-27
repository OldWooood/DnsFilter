package com.deatrg.dnsfilter.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsServerAddressTest {

    @Test
    fun plainIpv4DefaultsToPort53() {
        assertEquals("119.29.29.29" to 53, DnsServer.parseAddress("119.29.29.29"))
    }

    @Test
    fun ipv4WithCustomPort() {
        assertEquals("1.1.1.1" to 5353, DnsServer.parseAddress("1.1.1.1:5353"))
    }

    @Test
    fun hostnameWithAndWithoutPort() {
        assertEquals("dns.example.com" to 53, DnsServer.parseAddress("dns.example.com"))
        assertEquals("dns.example.com" to 853, DnsServer.parseAddress("dns.example.com:853"))
    }

    @Test
    fun bareIpv6LiteralDefaultsToPort53() {
        assertEquals("2001:db8::1" to 53, DnsServer.parseAddress("2001:db8::1"))
    }

    @Test
    fun bracketedIpv6WithPort() {
        assertEquals("2001:db8::1" to 5353, DnsServer.parseAddress("[2001:db8::1]:5353"))
        assertEquals("2001:db8::1" to 53, DnsServer.parseAddress("[2001:db8::1]"))
    }

    @Test
    fun invalidPortsAreRejected() {
        assertNull(DnsServer.parseAddress("1.1.1.1:0"))
        assertNull(DnsServer.parseAddress("1.1.1.1:65536"))
        assertNull(DnsServer.parseAddress("1.1.1.1:"))
        assertNull(DnsServer.parseAddress("1.1.1.1:abc"))
    }

    @Test
    fun urlsAndPathsAreRejected() {
        assertNull(DnsServer.parseAddress("https://1.1.1.1"))
        assertNull(DnsServer.parseAddress("1.1.1.1/path"))
        assertNull(DnsServer.parseAddress("user@1.1.1.1"))
    }

    @Test
    fun malformedHostsAreRejected() {
        assertNull(DnsServer.parseAddress(""))
        assertNull(DnsServer.parseAddress("  "))
        assertNull(DnsServer.parseAddress("-leading.example.com"))
        assertNull(DnsServer.parseAddress("trailing-.example.com"))
        assertNull(DnsServer.parseAddress("under_score.example.com"))
    }

    @Test
    fun ambiguousIpv6WithoutBracketsIsRejectedWhenItLooksLikeHostPort() {
        // "2001:db8::1:5353" is ambiguous (could be an IPv6 literal); only the
        // bracketed form carries a port. Multiple colons => treated as a bare
        // IPv6 literal and kept whole, so it must be a valid-looking literal.
        assertEquals("2001:db8::1:5353" to 53, DnsServer.parseAddress("2001:db8::1:5353"))
        // Not hex digits => not an IPv6 literal and not host:port => rejected.
        assertNull(DnsServer.parseAddress("::gg::"))
    }
}
