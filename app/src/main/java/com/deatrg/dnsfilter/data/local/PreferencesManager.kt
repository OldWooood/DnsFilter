package com.deatrg.dnsfilter.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.deatrg.dnsfilter.R
import com.deatrg.dnsfilter.domain.model.DnsServer
import com.deatrg.dnsfilter.domain.model.FilterList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dnsfilter_settings")

class PreferencesManager(private val context: Context) {

    private val dataStore = context.dataStore

    companion object {
        private val DNS_SERVERS = stringPreferencesKey("dns_servers")
        private val FILTER_LISTS = stringPreferencesKey("filter_lists")
    }

    val dnsServers: Flow<List<DnsServer>> = dataStore.data.map { prefs ->
        parseServerList(prefs[DNS_SERVERS])
    }

    /**
     * 确保默认 DNS 服务器已初始化（首次安装时调用）
     */
    suspend fun ensureDefaultServersInitialized() {
        dataStore.edit { prefs ->
            if (prefs[DNS_SERVERS] == null || prefs[DNS_SERVERS] == "[]") {
                prefs[DNS_SERVERS] = serversToJson(getDefaultServers())
            }
        }
    }

    val filterLists: Flow<List<FilterList>> = dataStore.data.map { prefs ->
        parseFilterListList(prefs[FILTER_LISTS])
    }

    /**
     * 确保默认过滤列表已初始化（首次安装时调用）
     */
    suspend fun ensureDefaultFilterListsInitialized() {
        dataStore.edit { prefs ->
            if (prefs[FILTER_LISTS] == null || prefs[FILTER_LISTS] == "[]") {
                prefs[FILTER_LISTS] = filterListsToJson(getDefaultFilterLists())
            }
        }
    }

    /**
     * 原子地读-改-写 DNS 服务器列表。
     * DataStore 保证同一时间只有一个 edit 在执行，消除并发读改写丢更新的问题。
     */
    suspend fun editDnsServers(transform: (List<DnsServer>) -> List<DnsServer>) {
        dataStore.edit { prefs ->
            val updated = transform(parseServerList(prefs[DNS_SERVERS]))
            prefs[DNS_SERVERS] = serversToJson(updated)
        }
    }

    /** 原子地读-改-写过滤列表。 */
    suspend fun editFilterLists(transform: (List<FilterList>) -> List<FilterList>) {
        dataStore.edit { prefs ->
            val updated = transform(parseFilterListList(prefs[FILTER_LISTS]))
            prefs[FILTER_LISTS] = filterListsToJson(updated)
        }
    }

    suspend fun saveDnsServers(servers: List<DnsServer>) {
        editDnsServers { servers }
    }

    suspend fun resetDnsServersToDefaults() {
        editDnsServers { getDefaultServers() }
    }

    suspend fun saveFilterLists(lists: List<FilterList>) {
        editFilterLists { lists }
    }

    private fun parseServerList(json: String?): List<DnsServer> =
        decodeJsonList(json, fallback = ::getDefaultServers) { obj ->
            DnsServer(
                id = obj.getString("id"),
                name = obj.getString("name"),
                address = obj.getString("address"),
                isEnabled = obj.getBoolean("isEnabled"),
                isBuiltIn = obj.getBoolean("isBuiltIn")
            )
        }

    private fun parseFilterListList(json: String?): List<FilterList> =
        decodeJsonList(json, fallback = ::getDefaultFilterLists) { obj ->
            FilterList(
                id = obj.getString("id"),
                name = obj.getString("name"),
                url = obj.getString("url"),
                isEnabled = obj.getBoolean("isEnabled"),
                isBuiltIn = obj.getBoolean("isBuiltIn")
            )
        }

    private fun serversToJson(servers: List<DnsServer>): String =
        encodeJsonList(servers) { server ->
            JSONObject().apply {
                put("id", server.id)
                put("name", server.name)
                put("address", server.address)
                put("isEnabled", server.isEnabled)
                put("isBuiltIn", server.isBuiltIn)
            }
        }

    private fun filterListsToJson(lists: List<FilterList>): String =
        encodeJsonList(lists) { list ->
            JSONObject().apply {
                put("id", list.id)
                put("name", list.name)
                put("url", list.url)
                put("isEnabled", list.isEnabled)
                put("isBuiltIn", list.isBuiltIn)
            }
        }

    private fun getDefaultServers(): List<DnsServer> = listOf(
        // Tencent DNS (Primary)
        DnsServer(
            id = "1",
            name = context.getString(R.string.default_dns_tencent),
            address = "119.29.29.29",
            isEnabled = true,
            isBuiltIn = true
        ),
        // Tencent DNS (Secondary)
        DnsServer(
            id = "2",
            name = context.getString(R.string.default_dns_tencent_backup),
            address = "119.28.28.28",
            isEnabled = false,
            isBuiltIn = true
        ),
        // AliDNS (Primary)
        DnsServer(
            id = "3",
            name = context.getString(R.string.default_dns_alibaba),
            address = "223.5.5.5",
            isEnabled = true,
            isBuiltIn = true
        ),
        // AliDNS (Secondary)
        DnsServer(
            id = "4",
            name = context.getString(R.string.default_dns_alibaba_backup),
            address = "223.6.6.6",
            isEnabled = false,
            isBuiltIn = true
        ),
    )

    private fun getDefaultFilterLists(): List<FilterList> = listOf(
        FilterList(
            id = "1",
            name = "Anti-Ad",
            url = "https://anti-ad.net/domains.txt",
            isEnabled = true,
            isBuiltIn = true
        )
    )
}

private inline fun <T> encodeJsonList(items: List<T>, toJson: (T) -> JSONObject): String {
    val array = JSONArray()
    items.forEach { array.put(toJson(it)) }
    return array.toString()
}

/**
 * 解析 JSON 数组。整个数组损坏时返回 fallback 默认值；
 * 单条损坏时只跳过该条，避免一条脏数据导致用户全部配置丢失。
 */
private inline fun <T : Any> decodeJsonList(
    json: String?,
    fallback: () -> List<T>,
    fromJson: (JSONObject) -> T?
): List<T> {
    if (json.isNullOrEmpty()) return fallback()
    return try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            try {
                fromJson(array.getJSONObject(i))
            } catch (e: Exception) {
                null
            }
        }
    } catch (e: Exception) {
        fallback()
    }
}
