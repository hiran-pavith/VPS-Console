package com.vpsconsole.app.ui.dashboard

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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vpsconsole.app.ui.LocalAppContainer
import com.vpsconsole.app.ui.components.EmptyState
import com.vpsconsole.app.ui.components.MetricTile
import com.vpsconsole.app.ui.components.SectionCard
import com.vpsconsole.app.ui.components.StatusBadge
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(
    onOpenServer: (Long) -> Unit,
    onNewServer: () -> Unit,
    onOpenTerminal: (Long) -> Unit,
    onOpenFiles: (Long) -> Unit
) {
    val container = LocalAppContainer.current
    val servers by container.serverRepo.servers.collectAsState(emptyList())
    val states by container.ssh.states.collectAsState(emptyMap())
    val events by container.alertRepo.events.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val online = servers.count { states[it.id]?.name == "CONNECTED" }
    val unread = events.count { !it.acknowledged }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Overview", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("Servers", servers.size.toString(), "configured", modifier = Modifier.weight(1f))
                MetricTile("Online", online.toString(), "SSH sessions active", modifier = Modifier.weight(1f))
                MetricTile("Alerts", unread.toString(), "unacknowledged", modifier = Modifier.weight(1f))
            }
        }
        item {
            SectionCard(title = "Quick actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onNewServer, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("Add VPS", Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch { servers.forEach { container.ssh.collect(it.id) } }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Text("Refresh", Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
        item {
            Text("Your servers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        if (servers.isEmpty()) {
            item {
                EmptyState("No servers yet. Add your first VPS to get started.") {
                    Button(onClick = onNewServer) { Text("Add server") }
                }
            }
        }
        items(servers, key = { it.id }) { server ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = CardDefaults.shape,
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
                        }
                        StatusBadge(states[server.id] ?: com.vpsconsole.app.ssh.ConnectionState.DISCONNECTED)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onOpenServer(server.id) }, modifier = Modifier.weight(1f)) {
                            Text("Monitor")
                        }
                        OutlinedButton(onClick = { onOpenTerminal(server.id) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Terminal, contentDescription = null)
                            Text("SSH", Modifier.padding(start = 4.dp))
                        }
                        OutlinedButton(onClick = { onOpenFiles(server.id) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Folder, contentDescription = null)
                            Text("SFTP", Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }
        }
    }
}
