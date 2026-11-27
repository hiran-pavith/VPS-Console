package com.vpsconsole.app.ui.extras

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vpsconsole.app.ui.LocalAppContainer
import com.vpsconsole.app.ui.components.EmptyState
import com.vpsconsole.app.ui.navigation.Routes

private data class ToolItem(val title: String, val subtitle: String, val icon: ImageVector, val route: (Long) -> String)

private val tools = listOf(
    ToolItem("Services", "systemd, processes, Docker", Icons.Filled.AdminPanelSettings, Routes::services),
    ToolItem("File manager", "browse and transfer via SFTP", Icons.Filled.Folder, Routes::files),
    ToolItem("Firewall", "ufw rules and status", Icons.Filled.Lock, Routes::firewall),
    ToolItem("Cron jobs", "scheduled tasks", Icons.Filled.HourglassEmpty, Routes::cron),
    ToolItem("Users", "accounts and sessions", Icons.Filled.People, Routes::users),
    ToolItem("Packages", "updates and upgrades", Icons.Filled.SystemUpdate, Routes::packages),
    ToolItem("Backups", "remote snapshots", Icons.Filled.Backup, Routes::backups),
    ToolItem("SSH tunnels", "local and remote forwarding", Icons.Filled.Tune, Routes::tunnels),
    ToolItem("Command library", "saved snippets", Icons.Filled.Terminal, Routes::scripts)
)

@Composable
fun ToolsScreen(serverId: Long, onNavigate: (String) -> Unit) {
    val container = LocalAppContainer.current
    val servers by container.serverRepo.servers.collectAsState(emptyList())
    val server = servers.firstOrNull { it.id == serverId }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Tools", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (server == null) {
            EmptyState("Select a server from the top bar to use these tools.")
        } else {
            Text(
                "Target: ${server.name} (${server.username}@${server.host})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 160.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(tools.size) { index ->
                    val tool = tools[index]
                    Card(
                        onClick = { onNavigate(tool.route(serverId)) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(tool.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(tool.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                tool.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
