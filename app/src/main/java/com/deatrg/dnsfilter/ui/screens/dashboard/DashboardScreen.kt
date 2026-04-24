package com.deatrg.dnsfilter.ui.screens.dashboard

import android.app.Application
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deatrg.dnsfilter.R
import com.deatrg.dnsfilter.domain.model.DnsStatistics
import androidx.compose.ui.res.stringResource
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = viewModel(
        factory = DashboardViewModel.Factory(LocalContext.current.applicationContext as Application)
    )
) {
    val isVpnActuallyRunning by viewModel.isVpnActuallyRunning.collectAsStateWithLifecycle(initialValue = false)
    val isVpnProcessing by viewModel.isVpnProcessing.collectAsStateWithLifecycle(initialValue = false)
    val statistics by viewModel.statistics.collectAsStateWithLifecycle(initialValue = null)
    val filterCount by viewModel.filterListCount.collectAsStateWithLifecycle(initialValue = 0)
    val isFilterLoaded by viewModel.isFilterLoaded.collectAsStateWithLifecycle(initialValue = false)
    val isFilterLoading by viewModel.isFilterLoading.collectAsStateWithLifecycle(initialValue = false)
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle(initialValue = null)
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.toggleVpn(true)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.showNoDnsServersError.collect {
            snackbarHostState.showSnackbar(context.getString(R.string.dashboard_snackbar_no_dns_servers))
        }
    }

    // 监听生命周期，后台时暂停轮询
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.resume()
                Lifecycle.Event.ON_PAUSE -> viewModel.pause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val onVpnToggle: (Boolean) -> Unit = { enabled ->
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                HeaderSection()
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
}

