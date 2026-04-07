package com.deatrg.dnsfilter.ui.screens.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.deatrg.dnsfilter.ServiceLocator
import com.deatrg.dnsfilter.domain.model.DnsQuery
import com.deatrg.dnsfilter.domain.model.DnsStatistics
import com.deatrg.dnsfilter.domain.repository.DnsLogRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class LogsViewModel(
    private val repository: DnsLogRepository
) : ViewModel() {

    val queries: StateFlow<List<DnsQuery>> = repository.queries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val statistics: StateFlow<DnsStatistics> = repository.statistics
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DnsStatistics())

    fun clearLogs() {
        viewModelScope.launch {
            repository.clearLogs()
        }
    }

    fun resetStatistics() {
        viewModelScope.launch {
            repository.resetStatistics()
        }
    }

    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LogsViewModel(ServiceLocator.provideDnsLogRepository()) as T
        }
    }
}
