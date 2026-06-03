package com.deatrg.dnsfilter.data.remote

import android.content.Context
import com.deatrg.dnsfilter.AppLog
import com.deatrg.dnsfilter.data.local.BlocklistCacheManager
import com.deatrg.dnsfilter.domain.model.FilterList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.util.BitSet
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class DomainFilter(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) {

    companion object {
        private const val TAG = "DomainFilter"
        private const val BLOOM_FPP = 0.01
        private const val BLOOM_MIN_SIZE = 10_000
    }

    private class BloomFilter(expectedInsertions: Int, private val falsePositiveRate: Double) {
        private val bitSize: Int = run {
            val ln2 = Math.log(2.0)
            val lnP = Math.log(falsePositiveRate)
            val raw = -expectedInsertions.toDouble() * lnP / (ln2 * ln2)
            if (raw.isFinite().not() || raw <= 64.0) 64
            else if (raw >= 1_000_000_000.0) 1_000_000_000
            else raw.toInt()
        }
        private val numHashFunctions: Int = run {
            val raw = Math.log(2.0) * bitSize / expectedInsertions.toDouble()
            if (raw.isFinite().not() || raw <= 1.0) 1
            else if (raw >= 64.0) 64
            else raw.toInt()
        }
        private val bits = BitSet(bitSize)

        private fun hashPair(value: String): Pair<Int, Int> {
            val h = value.hashCode()
            return Pair(h, h xor (h ushr 16))
        }

        fun put(value: String) {
            val (h1, h2) = hashPair(value)
            var h = h1.toLong() and 0xFFFFFFFFL
            val inc = h2.toLong() and 0xFFFFFFFFL
            for (i in 0 until numHashFunctions) {
                bits.set((h % bitSize).toInt())
                h = (h + inc) and 0xFFFFFFFFL
            }
        }

        fun mightContain(value: String): Boolean {
            val (h1, h2) = hashPair(value)
            var h = h1.toLong() and 0xFFFFFFFFL
            val inc = h2.toLong() and 0xFFFFFFFFL
            for (i in 0 until numHashFunctions) {
                if (!bits.get((h % bitSize).toInt())) return false
                h = (h + inc) and 0xFFFFFFFFL
            }
            return true
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cacheManager = BlocklistCacheManager(context)

    @Volatile
    private var blockedDomains: Set<String> = emptySet()

    @Volatile
    private var bloomFilter: BloomFilter? = null

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _filterListCount = MutableStateFlow(0)
    val filterListCount: StateFlow<Int> = _filterListCount.asStateFlow()
    
    // 下载进度：已下载数量 / 总数
    private val _downloadProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val downloadProgress: StateFlow<Pair<Int, Int>?> = _downloadProgress.asStateFlow()

    private val _cacheVersion = MutableStateFlow(0L)
    val cacheVersion: StateFlow<Long> = _cacheVersion.asStateFlow()

    private val cacheVersionCounter = AtomicLong(0L)

    // Incremented whenever blockedDomains is replaced. Observed by DnsVpnService
    // to invalidate blocked response cache when blocklists change.
    private val _blocklistVersion = MutableStateFlow(0L)
    val blocklistVersion: StateFlow<Long> = _blocklistVersion.asStateFlow()
    private val blocklistVersionCounter = AtomicLong(0L)

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
        blockedDomains = emptySet()
        rebuildBloomFilter()

        // 如果没有启用的过滤列表，直接标记为已加载（空 blocklist 是合法状态）
        if (filterListsToLoad.isEmpty()) {
            _filterListCount.value = 0
            _isLoaded.value = true
            AppLog.d(TAG, "No filter lists enabled, marking as loaded with empty blocklist")
            return@withContext
        }

        // 从本地缓存加载
        var totalLoaded = 0
        val newBlockedDomains = HashSet<String>()
        val cachedBlocklists = loadCachedBlocklists(filterListsToLoad)
        filterListsToLoad.forEach { filterList ->
            val cachedDomains = cachedBlocklists[filterList]
            if (cachedDomains != null) {
                addDomainsToBlocklist(cachedDomains, newBlockedDomains)
                totalLoaded += cachedDomains.size
                AppLog.d(TAG, "Loaded ${cachedDomains.size} domains from cache for ${filterList.name}")
            }
        }

        blockedDomains = newBlockedDomains
        _blocklistVersion.value = blocklistVersionCounter.incrementAndGet()
        _filterListCount.value = newBlockedDomains.size

        // 只要有数据就标记为已加载（允许部分列表失败）
        if (totalLoaded > 0) {
            _isLoaded.value = true
            AppLog.d(TAG, "Total loaded from cache: $totalLoaded domains")
        }
    }

    /**
     * 下载并更新指定过滤列表
     * @return 下载的域名数量，null 表示下载失败
     */
    suspend fun downloadFilterList(filterList: FilterList): Int? = withContext(Dispatchers.IO) {
        downloadFilterListDomains(filterList)?.size
    }

    private suspend fun downloadFilterListDomains(filterList: FilterList): Set<String>? = withContext(Dispatchers.IO) {
        try {
            AppLog.d(TAG, "Downloading filter list: ${filterList.name} from ${filterList.url}")
            val domains = mutableSetOf<String>()

            val request = Request.Builder()
                .url(filterList.url)
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.w(TAG, "Failed to download ${filterList.name}: HTTP ${response.code}")
                    return@withContext null
                }

                val body = response.body ?: return@withContext null
                BufferedReader(body.charStream()).use { reader ->
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
            notifyCacheChanged()
            
            AppLog.d(TAG, "Downloaded ${domains.size} domains for ${filterList.name}")
            domains
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to download ${filterList.name}", e)
            null
        }
    }

    /**
     * Load enabled filter lists from cache, downloading missing or expired lists.
     * @param forceReload Force a download and skip the 24-hour cache freshness check.
     * @return true when usable blocklist data is available.
     */
    suspend fun loadFilterLists(forceReload: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (_isLoading.value) return@withContext _isLoaded.value

        // 如果没有需要加载的列表，直接返回 true（空 blocklist 是合法状态）
        if (filterListsToLoad.isEmpty()) {
            _isLoaded.value = true
            AppLog.d(TAG, "No filter lists to load, returning success with empty blocklist")
            return@withContext true
        }

        _isLoading.value = true
        _downloadProgress.value = Pair(0, filterListsToLoad.size)
        val downloadedCount = AtomicInteger(0)

        try {
            var loadedCount = 0
            val newBlockedDomains = HashSet<String>()

            filterListsToLoad.forEach { filterList ->
                val hasCache = cacheManager.hasCache(filterList)

                val domains = if (hasCache && !forceReload && !cacheManager.needsUpdate(filterList)) {
                    // 使用缓存（除非强制刷新）
                    cacheManager.loadBlocklist(filterList)
                } else {
                    // 需要下载
                    val downloadedDomains = downloadFilterListDomains(filterList)
                    if (downloadedDomains != null) {
                        loadedCount++
                        downloadedDomains
                    } else {
                        // 下载失败，尝试使用旧缓存
                        if (hasCache) {
                            AppLog.w(TAG, "Download failed for ${filterList.name}, using old cache")
                            cacheManager.loadBlocklist(filterList)
                        } else {
                            null
                        }
                    }
                }

                domains?.let { addDomainsToBlocklist(it, newBlockedDomains) }
                downloadedCount.incrementAndGet()
                _downloadProgress.value = Pair(downloadedCount.get(), filterListsToLoad.size)
            }

blockedDomains = newBlockedDomains
        rebuildBloomFilter()
        _blocklistVersion.value = blocklistVersionCounter.incrementAndGet()
            _filterListCount.value = newBlockedDomains.size

            val hasAnyData = newBlockedDomains.isNotEmpty()
            _isLoaded.value = hasAnyData

            AppLog.d(TAG, "loadFilterLists completed: ${newBlockedDomains.size} domains, loaded=$loadedCount/${filterListsToLoad.size}")
            hasAnyData
        } finally {
            _isLoading.value = false
            _downloadProgress.value = null
        }
    }

    /**
     * 移除过滤列表
     */
    suspend fun removeFilterList(filterList: FilterList) = withContext(Dispatchers.IO) {
        cacheManager.clearCache(filterList)
    }

    /**
     * 从缓存重新加载所有列表
     */
    suspend fun reloadAllFromCache() = withContext(Dispatchers.IO) {
        val cachedBlocklists = loadCachedBlocklists(filterListsToLoad)
        val totalDomains = cachedBlocklists.values.sumOf { it.size }

        // 使用预分配容量创建新集合
        val newBlockedDomains = HashSet<String>(totalDomains.coerceAtLeast(1000))

        filterListsToLoad.forEach { filterList ->
            val cachedDomains = cachedBlocklists[filterList]
            if (cachedDomains != null) {
                cachedDomains.forEach { domain ->
                    if (!domain.contains("*")) {
                        newBlockedDomains.add(domain)
                    }
                }
            }
        }

        blockedDomains = newBlockedDomains
        rebuildBloomFilter()
        _blocklistVersion.value = blocklistVersionCounter.incrementAndGet()
        _filterListCount.value = newBlockedDomains.size
        _isLoaded.value = newBlockedDomains.isNotEmpty()
    }

    private fun addDomainsToBlocklist(
        domains: Set<String>,
        blockedDomains: MutableSet<String>
    ) {
        domains.forEach { domain ->
            if (!domain.contains("*")) {
                blockedDomains.add(domain)
            }
        }
    }

    private fun parseHostLine(line: String): String? {
        if (line.isEmpty() || line[0] == '#') return null

        val firstWhitespace = line.indexOfFirst { it.isWhitespace() }
        if (firstWhitespace >= 0) {
            val ip = line.substring(0, firstWhitespace)
            if (ip != "0.0.0.0" && ip != "127.0.0.1") return null

            var domainStart = firstWhitespace + 1
            while (domainStart < line.length && line[domainStart].isWhitespace()) {
                domainStart++
            }
            if (domainStart >= line.length) return null

            var domainEnd = domainStart
            while (domainEnd < line.length && !line[domainEnd].isWhitespace()) {
                domainEnd++
            }
            val domain = line.substring(domainStart, domainEnd).lowercase().trimEnd('.')
            return if (domain.isNotEmpty()) domain else null
        }

        return if (line.contains(".")) {
            line.lowercase().trimEnd('.')
        } else {
            null
        }
    }

    fun isDomainBlocked(domain: String): Boolean {
        val bloom = bloomFilter
        if (bloom != null && !bloom.mightContain(domain)) return false
        return blockedDomains.contains(domain)
    }

    private fun rebuildBloomFilter() {
        val domains = blockedDomains
        bloomFilter = if (domains.size >= 1000) {
            BloomFilter(domains.size, BLOOM_FPP).also { bf ->
                domains.forEach { bf.put(it) }
            }
        } else null
    }

    /**
     * 获取指定过滤列表的最后更新时间
     */
    fun getFilterLastUpdated(filterList: FilterList): Long? {
        return cacheManager.getLastUpdated(filterList.url)
    }

    fun shutdown() {
        scope.cancel()
    }

    private suspend fun loadCachedBlocklists(filterLists: List<FilterList>): Map<FilterList, Set<String>> {
        val loaded = LinkedHashMap<FilterList, Set<String>>(filterLists.size)
        filterLists.forEach { filterList ->
            cacheManager.loadBlocklist(filterList)?.let { loaded[filterList] = it }
        }
        return loaded
    }

    private fun notifyCacheChanged() {
        _cacheVersion.value = cacheVersionCounter.incrementAndGet()
    }
}
