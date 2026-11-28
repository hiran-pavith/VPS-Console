package com.vpsconsole.app.ui.extras

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vpsconsole.app.ui.components.SingleLineButton as Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import com.vpsconsole.app.ui.components.SingleLineOutlinedButton as OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.vpsconsole.app.ui.components.AutoText as Text
import com.vpsconsole.app.ui.components.SingleLineTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vpsconsole.app.data.model.CommandEntity
import com.vpsconsole.app.data.model.TunnelEntity
import com.vpsconsole.app.data.model.TunnelType
import com.vpsconsole.app.ssh.SftpStreams
import com.vpsconsole.app.ui.LocalAppContainer
import com.vpsconsole.app.ui.components.ConfirmDialog
import com.vpsconsole.app.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.SFTPClient
import java.io.IOException

@Composable
fun BackupsScreen(serverId: Long) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf("/etc") }
    var destination by remember { mutableStateOf("/root/backups") }
    var output by remember { mutableStateOf("") }
    var downloadTarget by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        output = runCommand(
            container, serverId,
            "mkdir -p ${shellQuote(destination)} && ls -lh ${shellQuote(destination)} | tail -n 40"
        )
    }

    LaunchedEffect(serverId) { refresh() }

    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
        val name = downloadTarget
        downloadTarget = null
        if (uri != null && name != null) {
            scope.launch {
                val result = container.ssh.withSftp(serverId) { sftp: SFTPClient ->
                    withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openOutputStream(uri)
                            ?: throw IOException("Cannot open the destination file")
                        stream.use { SftpStreams.download(sftp, joinPath(destination, name), it) }
                    }
                }
                output = result.exceptionOrNull()?.message ?: "Downloaded $name"
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Backups", style = MaterialTheme.typography.headlineSmall)
        SectionCard(title = "Create snapshot") {
            OutlinedTextField(value = source, onValueChange = { source = it }, label = { Text("Path to back up") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = destination, onValueChange = { destination = it }, label = { Text("Destination folder") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = {
                scope.launch {
                    val name = "backup-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".tar.gz"
                    val parent = source.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
                    val base = source.trimEnd('/').substringAfterLast('/')
                    val command = "mkdir -p ${shellQuote(destination)} && tar -czf ${shellQuote(joinPath(destination, name))} -C ${shellQuote(parent)} ${shellQuote(base)} && echo 'Created $name'"
                    output = runCommand(container, serverId, command)
                    refresh()
                }
            }) { Text("Create tar.gz snapshot") }
        }
        Row {
            Button(onClick = { scope.launch { refresh() } }) { Text("Refresh") }
        }
        OutputBox(output, Modifier.weight(1f))
        val files = output.lines().mapNotNull { line ->
            val name = line.trim().substringAfterLast(" ")
            if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) name else null
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(files) { name ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        TextButton(onClick = { downloadTarget = name; downloadLauncher.launch(name) }) { Text("Download") }
                        TextButton(onClick = { pendingDelete = name }) { Text("Delete") }
                    }
                }
            }
        }
    }
    pendingDelete?.let { name ->
        ConfirmDialog(title = "Delete backup?", text = joinPath(destination, name), confirmLabel = "Delete",
            onConfirm = { scope.launch { output = runCommand(container, serverId, "rm -f ${shellQuote(joinPath(destination, name))}"); refresh() } },
            onDismiss = { pendingDelete = null })
    }
}

