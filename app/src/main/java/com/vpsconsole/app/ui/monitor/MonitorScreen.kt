package com.vpsconsole.app.ui.monitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vpsconsole.app.core.formatBytes
import com.vpsconsole.app.core.formatRate
import com.vpsconsole.app.core.formatTimestamp
import com.vpsconsole.app.core.formatUptime
import com.vpsconsole.app.core.percent
import com.vpsconsole.app.data.model.AlertRuleEntity
import com.vpsconsole.app.data.model.MetricEntity
import com.vpsconsole.app.data.model.MetricKind
import com.vpsconsole.app.data.settings.AppSettings
import com.vpsconsole.app.ssh.ConnectionState
import com.vpsconsole.app.ui.LocalAppContainer
import com.vpsconsole.app.ui.components.SectionCard
import com.vpsconsole.app.ui.components.StatusBadge
import com.vpsconsole.app.ui.theme.AccentTheme
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class MonitorWidget(val id: String, val label: String) {
    CPU("cpu", "CPU"),
    RAM("ram", "RAM"),
    DISK("disk", "Disk"),
    LOAD("load", "Load"),
    NET("net", "Network")
}

private data class WidgetDetail(val label: String, val value: String)

private data class MonitorCard(
    val widget: MonitorWidget,
    val value: String,
    val fraction: Float,
    val color: Color,
    val caption: String,
    val trend: List<Float>,
    val details: List<WidgetDetail>
)

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
    var showCustomize by remember { mutableStateOf(false) }

    LaunchedEffect(serverId, settings.refreshSeconds) {
        while (true) {
            container.ssh.collect(serverId)
            delay(settings.refreshSeconds * 1000L)
        }
    }

    val latest = metrics.lastOrNull()
    val accent = AccentTheme.from(settings.accentColor)
    val widgets = parseWidgetOrder(settings.monitorWidgetOrder)
    val cards = buildCards(widgets, latest, metrics, settings, accent)
    val (columns, gaugeSize) = when (settings.monitorWidgetSize) {
        "compact" -> 3 to 82.dp
        "large" -> 1 to 132.dp
        else -> 2 to 104.dp
    }
    val serverRules = rules.filter { it.serverId == serverId }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        server?.name ?: "Server",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${server?.host ?: ""} · ${state[serverId]?.name ?: "DISCONNECTED"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = { showCustomize = true }) { Text("Customize") }
                StatusBadge(state[serverId] ?: ConnectionState.DISCONNECTED)
            }
        }

        item {
            OverviewHero(server?.name ?: "Server", server?.host.orEmpty(), latest, accent)
        }

        item {
            SectionCard(title = "Live widgets") {
                if (cards.isEmpty()) {
                    Text(
                        "All widgets are hidden. Open Customize to enable them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                cards.chunked(columns).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        row.forEach { card ->
                            WidgetTile(
                                card = card,
                                settings = settings,
                                gaugeSize = gaugeSize,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
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
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${rule.kind} ≥ ${percent(rule.threshold)}" +
                                if (rule.isServerThreshold) " (server default)" else "",
                            Modifier.weight(1f)
                        )
                        Text(
                            if (rule.enabled) "on" else "off",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(
                            onClick = {
                                scope.launch { container.alertRepo.saveRule(rule.copy(enabled = !rule.enabled)) }
                            },
                            modifier = Modifier.semantics {
                                contentDescription = if (rule.enabled) "Disable rule" else "Enable rule"
                            }
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
                        FilterChip(
                            selected = newKind == kind,
                            onClick = { newKind = kind },
                            label = { Text(kind.name) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newThreshold,
                        onValueChange = { newThreshold = it },
                        label = { Text("Threshold %") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Button(
                        onClick = {
                            val value = newThreshold.toFloatOrNull()
                                ?.takeIf { it.isFinite() }
                                ?.coerceIn(1f, 100f) ?: return@Button
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
                onClick = {
                    scope.launch {
                        busy = true
                        container.ssh.collect(serverId)
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy
            ) { Text(if (busy) "Refreshing…" else "Refresh now") }
        }
    }

    if (showCustomize) {
        CustomizeDialog(
            settings = settings,
            onUpdate = { next -> container.settings.update { next } },
            onDismiss = { showCustomize = false }
        )
    }
}

@Composable
private fun OverviewHero(
    serverName: String,
    host: String,
    latest: MetricEntity?,
    accent: AccentTheme
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            Modifier.fillMaxWidth().background(
                Brush.linearGradient(
                    listOf(Color(accent.dark).copy(alpha = 0.26f), Color.Transparent)
                )
            )
        ) {
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "LIVE OVERVIEW",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(accent.dark),
                    fontWeight = FontWeight.Bold
                )
                Text(serverName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    host.ifEmpty { "No host configured" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatPill("Uptime", formatUptime(latest?.uptimeSec ?: 0))
                    StatPill("Load", String.format(Locale.US, "%.2f", latest?.load ?: 0f))
                    StatPill("Updated", latest?.let { formatTimestamp(it.timestamp) } ?: "No data yet")
                }
            }
        }
    }
}

@Composable
private fun StatPill(label: String, value: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
        )
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun WidgetTile(
    card: MonitorCard,
    settings: AppSettings,
    gaugeSize: Dp,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CircleGauge(
                label = card.widget.label,
                valueText = card.value,
                fraction = card.fraction,
                color = card.color,
                modifier = Modifier.fillMaxWidth(),
                caption = card.caption,
                gaugeSize = gaugeSize
            )
            if (settings.monitorShowTrends && card.trend.size > 1) {
                Sparkline(card.trend, card.color, Modifier.fillMaxWidth().height(36.dp))
            }
            if (settings.monitorShowDetails && card.details.isNotEmpty()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    card.details.forEach { detail ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                detail.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(detail.value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CircleGauge(
    label: String,
    valueText: String,
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    caption: String = "",
    gaugeSize: Dp = 108.dp
) {
    val progress = fraction.coerceIn(0f, 1f)
    Column(
        modifier = modifier.padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(gaugeSize)) {
            Canvas(Modifier.fillMaxSize()) {
                val strokeWidth = (gaugeSize.value / 9f).dp.toPx()
                val diameter = size.minDimension - strokeWidth
                val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                val arcSize = Size(diameter, diameter)
                val style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                drawArc(
                    color = color.copy(alpha = 0.15f),
                    startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = style
                )
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
                    style = if (gaugeSize >= 110.dp) MaterialTheme.typography.titleSmall
                    else MaterialTheme.typography.labelLarge,
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun Sparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val clean = values.map { if (it.isFinite()) it else 0f }
        val min = clean.minOrNull() ?: 0f
        val max = clean.maxOrNull() ?: 0f
        val range = (max - min).coerceAtLeast(0.0001f)
        val step = size.width / (clean.size - 1)
        val line = Path()
        val fill = Path()
        clean.forEachIndexed { index, value ->
            val x = index * step
            val y = size.height - (((value - min) / range) * size.height * 0.8f + size.height * 0.1f)
            if (index == 0) {
                line.moveTo(x, y)
                fill.moveTo(0f, size.height)
                fill.lineTo(x, y)
            } else {
                line.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(size.width, size.height)
        fill.close()
        drawPath(fill, color.copy(alpha = 0.14f))
        drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun CustomizeDialog(
    settings: AppSettings,
    onUpdate: (AppSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val order = parseWidgetOrder(settings.monitorWidgetOrder)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Customize dashboard") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Widget order and visibility", style = MaterialTheme.typography.titleSmall)
                order.forEachIndexed { index, widget ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            enabled = index > 0,
                            onClick = {
                                onUpdate(settings.copy(monitorWidgetOrder = serializeWidgets(order.moved(index, index - 1))))
                            }
                        ) { Text("↑") }
                        IconButton(
                            enabled = index < order.lastIndex,
                            onClick = {
                                onUpdate(settings.copy(monitorWidgetOrder = serializeWidgets(order.moved(index, index + 1))))
                            }
                        ) { Text("↓") }
                        Text(widget.label, Modifier.weight(1f))
                        Switch(
                            checked = isWidgetVisible(widget, settings),
                            onCheckedChange = { onUpdate(withWidgetVisible(settings, widget, it)) }
                        )
                    }
                }
                Text("Widget size", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("compact" to "Compact", "regular" to "Regular", "large" to "Large").forEach { (value, label) ->
                        FilterChip(
                            selected = settings.monitorWidgetSize == value,
                            onClick = { onUpdate(settings.copy(monitorWidgetSize = value)) },
                            label = { Text(label) }
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Trend lines", Modifier.weight(1f))
                    Switch(
                        checked = settings.monitorShowTrends,
                        onCheckedChange = { onUpdate(settings.copy(monitorShowTrends = it)) }
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Detail rows", Modifier.weight(1f))
                    Switch(
                        checked = settings.monitorShowDetails,
                        onCheckedChange = { onUpdate(settings.copy(monitorShowDetails = it)) }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            TextButton(
                onClick = {
                    onUpdate(
                        settings.copy(
                            showCpuGauge = true,
                            showRamGauge = true,
                            showDiskGauge = true,
                            showLoadGauge = true,
                            showNetworkGauge = true,
                            monitorWidgetOrder = serializeWidgets(MonitorWidget.entries),
                            monitorWidgetSize = "regular",
                            monitorShowTrends = true,
                            monitorShowDetails = true
                        )
                    )
                    onDismiss()
                }
            ) { Text("Reset") }
        }
    )
}

private fun parseWidgetOrder(raw: String): List<MonitorWidget> {
    val parsed = raw.split(',')
        .map { it.trim().lowercase(Locale.US) }
        .mapNotNull { id -> MonitorWidget.entries.firstOrNull { it.id == id } }
    return (parsed + MonitorWidget.entries).distinct()
}

private fun serializeWidgets(widgets: List<MonitorWidget>): String =
    widgets.joinToString(",") { it.id }

private fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

private fun isWidgetVisible(widget: MonitorWidget, settings: AppSettings): Boolean = when (widget) {
    MonitorWidget.CPU -> settings.showCpuGauge
    MonitorWidget.RAM -> settings.showRamGauge
    MonitorWidget.DISK -> settings.showDiskGauge
    MonitorWidget.LOAD -> settings.showLoadGauge
    MonitorWidget.NET -> settings.showNetworkGauge
}

private fun withWidgetVisible(
    settings: AppSettings,
    widget: MonitorWidget,
    enabled: Boolean
): AppSettings = when (widget) {
    MonitorWidget.CPU -> settings.copy(showCpuGauge = enabled)
    MonitorWidget.RAM -> settings.copy(showRamGauge = enabled)
    MonitorWidget.DISK -> settings.copy(showDiskGauge = enabled)
    MonitorWidget.LOAD -> settings.copy(showLoadGauge = enabled)
    MonitorWidget.NET -> settings.copy(showNetworkGauge = enabled)
}

private fun buildCards(
    widgets: List<MonitorWidget>,
    latest: MetricEntity?,
    history: List<MetricEntity>,
    settings: AppSettings,
    accent: AccentTheme
): List<MonitorCard> {
    val recent = history.takeLast(40)
    val cpu = recent.map { it.cpu }
    val ram = recent.map { it.ram }
    val disk = recent.map { it.disk }
    val load = recent.map { it.load }
    val net = recent.map { (it.rxMbps + it.txMbps).coerceAtMost(10_000f) }
    val accentColor = Color(accent.dark)
    val secondary = Color(accent.secondaryDark)

    return widgets.filter { isWidgetVisible(it, settings) }.map { widget ->
        when (widget) {
            MonitorWidget.CPU -> MonitorCard(
                widget = widget,
                value = percent(latest?.cpu ?: 0f),
                fraction = (latest?.cpu ?: 0f) / 100f,
                color = accentColor,
                caption = "avg ${percent(average(cpu))}",
                trend = cpu,
                details = listOf(
                    WidgetDetail("Average", percent(average(cpu))),
                    WidgetDetail("Peak", percent(max(cpu))),
                    WidgetDetail("Idle", percent((100f - average(cpu)).coerceIn(0f, 100f)))
                )
            )

            MonitorWidget.RAM -> MonitorCard(
                widget = widget,
                value = percent(latest?.ram ?: 0f),
                fraction = (latest?.ram ?: 0f) / 100f,
                color = secondary,
                caption = "avg ${percent(average(ram))}",
                trend = ram,
                details = listOf(
                    WidgetDetail("Average", percent(average(ram))),
                    WidgetDetail("Peak", percent(max(ram)))
                )
            )

            MonitorWidget.DISK -> MonitorCard(
                widget = widget,
                value = percent(latest?.disk ?: 0f),
                fraction = (latest?.disk ?: 0f) / 100f,
                color = Color(0xFFD29922),
                caption = "avg ${percent(average(disk))}",
                trend = disk,
                details = listOf(
                    WidgetDetail("Average", percent(average(disk))),
                    WidgetDetail("Peak", percent(max(disk)))
                )
            )

            MonitorWidget.LOAD -> MonitorCard(
                widget = widget,
                value = String.format(Locale.US, "%.2f", latest?.load ?: 0f),
                fraction = (latest?.load ?: 0f) / 4f,
                color = Color(0xFFA371F7),
                caption = "uptime ${formatUptime(latest?.uptimeSec ?: 0)}",
                trend = load,
                details = listOf(
                    WidgetDetail("Average", String.format(Locale.US, "%.2f", average(load))),
                    WidgetDetail("Samples", "${recent.size}")
                )
            )

            MonitorWidget.NET -> MonitorCard(
                widget = widget,
                value = formatRate((latest?.rxMbps ?: 0f) + (latest?.txMbps ?: 0f)),
                fraction = networkFraction(latest?.rxMbps ?: 0f, latest?.txMbps ?: 0f),
                color = accentColor,
                caption = "↓ ${formatRate(latest?.rxMbps ?: 0f)}  ↑ ${formatRate(latest?.txMbps ?: 0f)}",
                trend = net,
                details = listOf(
                    WidgetDetail("Rx total", formatBytes(latest?.rxBytes ?: 0)),
                    WidgetDetail("Tx total", formatBytes(latest?.txBytes ?: 0))
                )
            )
        }
    }
}

private fun average(values: List<Float>): Float {
    val clean = values.filter { it.isFinite() }
    return if (clean.isEmpty()) 0f else clean.sum() / clean.size
}

private fun max(values: List<Float>): Float =
    values.filter { it.isFinite() }.maxOrNull() ?: 0f

/** Network gauge fraction: relative to the larger of rx/tx, with a 100 Mbps floor. */
private fun networkFraction(value: Float, other: Float): Float {
    val scale = maxOf(value, other, 100f)
    return (value / scale).coerceIn(0f, 1f)
}
