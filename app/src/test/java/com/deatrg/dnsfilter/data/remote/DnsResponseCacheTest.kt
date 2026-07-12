package com.deatrg.dnsfilter.data.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsResponseCacheTest {

    @Test
    fun hitReportsAgeAndEntryExpiresAtTtl() {
        var now = 1_000L
        val cache = DnsResponseCache(maxEntries = 2, nowMs = { now })
        val response = byteArrayOf(1, 2, 3)

        assertTrue(cache.put("example.com:1:1", response, ttlSeconds = 10, cache.generation()))
        response[0] = 9

        now = 4_500L
        val hit = cache.get("example.com:1:1")
        assertNotNull(hit)
        assertEquals(4L, hit?.ageSeconds)
        assertArrayEquals(byteArrayOf(1, 2, 3), hit?.response)

        now = 11_000L
        assertNull(cache.get("example.com:1:1"))
        assertEquals(0, cache.size())
    }

    @Test
    fun leastRecentlyUsedEntryIsEvicted() {
        val cache = DnsResponseCache(maxEntries = 2, nowMs = { 0L })
        val generation = cache.generation()
        assertTrue(cache.put("a", byteArrayOf(1), 60, generation))
        assertTrue(cache.put("b", byteArrayOf(2), 60, generation))

        assertNotNull(cache.get("a")) // a is now more recently used than b.
        assertTrue(cache.put("c", byteArrayOf(3), 60, generation))

        assertNull(cache.get("b"))
        assertNotNull(cache.get("a"))
        assertNotNull(cache.get("c"))
        assertEquals(2, cache.size())
    }

    @Test
    fun clearRejectsResponsesFromPreviousGeneration() {
        val cache = DnsResponseCache(maxEntries = 2, nowMs = { 0L })
        val oldGeneration = cache.generation()

        cache.clear()

        assertFalse(cache.put("old", byteArrayOf(1), 60, oldGeneration))
        assertTrue(cache.put("new", byteArrayOf(2), 60, cache.generation()))
        assertNull(cache.get("old"))
        assertNotNull(cache.get("new"))
    }
}
