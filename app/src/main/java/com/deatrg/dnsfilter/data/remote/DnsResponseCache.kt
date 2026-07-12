package com.deatrg.dnsfilter.data.remote

/**
 * Small positive-response LRU used as an L2 behind Android Resolver.
 * Entries keep their original insertion time so cache hits return remaining TTLs.
 */
internal class DnsResponseCache(
    private val maxEntries: Int,
    private val nowMs: () -> Long
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    data class Hit(
        val response: ByteArray,
        val ageSeconds: Long
    )

    private data class Entry(
        val response: ByteArray,
        val storedAtMs: Long,
        val expiresAtMs: Long
    )

    private val lock = Any()
    private var generation = 0L
    private val entries = object : LinkedHashMap<String, Entry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean {
            return size > maxEntries
        }
    }

    fun generation(): Long = synchronized(lock) { generation }

    fun get(key: String): Hit? = synchronized(lock) {
        val entry = entries[key] ?: return@synchronized null
        val now = nowMs()
        if (now >= entry.expiresAtMs) {
            entries.remove(key)
            return@synchronized null
        }
        Hit(
            response = entry.response,
            ageSeconds = ((now - entry.storedAtMs).coerceAtLeast(0L) + 999L) / 1000L
        )
    }

    fun put(
        key: String,
        response: ByteArray,
        ttlSeconds: Long,
        expectedGeneration: Long
    ): Boolean = synchronized(lock) {
        if (ttlSeconds <= 0 || generation != expectedGeneration) return@synchronized false
        val now = nowMs()
        val ttlMs = ttlSeconds.coerceAtMost(Long.MAX_VALUE / 1000L) * 1000L
        entries[key] = Entry(
            response = response.copyOf(),
            storedAtMs = now,
            expiresAtMs = now + ttlMs
        )
        true
    }

    fun clear() = synchronized(lock) {
        generation++
        entries.clear()
    }

    internal fun size(): Int = synchronized(lock) { entries.size }
}
