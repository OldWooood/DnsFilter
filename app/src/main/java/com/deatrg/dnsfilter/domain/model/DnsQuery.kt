package com.deatrg.dnsfilter.domain.model

import java.util.concurrent.atomic.AtomicLong

data class DnsQuery(
    val id: Long = idGenerator.incrementAndGet(),
    val domain: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isBlocked: Boolean,
    val matchedFilter: String? = null,
    val responseIp: String? = null,
    val responseTime: Long? = null
) {
    companion object {
        private val idGenerator = AtomicLong(0)
    }
}
