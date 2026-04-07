package com.deatrg.dnsfilter.domain.model

data class DnsStatistics(
    val totalQueries: Long = 0,
    val blockedQueries: Long = 0,
    val allowedQueries: Long = 0,
    val averageResponseTime: Long = 0
)
