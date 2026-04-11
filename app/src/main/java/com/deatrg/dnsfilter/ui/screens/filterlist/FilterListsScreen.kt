package com.deatrg.dnsfilter.ui.screens.filterlist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deatrg.dnsfilter.domain.model.FilterList
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun FilterListsScreen(
    viewModel: FilterListsViewModel = viewModel(factory = FilterListsViewModel.Factory())
) {
    val filterLists by viewModel.filterListsUi.collectAsStateWithLifecycle(initialValue = emptyList())
    val filterCount by viewModel.filterCount.collectAsStateWithLifecycle(initialValue = 0)
    val isLoaded by viewModel.isLoaded.collectAsStateWithLifecycle(initialValue = false)
    var showAddDialog by remember { mutableStateOf(false) }
    var filterListToDelete by remember { mutableStateOf<FilterList?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Filter List")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Header - fixed
            HeaderSection(
                filterCount = filterCount,
                isLoaded = isLoaded
            )

            // Filter list - scrollable
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filterLists, key = { it.filterList.id }) { item ->
                    FilterListCard(
                        item = item,
                        onToggle = { viewModel.toggleFilterList(item.filterList) },
                        onDelete = { filterListToDelete = item.filterList }
                    )
                }

                if (filterLists.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.Block,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("No filter lists configured")
                                Text(
                                    text = "Tap + to add a filter list",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            // Bottom spacing for FAB
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showAddDialog) {
        AddFilterListDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, url ->
                viewModel.addFilterList(name, url)
                showAddDialog = false
            }
        )
    }

    filterListToDelete?.let { filterList ->
        AlertDialog(
            onDismissRequest = { filterListToDelete = null },
            title = { Text("Delete Filter List") },
            text = { Text("Are you sure you want to delete \"${filterList.name}\"?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFilterList(filterList.id)
                        filterListToDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { filterListToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun HeaderSection(
    filterCount: Int,
    isLoaded: Boolean
) {
    Text(
        text = "Filter Lists",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isLoaded) "$filterCount domains blocked" else "Loading...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!isLoaded) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun FilterListCard(
    item: FilterListUiModel,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }
    val filterList = item.filterList

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = filterList.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (filterList.isBuiltIn) {
                        Spacer(modifier = Modifier.width(8.dp))
                        AssistChip(
                            onClick = { },
                            label = { Text("Built-in") },
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = filterList.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.lastUpdated != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Updated: ${dateFormat.format(Date(item.lastUpdated))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = if (filterList.isEnabled) "Enabled" else "Disabled",
                    tint = if (filterList.isEnabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
            }
            if (!filterList.isBuiltIn) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun AddFilterListDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, url: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Filter List") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL") },
                    placeholder = { Text("https://...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Supports AdAway-format blocklists (.txt or hosts files)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name, url) },
                enabled = name.isNotBlank() && url.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
