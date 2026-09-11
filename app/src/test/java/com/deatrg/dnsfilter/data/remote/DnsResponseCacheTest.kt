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
        // Expired entries are retained for the serve-stale window, not evicted.
        assertEquals(1, cache.size())
        now = 12_000L
        assertNull(cache.getStale("example.com:1:1", maxStaleMs = 0))
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

    @Test
    fun negativeEntriesAreServedFreshThenExpire() {
        var now = 0L
        val cache = DnsResponseCache(maxEntries = 2, nowMs = { now })

        assertTrue(cache.putNegative("missing.example:1:1", byteArrayOf(7, 7), 60, cache.generation()))
        val hit = cache.get("missing.example:1:1")
        assertNotNull(hit)
        assertEquals(DnsResponseCache.EntryKind.NEGATIVE, hit?.kind)
        assertEquals(false, hit?.stale)

        now = 61_000L
        assertNull(cache.get("missing.example:1:1"))
    }

    @Test
    fun expiredEntriesServeStaleWithinWindow() {
        var now = 0L
        val cache = DnsResponseCache(maxEntries = 2, nowMs = { now })
        val windowMs = 3L * 24 * 60 * 60 * 1000

        assertTrue(cache.put("example.com:1:1", byteArrayOf(1), 60, cache.generation()))

        now = 61_000L
        assertNull(cache.get("example.com:1:1"))
        val stale = cache.getStale("example.com:1:1", windowMs)
        assertNotNull(stale)
        assertEquals(true, stale?.stale)
        assertArrayEquals(byteArrayOf(1), stale?.response)

        // Fresh entries are not reported as stale.
        now = 0L
        assertTrue(cache.put("fresh.example:1:1", byteArrayOf(2), 60, cache.generation()))
        assertNull(cache.getStale("fresh.example:1:1", windowMs))

        // Beyond the window the entry is evicted.
        now = 61_000L + windowMs + 1
        assertNull(cache.getStale("example.com:1:1", windowMs))
        assertEquals(1, cache.size())
    }

    @Test
    fun servfailMarkersExpireAndClear() {
        var now = 0L
        val cache = DnsResponseCache(maxEntries = 2, nowMs = { now })

        cache.putServfail("down.example:1:1", 10)
        assertTrue(cache.isServfailCached("down.example:1:1"))

        now = 11_000L
        assertFalse(cache.isServfailCached("down.example:1:1"))

        cache.putServfail("down.example:1:1", 10)
        cache.clear()
        assertFalse(cache.isServfailCached("down.example:1:1"))
    }

    @Test
    fun hotNearExpiryEntriesHintPrefetch() {
        var now = 0L
        val cache = DnsResponseCache(maxEntries = 2, nowMs = { now })

        assertTrue(cache.put("hot.example:1:1", byteArrayOf(1), 100, cache.generation()))
        repeat(3) { cache.get("hot.example:1:1") }

        now = 50_000L
        assertFalse(cache.prefetchHint("hot.example:1:1"))

        // Remaining 9s of 100s original TTL is below the 10% threshold.
        now = 91_000L
        assertTrue(cache.prefetchHint("hot.example:1:1"))
    }
}
