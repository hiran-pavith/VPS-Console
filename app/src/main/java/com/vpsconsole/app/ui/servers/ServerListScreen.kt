package com.vpsconsole.app.ui.servers

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Terminal
import com.vpsconsole.app.ui.components.SingleLineButton as Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.vpsconsole.app.ui.components.SingleLineOutlinedButton as OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.vpsconsole.app.ui.components.AutoText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vpsconsole.app.ui.LocalAppContainer
import com.vpsconsole.app.ui.components.ConfirmDialog
import com.vpsconsole.app.ui.components.EmptyState
import com.vpsconsole.app.ui.components.StatusBadge
import com.vpsconsole.app.ssh.ConnectionState
import kotlinx.coroutines.launch

@Composable
fun ServerListScreen(
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpen: (Long) -> Unit,
    onTerminal: (Long) -> Unit,
    onFiles: (Long) -> Unit
) {
    val container = LocalAppContainer.current
    val servers by container.serverRepo.servers.collectAsState(emptyList())
    val settings by container.settings.settings.collectAsState()
    val states by container.ssh.states.collectAsState(emptyMap())
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("All") }
    var pendingDelete by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()

    val groups = listOf("All") + servers.map { it.group }.filter { it.isNotBlank() }.distinct().sorted()
    val sorted = when (settings.serverSortOrder) {
        "host" -> servers.sortedBy { it.host }
        "group" -> servers.sortedWith(compareBy({ it.group }, { it.name }))
        else -> servers.sortedBy { it.name }
    }
    val visible = sorted.filter { server ->
        (group == "All" || server.group == group) &&
            (query.isBlank() || listOf(server.name, server.host, server.username, server.group)
                .any { it.contains(query, ignoreCase = true) })
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search servers") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groups.forEach { label ->
                    FilterChip(
                        selected = group == label,
                        onClick = { group = label },
                        label = { Text(label) }
                    )
                }
            }
        }
        item {
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("Add VPS", Modifier.padding(start = 6.dp))
            }
        }
        if (visible.isEmpty()) {
            item {
                EmptyState(if (servers.isEmpty()) "No servers configured." else "No servers match your filters.")
            }
        }
        items(visible, key = { it.id }) { server ->
            val state = states[server.id] ?: ConnectionState.DISCONNECTED
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(server.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${server.username}@${server.host}:${server.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (server.group.isNotBlank()) {
                                Text(
                                    server.group,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        StatusBadge(state)
                    }
                    if (server.note.isNotBlank()) {
                        Text(server.note, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    if (state == ConnectionState.CONNECTED) container.ssh.close(server.id)
                                    else container.ssh.connect(server.id)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(if (state == ConnectionState.CONNECTED) "Disconnect" else "Connect") }
                        OutlinedButton(onClick = { onOpen(server.id) }, modifier = Modifier.weight(1f)) { Text("Details") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        IconButton(onClick = { onTerminal(server.id) }) {
                            Icon(Icons.Filled.Terminal, contentDescription = "Terminal")
                        }
                        IconButton(onClick = { onFiles(server.id) }) {
                            Icon(Icons.Filled.Folder, contentDescription = "SFTP")
                        }
                        IconButton(onClick = { onEdit(server.id) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit")
                        }
                        IconButton(onClick = { pendingDelete = server.id }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { id ->
        ConfirmDialog(
            title = "Delete server?",
            text = "The server and its stored credentials will be removed. This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                scope.launch {
                    container.serverRepo.get(id)?.let { container.serverRepo.delete(it) }
                    container.ssh.close(id)
                }
            },
            onDismiss = { pendingDelete = null }
        )
    }
}
