package com.deatrg.dnsfilter.data.repository

import com.deatrg.dnsfilter.data.local.PreferencesManager
import com.deatrg.dnsfilter.data.remote.DomainFilter
import com.deatrg.dnsfilter.domain.model.FilterList
import com.deatrg.dnsfilter.domain.repository.FilterListRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class FilterListRepositoryImpl(
    private val preferencesManager: PreferencesManager,
    private val domainFilter: DomainFilter
) : FilterListRepository {

    override val filterLists: Flow<List<FilterList>> = preferencesManager.filterLists
    override val filterListCount: Flow<Int> = domainFilter.filterListCount
    override val isLoaded: Flow<Boolean> = domainFilter.isLoaded
    override val enabledDnsServerCount: Flow<Int> = preferencesManager.dnsServers.map { servers ->
        servers.count { it.isEnabled }
    }

    /**
     * 保存过滤列表配置（不立即下载，只在设置时同步到 DomainFilter）
     */
    override suspend fun saveFilterLists(lists: List<FilterList>) {
        preferencesManager.saveFilterLists(lists)
        domainFilter.setFilterLists(lists.filter { it.isEnabled })
    }

    /**
     * 添加过滤列表 - 立即下载并加载到内存
     */
    override suspend fun addFilterList(list: FilterList) {
        val current = preferencesManager.filterLists.first().toMutableList()
        current.add(list)
        preferencesManager.saveFilterLists(current)

        // 立即下载
        if (list.isEnabled) {
            domainFilter.downloadFilterList(list)
            // 更新 filterListsToLoad 并重新加载所有列表到内存
            domainFilter.setFilterLists(current.filter { it.isEnabled })
            domainFilter.reloadAllFromCache()
        }
    }

    /**
     * 更新过滤列表 - 如果 URL 变化则重新下载
     */
    override suspend fun updateFilterList(list: FilterList) {
        val current = preferencesManager.filterLists.first().toMutableList()
        val index = current.indexOfFirst { it.id == list.id }
        if (index != -1) {
            val oldList = current[index]
            current[index] = list
            preferencesManager.saveFilterLists(current)

            // 如果启用状态或 URL 变化，重新下载
            if (list.isEnabled) {
                if (oldList.url != list.url || !domainFilter.isLoaded.value) {
                    domainFilter.downloadFilterList(list)
                }
            }
            // 更新 filterListsToLoad 并从磁盘缓存重新加载到内存
            domainFilter.setFilterLists(current.filter { it.isEnabled })
        }
    }

    /**
     * 删除过滤列表 - 清除缓存
     */
    override suspend fun deleteFilterList(listId: String) {
        val current = preferencesManager.filterLists.first().toMutableList()
        val listToRemove = current.find { it.id == listId }
        current.removeAll { it.id == listId }
        preferencesManager.saveFilterLists(current)
        
        // 清除缓存
        if (listToRemove != null) {
            domainFilter.removeFilterList(listToRemove)
        }
    }

    /**
     * 初始化时从本地缓存加载（不下载）
     */
    override suspend fun loadFilterLists() {
        val lists = preferencesManager.filterLists.first()
        domainFilter.setFilterLists(lists.filter { it.isEnabled })
    }

    /**
     * 刷新过滤列表（强制重新下载所有列表）
     */
    override suspend fun refreshLists() {
        val lists = preferencesManager.filterLists.first()
        domainFilter.setFilterLists(lists.filter { it.isEnabled })
        domainFilter.loadFilterLists(forceReload = true)
    }

    /**
     * 检查并自动更新过期的列表
     */
    suspend fun checkAndUpdate() {
        domainFilter.checkAndUpdate()
    }

    override fun getFilterLastUpdated(filterList: FilterList): Long? {
        return domainFilter.getFilterLastUpdated(filterList)
    }
}
