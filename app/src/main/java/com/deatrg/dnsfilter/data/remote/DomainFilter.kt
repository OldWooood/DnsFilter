package com.deatrg.dnsfilter.data.remote

import android.content.Context
import com.deatrg.dnsfilter.AppLog
import com.deatrg.dnsfilter.data.local.BlocklistCacheManager
import com.deatrg.dnsfilter.domain.model.FilterList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class DomainFilter(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) {

    companion object {
        private const val TAG = "DomainFilter"
    }

    private val cacheManager = BlocklistCacheManager(context)

    @Volatile
    private var blockedDomains: Set<String> = emptySet()

    @Volatile
    private var filterListsToLoad: List<FilterList> = emptyList()

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

    /**
     * 设置要加载的过滤列表（从本地缓存加载，如果没有缓存则标记需要下载）
     */
    suspend fun setFilterLists(filterLists: List<FilterList>) = withContext(Dispatchers.IO) {
        val enabled = filterLists.filter { it.isEnabled }
        filterListsToLoad = enabled

        // 重置状态
        _isLoaded.value = false
        _isLoading.value = false
        _downloadProgress.value = null
        _filterListCount.value = 0
        blockedDomains = emptySet()

        // 如果没有启用的过滤列表，直接标记为已加载（空 blocklist 是合法状态）
        if (enabled.isEmpty()) {
            AppLog.d(TAG, "No filter lists enabled, marking as loaded with empty blocklist")
            _isLoaded.value = true
            return@withContext
        }

        // 并行从本地缓存加载
        val cachedBlocklists = coroutineScope {
            enabled.map { list -> async { cacheManager.loadBlocklist(list) } }.awaitAll()
        }
        applyMergedDomains(cachedBlocklists.filterNotNull())
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

        val lists = filterListsToLoad

        // 如果没有需要加载的列表，直接返回 true（空 blocklist 是合法状态）
        if (lists.isEmpty()) {
            _isLoaded.value = true
            AppLog.d(TAG, "No filter lists to load, returning success with empty blocklist")
            return@withContext true
        }

        _isLoading.value = true
        _downloadProgress.value = Pair(0, lists.size)

        try {
            // 各列表并行下载/加载，互不阻塞
            val sources = coroutineScope {
                val completed = AtomicInteger(0)
                lists.map { filterList ->
                    async {
                        val result = obtainDomains(filterList, forceReload)
                        _downloadProgress.value = Pair(completed.incrementAndGet(), lists.size)
                        result
                    }
                }.awaitAll()
            }

            applyMergedDomains(sources.filterNotNull())
            _isLoaded.value
        } finally {
            _isLoading.value = false
            _downloadProgress.value = null
        }
    }

    /** 缓存优先；过期或强制刷新时下载，下载失败回退旧缓存。 */
    private suspend fun obtainDomains(filterList: FilterList, forceReload: Boolean): Set<String>? {
        val hasCache = cacheManager.hasCache(filterList)

        return if (hasCache && !forceReload && !cacheManager.needsUpdate(filterList)) {
            cacheManager.loadBlocklist(filterList)
        } else {
            downloadFilterListDomains(filterList) ?: run {
                if (hasCache) {
                    AppLog.w(TAG, "Download failed for ${filterList.name}, using old cache")
                    cacheManager.loadBlocklist(filterList)
                } else {
                    null
                }
            }
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
        val cachedBlocklists = coroutineScope {
            filterListsToLoad.map { list -> async { cacheManager.loadBlocklist(list) } }.awaitAll()
        }
        applyMergedDomains(cachedBlocklists.filterNotNull())
    }

    /** 合并各列表域名（忽略通配符条目）并发布到内存与状态流。 */
    private fun applyMergedDomains(sources: List<Set<String>>) {
        var capacityHint = 0
        sources.forEach { capacityHint += it.size }
        val merged = HashSet<String>(capacityHint.coerceAtLeast(1000))
        sources.forEach { source ->
            source.forEach { domain ->
                if (!domain.contains("*")) {
                    merged.add(domain)
                }
            }
        }

        blockedDomains = merged
        _filterListCount.value = merged.size
        _isLoaded.value = merged.isNotEmpty()
        AppLog.d(TAG, "Blocklist updated: ${merged.size} domains from ${sources.size} lists")
    }

    fun isDomainBlocked(domain: String): Boolean {
        return blockedDomains.contains(domain)
    }

    /**
     * 获取指定过滤列表的最后更新时间
     */
    fun getFilterLastUpdated(filterList: FilterList): Long? {
        return cacheManager.getLastUpdated(filterList)
    }

    private fun notifyCacheChanged() {
        _cacheVersion.value = cacheVersionCounter.incrementAndGet()
    }
}

/**
 * 解析 hosts 格式行（`0.0.0.0 domain` / `127.0.0.1 domain`）或纯域名行，
 * 返回小写化并去掉尾部点的域名；无法解析时返回 null。
 */
internal fun parseHostLine(rawLine: String): String? {
    val line = rawLine.trim()
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

    return if (line.contains(".") && !looksLikeIpLiteral(line)) {
        line.lowercase().trimEnd('.')
    } else {
        null
    }
}

/** 仅由数字和点组成（如 `0.0.0.0`）的 token 是 IP 字面量，不是可拦截的域名。 */
private fun looksLikeIpLiteral(token: String): Boolean {
    return token.isNotEmpty() && token.all { it.isDigit() || it == '.' }
}
