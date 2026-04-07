package com.deatrg.dnsfilter.domain.model

data class FilterList(
    val id: String,
    val name: String,
    val url: String,
    val isEnabled: Boolean = true,
    val isBuiltIn: Boolean = false
)
