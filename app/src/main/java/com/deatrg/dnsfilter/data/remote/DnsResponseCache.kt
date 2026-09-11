package com.deatrg.dnsfilter.data.remote

/**
 * DNS L2 behind the Android Resolver (L1).
 *
 * Stores positive responses plus RFC 2308 negative responses (NXDOMAIN/NODATA)
 * and short RFC 9520 SERVFAIL markers. Expired entries may still be served
 * stale (RFC 8767) while a background refresh runs, so network transitions
 * don't cause a cold-start storm.
 *
 * Entries keep their original insertion time so cache hits return remaining TTLs.
 */
internal class DnsResponseCache(
    private val maxEntries: Int,
    private val nowMs: () -> Long
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    internal enum class EntryKind {
        POSITIVE,
        NEGATIVE
    }

    data class Hit(
        val response: ByteArray,
        val ageSeconds: Long,
        val stale: Boolean = false,
        val kind: EntryKind = EntryKind.POSITIVE
    )

    private data class Entry(
        val response: ByteArray,
        val storedAtMs: Long,
        val expiresAtMs: Long,
        val kind: EntryKind,
        val originalTtlMs: Long,
        var hits: Long = 0L
    )

    private val lock = Any()
    private var generation = 0L
    private val entries = object : LinkedHashMap<String, Entry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean {
            return size > maxEntries
        }
    }

    /** Short-lived upstream-failure markers (RFC 9520): key -> expiresAtMs. */
    private val servfailMarkers = mutableMapOf<String, Long>()

    fun generation(): Long = synchronized(lock) { generation }

    /** Fresh hits only; increments the per-entry hit counter used for prefetch hints. */
    fun get(key: String): Hit? = synchronized(lock) {
        val entry = entries[key] ?: return@synchronized null
        val now = nowMs()
        if (now >= entry.expiresAtMs) {
            // Expired entries are RETAINED (not evicted) so getStale() can serve
            // them inside the RFC 8767 window; beyond-window eviction happens
            // there or under LRU pressure.
            return@synchronized null
        }
        entry.hits++
        Hit(
            response = entry.response,
            ageSeconds = ((now - entry.storedAtMs).coerceAtLeast(0L) + 999L) / 1000L,
            stale = false,
            kind = entry.kind
        )
    }

    /**
     * Returns the entry even after expiry, as long as it expired less than
     * [maxStaleMs] ago. Returns null for fresh entries (use [get]) and for
     * entries beyond the stale window (which are evicted).
     */
    fun getStale(key: String, maxStaleMs: Long): Hit? = synchronized(lock) {
        val entry = entries[key] ?: return@synchronized null
        val now = nowMs()
        if (now < entry.expiresAtMs) return@synchronized null
        if (now - entry.expiresAtMs > maxStaleMs) {
            entries.remove(key)
            return@synchronized null
        }
        Hit(
            response = entry.response,
            ageSeconds = ((now - entry.storedAtMs).coerceAtLeast(0L) + 999L) / 1000L,
            stale = true,
            kind = entry.kind
        )
    }

    /**
     * Unbound-style prefetch hint: a hot entry (3+ hits) whose remaining TTL is
     * below 10% should be refreshed in the background while serving from cache.
     */
    fun prefetchHint(key: String): Boolean = synchronized(lock) {
        val entry = entries[key] ?: return@synchronized false
        val now = nowMs()
        val remainingMs = entry.expiresAtMs - now
        if (remainingMs <= 0) return@synchronized false
        if (entry.originalTtlMs <= 0) return@synchronized false
        entry.hits >= 3 && remainingMs * 10 < entry.originalTtlMs
    }

    fun put(
        key: String,
        response: ByteArray,
        ttlSeconds: Long,
        expectedGeneration: Long
    ): Boolean = synchronized(lock) {
        putLocked(key, response, ttlSeconds, expectedGeneration, EntryKind.POSITIVE)
    }

    fun putNegative(
        key: String,
        response: ByteArray,
        ttlSeconds: Long,
        expectedGeneration: Long
    ): Boolean = synchronized(lock) {
        putLocked(key, response, ttlSeconds, expectedGeneration, EntryKind.NEGATIVE)
    }

    private fun putLocked(
        key: String,
        response: ByteArray,
        ttlSeconds: Long,
        expectedGeneration: Long,
        kind: EntryKind
    ): Boolean {
        if (ttlSeconds <= 0 || generation != expectedGeneration) return false
        val now = nowMs()
        val ttlMs = ttlSeconds.coerceAtMost(Long.MAX_VALUE / 1000L) * 1000L
        entries[key] = Entry(
            response = response.copyOf(),
            storedAtMs = now,
            expiresAtMs = now + ttlMs,
            kind = kind,
            originalTtlMs = ttlMs,
            hits = 0L
        )
        return true
    }

    fun putServfail(key: String, ttlSeconds: Long) = synchronized(lock) {
        if (ttlSeconds <= 0) return@synchronized
        val now = nowMs()
        servfailMarkers[key] = now + ttlSeconds.coerceAtMost(Long.MAX_VALUE / 1000L) * 1000L
    }

    fun isServfailCached(key: String): Boolean = synchronized(lock) {
        val expiresAt = servfailMarkers[key] ?: return@synchronized false
        if (nowMs() >= expiresAt) {
            servfailMarkers.remove(key)
            return@synchronized false
        }
        true
    }

    fun clear() = synchronized(lock) {
        generation++
        entries.clear()
        servfailMarkers.clear()
    }

    internal fun size(): Int = synchronized(lock) { entries.size }
}
