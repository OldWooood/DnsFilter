package com.deatrg.dnsfilter.data.repository

import com.deatrg.dnsfilter.data.local.PreferencesManager
import com.deatrg.dnsfilter.domain.model.DnsQuery
import com.deatrg.dnsfilter.domain.model.DnsStatistics
import com.deatrg.dnsfilter.domain.repository.DnsLogRepository
import com.deatrg.dnsfilter.service.DnsQueryLogger
import kotlinx.coroutines.flow.Flow

class DnsLogRepositoryImpl(
    private val preferencesManager: PreferencesManager
) : DnsLogRepository {

    override val queries: Flow<List<DnsQuery>> = DnsQueryLogger.queries
    override val statistics: Flow<DnsStatistics> = preferencesManager.statistics

    override suspend fun clearLogs() {
        DnsQueryLogger.clearQueries()
    }

    override suspend fun resetStatistics() {
        preferencesManager.resetStatistics()
    }
}
