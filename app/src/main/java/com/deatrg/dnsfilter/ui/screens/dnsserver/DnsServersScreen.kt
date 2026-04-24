package com.deatrg.dnsfilter.ui.screens.dnsserver

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deatrg.dnsfilter.R
import com.deatrg.dnsfilter.domain.model.DnsServer
import com.deatrg.dnsfilter.domain.model.DnsServerType
import androidx.compose.ui.res.stringResource

@Composable
fun DnsServersScreen(
    viewModel: DnsServersViewModel = viewModel(factory = DnsServersViewModel.Factory())
) {
    val servers by viewModel.dnsServers.collectAsStateWithLifecycle(initialValue = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                shape = RoundedCornerShape(18.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.dns_server_add))
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
            HeaderSection()

            // Server list - scrollable
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(servers, key = { it.id }) { server ->
                    DnsServerCard(
                        server = server,
                        onToggle = { viewModel.toggleServer(server) },
                        onDelete = { viewModel.deleteServer(server.id) }
                    )
                }

                if (servers.isEmpty()) {
                    item {
                        EmptyStateCard()
                    }
                }
            }

            // Reset button - fixed at bottom
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { viewModel.resetToDefaults() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.dns_servers_reset_defaults))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    if (showAddDialog) {
        AddDnsServerDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, address, type ->
                viewModel.addServer(name, address, type)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun HeaderSection() {
    Text(
        text = stringResource(R.string.dns_servers_title),
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.onBackground
    )
    Text(
        text = stringResource(R.string.dns_servers_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 16.dp)
    )
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
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Dns,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.dns_servers_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.dns_servers_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DnsServerCard(
    server: DnsServer,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
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
                        text = server.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val typeColor = when (server.type) {
                        DnsServerType.PLAIN -> MaterialTheme.colorScheme.primary
                        DnsServerType.DOH -> MaterialTheme.colorScheme.tertiary
                        DnsServerType.DOT -> MaterialTheme.colorScheme.secondary
                    }
                    val typeContainerColor = when (server.type) {
                        DnsServerType.PLAIN -> MaterialTheme.colorScheme.primaryContainer
                        DnsServerType.DOH -> MaterialTheme.colorScheme.tertiaryContainer
                        DnsServerType.DOT -> MaterialTheme.colorScheme.secondaryContainer
                    }
                    SuggestionChip(
                        onClick = { },
                        label = {
                            Text(
                                server.type.name,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = typeContainerColor,
                            labelColor = typeColor
                        ),
                        border = null
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = server.address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (server.isEnabled) Icons.Filled.CheckCircle else Icons.Outlined.Cancel,
                    contentDescription = if (server.isEnabled) stringResource(R.string.dns_server_enabled) else stringResource(R.string.dns_server_disabled),
                    tint = if (server.isEnabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    modifier = Modifier.size(28.dp)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.dns_server_delete),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDnsServerDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, address: String, type: DnsServerType) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(DnsServerType.PLAIN) }
    var expanded by remember { mutableStateOf(false) }
    val selectableTypes = remember {
        DnsServerType.entries.filter { it != DnsServerType.DOT }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = {
            Text(
                stringResource(R.string.dns_server_add),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.dns_server_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(stringResource(R.string.dns_server_address)) },
                    placeholder = { Text(stringResource(R.string.dns_server_address_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                )
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = selectedType.name,
                        onValueChange = { },
                        readOnly = true,
                        label = { Text(stringResource(R.string.dns_server_type)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        shape = RoundedCornerShape(16.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        selectableTypes.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.name) },
                                onClick = {
                                    selectedType = type
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name, address, selectedType) },
                enabled = name.isNotBlank() && address.isNotBlank(),
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
