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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vpsconsole.app.core.formatRate
import com.vpsconsole.app.core.formatTimestamp
import com.vpsconsole.app.core.formatUptime
import com.vpsconsole.app.core.percent
import com.vpsconsole.app.data.model.AlertRuleEntity
import com.vpsconsole.app.data.model.MetricKind
import com.vpsconsole.app.ui.LocalAppContainer
import com.vpsconsole.app.ui.components.MetricTile
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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(
                    "CPU", percent(latest?.cpu ?: 0f),
                    latest?.let { formatTimestamp(it.timestamp) }.orEmpty(),
                    color = Color(0xFF58A6FF), progress = (latest?.cpu ?: 0f) / 100f,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    "RAM", percent(latest?.ram ?: 0f),
                    color = Color(0xFF3FB950), progress = (latest?.ram ?: 0f) / 100f,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(
                    "Disk", percent(latest?.disk ?: 0f),
                    color = Color(0xFFD29922), progress = (latest?.disk ?: 0f) / 100f,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    "Load", String.format(Locale.US, "%.2f", latest?.load ?: 0f),
                    "uptime ${formatUptime(latest?.uptimeSec ?: 0)}",
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            SectionCard(title = "Network · ↓ ${formatRate(latest?.rxMbps ?: 0f)}  ↑ ${formatRate(latest?.txMbps ?: 0f)}") {
                LineChart(metrics.map { it.rxMbps }, Color(0xFF58A6FF), Modifier.fillMaxWidth().height(110.dp))
                LineChart(metrics.map { it.txMbps }, Color(0xFF3FB950), Modifier.fillMaxWidth().height(110.dp))
            }
        }
        item {
            SectionCard(title = "CPU history") {
                LineChart(metrics.map { it.cpu }, Color(0xFF58A6FF), Modifier.fillMaxWidth().height(140.dp))
            }
        }
        item {
            SectionCard(title = "Memory history") {
                LineChart(metrics.map { it.ram }, Color(0xFF3FB950), Modifier.fillMaxWidth().height(140.dp))
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
                            val value = newThreshold.toFloatOrNull()?.coerceIn(1f, 100f) ?: return@Button
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

@Composable
private fun LineChart(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val max = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val path = Path()
        val stepX = size.width / (values.size - 1)
        values.forEachIndexed { index, value ->
            val x = index * stepX
            val y = size.height - (value / max) * size.height * 0.9f
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 3f))
        drawLine(
            color = color.copy(alpha = 0.15f),
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 2f
        )
    }
}
