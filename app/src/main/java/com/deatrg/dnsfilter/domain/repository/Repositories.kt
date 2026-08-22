package com.deatrg.dnsfilter.domain.repository

import com.deatrg.dnsfilter.domain.model.DnsServer
import com.deatrg.dnsfilter.domain.model.FilterList
import kotlinx.coroutines.flow.Flow

interface DnsServerRepository {
    val dnsServers: Flow<List<DnsServer>>
    suspend fun addDnsServer(server: DnsServer)
    suspend fun updateDnsServer(server: DnsServer)
    suspend fun deleteDnsServer(serverId: String)
    suspend fun resetToDefaults()
}

interface FilterListRepository {
    val filterLists: Flow<List<FilterList>>
    val filterListCount: Flow<Int>
    val isLoaded: Flow<Boolean>
    val isLoading: Flow<Boolean>
    /** 下载进度：已完成 / 总数；空闲时为 null */
    val downloadProgress: Flow<Pair<Int, Int>?>
    val cacheVersion: Flow<Long>
    suspend fun addFilterList(list: FilterList)
    suspend fun updateFilterList(list: FilterList)
    suspend fun deleteFilterList(listId: String)

    /**
     * 从缓存加载启用的列表，缺失或过期时下载。
     * @return true 表示有可用的 blocklist 数据。
     */
    suspend fun loadFilterLists(): Boolean

    /** 强制重新下载所有启用的列表。 @return 同 [loadFilterLists]。 */
    suspend fun refreshLists(): Boolean
    fun getFilterLastUpdated(filterList: FilterList): Long?
}
