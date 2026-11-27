package com.vpsconsole.app.ui.monitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vpsconsole.app.core.formatRate
import com.vpsconsole.app.core.formatTimestamp
import com.vpsconsole.app.core.formatUptime
import com.vpsconsole.app.core.percent
import com.vpsconsole.app.data.model.AlertRuleEntity
import com.vpsconsole.app.data.model.MetricKind
import com.vpsconsole.app.ui.LocalAppContainer
import com.vpsconsole.app.ui.components.SectionCard
import com.vpsconsole.app.ui.components.StatusBadge
import com.vpsconsole.app.ssh.ConnectionState
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MonitorScreen(serverId: Long) {
    val container = LocalAppContainer.current
    val settings by container.settings.settings.collectAsState()
    val metrics by container.metricRepo.history(
        serverId,
        System.currentTimeMillis() - settings.metricHistoryHours * 3_600_000L
    ).collectAsState(emptyList())
    val rules by container.alertRepo.rules.collectAsState(emptyList())
    val state by container.ssh.states.collectAsState(emptyMap())
    val server by container.serverRepo.observe(serverId).collectAsState(null)
    val scope = rememberCoroutineScope()
    var newKind by remember { mutableStateOf(MetricKind.CPU) }
    var newThreshold by remember { mutableStateOf("85") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(serverId, settings.refreshSeconds) {
        while (true) {
            container.ssh.collect(serverId)
            delay(settings.refreshSeconds * 1000L)
        }
    }

    val latest = metrics.lastOrNull()
    val serverRules = rules.filter { it.serverId == serverId }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(server?.name ?: "Server", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "${server?.host ?: ""} · ${state[serverId]?.name ?: "DISCONNECTED"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusBadge(state[serverId] ?: ConnectionState.DISCONNECTED)
            }
        }
        item {
            SectionCard(title = "Live metrics") {
                val accent = com.vpsconsole.app.ui.theme.AccentTheme.from(settings.accentColor)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    if (settings.showCpuGauge) CircleGauge(
                        label = "CPU", valueText = percent(latest?.cpu ?: 0f),
                        fraction = (latest?.cpu ?: 0f) / 100f,
                        color = Color(accent.dark),
                        modifier = Modifier.weight(1f)
                    )
                    if (settings.showRamGauge) CircleGauge(
                        label = "RAM", valueText = percent(latest?.ram ?: 0f),
                        fraction = (latest?.ram ?: 0f) / 100f,
                        color = Color(accent.secondaryDark),
                        modifier = Modifier.weight(1f)
                    )
                    if (settings.showDiskGauge) CircleGauge(
                        label = "Disk", valueText = percent(latest?.disk ?: 0f),
                        fraction = (latest?.disk ?: 0f) / 100f,
                        color = Color(0xFFD29922),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    CircleGauge(
                        label = "Load", valueText = String.format(Locale.US, "%.2f", latest?.load ?: 0f),
                        fraction = ((latest?.load ?: 0f) / 4f).coerceIn(0f, 1f),
                        color = Color(0xFFA371F7),
                        caption = "uptime ${formatUptime(latest?.uptimeSec ?: 0)}",
                        modifier = Modifier.weight(1f)
                    )
                    if (settings.showNetworkGauge) CircleGauge(
                        label = "↓ Rx", valueText = formatRate(latest?.rxMbps ?: 0f),
                        fraction = networkFraction(latest?.rxMbps ?: 0f, latest?.txMbps ?: 0f),
                        color = Color(accent.dark),
                        modifier = Modifier.weight(1f)
                    )
                    if (settings.showNetworkGauge) CircleGauge(
                        label = "↑ Tx", valueText = formatRate(latest?.txMbps ?: 0f),
                        fraction = networkFraction(latest?.txMbps ?: 0f, latest?.rxMbps ?: 0f),
                        color = Color(accent.secondaryDark),
                        modifier = Modifier.weight(1f)
                    )
                }
                if (latest != null) {
                    Text(
                        "Updated ${formatTimestamp(latest.timestamp)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )
                }
            }
        }
        item {
            SectionCard(title = "Alert rules") {
                Text(
                    "Alerts are evaluated by the background worker every 15 minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                serverRules.forEach { rule ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("${rule.kind} ≥ ${percent(rule.threshold)}", Modifier.weight(1f))
                        Text(
                            if (rule.enabled) "on" else "off",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(
                            onClick = {
                                scope.launch { container.alertRepo.saveRule(rule.copy(enabled = !rule.enabled)) }
                            },
                            modifier = Modifier.semantics { contentDescription = if (rule.enabled) "Disable rule" else "Enable rule" }
                        ) { Text(if (rule.enabled) "⏸" else "▶") }
                        IconButton(
                            onClick = { scope.launch { container.alertRepo.deleteRule(rule) } },
                            modifier = Modifier.semantics { contentDescription = "Delete rule" }
                        ) { Text("✕") }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MetricKind.entries.forEach { kind ->
                        FilterChip(selected = newKind == kind, onClick = { newKind = kind }, label = { Text(kind.name) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newThreshold,
                        onValueChange = { newThreshold = it },
                        label = { Text("Threshold %") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            val value = newThreshold.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(1f, 100f) ?: return@Button
                            scope.launch {
                                container.alertRepo.saveRule(
                                    AlertRuleEntity(serverId = serverId, kind = newKind, threshold = value)
                                )
                            }
                        }
                    ) { Text("Add") }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { scope.launch { busy = true; container.ssh.collect(serverId); busy = false } },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy
            ) { Text(if (busy) "Refreshing…" else "Refresh now") }
        }
    }
}

/** Network gauge fraction: relative to the larger of rx/tx, with a 100 Mbps floor. */
private fun networkFraction(value: Float, other: Float): Float {
    val scale = maxOf(value, other, 100f)
    return (value / scale).coerceIn(0f, 1f)
}

@Composable
private fun CircleGauge(
    label: String,
    valueText: String,
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    caption: String = ""
) {
    val progress = fraction.coerceIn(0f, 1f)
    Column(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(108.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val strokeWidth = 12.dp.toPx()
                val diameter = size.minDimension - strokeWidth
                val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                val arcSize = Size(diameter, diameter)
                val style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                // Track
                drawArc(
                    color = color.copy(alpha = 0.15f),
                    startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = style
                )
                // Progress (full circle for 100%)
                if (progress > 0f) {
                    drawArc(
                        color = color,
                        startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                        topLeft = topLeft, size = arcSize, style = style
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    valueText,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = color,
                    maxLines = 1
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (caption.isNotEmpty()) {
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