@Composable
private fun HeaderSection() {
    val dateFormat = remember { SimpleDateFormat("EEEE, MMM d", Locale.getDefault()) }
    val today = remember { dateFormat.format(Date()) }

    Column {
        Text(
            text = today,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.dashboard_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground
        )
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
    val animSpec = tween<Color>(600, easing = FastOutSlowInEasing)

    // Animate background colors - running state uses deep indigo/violet gradient for white text readability
    val bgColorStart by animateColorAsState(
        targetValue = if (isRunning) Color(0xFF4F46E5) else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = animSpec,
        label = "bg_start"
    )
    val bgColorMid by animateColorAsState(
        targetValue = if (isRunning) Color(0xFF4338CA) else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = animSpec,
        label = "bg_mid"
    )
    val bgColorEnd by animateColorAsState(
        targetValue = if (isRunning) Color(0xFF3730A3) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
        animationSpec = animSpec,
        label = "bg_end"
    )

    // Animate content color
    val contentColor by animateColorAsState(
        targetValue = if (isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = animSpec,
        label = "content_color"
    )

    // Animate icon background color
    val iconBgColor by animateColorAsState(
        targetValue = if (isRunning) contentColor.copy(alpha = 0.2f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f),
        animationSpec = animSpec,
        label = "icon_bg"
    )

    // Animate button container color
    val btnContainerColor by animateColorAsState(
        targetValue = if (isRunning) MaterialTheme.colorScheme.error.copy(alpha = 0.95f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
        animationSpec = animSpec,
        label = "btn_bg"
    )

    val gradientBrush = Brush.linearGradient(
        colors = listOf(bgColorStart, bgColorMid, bgColorEnd)
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradientBrush)
                .padding(20.dp)
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
                    modifier = Modifier.size(72.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // 静态 glow ring（移除无限动画，避免后台持续消耗 CPU）
                    if (isRunning) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .scale(scale)
                                .clip(CircleShape)
                                .background(contentColor.copy(alpha = 0.2f))
                        )
                    }
                    // Icon background
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .scale(scale)
                            .clip(CircleShape)
                            .background(iconBgColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = contentColor.copy(alpha = if (isRunning) 1f else 0.6f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Status Text
                val progressText = downloadProgress?.let { (current, total) ->
                    if (total > 0) " ($current/$total)" else ""
                } ?: ""

                val hasNoFilters = filterCount == 0 && !isFilterLoading

                val statusText = when {
                    isProcessing && isFilterLoading && filterCount == 0 -> stringResource(R.string.status_downloading_blocklist, progressText)
                    isProcessing && isRunning -> stringResource(R.string.status_stopping)
                    isProcessing && !isRunning -> stringResource(R.string.status_starting)
                    isRunning -> stringResource(R.string.status_protection_active)
                    else -> stringResource(R.string.status_protection_inactive)
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )

                Text(
                    text = when {
                        isRunning -> if (filterCount > 0) stringResource(R.string.status_dns_filtering_enabled) else stringResource(R.string.status_dns_filtering_no_blocklist)
                        isFilterLoading && filterCount == 0 -> stringResource(R.string.status_downloading_filter_rules, progressText)
                        hasNoFilters -> stringResource(R.string.status_tap_enable_no_blocklist)
                        else -> stringResource(R.string.status_tap_enable)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.85f)
                )

                // 显示下载进度条
                if (isFilterLoading && downloadProgress != null && filterCount == 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    val (current, total) = downloadProgress
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = { current.toFloat() / total.toFloat() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp)),
                            color = contentColor,
                            trackColor = contentColor.copy(alpha = 0.25f)
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp)),
                            color = contentColor,
                            trackColor = contentColor.copy(alpha = 0.25f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Toggle Button
                Button(
                    onClick = { onToggle(!isRunning) },
                    enabled = !isProcessing || isFilterLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = btnContainerColor,
                        contentColor = if (isRunning) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isRunning) stringResource(R.string.action_stop) else stringResource(R.string.action_start),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
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
            text = stringResource(R.string.statistics_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
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
                    title = stringResource(R.string.stat_total),
                    value = formatLargeNumber(statistics?.totalQueries ?: 0),
                    icon = Icons.Outlined.QueryStats,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ModernStatCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.stat_blocked),
                    value = formatLargeNumber(statistics?.blockedQueries ?: 0),
                    icon = Icons.Outlined.Block,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.error
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 拦截率卡片
                val total = statistics?.totalQueries ?: 0
                val blocked = statistics?.blockedQueries ?: 0
                val blockRate = if (total > 0) {
                    (blocked.toFloat() / total.toFloat() * 100f)
                } else 0f
                val blockRateText = String.format(Locale.getDefault(), "%.1f%%", blockRate)

                BlockRateStatCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.stat_block_rate),
                    value = blockRateText,
                    progress = blockRate / 100f,
                    icon = Icons.Outlined.Security,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.tertiary
                )
                ModernStatCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.stat_avg_response),
                    value = "${statistics?.averageResponseTime ?: 0}ms",
                    icon = Icons.Outlined.Speed,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.primary
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
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.Start
        ) {
            // Icon with subtle background
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(contentColor.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = contentColor
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

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
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = contentColor,
                    textAlign = TextAlign.Start
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = contentColor.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun BlockRateStatCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    progress: Float,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color
) {
    val cardShape = RoundedCornerShape(20.dp)
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "progress_animation"
    )

    Card(
        modifier = modifier,
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.Start
            ) {
                // Icon with subtle background
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(contentColor.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = contentColor
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

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
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = contentColor,
                        textAlign = TextAlign.Start
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = contentColor.copy(alpha = 0.7f)
                )
            }

            // Circular progress indicator - top right
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .align(Alignment.TopEnd),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxSize(),
                    color = contentColor,
                    trackColor = contentColor.copy(alpha = 0.12f),
                    strokeWidth = 3.dp
                )
            }
        }
    }
}

private fun formatLargeNumber(number: Long): String {
    return when {
        number >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", number / 1_000_000.0)
        number >= 1_000 -> String.format(Locale.getDefault(), "%.1fK", number / 1_000.0)
        else -> number.toString()
    }
}


