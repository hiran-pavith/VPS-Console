package com.vpsconsole.app.ui.services

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vpsconsole.app.data.model.ContainerInfo
import com.vpsconsole.app.data.model.ProcessInfo
import com.vpsconsole.app.data.model.ServiceInfo
import com.vpsconsole.app.ui.LocalAppContainer
import com.vpsconsole.app.ui.components.ConfirmDialog
import com.vpsconsole.app.ui.components.EmptyState
import com.vpsconsole.app.ui.components.MetricTile
import com.vpsconsole.app.ui.components.SectionCard
import java.util.Locale
import kotlinx.coroutines.launch

private enum class Tab { SERVICES, PROCESSES, DOCKER }

@Composable
fun ServicesScreen(serverId: Long) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(Tab.SERVICES) }
    var raw by remember { mutableStateOf("") }
    var loadAvg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var logTitle by remember { mutableStateOf<String?>(null) }
    var logText by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }

    suspend fun reload() {
        busy = true
        val command = when (tab) {
            Tab.SERVICES -> "systemctl list-units --type=service --all --no-pager --plain --no-legend"
            Tab.PROCESSES -> "ps -eo pid,user,%cpu,%mem,stat,args --sort=-%cpu | head -n 60"
            Tab.DOCKER -> "docker ps -a --format '{{.ID}}|{{.Names}}|{{.Image}}|{{.Status}}|{{.Ports}}'"
        }
        raw = container.ssh.exec(serverId, command, timeoutSeconds = 60)
            .getOrElse { "Error: ${it.message}" }
        loadAvg = container.ssh.exec(serverId, "cat /proc/loadavg 2>&1", timeoutSeconds = 15)
            .getOrElse { "" }
        busy = false
    }

    LaunchedEffect(serverId, tab) { reload() }

    fun run(command: String) {
        scope.launch {
            val result = container.ssh.exec(serverId, command, timeoutSeconds = 90)
            if (result.isFailure) logText = result.exceptionOrNull()?.message ?: "Command failed"
            reload()
        }
    }

    fun showLogs(title: String, command: String) {
        scope.launch {
            logTitle = title
            logText = container.ssh.exec(serverId, command, timeoutSeconds = 60)
                .getOrElse { "Error: ${it.message}" }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(title = "System load") {
            val parts = loadAvg.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (parts.size >= 3) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricTile("1 min", parts[0], "load average", color = Color(0xFF58A6FF), modifier = Modifier.weight(1f))
                    MetricTile("5 min", parts[1], "load average", color = Color(0xFF3FB950), modifier = Modifier.weight(1f))
                    MetricTile("15 min", parts[2], "load average", color = Color(0xFFD29922), modifier = Modifier.weight(1f))
                }
                if (parts.size >= 4) {
                    Text(
                        "Running / total processes: ${parts[3]}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text("Load information unavailable.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tab.entries.forEach { value ->
                FilterChip(
                    selected = tab == value,
                    onClick = { tab = value },
                    label = { Text(value.name.lowercase().replaceFirstChar { it.uppercase() }) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { reload() } }, enabled = !busy) {
                Text(if (busy) "Loading…" else "Refresh")
            }
            if (tab == Tab.DOCKER) {
                OutlinedButton(onClick = { run("docker system prune -af") }) { Text("Prune Docker") }
            }
        }

        when (tab) {
            Tab.SERVICES -> {
                val services = parseServices(raw)
                if (services.isEmpty()) {
                    EmptyState("No services found or SSH command unavailable.")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(services, key = { it.name }) { service ->
                            ServiceRow(service,
                                onLogs = { showLogs(service.name, "journalctl -u ${service.name} -n 100 --no-pager") },
                                onAction = { action ->
                                    pending = "$action ${service.name}" to "systemctl $action ${service.name}"
                                })
                        }
                    }
                }
            }

            Tab.PROCESSES -> {
                val processes = parseProcesses(raw)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(processes, key = { it.pid }) { process ->
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${process.pid} · ${process.command}", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${process.user} · CPU ${String.format(Locale.US, "%.1f", process.cpu)}% · MEM ${String.format(Locale.US, "%.1f", process.mem)}% · ${process.state}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Button(
                                    onClick = {
                                        pending = "Kill ${process.pid}" to "kill -9 ${process.pid}"
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) { Text("Kill") }
                            }
                        }
                    }
                }
            }

            Tab.DOCKER -> {
                val containers = parseContainers(raw)
                if (containers.isEmpty()) {
                    EmptyState("No containers found. Is Docker installed?")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(containers, key = { it.id }) { item ->
                            Card(
                                Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(item.name, style = MaterialTheme.typography.titleSmall)
                                    Text("${item.image} · ${item.status}", style = MaterialTheme.typography.bodySmall)
                                    if (item.ports.isNotBlank()) {
                                        Text(item.ports, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf("start", "stop", "restart").forEach { action ->
                                            OutlinedButton(onClick = {
                                                pending = "$action ${item.name}" to "docker $action ${item.name}"
                                            }) { Text(action) }
                                        }
                                        TextButton(onClick = {
                                            showLogs(item.name, "docker logs --tail 100 ${item.name}")
                                        }) { Text("Logs") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pending?.let { (title, command) ->
        ConfirmDialog(title = "Run $title?", text = command, confirmLabel = "Run", onConfirm = { run(command) },
            onDismiss = { pending = null })
    }

    logTitle?.let { title ->
        AlertDialog(
            onDismissRequest = { logTitle = null },
            title = { Text("Logs · $title") },
            text = {
                Text(
                    logText.ifEmpty { "No output" },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.height(400.dp).verticalScroll(rememberScrollState())
                )
            },
            confirmButton = { TextButton(onClick = { logTitle = null }) { Text("Close") } }
        )
    }
}

@Composable
private fun ServiceRow(service: ServiceInfo, onLogs: () -> Unit, onAction: (String) -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(service.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    service.active,
                    color = if (service.active == "active") Color(0xFF3FB950) else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Text(
                "Load: ${service.load} · Sub: ${service.sub}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (service.description.isNotBlank()) {
                Text(service.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("start", "stop", "restart").forEach { action ->
                    OutlinedButton(onClick = { onAction(action) }) { Text(action) }
                }
                TextButton(onClick = onLogs) { Text("Logs") }
            }
        }
    }
}

private fun parseServices(raw: String): List<ServiceInfo> = raw.lines().mapNotNull { line ->
    val parts = line.trim().split(Regex("\\s+"), limit = 5)
    if (parts.size >= 4 && parts[0].endsWith(".service")) {
        ServiceInfo(parts[0], parts[1], parts[2], parts[3], parts.getOrNull(4).orEmpty())
    } else null
}

private fun parseProcesses(raw: String): List<ProcessInfo> = raw.lines().drop(1).mapNotNull { line ->
    val parts = line.trim().split(Regex("\\s+"), limit = 6)
    if (parts.size == 6) {
        ProcessInfo(
            pid = parts[0].toIntOrNull() ?: return@mapNotNull null,
            user = parts[1],
            cpu = parts[2].toFloatOrNull() ?: 0f,
            mem = parts[3].toFloatOrNull() ?: 0f,
            state = parts[4],
            command = parts[5]
        )
    } else null
}

private fun parseContainers(raw: String): List<ContainerInfo> = raw.lines().mapNotNull { line ->
    val parts = line.split("|")
    if (parts.size >= 4) {
        ContainerInfo(parts[0], parts[1], parts[2], parts[3], parts.getOrNull(4).orEmpty())
    } else null
}
