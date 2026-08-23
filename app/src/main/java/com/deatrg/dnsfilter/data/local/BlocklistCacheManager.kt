package com.deatrg.dnsfilter.data.local

import android.content.Context
import com.deatrg.dnsfilter.AppLog
import com.deatrg.dnsfilter.domain.model.FilterList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Blocklist 本地缓存管理器
 * 将下载的过滤规则持久化到磁盘，避免每次启动都重新下载
 *
 * 缓存文件与元数据均按 filterList.id 做 key，保证编辑 URL 后元数据一致。
 */
class BlocklistCacheManager(private val context: Context) {

    companion object {
        private const val TAG = "BlocklistCache"
        private const val CACHE_DIR = "blocklist_cache"
        private const val META_FILE = "cache_meta.json"
        private const val CACHE_SUFFIX = ".txt"
        private const val TMP_SUFFIX = ".tmp"
        private const val UPDATE_INTERVAL_HOURS = 24L // Cache freshness window; manual refresh bypasses this.

        // 缓存元数据
        private data class CacheMeta(
            val id: String,
            val url: String,
            val lastUpdated: Long,
            val domainCount: Int
        )
    }

    private val cacheDir: File by lazy {
        File(context.cacheDir, CACHE_DIR).apply {
            if (!exists()) mkdirs()
        }
    }
    private val metaLock = Any()
    @Volatile
    private var metaCache: MutableMap<String, CacheMeta>? = null

    private fun getCacheFile(filterListId: String): File {
        return File(cacheDir, "$filterListId$CACHE_SUFFIX")
    }

    /**
     * 保存 blocklist 到缓存。
     * 先写临时文件再原子 rename，避免写一半崩溃留下损坏的缓存。
     */
    suspend fun saveBlocklist(filterList: FilterList, domains: Set<String>) = withContext(Dispatchers.IO) {
        val target = getCacheFile(filterList.id)
        val tmp = File(cacheDir, "$filterList.id$CACHE_SUFFIX$TMP_SUFFIX")
        try {
            BufferedWriter(FileWriter(tmp)).use { writer ->
                domains.forEach { domain ->
                    writer.write(domain)
                    writer.newLine()
                }
            }
            if (!tmp.renameTo(target)) {
                // 某些文件系统上 rename 不能覆盖已存在的文件
                if (target.exists() && !target.delete()) {
                    throw IOException("Failed to delete old cache file ${target.name}")
                }
                if (!tmp.renameTo(target)) {
                    throw IOException("Failed to rename cache file ${tmp.name}")
                }
            }

            // 更新元数据
            updateMeta(filterList, domains.size)
        } catch (e: Exception) {
            tmp.delete()
            AppLog.e(TAG, "Failed to save blocklist cache for ${filterList.name}", e)
        }
    }

    /**
     * 从缓存加载 blocklist
     */
    suspend fun loadBlocklist(filterList: FilterList): Set<String>? = withContext(Dispatchers.IO) {
        val cacheFile = getCacheFile(filterList.id)
        if (!cacheFile.exists()) return@withContext null

        try {
            val domains = mutableSetOf<String>()
            BufferedReader(FileReader(cacheFile)).use { reader ->
                reader.lineSequence().forEach { line ->
                    if (line.isNotBlank()) {
                        domains.add(line.trim())
                    }
                }
            }
            domains
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to load blocklist cache for ${filterList.name}", e)
            null
        }
    }

    /**
     * 检查缓存是否需要更新
     */
    fun needsUpdate(filterList: FilterList): Boolean {
        val meta = getMetaCompat(filterList)
        if (meta == null) return true

        val hoursSinceUpdate = (System.currentTimeMillis() - meta.lastUpdated) / TimeUnit.HOURS.toMillis(1)
        return hoursSinceUpdate >= UPDATE_INTERVAL_HOURS
    }

    /**
     * 检查是否有缓存
     */
    fun hasCache(filterList: FilterList): Boolean {
        return getCacheFile(filterList.id).exists()
    }

    /**
     * 获取指定列表的最后更新时间
     */
    fun getLastUpdated(filterList: FilterList): Long? {
        return getMetaCompat(filterList)?.lastUpdated
    }

