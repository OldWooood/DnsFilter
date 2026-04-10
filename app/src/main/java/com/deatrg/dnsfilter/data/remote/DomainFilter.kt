package com.deatrg.dnsfilter.data.remote

import android.content.Context
import android.util.Log
import com.deatrg.dnsfilter.data.local.BlocklistCacheManager
import com.deatrg.dnsfilter.domain.model.FilterList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger

class DomainFilter(private val context: Context) {

    companion object {
        private const val TAG = "DomainFilter"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cacheManager = BlocklistCacheManager(context)
    
    private val blockedDomains = mutableSetOf<String>()
    private val blockedPatterns = mutableListOf<Regex>()

    // LRU cache for allowed domains (O(1) lookup)
    private val okCache = object : LinkedHashMap<String, Boolean>(1000, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
            return size > 10000
        }
    }

    // LRU cache for blocked domains with reason
    private val blockedCache = object : LinkedHashMap<String, String>(1000, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > 10000
        }
    }

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _filterListCount = MutableStateFlow(0)
    val filterListCount: StateFlow<Int> = _filterListCount.asStateFlow()
    
    // 下载进度：已下载数量 / 总数
    private val _downloadProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val downloadProgress: StateFlow<Pair<Int, Int>?> = _downloadProgress.asStateFlow()

    private var filterListsToLoad: List<FilterList> = emptyList()

    /**
     * 设置要加载的过滤列表（从本地缓存加载，如果没有缓存则标记需要下载）
     */
    suspend fun setFilterLists(filterLists: List<FilterList>) = withContext(Dispatchers.IO) {
        filterListsToLoad = filterLists.filter { it.isEnabled }
        
        // 重置状态
        _isLoaded.value = false
        _isLoading.value = false
        _downloadProgress.value = null
        _filterListCount.value = 0
        synchronized(this@DomainFilter) {
            okCache.clear()
            blockedCache.clear()
        }
        blockedDomains.clear()
        blockedPatterns.clear()
        
        // 如果没有启用的过滤列表，直接标记为已加载（空 blocklist 是合法状态）
        if (filterListsToLoad.isEmpty()) {
            _isLoaded.value = true
            Log.d(TAG, "No filter lists enabled, marking as loaded with empty blocklist")
            return@withContext
        }
        
        // 从本地缓存加载
        var totalLoaded = 0
        filterListsToLoad.forEach { filterList ->
            val cachedDomains = cacheManager.loadBlocklist(filterList)
            if (cachedDomains != null) {
                addDomainsToBlocklist(cachedDomains)
                totalLoaded += cachedDomains.size
                Log.d(TAG, "Loaded ${cachedDomains.size} domains from cache for ${filterList.name}")
            }
        }
        
        _filterListCount.value = blockedDomains.size + blockedPatterns.size
        // 只要有数据就标记为已加载（允许部分列表失败）
        if (totalLoaded > 0) {
            _isLoaded.value = true
            Log.d(TAG, "Total loaded from cache: $totalLoaded domains")
        }
    }

    /**
     * 下载并更新指定过滤列表
     * @return 下载的域名数量，null 表示下载失败
     */
    suspend fun downloadFilterList(filterList: FilterList): Int? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Downloading filter list: ${filterList.name} from ${filterList.url}")
            val url = URL(filterList.url)
            val domains = mutableSetOf<String>()
            
            url.openConnection().getInputStream().use { input ->
                BufferedReader(InputStreamReader(input)).use { reader ->
                    reader.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .forEach { line ->
                            val domain = parseHostLine(line)
                            if (domain != null) {
                                domains.add(domain)
                            }
                        }
                }
            }
            
            // 保存到缓存
            cacheManager.saveBlocklist(filterList, domains)
            
            Log.d(TAG, "Downloaded ${domains.size} domains for ${filterList.name}")
            domains.size
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download ${filterList.name}", e)
            null
        }
    }

    /**
     * 检查并自动更新需要更新的列表（后台静默更新，不显示 loading）
     */
    suspend fun checkAndUpdate(): Boolean = withContext(Dispatchers.IO) {
        if (_isLoading.value) return@withContext false

        try {
            val updatedLists = mutableListOf<FilterList>()

            filterListsToLoad.forEach { filterList ->
                val needsUpdate = cacheManager.needsUpdate(filterList)
                val hasCache = cacheManager.hasCache(filterList)

                if (!hasCache || needsUpdate) {
                    // 后台更新：先下载
                    val count = downloadFilterList(filterList)
                    if (count != null) {
                        updatedLists.add(filterList)
                    }
                }
            }

            // 只在有列表更新时才重新加载所有列表（只加载一次）
            if (updatedLists.isNotEmpty()) {
                reloadAllFromCache()
            }

            _filterListCount.value = blockedDomains.size + blockedPatterns.size
            updatedLists.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates", e)
            false
        }
    }

    /**
     * 手动触发加载（用于首次启动时下载）
     * 返回 true 表示可以开始拦截（有数据或空列表都视为成功）
     */
    suspend fun loadFilterLists(): Boolean = withContext(Dispatchers.IO) {
        if (_isLoading.value) return@withContext _isLoaded.value
        
        // 如果没有需要加载的列表，直接返回 true（空 blocklist 是合法状态）
        if (filterListsToLoad.isEmpty()) {
            _isLoaded.value = true
            Log.d(TAG, "No filter lists to load, returning success with empty blocklist")
            return@withContext true
        }
        
        _isLoading.value = true
        _downloadProgress.value = Pair(0, filterListsToLoad.size)
        val downloadedCount = AtomicInteger(0)
        
        try {
            // 清除旧数据
            synchronized(this@DomainFilter) {
                okCache.clear()
                blockedCache.clear()
            }
            blockedDomains.clear()
            blockedPatterns.clear()
            
            var loadedCount = 0
            
            filterListsToLoad.forEach { filterList ->
                val hasCache = cacheManager.hasCache(filterList)
                
                val domains = if (hasCache && !cacheManager.needsUpdate(filterList)) {
                    // 使用缓存
                    cacheManager.loadBlocklist(filterList)
                } else {
                    // 需要下载
                    val count = downloadFilterList(filterList)
                    if (count != null) {
                        loadedCount++
                        cacheManager.loadBlocklist(filterList)
                    } else {
                        // 下载失败，尝试使用旧缓存
                        if (hasCache) {
                            Log.w(TAG, "Download failed for ${filterList.name}, using old cache")
                            cacheManager.loadBlocklist(filterList)
                        } else {
                            null
                        }
                    }
                }
                
                domains?.let { addDomainsToBlocklist(it) }
                downloadedCount.incrementAndGet()
                _downloadProgress.value = Pair(downloadedCount.get(), filterListsToLoad.size)
            }
            
            _filterListCount.value = blockedDomains.size + blockedPatterns.size
            
            // 只要有至少一个列表加载成功，或所有列表都有旧缓存，就视为成功
            // 允许部分列表失败，但至少要有一个列表的缓存
            val hasAnyData = blockedDomains.isNotEmpty() || blockedPatterns.isNotEmpty()
            _isLoaded.value = hasAnyData
            
            Log.d(TAG, "loadFilterLists completed: ${blockedDomains.size} domains, loaded=$loadedCount/${filterListsToLoad.size}")
            hasAnyData
        } finally {
            _isLoading.value = false
            _downloadProgress.value = null
        }
    }

    /**
     * 重新加载单个过滤列表（用于添加新列表后）
     */
    suspend fun reloadFilterList(filterList: FilterList) = withContext(Dispatchers.IO) {
        // 下载并更新
        downloadFilterList(filterList)
        reloadAllFromCache()
    }

    /**
     * 移除过滤列表
     */
    suspend fun removeFilterList(filterList: FilterList) = withContext(Dispatchers.IO) {
        cacheManager.clearCache(filterList)
        // 重新加载所有列表
        reloadAllFromCache()
    }

    /**
     * 从缓存重新加载所有列表
     */
    private suspend fun reloadAllFromCache() = withContext(Dispatchers.IO) {
        // 预计算总大小以避免 rehash
        var totalDomains = 0
        filterListsToLoad.forEach { filterList ->
            cacheManager.loadBlocklist(filterList)?.let { totalDomains += it.size }
        }

        // 使用预分配容量创建新集合
        val newBlockedDomains = HashSet<String>(totalDomains.coerceAtLeast(1000))
        val newBlockedPatterns = mutableListOf<Regex>()

        filterListsToLoad.forEach { filterList ->
            val cachedDomains = cacheManager.loadBlocklist(filterList)
            if (cachedDomains != null) {
                cachedDomains.forEach { domain ->
                    if (domain.contains("*")) {
                        newBlockedPatterns.add(createPattern(domain))
                    } else {
                        newBlockedDomains.add(domain)
                    }
                }
            }
        }

        // 原子性替换集合内容
        synchronized(this@DomainFilter) {
            okCache.clear()
            blockedCache.clear()
            blockedDomains.clear()
            blockedPatterns.clear()
            blockedDomains.addAll(newBlockedDomains)
            blockedPatterns.addAll(newBlockedPatterns)
        }

        _filterListCount.value = blockedDomains.size + blockedPatterns.size
        _isLoaded.value = blockedDomains.isNotEmpty() || blockedPatterns.isNotEmpty()
    }

    private fun addDomainsToBlocklist(domains: Set<String>) {
        domains.forEach { domain ->
            if (domain.contains("*")) {
                blockedPatterns.add(createPattern(domain))
            } else {
                blockedDomains.add(domain)
            }
        }
    }

    private fun parseHostLine(line: String): String? {
        val parts = line.split(Regex("\\s+"))
        return if (parts.size >= 2) {
            val ip = parts[0]
            val domain = parts[1]
            if ((ip == "0.0.0.0" || ip == "127.0.0.1") && domain.isNotEmpty()) {
                domain.lowercase()
            } else null
        } else if (line.contains(".") && !line.startsWith("#")) {
            line.lowercase().trimEnd('.')
        } else null
    }

    private fun createPattern(domain: String): Regex {
        val regexPattern = domain
            .replace(".", "\\.")
            .replace("*", ".*")
        return Regex("^$regexPattern$", RegexOption.IGNORE_CASE)
    }

    fun isDomainBlocked(domain: String): BlockResult {
        val normalizedDomain = domain.lowercase().trimEnd('.')

        synchronized(this) {
            // Check caches first (O(1))
            okCache[normalizedDomain]?.let {
                return BlockResult(false, null)
            }

            blockedCache[normalizedDomain]?.let {
                return BlockResult(true, it)
            }
        }

        // Check if domain is blocked (may involve parent domain traversal)
        val result = checkBlocked(normalizedDomain)

        synchronized(this) {
            if (result.isBlocked) {
                blockedCache[normalizedDomain] = result.reason ?: "blocked"
            } else {
                okCache[normalizedDomain] = true
            }
        }

        return result
    }

    private fun checkBlocked(domain: String): BlockResult {
        // O(1) lookup in HashSet
        if (blockedDomains.contains(domain)) {
            return BlockResult(true, "blocked_domain")
        }

        // Check parent domains (e.g., ad.example.com -> example.com -> .com)
        var checkDomain = domain
        while (checkDomain.contains(".")) {
            if (blockedDomains.contains(checkDomain)) {
                return BlockResult(true, "blocked_parent_domain")
            }
            checkDomain = checkDomain.substringAfter(".")
        }

        // Check regex patterns
        for (pattern in blockedPatterns) {
            if (pattern.matches(domain)) {
                return BlockResult(true, "blocked_pattern")
            }
        }

        return BlockResult(false, null)
    }

    fun shutdown() {
        scope.cancel()
    }
}

data class BlockResult(
    val isBlocked: Boolean,
    val reason: String?
)
