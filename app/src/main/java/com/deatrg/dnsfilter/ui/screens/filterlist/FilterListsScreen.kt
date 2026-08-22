package com.deatrg.dnsfilter.ui.screens.filterlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deatrg.dnsfilter.R
import com.deatrg.dnsfilter.domain.model.FilterList
import com.deatrg.dnsfilter.ui.components.AppPanel
import com.deatrg.dnsfilter.ui.components.EmptyState
import com.deatrg.dnsfilter.ui.components.PageHeader
import com.deatrg.dnsfilter.ui.components.StatusDot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FilterListsScreen(
    viewModel: FilterListsViewModel = viewModel(factory = FilterListsViewModel.Factory)
) {
    val filterLists by viewModel.filterListsUi.collectAsStateWithLifecycle(initialValue = emptyList())
    val filterCount by viewModel.filterCount.collectAsStateWithLifecycle(initialValue = 0)
    val isLoaded by viewModel.isLoaded.collectAsStateWithLifecycle(initialValue = false)
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle(initialValue = false)
    var showAddDialog by remember { mutableStateOf(false) }
    var filterListToDelete by remember { mutableStateOf<FilterList?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                PageHeader(
                    eyebrow = stringResource(R.string.filter_lists_eyebrow),
                    title = stringResource(R.string.filter_lists_title),
                    actions = {
                        FilledTonalIconButton(
                            onClick = { if (!isLoading) viewModel.refreshLists() },
                            enabled = !isLoading
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.filter_list_manual_update))
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(onClick = { showAddDialog = true }) {
                            Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.filter_list_add))
                        }
                    }
                )
            }

            item {
                AppPanel {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(isLoaded)
                            Spacer(Modifier.width(10.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    if (isLoaded) {
                                        pluralStringResource(R.plurals.filter_lists_domains_blocked, filterCount, filterCount)
                                    } else {
                                        stringResource(R.string.filter_lists_loading)
                                    },
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    pluralStringResource(
                                        R.plurals.filter_lists_source_summary,
                                        filterLists.size,
                                        filterLists.size
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        AnimatedVisibility(isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }

            if (filterLists.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.FilterAlt,
                        title = stringResource(R.string.filter_lists_empty_title),
                        supportingText = stringResource(R.string.filter_lists_empty_hint)
                    )
                }
            } else {
                items(filterLists, key = { it.filterList.id }) { item ->
                    FilterListRow(
                        item = item,
                        onToggle = { viewModel.toggleFilterList(item.filterList) },
                        onDelete = { filterListToDelete = item.filterList }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddFilterListDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, url ->
                viewModel.addFilterList(name.trim(), url.trim())
                showAddDialog = false
            }
        )
    }

    filterListToDelete?.let { filterList ->
        AlertDialog(
            onDismissRequest = { filterListToDelete = null },
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.filter_list_delete_title), style = MaterialTheme.typography.headlineMedium) },
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
private fun FilterListRow(
    item: FilterListUiModel,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val filterList = item.filterList
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd · HH:mm", Locale.getDefault()) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 15.dp, end = 10.dp, bottom = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (filterList.isEnabled) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.FilterAlt,
                    contentDescription = null,
                    tint = if (filterList.isEnabled) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        filterList.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (filterList.isBuiltIn) {
                        Spacer(Modifier.width(7.dp))
                        Text(
                            stringResource(R.string.filter_list_built_in).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    filterList.url,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                item.lastUpdated?.let {
                    Text(
                        stringResource(R.string.filter_list_updated, dateFormat.format(Date(it))),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!filterList.isBuiltIn) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.filter_list_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(checked = filterList.isEnabled, onCheckedChange = { onToggle() })
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
    val focusManager = LocalFocusManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.filter_list_add), style = MaterialTheme.typography.headlineMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.filter_list_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.filter_list_url)) },
                    placeholder = { Text(stringResource(R.string.filter_list_url_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                Text(
                    stringResource(R.string.filter_list_format_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    focusManager.clearFocus()
                    onAdd(name, url)
                },
                enabled = name.isNotBlank() && url.isNotBlank()
            ) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