    /**
     * 清除指定 blocklist 的缓存
     */
    suspend fun clearCache(filterList: FilterList) = withContext(Dispatchers.IO) {
        getCacheFile(filterList.id).delete()
        removeMeta(filterList)
    }

    private fun updateMeta(filterList: FilterList, domainCount: Int) {
        val snapshot = synchronized(metaLock) {
            val metaMap = getOrLoadMetaCache()
            metaMap[filterList.id] = CacheMeta(
                id = filterList.id,
                url = filterList.url,
                lastUpdated = System.currentTimeMillis(),
                domainCount = domainCount
            )
            // 清理 3.0.2 之前按 URL 做 key 的遗留条目
            metaMap.remove(filterList.url)
            metaMap.toMap()
        }
        saveAllMeta(snapshot)
    }

    private fun getMeta(id: String): CacheMeta? {
        return synchronized(metaLock) {
            getOrLoadMetaCache()[id]
        }
    }

    /**
     * 兼容历史数据：3.0.2 之前元数据以 URL 为 key，之后改为 id。
     * 升级用户磁盘上的旧 meta 只有 URL 键，按 id 查不到时回退 URL。
     */
    private fun getMetaCompat(filterList: FilterList): CacheMeta? {
        return getMeta(filterList.id) ?: getMeta(filterList.url)
    }

    private fun removeMeta(filterList: FilterList) {
        val snapshot = synchronized(metaLock) {
            val metaMap = getOrLoadMetaCache()
            metaMap.remove(filterList.id)
            metaMap.remove(filterList.url)
            metaMap.toMap()
        }
        saveAllMeta(snapshot)
    }

    private fun loadAllMetaFromDisk(): Map<String, CacheMeta> {
        val metaFile = File(cacheDir, META_FILE)
        if (!metaFile.exists()) return emptyMap()

        return try {
            BufferedReader(FileReader(metaFile)).use { reader ->
                val json = reader.readText()
                parseMetaJson(json)
            }
        } catch (e: Exception) {
            AppLog.w(TAG) { "Failed to load cache meta: ${e.message}" }
            emptyMap()
        }
    }

    private fun getOrLoadMetaCache(): MutableMap<String, CacheMeta> {
        val cached = metaCache
        if (cached != null) {
            return cached
        }
        val loaded = loadAllMetaFromDisk().toMutableMap()
        metaCache = loaded
        return loaded
    }

    private fun saveAllMeta(metaMap: Map<String, CacheMeta>) {
        val tmp = File(cacheDir, "$META_FILE$TMP_SUFFIX")
        try {
            BufferedWriter(FileWriter(tmp)).use { writer ->
                writer.write(metaMapToJson(metaMap))
            }
            val target = File(cacheDir, META_FILE)
            if (!tmp.renameTo(target)) {
                if (target.exists() && !target.delete()) {
                    throw IOException("Failed to delete old meta file")
                }
                if (!tmp.renameTo(target)) {
                    throw IOException("Failed to rename meta file")
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            AppLog.e(TAG, "Failed to save cache meta", e)
        }
    }

    private fun parseMetaJson(json: String): Map<String, CacheMeta> {
        val map = mutableMapOf<String, CacheMeta>()
        try {
            val obj = JSONObject(json)
            obj.keys().forEach { id ->
                val metaObj = obj.getJSONObject(id)
                map[id] = CacheMeta(
                    id = id,
                    url = metaObj.optString("url"),
                    lastUpdated = metaObj.getLong("lastUpdated"),
                    domainCount = metaObj.optInt("domainCount")
                )
            }
        } catch (e: Exception) {
            AppLog.w(TAG) { "Failed to parse cache meta: ${e.message}" }
        }
        return map
    }

    private fun metaMapToJson(metaMap: Map<String, CacheMeta>): String {
        val obj = JSONObject()
        metaMap.forEach { (id, meta) ->
            val metaObj = JSONObject()
            metaObj.put("url", meta.url)
            metaObj.put("lastUpdated", meta.lastUpdated)
            metaObj.put("domainCount", meta.domainCount)
            obj.put(id, metaObj)
        }
        return obj.toString()
    }
}
