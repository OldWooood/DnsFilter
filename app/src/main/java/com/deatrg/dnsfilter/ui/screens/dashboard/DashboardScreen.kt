package com.deatrg.dnsfilter.ui.screens.dashboard

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deatrg.dnsfilter.R
import com.deatrg.dnsfilter.domain.model.DnsStatistics
import com.deatrg.dnsfilter.ui.components.PageHeader
import com.deatrg.dnsfilter.ui.components.SectionLabel
import com.deatrg.dnsfilter.ui.components.StatusDot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = viewModel(factory = DashboardViewModel.Factory)
) {
    val isRunning by viewModel.isVpnRunning.collectAsStateWithLifecycle(initialValue = false)
    val isProcessing by viewModel.isVpnProcessing.collectAsStateWithLifecycle(initialValue = false)
    val statistics by viewModel.statistics.collectAsStateWithLifecycle(initialValue = null)
    val filterCount by viewModel.filterListCount.collectAsStateWithLifecycle(initialValue = 0)
    val isFilterLoading by viewModel.isFilterLoading.collectAsStateWithLifecycle(initialValue = false)
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle(initialValue = null)
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.toggleVpn(true)
    }

    LaunchedEffect(Unit) {
        viewModel.vpnErrors.collect { error ->
            val messageRes = when (error) {
                VpnError.NoDnsServers -> R.string.dashboard_snackbar_no_dns_servers
                VpnError.NoBlocklistData -> R.string.dashboard_snackbar_no_blocklist
                VpnError.StartFailed -> R.string.dashboard_snackbar_vpn_start_failed
            }
            snackbarHostState.showSnackbar(context.getString(messageRes))
        }
    }

    val toggleVpn: (Boolean) -> Unit = { enabled ->
        if (enabled) {
            val permissionIntent = viewModel.requestVpnPermission()
            if (permissionIntent == null) viewModel.toggleVpn(true) else vpnPermissionLauncher.launch(permissionIntent)
        } else {
            viewModel.toggleVpn(false)
        }
    }

    val dateLabel = remember {
        SimpleDateFormat("EEEE · MMM d", Locale.getDefault()).format(Date())
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            PageHeader(
                eyebrow = stringResource(R.string.dashboard_eyebrow),
                title = stringResource(R.string.dashboard_title),
                supportingText = dateLabel
            )
            ProtectionPanel(
                isRunning = isRunning,
                isProcessing = isProcessing,
                isFilterLoading = isFilterLoading,
                downloadProgress = downloadProgress,
                filterCount = filterCount,
                onToggle = { toggleVpn(!isRunning) }
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.statistics_title))
                ActivityPanel(statistics)
            }
        }
    }
}

@Composable
private fun ProtectionPanel(
    isRunning: Boolean,
    isProcessing: Boolean,
    isFilterLoading: Boolean,
    downloadProgress: Pair<Int, Int>?,
    filterCount: Int,
    onToggle: () -> Unit
) {
    val containerColor = if (isRunning) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (isRunning) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val progressSuffix = downloadProgress?.let { (current, total) ->
        if (total > 0) "  $current/$total" else ""
    }.orEmpty()
    val title = when {
        isProcessing && isRunning -> stringResource(R.string.status_stopping)
        isProcessing -> stringResource(R.string.status_starting)
        isRunning -> stringResource(R.string.status_protection_active)
        else -> stringResource(R.string.status_protection_inactive)
    }
    val detail = when {
        isFilterLoading && filterCount == 0 -> stringResource(R.string.status_downloading_filter_rules, progressSuffix)
        isRunning && filterCount > 0 -> stringResource(R.string.status_dns_filtering_enabled)
        isRunning -> stringResource(R.string.status_dns_filtering_no_blocklist)
        filterCount == 0 -> stringResource(R.string.status_tap_enable_no_blocklist)
        else -> stringResource(R.string.status_tap_enable)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = containerColor,
        tonalElevation = if (isRunning) 0.dp else 1.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(active = isRunning)
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = if (isRunning) stringResource(R.string.dns_server_enabled) else stringResource(R.string.dns_server_disabled),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.72f)
                    )
                }
                Text(
                    text = stringResource(R.string.dashboard_rules_loaded, formatLargeNumber(filterCount.toLong())),
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    color = contentColor.copy(alpha = 0.72f)
                )
            }

            Spacer(Modifier.height(18.dp))
            AnimatedContent(
                targetState = title,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "protection_status"
            ) { status ->
                Text(text = status, style = MaterialTheme.typography.headlineMedium, color = contentColor)
            }
            Spacer(Modifier.height(7.dp))
            Text(text = detail, style = MaterialTheme.typography.bodyMedium, color = contentColor.copy(alpha = 0.72f))

            if (isFilterLoading && filterCount == 0) {
                Spacer(Modifier.height(12.dp))
                val progress = downloadProgress?.let { (current, total) ->
                    if (total > 0) current.toFloat() / total else 0f
                }
                if (progress == null) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = contentColor,
                        trackColor = contentColor.copy(alpha = 0.15f)
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = contentColor,
                        trackColor = contentColor.copy(alpha = 0.15f)
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onToggle,
                enabled = !isProcessing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = if (isRunning) {
                    ButtonDefaults.buttonColors(
                        containerColor = contentColor,
                        contentColor = containerColor
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
                elevation = ButtonDefaults.buttonElevation(0.dp)
            ) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(9.dp))
                Text(if (isRunning) stringResource(R.string.action_stop) else stringResource(R.string.action_start))
            }
        }
    }
}

@Composable
private fun ActivityPanel(statistics: DnsStatistics?) {
    val total = statistics?.totalQueries ?: 0
    val blocked = statistics?.blockedQueries ?: 0
    val average = statistics?.averageResponseTime ?: 0
    val blockRate = if (total == 0L) 0f else blocked.toFloat() / total

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(
                text = stringResource(R.string.stat_total),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(3.dp))
            AnimatedContent(
                targetState = formatLargeNumber(total),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "total_queries"
            ) { value ->
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineLarge,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { blockRate.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            MetricRow(stringResource(R.string.stat_blocked), formatLargeNumber(blocked))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            MetricRow(
                stringResource(R.string.stat_block_rate),
                String.format(Locale.getDefault(), "%.1f%%", blockRate * 100f)
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            MetricRow(stringResource(R.string.stat_avg_response), "${average}ms")
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun formatLargeNumber(number: Long): String = when {
    number >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", number / 1_000_000.0)
    number >= 1_000 -> String.format(Locale.getDefault(), "%.1fK", number / 1_000.0)
    else -> number.toString()
}
