package com.deatrg.dnsfilter.service

import com.deatrg.dnsfilter.domain.model.DnsQuery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * 单例 DNS 查询日志存储
 * 供 DnsVpnService 记录查询，供 UI 层订阅显示
 */
object DnsQueryLogger {
    private const val MAX_QUERIES = 500
    
    private val _queriesDeque = ArrayDeque<DnsQuery>(MAX_QUERIES)
    private val _queries = MutableStateFlow<List<DnsQuery>>(emptyList())
    val queries: StateFlow<List<DnsQuery>> = _queries.asStateFlow()
    
    /**
     * 添加一条查询记录
     */
    fun addQuery(query: DnsQuery) {
        synchronized(_queriesDeque) {
            if (_queriesDeque.size >= MAX_QUERIES) {
                _queriesDeque.removeLast()
            }
            _queriesDeque.addFirst(query)
            _queries.value = _queriesDeque.toList()
        }
    }
    
    /**
     * 清空所有日志
     */
    fun clearQueries() {
        synchronized(_queriesDeque) {
            _queriesDeque.clear()
            _queries.value = emptyList()
        }
    }
}
