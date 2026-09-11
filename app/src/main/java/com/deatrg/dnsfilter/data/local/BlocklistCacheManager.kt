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
        private const val CACHE_BIN_SUFFIX = ".bin"
        private const val TMP_SUFFIX = ".tmp"
        private const val UPDATE_INTERVAL_HOURS = 24L // Cache freshness window; manual refresh bypasses this.

        internal const val BIN_MAGIC = 0x444E5342 // "DNSB"
        internal const val BIN_VERSION = 1
        internal const val BIN_MAX_DOMAINS = 2_000_000
        internal const val BIN_MAX_DOMAIN_BYTES = 256

        internal fun cacheBinFileName(filterListId: String): String {
            return "$filterListId$CACHE_BIN_SUFFIX"
        }

        internal fun cacheBinTmpFileName(filterListId: String): String {
            return "${filterListId}$CACHE_BIN_SUFFIX$TMP_SUFFIX"
        }

        /**
         * 紧凑二进制格式：MAGIC u32 | VERSION u32 | COUNT u32 | 条目…。
         * 每条目：LEN u32 + UTF-8 bytes。纯函数，便于单测与发版校验。
         */
        internal fun encodeBlocklistBinary(domains: Set<String>): ByteArray {
            val out = java.io.ByteArrayOutputStream(domains.size.coerceAtMost(1 shl 20) * 24 + 12)
            val data = java.io.DataOutputStream(out)
            data.writeInt(BIN_MAGIC)
            data.writeInt(BIN_VERSION)
            data.writeInt(domains.size)
            domains.forEach { domain ->
                val bytes = domain.toByteArray(Charsets.UTF_8)
                data.writeInt(bytes.size)
                data.write(bytes)
            }
            data.flush()
            return out.toByteArray()
        }

        /**
         * 解析二进制缓存；MAGIC/VERSION/长度任一非法即返回 null，
         * 调用方回退到文本缓存。
         */
        internal fun decodeBlocklistBinary(bytes: ByteArray): Set<String>? {
            try {
                val data = java.io.DataInputStream(bytes.inputStream())
                if (data.readInt() != BIN_MAGIC) return null
                if (data.readInt() != BIN_VERSION) return null
                val count = data.readInt()
                if (count < 0 || count > BIN_MAX_DOMAINS) return null
                val domains = HashSet<String>((count * 4 / 3).coerceAtLeast(16))
                repeat(count) {
                    val len = data.readInt()
                    if (len <= 0 || len > BIN_MAX_DOMAIN_BYTES) return null
                    val buf = ByteArray(len)
                    data.readFully(buf)
                    val domain = String(buf, Charsets.UTF_8).trim()
                    if (domain.isEmpty()) return null
                    domains.add(domain)
                }
                // 尾部有多余字节视为损坏，避免半截写入被误用。
                if (data.available() > 0) return null
                return domains
            } catch (_: Exception) {
                return null
            }
        }

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

    private fun getCacheBinFile(filterListId: String): File {
        return File(cacheDir, cacheBinFileName(filterListId))
    }

    /**
     * 保存 blocklist 到缓存：只写二进制格式。
     * 先写临时文件再原子 rename，避免写一半崩溃留下损坏的缓存。
     */
    suspend fun saveBlocklist(filterList: FilterList, domains: Set<String>) = withContext(Dispatchers.IO) {
        val target = getCacheBinFile(filterList.id)
        val tmp = File(cacheDir, cacheBinTmpFileName(filterList.id))
        try {
            tmp.writeBytes(encodeBlocklistBinary(domains))
            if (!tmp.renameTo(target)) {
                // 某些文件系统上 rename 不能覆盖已存在的文件
                if (target.exists() && !target.delete()) {
                    throw IOException("Failed to delete old binary cache ${target.name}")
                }
                if (!tmp.renameTo(target)) {
                    throw IOException("Failed to rename binary cache ${tmp.name}")
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
     * 从缓存加载 blocklist：只读二进制。缺失或损坏时返回 null，
     * 调用方按无缓存处理并重新下载。
     */
    suspend fun loadBlocklist(filterList: FilterList): Set<String>? = withContext(Dispatchers.IO) {
        loadBlocklistBinary(filterList)
    }

    private fun loadBlocklistBinary(filterList: FilterList): Set<String>? {
        val binFile = getCacheBinFile(filterList.id)
        if (!binFile.exists()) return null
        return try {
            decodeBlocklistBinary(binFile.readBytes())
        } catch (e: Exception) {
            AppLog.w(TAG) { "Binary cache unreadable for ${filterList.name}" }
            null
        }
    }

    /**
     * 检查是否有缓存
     */
    fun hasCache(filterList: FilterList): Boolean {
        return getCacheBinFile(filterList.id).exists()
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
     * 获取指定列表的最后更新时间
     */
    fun getLastUpdated(filterList: FilterList): Long? {
        return getMetaCompat(filterList)?.lastUpdated
    }

    /**
     * 清除指定 blocklist 的缓存
     */
    suspend fun clearCache(filterList: FilterList) = withContext(Dispatchers.IO) {
        getCacheBinFile(filterList.id).delete()
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
