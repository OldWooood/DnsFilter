package com.deatrg.dnsfilter.ui.screens.dnsserver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.deatrg.dnsfilter.ServiceLocator
import com.deatrg.dnsfilter.domain.model.DnsServer
import com.deatrg.dnsfilter.domain.model.DnsServerType
import com.deatrg.dnsfilter.domain.repository.DnsServerRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

class DnsServersViewModel(
    private val repository: DnsServerRepository
) : ViewModel() {

    val dnsServers: StateFlow<List<DnsServer>> = repository.dnsServers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addServer(name: String, address: String, type: DnsServerType) {
        if (type == DnsServerType.DOT) return
        viewModelScope.launch {
            val server = DnsServer(
                id = UUID.randomUUID().toString(),
                name = name,
                address = address,
                type = type,
                isEnabled = true
            )
            repository.addDnsServer(server)
        }
    }

    fun updateServer(server: DnsServer) {
        viewModelScope.launch {
            repository.updateDnsServer(server)
        }
    }

    fun toggleServer(server: DnsServer) {
        viewModelScope.launch {
            repository.updateDnsServer(server.copy(isEnabled = !server.isEnabled))
        }
    }

    fun deleteServer(serverId: String) {
        viewModelScope.launch {
            repository.deleteDnsServer(serverId)
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            repository.resetToDefaults()
        }
    }

    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DnsServersViewModel(ServiceLocator.provideDnsServerRepository()) as T
        }
    }
}
