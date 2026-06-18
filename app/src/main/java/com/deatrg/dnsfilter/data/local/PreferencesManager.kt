package com.deatrg.dnsfilter.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
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
        private val VPN_ENABLED = booleanPreferencesKey("vpn_enabled")
    }

    val dnsServers: Flow<List<DnsServer>> = dataStore.data.map { prefs ->
        val json = prefs[DNS_SERVERS] ?: "[]"
        parseServers(json)
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
        val json = prefs[FILTER_LISTS] ?: "[]"
        parseFilterLists(json)
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

    val isVpnEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[VPN_ENABLED] ?: false
    }

    suspend fun saveDnsServers(servers: List<DnsServer>) {
        dataStore.edit { prefs ->
            prefs[DNS_SERVERS] = serversToJson(servers)
        }
    }

    suspend fun resetDnsServersToDefaults() {
        saveDnsServers(getDefaultServers())
    }

    suspend fun saveFilterLists(lists: List<FilterList>) {
        dataStore.edit { prefs ->
            prefs[FILTER_LISTS] = filterListsToJson(lists)
        }
    }

    suspend fun setVpnEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[VPN_ENABLED] = enabled
        }
    }

    private fun parseServers(json: String): List<DnsServer> {
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                DnsServer(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    address = obj.getString("address"),
                    isEnabled = obj.getBoolean("isEnabled")
                )
            }
        } catch (e: Exception) {
            getDefaultServers()
        }
    }

    private fun parseFilterLists(json: String): List<FilterList> {
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                FilterList(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    url = obj.getString("url"),
                    isEnabled = obj.getBoolean("isEnabled"),
                    isBuiltIn = obj.getBoolean("isBuiltIn")
                )
            }
        } catch (e: Exception) {
            getDefaultFilterLists()
        }
    }

    private fun serversToJson(servers: List<DnsServer>): String {
        val array = JSONArray()
        servers.forEach { server ->
            val obj = JSONObject().apply {
                put("id", server.id)
                put("name", server.name)
                put("address", server.address)
                put("isEnabled", server.isEnabled)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun filterListsToJson(lists: List<FilterList>): String {
        val array = JSONArray()
        lists.forEach { list ->
            val obj = JSONObject().apply {
                put("id", list.id)
                put("name", list.name)
                put("url", list.url)
                put("isEnabled", list.isEnabled)
                put("isBuiltIn", list.isBuiltIn)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun getDefaultServers(): List<DnsServer> = listOf(
        // Tencent DNS (Primary)
        DnsServer(
            id = "1",
            name = "Tencent DNS",
            address = "119.29.29.29",
            isEnabled = true
        ),
        // Tencent DNS (Secondary)
        DnsServer(
            id = "2",
            name = "Tencent DNS 2",
            address = "119.28.28.28",
            isEnabled = false
        ),
        // AliDNS (Primary)
        DnsServer(
            id = "3",
            name = "AliDNS",
            address = "223.5.5.5",
            isEnabled = true
        ),
        // AliDNS (Secondary)
        DnsServer(
            id = "4",
            name = "AliDNS 2",
            address = "223.6.6.6",
            isEnabled = false
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
