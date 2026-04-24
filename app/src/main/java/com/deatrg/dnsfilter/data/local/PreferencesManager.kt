package com.deatrg.dnsfilter.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.deatrg.dnsfilter.domain.model.DnsServer
import com.deatrg.dnsfilter.domain.model.DnsServerType
import com.deatrg.dnsfilter.domain.model.DnsStatistics
import com.deatrg.dnsfilter.domain.model.FilterList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dnsfilter_settings")

class PreferencesManager(private val context: Context) {

    private val dataStore = context.dataStore

    data class StatisticsState(
        val statistics: DnsStatistics,
        val totalResponseTime: Long,
        val responseSampleCount: Long
    )

    companion object {
        private val DNS_SERVERS = stringPreferencesKey("dns_servers")
        private val FILTER_LISTS = stringPreferencesKey("filter_lists")
        private val FILTERING_ENABLED = booleanPreferencesKey("filtering_enabled")
        private val VPN_ENABLED = booleanPreferencesKey("vpn_enabled")
        private val STATS_TOTAL = longPreferencesKey("stats_total")
        private val STATS_BLOCKED = longPreferencesKey("stats_blocked")
        private val STATS_ALLOWED = longPreferencesKey("stats_allowed")
        private val STATS_AVG_RESPONSE = longPreferencesKey("stats_avg_response")
        private val STATS_RESPONSE_TOTAL = longPreferencesKey("stats_response_total")
        private val STATS_RESPONSE_SAMPLE_COUNT = longPreferencesKey("stats_response_sample_count")
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

    val isFilteringEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[FILTERING_ENABLED] ?: false
    }

    val isVpnEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[VPN_ENABLED] ?: false
    }

    val statistics: Flow<DnsStatistics> = dataStore.data.map { prefs ->
        DnsStatistics(
            totalQueries = prefs[STATS_TOTAL] ?: 0,
            blockedQueries = prefs[STATS_BLOCKED] ?: 0,
            allowedQueries = prefs[STATS_ALLOWED] ?: 0,
            averageResponseTime = prefs[STATS_AVG_RESPONSE] ?: 0
        )
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

    suspend fun setFilteringEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[FILTERING_ENABLED] = enabled
        }
    }

    suspend fun setVpnEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[VPN_ENABLED] = enabled
        }
    }

    suspend fun updateStatistics(
        stats: DnsStatistics,
        totalResponseTime: Long,
        responseSampleCount: Long
    ) {
        dataStore.edit { prefs ->
            prefs[STATS_TOTAL] = stats.totalQueries
            prefs[STATS_BLOCKED] = stats.blockedQueries
            prefs[STATS_ALLOWED] = stats.allowedQueries
            prefs[STATS_AVG_RESPONSE] = stats.averageResponseTime
            prefs[STATS_RESPONSE_TOTAL] = totalResponseTime
            prefs[STATS_RESPONSE_SAMPLE_COUNT] = responseSampleCount
        }
    }

    suspend fun getStatisticsState(): StatisticsState {
        val prefs = dataStore.data.first()
        return StatisticsState(
            statistics = DnsStatistics(
                totalQueries = prefs[STATS_TOTAL] ?: 0,
                blockedQueries = prefs[STATS_BLOCKED] ?: 0,
                allowedQueries = prefs[STATS_ALLOWED] ?: 0,
                averageResponseTime = prefs[STATS_AVG_RESPONSE] ?: 0
            ),
            totalResponseTime = prefs[STATS_RESPONSE_TOTAL] ?: 0,
            responseSampleCount = prefs[STATS_RESPONSE_SAMPLE_COUNT] ?: 0
        )
    }

    suspend fun incrementStats(blocked: Boolean, responseTime: Long) {
        dataStore.edit { prefs ->
            val total = (prefs[STATS_TOTAL] ?: 0) + 1
            val blockedCount = (prefs[STATS_BLOCKED] ?: 0) + if (blocked) 1 else 0
            val allowedCount = (prefs[STATS_ALLOWED] ?: 0) + if (!blocked) 1 else 0
            val prevResponseTotal = prefs[STATS_RESPONSE_TOTAL] ?: 0L
            val prevResponseSampleCount = prefs[STATS_RESPONSE_SAMPLE_COUNT] ?: 0L
            val includeInAvg = !blocked
            val newResponseTotal = if (includeInAvg) prevResponseTotal + responseTime else prevResponseTotal
            val newResponseSampleCount = if (includeInAvg) prevResponseSampleCount + 1 else prevResponseSampleCount
            val avgResponse = if (newResponseSampleCount > 0) {
                newResponseTotal / newResponseSampleCount
            } else {
                0L
            }

            prefs[STATS_TOTAL] = total
            prefs[STATS_BLOCKED] = blockedCount
            prefs[STATS_ALLOWED] = allowedCount
            prefs[STATS_AVG_RESPONSE] = avgResponse
            prefs[STATS_RESPONSE_TOTAL] = newResponseTotal
            prefs[STATS_RESPONSE_SAMPLE_COUNT] = newResponseSampleCount
        }
    }

    suspend fun resetStatistics() {
        dataStore.edit { prefs ->
            prefs[STATS_TOTAL] = 0L
            prefs[STATS_BLOCKED] = 0L
            prefs[STATS_ALLOWED] = 0L
            prefs[STATS_AVG_RESPONSE] = 0L
            prefs[STATS_RESPONSE_TOTAL] = 0L
            prefs[STATS_RESPONSE_SAMPLE_COUNT] = 0L
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
                    type = DnsServerType.valueOf(obj.getString("type")),
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
                put("type", server.type.name)
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
            type = DnsServerType.PLAIN,
            isEnabled = true
        ),
        // Tencent DNS (Secondary)
        DnsServer(
            id = "2",
            name = "Tencent DNS 2",
            address = "119.28.28.28",
            type = DnsServerType.PLAIN,
            isEnabled = true
        ),
        // AliDNS (Primary)
        DnsServer(
            id = "3",
            name = "AliDNS",
            address = "223.5.5.5",
            type = DnsServerType.PLAIN,
            isEnabled = true
        ),
        // AliDNS (Secondary)
        DnsServer(
            id = "4",
            name = "AliDNS 2",
            address = "223.6.6.6",
            type = DnsServerType.PLAIN,
            isEnabled = true
        ),
        // DNSPod DoH
        DnsServer(
            id = "5",
            name = "DNSPod DoH",
            address = "https://120.53.53.53/dns-query",
            type = DnsServerType.DOH,
            isEnabled = false
        )
    )

    private fun getDefaultFilterLists(): List<FilterList> = listOf(
        FilterList(
            id = "1",
            name = "anti-ad",
            url = "https://anti-ad.net/domains.txt",
            isEnabled = true,
            isBuiltIn = true
        )
    )
}
