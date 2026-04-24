package com.deatrg.dnsfilter.ui.screens.filterlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deatrg.dnsfilter.R
import com.deatrg.dnsfilter.domain.model.FilterList
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
                onClick = { showAddDialog = true },
                shape = RoundedCornerShape(18.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.filter_list_add))
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
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
                        EmptyStateCard()
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
            shape = RoundedCornerShape(28.dp),
            title = {
                Text(
                    stringResource(R.string.filter_list_delete_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = { Text(stringResource(R.string.filter_list_delete_message, filterList.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFilterList(filterList.id)
                        filterListToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.filter_list_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { filterListToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
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
        text = stringResource(R.string.filter_lists_title),
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.onBackground
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isLoaded) {
                pluralStringResource(R.plurals.filter_lists_domains_blocked, filterCount, filterCount)
            } else {
                stringResource(R.string.filter_lists_loading)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!isLoaded) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
        }
    }
}

@Composable
private fun EmptyStateCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                                MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Block,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.filter_lists_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.filter_lists_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
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
                        fontWeight = FontWeight.SemiBold
                    )
                    if (filterList.isBuiltIn) {
                        Spacer(modifier = Modifier.width(8.dp))
                        SuggestionChip(
                            onClick = { },
                            label = {
                                Text(
                                    stringResource(R.string.filter_list_built_in),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            border = null
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = filterList.url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.lastUpdated != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.filter_list_updated, dateFormat.format(Date(item.lastUpdated))),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (filterList.isEnabled) Icons.Filled.Shield else Icons.Outlined.Shield,
                    contentDescription = if (filterList.isEnabled) stringResource(R.string.filter_list_enabled) else stringResource(R.string.filter_list_disabled),
                    tint = if (filterList.isEnabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    modifier = Modifier.size(28.dp)
                )
            }
            if (!filterList.isBuiltIn) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.filter_list_delete),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
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
        shape = RoundedCornerShape(28.dp),
        title = {
            Text(
                stringResource(R.string.filter_list_add),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.filter_list_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.filter_list_url)) },
                    placeholder = { Text(stringResource(R.string.filter_list_url_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                )
                Text(
                    text = stringResource(R.string.filter_list_format_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name, url) },
                enabled = name.isNotBlank() && url.isNotBlank(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
