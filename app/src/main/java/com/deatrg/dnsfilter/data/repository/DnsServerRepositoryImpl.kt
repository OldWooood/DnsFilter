package com.deatrg.dnsfilter.data.repository

import com.deatrg.dnsfilter.data.local.PreferencesManager
import com.deatrg.dnsfilter.domain.model.DnsServer
import com.deatrg.dnsfilter.domain.repository.DnsServerRepository
import kotlinx.coroutines.flow.Flow

class DnsServerRepositoryImpl(
    private val preferencesManager: PreferencesManager
) : DnsServerRepository {

    override val dnsServers: Flow<List<DnsServer>> = preferencesManager.dnsServers

    override suspend fun addDnsServer(server: DnsServer) {
        preferencesManager.editDnsServers { current -> current + server }
    }

    override suspend fun updateDnsServer(server: DnsServer) {
        preferencesManager.editDnsServers { current ->
            current.map { existing ->
                if (existing.id == server.id) server.copy(isBuiltIn = existing.isBuiltIn) else existing
            }
        }
    }

    override suspend fun deleteDnsServer(serverId: String) {
        preferencesManager.editDnsServers { current ->
            current.filterNot { it.id == serverId && !it.isBuiltIn }
        }
    }

    override suspend fun resetToDefaults() {
        preferencesManager.resetDnsServersToDefaults()
    }
}
