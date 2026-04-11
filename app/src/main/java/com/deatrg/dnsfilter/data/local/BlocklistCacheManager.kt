package com.deatrg.dnsfilter.data.local

import android.content.Context
import com.deatrg.dnsfilter.domain.model.FilterList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Blocklist 本地缓存管理器
 * 将下载的过滤规则持久化到磁盘，避免每次启动都重新下载
 */
class BlocklistCacheManager(private val context: Context) {

    companion object {
        private const val CACHE_DIR = "blocklist_cache"
        private const val META_FILE = "cache_meta.json"
        private const val UPDATE_INTERVAL_HOURS = 24L // 24小时自动更新一次
        
        // 缓存元数据
        private data class CacheMeta(
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

    /**
     * 获取缓存文件
     */
    private fun getCacheFile(filterListId: String): File {
        return File(cacheDir, "${filterListId}.txt")
    }

    /**
     * 获取元数据文件
     */
    private fun getMetaFile(): File {
        return File(cacheDir, META_FILE)
    }

    /**
     * 保存 blocklist 到缓存
     */
    suspend fun saveBlocklist(filterList: FilterList, domains: Set<String>) = withContext(Dispatchers.IO) {
        val cacheFile = getCacheFile(filterList.id)
        try {
            BufferedWriter(FileWriter(cacheFile)).use { writer ->
                domains.forEach { domain ->
                    writer.write(domain)
                    writer.newLine()
                }
            }
            
            // 更新元数据
            updateMeta(filterList, domains.size)
        } catch (e: Exception) {
            e.printStackTrace()
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
            e.printStackTrace()
            null
        }
    }

    /**
     * 检查缓存是否需要更新
     */
    fun needsUpdate(filterList: FilterList): Boolean {
        val meta = getMeta(filterList.url)
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
     * 获取指定 URL 的最后更新时间
     */
    fun getLastUpdated(url: String): Long? {
        return getMeta(url)?.lastUpdated
    }

    /**
     * 清除指定 blocklist 的缓存
     */
    suspend fun clearCache(filterList: FilterList) = withContext(Dispatchers.IO) {
        getCacheFile(filterList.id).delete()
        removeMeta(filterList.url)
    }

    /**
     * 清除所有缓存
     */
    suspend fun clearAllCache() = withContext(Dispatchers.IO) {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * 获取缓存大小
     */
    fun getCacheSize(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0
    }

    private fun updateMeta(filterList: FilterList, domainCount: Int) {
        val metaMap = loadAllMeta().toMutableMap()
        metaMap[filterList.url] = CacheMeta(
            url = filterList.url,
            lastUpdated = System.currentTimeMillis(),
            domainCount = domainCount
        )
        saveAllMeta(metaMap)
    }

    private fun getMeta(url: String): CacheMeta? {
        return loadAllMeta()[url]
    }

    private fun removeMeta(url: String) {
        val metaMap = loadAllMeta().toMutableMap()
        metaMap.remove(url)
        saveAllMeta(metaMap)
    }

    private fun loadAllMeta(): Map<String, CacheMeta> {
        val metaFile = getMetaFile()
        if (!metaFile.exists()) return emptyMap()
        
        return try {
            BufferedReader(FileReader(metaFile)).use { reader ->
                val json = reader.readText()
                parseMetaJson(json)
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun saveAllMeta(metaMap: Map<String, CacheMeta>) {
        try {
            BufferedWriter(FileWriter(getMetaFile())).use { writer ->
                writer.write(metaMapToJson(metaMap))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseMetaJson(json: String): Map<String, CacheMeta> {
        val map = mutableMapOf<String, CacheMeta>()
        try {
            val obj = org.json.JSONObject(json)
            obj.keys().forEach { url ->
                val metaObj = obj.getJSONObject(url)
                map[url] = CacheMeta(
                    url = url,
                    lastUpdated = metaObj.getLong("lastUpdated"),
                    domainCount = metaObj.getInt("domainCount")
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return map
    }

    private fun metaMapToJson(metaMap: Map<String, CacheMeta>): String {
        val obj = org.json.JSONObject()
        metaMap.forEach { (url, meta) ->
            val metaObj = org.json.JSONObject()
            metaObj.put("lastUpdated", meta.lastUpdated)
            metaObj.put("domainCount", meta.domainCount)
            obj.put(url, metaObj)
        }
        return obj.toString()
    }
}
