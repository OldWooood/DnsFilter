package com.deatrg.dnsfilter.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocklistBinaryFormatTest {

    @Test
    fun `round trip preserves domains`() {
        val domains = setOf("example.com", "ads.tracker.net", "xn--nxasmq6b.example")
        val decoded = BlocklistCacheManager.decodeBlocklistBinary(
            BlocklistCacheManager.encodeBlocklistBinary(domains)
        )
        assertEquals(domains, decoded)
    }

    @Test
    fun `empty set round trips`() {
        val decoded = BlocklistCacheManager.decodeBlocklistBinary(
            BlocklistCacheManager.encodeBlocklistBinary(emptySet())
        )
        assertEquals(emptySet<String>(), decoded)
    }

    @Test
    fun `bad magic is rejected`() {
        val bytes = BlocklistCacheManager.encodeBlocklistBinary(setOf("example.com"))
        bytes[0] = 0x00
        assertNull(BlocklistCacheManager.decodeBlocklistBinary(bytes))
    }

    @Test
    fun `truncated input is rejected`() {
        val bytes = BlocklistCacheManager.encodeBlocklistBinary(setOf("example.com"))
        assertNull(BlocklistCacheManager.decodeBlocklistBinary(bytes.copyOf(bytes.size - 2)))
    }

    @Test
    fun `trailing garbage is rejected`() {
        val bytes = BlocklistCacheManager.encodeBlocklistBinary(setOf("example.com"))
        assertNull(BlocklistCacheManager.decodeBlocklistBinary(bytes + byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `bin file names contain no path separators`() {
        listOf("1", "builtin_anti_ad").forEach { id ->
            val name = BlocklistCacheManager.cacheBinFileName(id)
            assertTrue(name.endsWith(".bin"))
            assertEquals(false, name.contains('/'))
        }
    }
}