@Composable
fun TunnelsScreen(serverId: Long) {
    val container = LocalAppContainer.current
    val tunnels by container.tunnelRepo.tunnels.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(TunnelType.LOCAL) }
    var localPort by remember { mutableStateOf("8080") }
    var remotePort by remember { mutableStateOf("8080") }
    var targetHost by remember { mutableStateOf("127.0.0.1") }
    var targetPort by remember { mutableStateOf("80") }
    var remoteBindHost by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(serverId, tunnels) {
        tunnels.filter { it.serverId == serverId }.forEach { tunnel ->
            if (!container.ssh.isTunnelRunning(tunnel.id)) {
                container.ssh.startTunnel(tunnel).onFailure { message = it.message ?: "Tunnel failed" }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("SSH tunnels", style = MaterialTheme.typography.headlineSmall)
        SectionCard(title = "New tunnel") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = type == TunnelType.LOCAL, onClick = { type = TunnelType.LOCAL }, label = { Text("Local (-L)") })
                FilterChip(selected = type == TunnelType.REMOTE, onClick = { type = TunnelType.REMOTE }, label = { Text("Reverse (-R)") })
            }
            if (type == TunnelType.LOCAL) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = localPort, onValueChange = { localPort = it }, label = { Text("Device listen port") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = targetHost, onValueChange = { targetHost = it }, label = { Text("Server target host") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                OutlinedTextField(value = targetPort, onValueChange = { targetPort = it }, label = { Text("Server target port") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = remotePort, onValueChange = { remotePort = it }, label = { Text("VPS listen port") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = remoteBindHost, onValueChange = { remoteBindHost = it }, label = { Text("VPS bind address (optional)") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = targetHost, onValueChange = { targetHost = it }, label = { Text("Local target host") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = targetPort, onValueChange = { targetPort = it }, label = { Text("Local target port") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                OutlinedButton(
                    onClick = {
                        type = TunnelType.REMOTE
                        remotePort = "8080"
                        remoteBindHost = "127.0.0.1"
                        targetHost = "127.0.0.1"
                        targetPort = "8080"
                        message = "ADB reverse preset loaded: VPS 127.0.0.1:8080 → device 127.0.0.1:8080"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Use ADB reverse preset") }
                Text(
                    "Reverse mode forwards a port on the VPS back to a service reachable from this device. " +
                        "Leave bind address empty to listen on all interfaces (requires GatewayPorts on the server). " +
                        "For host-side adb reverse, use the ADB Console tab instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = {
                scope.launch {
                    val entity = TunnelEntity(
                        serverId = serverId,
                        type = type,
                        localPort = if (type == TunnelType.LOCAL) localPort.toIntOrNull() ?: 0 else 0,
                        remotePort = if (type == TunnelType.REMOTE) remotePort.toIntOrNull() ?: 0 else 0,
                        targetHost = targetHost.trim().ifEmpty { "127.0.0.1" },
                        targetPort = targetPort.toIntOrNull() ?: 0,
                        remoteBindHost = if (type == TunnelType.REMOTE) remoteBindHost.trim() else ""
                    )
                    val portOk = if (type == TunnelType.LOCAL) entity.localPort in 1..65535 else entity.remotePort in 1..65535
                    if (portOk && entity.targetPort in 1..65535) {
                        val id = container.tunnelRepo.save(entity)
                        container.ssh.startTunnel(entity.copy(id = id))
                            .onFailure { message = it.message ?: "Tunnel failed" }
                            .onSuccess { message = if (type == TunnelType.LOCAL) "Local tunnel started" else "Reverse tunnel started" }
                    } else message = "Enter valid ports"
                }
            }) { Text(if (type == TunnelType.LOCAL) "Start local tunnel" else "Start reverse tunnel") }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tunnels.filter { it.serverId == serverId }) { tunnel ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${tunnel.type} :${if (tunnel.type == TunnelType.LOCAL) tunnel.localPort else tunnel.remotePort}", style = MaterialTheme.typography.bodyMedium)
                            Text("→ ${tunnel.targetHost}:${tunnel.targetPort}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (container.ssh.isTunnelRunning(tunnel.id)) "running" else "stopped", style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = {
                            container.ssh.stopTunnel(tunnel.id)
                            scope.launch { container.tunnelRepo.delete(tunnel) }
                        }) { Text("Stop") }
                    }
                }
            }
        }
    }
}

@Composable
fun ScriptsScreen(serverId: Long) {
    val container = LocalAppContainer.current
    val commands by container.commandRepo.forServer(serverId).collectAsState(emptyList())
    val settings by container.settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var global by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<CommandEntity?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Command library", style = MaterialTheme.typography.headlineSmall)
        SectionCard(title = "Save command") {
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text("Command") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Available for all servers", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.Switch(checked = global, onCheckedChange = { global = it })
            }
            Button(onClick = {
                if (title.isNotBlank() && command.isNotBlank()) {
                    scope.launch {
                        container.commandRepo.save(
                            CommandEntity(serverId = if (global) 0 else serverId, title = title.trim(), command = command.trim())
                        )
                        title = ""; command = ""
                    }
                }
            }) { Text("Save") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(commands, key = { it.id }) { item ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall)
                        Text(item.command, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = {
                                val dangerous = listOf("rm -rf", "reboot", "shutdown", "mkfs", "dd if=")
                                    .any { item.command.contains(it, ignoreCase = true) }
                                if (dangerous && settings.confirmDangerousCommands) pending = item
                                else scope.launch {
                                    output = runCommand(container, serverId, item.command)
                                    container.commandRepo.markUsed(item)
                                }
                            }) { Text("Run") }
                            TextButton(onClick = { scope.launch { container.commandRepo.delete(item) } }) { Text("Delete") }
                        }
                    }
                }
            }
        }
        if (output.isNotBlank()) OutputBox(output)
    }
    pending?.let { item ->
        ConfirmDialog(title = "Run dangerous command?", text = item.command, confirmLabel = "Run anyway",
            onConfirm = {
                scope.launch {
                    output = runCommand(container, serverId, item.command)
                    container.commandRepo.markUsed(item)
                }
            },
            onDismiss = { pending = null })
    }
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private fun joinPath(base: String, name: String): String =
    if (base.endsWith("/")) "$base$name" else "$base/$name"
