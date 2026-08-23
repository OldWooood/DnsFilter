package com.deatrg.dnsfilter.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 回归测试：缓存临时文件名曾因 `$filterList.id` 缺少 `${}` 被展开成
 * data class 的 toString（含 URL 中的 '/'），导致写缓存与元数据始终失败。
 */
class BlocklistCacheFileNameTest {

    @Test
    fun `cache file name appends txt suffix`() {
        assertEquals("1.txt", BlocklistCacheManager.cacheFileName("1"))
    }

    @Test
    fun `tmp file name appends txt and tmp suffix`() {
        assertEquals("1.txt.tmp", BlocklistCacheManager.cacheTmpFileName("1"))
    }

    @Test
    fun `file names contain no path separators`() {
        // 任意 id 下文件名都必须是单段路径，否则会因目录不存在而写入失败
        listOf("1", "builtin_anti_ad", "0f4d2c1e-9a3b-4c5d-8e7f-1234567890ab").forEach { id ->
            assertFalse(BlocklistCacheManager.cacheFileName(id).contains('/'))
            assertFalse(BlocklistCacheManager.cacheTmpFileName(id).contains('/'))
        }
    }
}
