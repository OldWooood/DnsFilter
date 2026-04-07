package com.deatrg.dnsfilter.domain.model

data class DnsServer(
    val id: String,
    val name: String,
    val address: String,
    val type: DnsServerType,
    val isEnabled: Boolean = true
)

enum class DnsServerType {
    PLAIN,
    DOH,
    DOT
}
