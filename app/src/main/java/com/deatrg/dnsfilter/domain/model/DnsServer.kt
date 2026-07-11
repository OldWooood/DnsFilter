package com.deatrg.dnsfilter.domain.model

data class DnsServer(
    val id: String,
    val name: String,
    val address: String,
    val isEnabled: Boolean = true,
    val isBuiltIn: Boolean = false
)
