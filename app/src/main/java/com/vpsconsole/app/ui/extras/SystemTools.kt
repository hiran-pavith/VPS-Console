package com.vpsconsole.app.ui.extras

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vpsconsole.app.data.model.CronEntry
import com.vpsconsole.app.data.model.SystemUser
import com.vpsconsole.app.ui.LocalAppContainer
import com.vpsconsole.app.ui.components.ConfirmDialog
import com.vpsconsole.app.ui.components.SectionCard
import kotlinx.coroutines.launch
import java.util.Base64

@Composable
fun FirewallScreen(serverId: Long) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var output by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }

    suspend fun reload() {
        output = runCommand(
            container, serverId,
            "ufw status verbose || echo '--- firewall-cmd ---'; firewall-cmd --state 2>&1 || true; echo '--- iptables ---'; iptables -L INPUT -n --line-numbers 2>&1 | head -n 40"
        )
    }

    LaunchedEffect(serverId) { reload() }

    fun execute(title: String, command: String) {
        scope.launch { output = runCommand(container, serverId, command) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Firewall", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        SectionCard(title = "ufw controls") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("enable", "disable", "reload", "status").forEach { action ->
                    OutlinedButton(
                        onClick = {
                            pending = "ufw $action" to (if (action == "status") "ufw status verbose" else "ufw $action")
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(action) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port / service (e.g. 8080/tcp)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(onClick = {
                    if (port.isNotBlank()) pending = "Allow $port" to "ufw allow $port"
                }) { Text("Allow") }
                OutlinedButton(onClick = {
                    if (port.isNotBlank()) pending = "Deny $port" to "ufw deny $port"
                }) { Text("Deny") }
            }
        }
        Row {
            Button(onClick = { scope.launch { reload() } }) { Text("Refresh") }
        }
        OutputBox(output, Modifier.weight(1f))
    }
    pending?.let { (title, command) ->
        ConfirmDialog(title = "Run $title?", text = command, confirmLabel = "Run",
            onConfirm = { execute(title, command) }, onDismiss = { pending = null })
    }
}

@Composable
fun CronScreen(serverId: Long) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var raw by remember { mutableStateOf("") }
    var schedule by remember { mutableStateOf("0 * * * *") }
    var command by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf("") }

    val entries = parseCron(raw)

    suspend fun reload() {
        raw = runCommand(container, serverId, "crontab -l 2>&1 || true")
    }

    LaunchedEffect(serverId) { reload() }

    suspend fun write(lines: List<String>) {
        val encoded = Base64.getEncoder().encodeToString((lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
        val result = runCommand(container, serverId, "printf %s $encoded | base64 -d | crontab -")
        message = if (result.startsWith("Error")) result else "Crontab updated"
        reload()
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Cron jobs", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        SectionCard(title = "Add job") {
            OutlinedTextField(value = schedule, onValueChange = { schedule = it }, label = { Text("Schedule (cron expression)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text("Command") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = {
                if (command.isNotBlank()) scope.launch {
                    write(raw.lines().filter { it.isNotBlank() } + "$schedule $command")
                    command = ""
                }
            }) { Text("Add job") }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(entries) { index, entry ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.schedule, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(entry.command, style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(onClick = { pendingDelete = index }) { Text("Delete") }
                    }
                }
            }
        }
        if (entries.isEmpty()) Text("No cron jobs found.", style = MaterialTheme.typography.bodySmall)
    }
    pendingDelete?.let { index ->
        ConfirmDialog(
            title = "Delete cron job?", text = entries.getOrNull(index)?.raw ?: "",
            confirmLabel = "Delete",
            onConfirm = {
                // Delete by exact line identity, not index — parseCron() skips
                // comments/blanks so indexes into `entries` and `raw.lines()` diverge.
                val target = entries.getOrNull(index)?.raw
                scope.launch {
                    if (target != null) write(raw.lines().filter { it != target })
                    pendingDelete = null
                }
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
fun UsersScreen(serverId: Long) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var raw by remember { mutableStateOf("") }
    var sessions by remember { mutableStateOf("") }
    var actionOutput by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
    val users = parseUsers(raw)

    suspend fun reload() {
        raw = runCommand(container, serverId, "getent passwd 2>&1 || cat /etc/passwd")
        sessions = runCommand(container, serverId, "who 2>&1; echo '--- last logins ---'; last -n 10 2>&1")
    }

    LaunchedEffect(serverId) { reload() }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Users & sessions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Button(onClick = { scope.launch { reload() } }) { Text("Refresh") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(users) { _, user ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${user.name} (uid ${user.uid})", style = MaterialTheme.typography.bodyMedium)
                            Text("${user.home} · ${user.shell}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (user.uid >= 1000 || user.uid == 0) {
                            TextButton(onClick = { pending = "Lock ${user.name}" to "passwd -l ${user.name}" }) { Text("Lock") }
                            TextButton(onClick = { pending = "Unlock ${user.name}" to "passwd -u ${user.name}" }) { Text("Unlock") }
                        }
                    }
                }
            }
        }
        if (actionOutput.isNotBlank()) SectionCard(title = "Last action") { OutputBox(actionOutput) }
        SectionCard(title = "Active sessions & last logins") { OutputBox(sessions) }
    }
    pending?.let { (title, command) ->
        ConfirmDialog(title = "Run $title?", text = command, confirmLabel = "Run",
            onConfirm = { scope.launch { actionOutput = runCommand(container, serverId, command) } },
            onDismiss = { pending = null })
    }
}

