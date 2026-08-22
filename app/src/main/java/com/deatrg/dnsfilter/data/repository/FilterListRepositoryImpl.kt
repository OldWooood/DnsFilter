package com.deatrg.dnsfilter.data.repository

import com.deatrg.dnsfilter.data.local.PreferencesManager
import com.deatrg.dnsfilter.data.remote.DomainFilter
import com.deatrg.dnsfilter.domain.model.FilterList
import com.deatrg.dnsfilter.domain.repository.FilterListRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class FilterListRepositoryImpl(
    private val preferencesManager: PreferencesManager,
    private val domainFilter: DomainFilter
) : FilterListRepository {

    override val filterLists: Flow<List<FilterList>> = preferencesManager.filterLists
    override val filterListCount: Flow<Int> = domainFilter.filterListCount
    override val isLoaded: Flow<Boolean> = domainFilter.isLoaded
    override val isLoading: Flow<Boolean> = domainFilter.isLoading
    override val downloadProgress: Flow<Pair<Int, Int>?> = domainFilter.downloadProgress
    override val cacheVersion: Flow<Long> = domainFilter.cacheVersion

    /**
     * 添加过滤列表 - 立即下载并加载到内存
     */
    override suspend fun addFilterList(list: FilterList) {
        preferencesManager.editFilterLists { current -> current + list }

        // 立即下载
        if (list.isEnabled) {
            domainFilter.downloadFilterList(list)
            // 更新 filterListsToLoad 并重新加载所有列表到内存
            syncEnabledLists()
            domainFilter.reloadAllFromCache()
        }
    }

    /**
     * 更新过滤列表 - 如果 URL 变化则重新下载
     */
    override suspend fun updateFilterList(list: FilterList) {
        var urlChanged = false
        preferencesManager.editFilterLists { current ->
            current.map { existing ->
                if (existing.id == list.id) {
                    urlChanged = existing.url != list.url
                    list
                } else {
                    existing
                }
            }
        }

        // 如果启用状态或 URL 变化，重新下载
        if (list.isEnabled && (urlChanged || !domainFilter.isLoaded.value)) {
            domainFilter.downloadFilterList(list)
        }
        // 更新 filterListsToLoad 并从磁盘缓存重新加载到内存
        syncEnabledLists()
    }

    /**
     * 删除过滤列表 - 清除缓存
     */
    override suspend fun deleteFilterList(listId: String) {
        val listToRemove = preferencesManager.filterLists.first().find { it.id == listId }
        preferencesManager.editFilterLists { current -> current.filterNot { it.id == listId } }

        // 清除缓存
        listToRemove?.let { domainFilter.removeFilterList(it) }

        syncEnabledLists()
    }

    /**
     * 初始化时从本地缓存加载（不下载）
     */
    override suspend fun loadFilterLists(): Boolean {
        syncEnabledLists()
        return domainFilter.loadFilterLists()
    }

    /**
     * 刷新过滤列表（强制重新下载所有列表）
     */
    override suspend fun refreshLists(): Boolean {
        syncEnabledLists()
        return domainFilter.loadFilterLists(forceReload = true)
    }

    override fun getFilterLastUpdated(filterList: FilterList): Long? {
        return domainFilter.getFilterLastUpdated(filterList)
    }

    private suspend fun syncEnabledLists() {
        domainFilter.setFilterLists(preferencesManager.filterLists.first().filter { it.isEnabled })
    }
}
