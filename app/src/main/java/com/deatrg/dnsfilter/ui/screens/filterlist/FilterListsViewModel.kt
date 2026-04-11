package com.deatrg.dnsfilter.ui.screens.filterlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.deatrg.dnsfilter.ServiceLocator
import com.deatrg.dnsfilter.domain.model.FilterList
import com.deatrg.dnsfilter.domain.repository.FilterListRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class FilterListUiModel(
    val filterList: FilterList,
    val lastUpdated: Long?
)

class FilterListsViewModel(
    private val repository: FilterListRepository
) : ViewModel() {

    val filterLists: StateFlow<List<FilterList>> = repository.filterLists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filterListsUi: StateFlow<List<FilterListUiModel>> = repository.filterLists
        .map { lists -> lists.map { FilterListUiModel(it, repository.getFilterLastUpdated(it)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filterCount: StateFlow<Int> = repository.filterListCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val isLoaded: StateFlow<Boolean> = repository.isLoaded
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun addFilterList(name: String, url: String) {
        viewModelScope.launch {
            val filterList = FilterList(
                id = UUID.randomUUID().toString(),
                name = name,
                url = url,
                isEnabled = true,
                isBuiltIn = false
            )
            repository.addFilterList(filterList)
        }
    }

    fun toggleFilterList(filterList: FilterList) {
        viewModelScope.launch {
            repository.updateFilterList(filterList.copy(isEnabled = !filterList.isEnabled))
        }
    }

    fun deleteFilterList(listId: String) {
        viewModelScope.launch {
            repository.deleteFilterList(listId)
        }
    }

    fun refreshLists() {
        viewModelScope.launch {
            repository.loadFilterLists()
        }
    }

    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FilterListsViewModel(ServiceLocator.provideFilterListRepository()) as T
        }
    }
}
