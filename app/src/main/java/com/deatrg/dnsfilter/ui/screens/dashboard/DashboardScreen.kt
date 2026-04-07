package com.deatrg.dnsfilter.ui.screens.dashboard

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deatrg.dnsfilter.domain.model.DnsStatistics

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = viewModel(factory = DashboardViewModel.Factory(LocalContext.current.applicationContext))
) {
    val isVpnActuallyRunning by viewModel.isVpnActuallyRunning.collectAsStateWithLifecycle(initialValue = false)
    val isVpnProcessing by viewModel.isVpnProcessing.collectAsStateWithLifecycle(initialValue = false)
    val statistics by viewModel.statistics.collectAsStateWithLifecycle(initialValue = null)
    val filterCount by viewModel.filterListCount.collectAsStateWithLifecycle(initialValue = 0)
    val isFilterLoaded by viewModel.isFilterLoaded.collectAsStateWithLifecycle(initialValue = false)
    val isFilterLoading by viewModel.isFilterLoading.collectAsStateWithLifecycle(initialValue = false)
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle(initialValue = null)

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.toggleVpn(true)
        }
    }

    fun onVpnToggle(enabled: Boolean) {
        if (enabled) {
            val permissionIntent = viewModel.requestVpnPermission()
            if (permissionIntent != null) {
                vpnPermissionLauncher.launch(permissionIntent)
            } else {
                viewModel.toggleVpn(true)
            }
        } else {
            viewModel.toggleVpn(false)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Text(
                text = "Dashboard",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        item {
            StatusCard(
                isRunning = isVpnActuallyRunning,
                isProcessing = isVpnProcessing,
                isFilterLoading = isFilterLoading,
                downloadProgress = downloadProgress,
                filterCount = filterCount,
                onToggle = { onVpnToggle(it) }
            )
        }

        item {
            StatisticsSection(statistics = statistics)
        }
    }
}

@Composable
private fun StatusCard(
    isRunning: Boolean,
    isProcessing: Boolean,
    isFilterLoading: Boolean,
    downloadProgress: Pair<Int, Int>?,
    filterCount: Int,
    onToggle: (Boolean) -> Unit
) {
    val cardShape = RoundedCornerShape(28.dp)
    val gradientBrush = if (isRunning) {
        Brush.linearGradient(
            colors = listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.primaryContainer
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }

    val contentColor = if (isRunning) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradientBrush)
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Animated Icon
                val scale by animateFloatAsState(
                    targetValue = if (isRunning) 1.1f else 1f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                    label = "icon_scale"
                )

                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .scale(scale)
                        .clip(CircleShape)
                        .background(
                            if (isRunning) contentColor.copy(alpha = 0.2f)
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = if (isRunning) contentColor else contentColor.copy(alpha = 0.5f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Status Text
                val progressText = downloadProgress?.let { (current, total) ->
                    if (total > 0) " ($current/$total)" else ""
                } ?: ""
                
                val hasNoFilters = filterCount == 0 && !isFilterLoading
                
                val statusText = when {
                    isProcessing && isFilterLoading && filterCount == 0 -> "Downloading blocklist$progressText..."
                    isProcessing && isRunning -> "Stopping..."
                    isProcessing && !isRunning -> "Starting..."
                    isRunning -> "Protection Active"
                    else -> "Protection Inactive"
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )

                Text(
                    text = when {
                        isRunning -> if (filterCount > 0) "DNS filtering is enabled" else "DNS filtering enabled (no blocklist)"
                        isFilterLoading && filterCount == 0 -> "Downloading filter rules$progressText..."
                        hasNoFilters -> "Tap to enable protection (no blocklist configured)"
                        else -> "Tap to enable protection"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.8f)
                )
                
                // 显示下载进度条
                if (isFilterLoading && downloadProgress != null && filterCount == 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    val (current, total) = downloadProgress
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = { current.toFloat() / total.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Toggle Button
                Button(
                    onClick = { onToggle(!isRunning) },
                    enabled = !isProcessing || isFilterLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        contentColor = if (isRunning) {
                            MaterialTheme.colorScheme.onError
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isRunning) "STOP" else "START",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun StatisticsSection(statistics: DnsStatistics?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Statistics",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )

        // 2x2 Grid of stat cards
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ModernStatCard(
                    modifier = Modifier.weight(1f),
                    title = "Total",
                    value = statistics?.totalQueries?.toString() ?: "0",
                    icon = Icons.Outlined.QueryStats,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
                ModernStatCard(
                    modifier = Modifier.weight(1f),
                    title = "Blocked",
                    value = statistics?.blockedQueries?.toString() ?: "0",
                    icon = Icons.Outlined.Block,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ModernStatCard(
                    modifier = Modifier.weight(1f),
                    title = "Allowed",
                    value = statistics?.allowedQueries?.toString() ?: "0",
                    icon = Icons.Outlined.CheckCircle,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
                ModernStatCard(
                    modifier = Modifier.weight(1f),
                    title = "Avg Response",
                    value = "${statistics?.averageResponseTime ?: 0}ms",
                    icon = Icons.Outlined.Speed,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun ModernStatCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color
) {
    val cardShape = RoundedCornerShape(20.dp)

    Card(
        modifier = modifier,
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            // Icon with background
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(contentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = contentColor
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Value with animation
            AnimatedContent(
                targetState = value,
                transitionSpec = {
                    slideInVertically { it } + fadeIn() togetherWith
                            slideOutVertically { -it } + fadeOut()
                },
                label = "value_animation"
            ) { targetValue ->
                Text(
                    text = targetValue,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    textAlign = TextAlign.Start
                )
            }

            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.7f)
            )
        }
    }
}

