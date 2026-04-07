package com.deatrg.dnsfilter.data.repository

import com.deatrg.dnsfilter.data.local.PreferencesManager
import com.deatrg.dnsfilter.domain.model.DnsServer
import com.deatrg.dnsfilter.domain.repository.DnsServerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class DnsServerRepositoryImpl(
    private val preferencesManager: PreferencesManager
) : DnsServerRepository {

    override val dnsServers: Flow<List<DnsServer>> = preferencesManager.dnsServers

    override suspend fun saveDnsServers(servers: List<DnsServer>) {
        preferencesManager.saveDnsServers(servers)
    }

    override suspend fun addDnsServer(server: DnsServer) {
        val current = preferencesManager.dnsServers.first().toMutableList()
        current.add(server)
        preferencesManager.saveDnsServers(current)
    }

    override suspend fun updateDnsServer(server: DnsServer) {
        val current = preferencesManager.dnsServers.first().toMutableList()
        val index = current.indexOfFirst { it.id == server.id }
        if (index != -1) {
            current[index] = server
            preferencesManager.saveDnsServers(current)
        }
    }

    override suspend fun deleteDnsServer(serverId: String) {
        val current = preferencesManager.dnsServers.first().toMutableList()
        current.removeAll { it.id == serverId }
        preferencesManager.saveDnsServers(current)
    }

    override suspend fun resetToDefaults() {
        preferencesManager.resetDnsServersToDefaults()
    }
}