@Composable
fun PackagesScreen(serverId: Long) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var manager by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }

    suspend fun reload() {
        manager = runCommand(container, serverId, "command -v apt-get || command -v dnf || command -v yum || command -v apk || true").trim().ifEmpty { "unknown" }
        output = if (manager.contains("apt")) {
            runCommand(container, serverId, "apt-get -s upgrade 2>&1 | tail -n 40; echo '--- installed ---'; dpkg -l 2>/dev/null | wc -l")
        } else {
            runCommand(container, serverId, "$manager check-update 2>&1 | tail -n 40 || true")
        }
    }

    LaunchedEffect(serverId) { reload() }

    fun commandFor(action: String): String = when {
        manager.contains("apt") -> when (action) {
            "Update lists" -> "apt-get update"
            "Upgrade" -> "DEBIAN_FRONTEND=noninteractive apt-get upgrade -y"
            "Autoremove" -> "apt-get autoremove -y"
            else -> "apt-get clean"
        }
        manager.contains("dnf") || manager.contains("yum") -> when (action) {
            "Update lists" -> "$manager check-update || true"
            "Upgrade" -> "$manager update -y"
            else -> "$manager clean all"
        }
        else -> "$manager update"
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Packages & updates", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Detected package manager: $manager", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Update lists", "Upgrade", "Autoremove", "Clean").forEach { action ->
                OutlinedButton(onClick = { pending = action to commandFor(action) }, modifier = Modifier.weight(1f)) { Text(action) }
            }
        }
        Button(onClick = { scope.launch { reload() } }) { Text("Refresh") }
        OutputBox(output, Modifier.weight(1f))
    }
    pending?.let { (title, command) ->
        ConfirmDialog(title = "Run $title?", text = command, confirmLabel = "Run",
            onConfirm = { scope.launch { output = runCommand(container, serverId, command); reload() } },
            onDismiss = { pending = null })
    }
}

private fun parseCron(raw: String): List<CronEntry> = raw.lines().mapNotNull { line ->
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
    val parts = trimmed.split(Regex("\\s+"), limit = 6)
    if (parts.size == 6) CronEntry(parts.take(5).joinToString(" "), parts[5], trimmed) else null
}

private fun parseUsers(raw: String): List<SystemUser> = raw.lines().mapNotNull { line ->
    val parts = line.split(":")
    if (parts.size >= 7) {
        SystemUser(
            name = parts[0],
            uid = parts[2].toIntOrNull() ?: return@mapNotNull null,
            home = parts[5],
            shell = parts[6]
        )
    } else null
}
