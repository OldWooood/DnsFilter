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
        val kind: EntryKind = EntryKind.POSITIVE,
        /** 命中即得的 Unbound 式预取提示：热点且剩余 TTL < 10%，应后台刷新。 */
        val prefetch: Boolean = false
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

    /**
     * Fresh hits only; increments the per-entry hit counter and evaluates the
     * prefetch hint in the SAME lock acquisition — one lock per hot reader-path
     * lookup, with the hint delivered on [Hit.prefetch].
     */
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
            kind = entry.kind,
            prefetch = prefetchConditionLocked(entry, now)
        )
    }

    /**
     * Returns a fresh hit, or if expired within [maxStaleMs], a stale hit
     * (RFC 8767) in a SINGLE lock acquisition. Returns null on miss or when
     * expired beyond [maxStaleMs] (which evicts the entry).
     */
    fun getOrStale(key: String, maxStaleMs: Long): Hit? = synchronized(lock) {
        val entry = entries[key] ?: return@synchronized null
        val now = nowMs()
        if (now < entry.expiresAtMs) {
            entry.hits++
            return@synchronized Hit(
                response = entry.response,
                ageSeconds = ((now - entry.storedAtMs).coerceAtLeast(0L) + 999L) / 1000L,
                stale = false,
                kind = entry.kind,
                prefetch = prefetchConditionLocked(entry, now)
            )
        }
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
     * 仅测试/诊断使用：热路径上的判定已并入 [get] 同一把锁内（[Hit.prefetch]）。
     */
    fun prefetchHint(key: String): Boolean = synchronized(lock) {
        val entry = entries[key] ?: return@synchronized false
        prefetchConditionLocked(entry, nowMs())
    }

    private fun prefetchConditionLocked(entry: Entry, now: Long): Boolean {
        val remainingMs = entry.expiresAtMs - now
        if (remainingMs <= 0) return false
        if (entry.originalTtlMs <= 0) return false
        return entry.hits >= 3 && remainingMs * 10 < entry.originalTtlMs
    }

    fun put(
        key: String,
        response: ByteArray,
        ttlSeconds: Long,
        expectedGeneration: Long
    ): Boolean = putSnapshot(key, response, ttlSeconds, expectedGeneration, EntryKind.POSITIVE)

    fun putNegative(
        key: String,
        response: ByteArray,
        ttlSeconds: Long,
        expectedGeneration: Long
    ): Boolean = putSnapshot(key, response, ttlSeconds, expectedGeneration, EntryKind.NEGATIVE)

    /**
     * Snapshots [response] OUTSIDE the lock: the memcpy must not lengthen the
     * critical section shared with reader-thread hit lookups. The generation
     * check still happens inside the lock.
     */
    private fun putSnapshot(
        key: String,
        response: ByteArray,
        ttlSeconds: Long,
        expectedGeneration: Long,
        kind: EntryKind
    ): Boolean {
        val snapshot = response.copyOf()
        return synchronized(lock) {
            putLocked(key, snapshot, ttlSeconds, expectedGeneration, kind)
        }
    }

    /** [response] must already be an exclusive snapshot owned by the cache. */
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
            response = response,
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
