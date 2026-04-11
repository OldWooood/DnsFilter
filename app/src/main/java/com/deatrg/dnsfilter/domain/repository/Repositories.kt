package com.deatrg.dnsfilter.domain.repository

import com.deatrg.dnsfilter.domain.model.DnsServer
import com.deatrg.dnsfilter.domain.model.FilterList
import kotlinx.coroutines.flow.Flow

interface DnsServerRepository {
    val dnsServers: Flow<List<DnsServer>>
    suspend fun saveDnsServers(servers: List<DnsServer>)
    suspend fun addDnsServer(server: DnsServer)
    suspend fun updateDnsServer(server: DnsServer)
    suspend fun deleteDnsServer(serverId: String)
    suspend fun resetToDefaults()
}

interface FilterListRepository {
    val filterLists: Flow<List<FilterList>>
    val filterListCount: Flow<Int>
    val isLoaded: Flow<Boolean>
    val enabledDnsServerCount: Flow<Int>
    suspend fun saveFilterLists(lists: List<FilterList>)
    suspend fun addFilterList(list: FilterList)
    suspend fun updateFilterList(list: FilterList)
    suspend fun deleteFilterList(listId: String)
    suspend fun loadFilterLists()
    suspend fun refreshLists()
    fun getFilterLastUpdated(filterList: FilterList): Long?
}
