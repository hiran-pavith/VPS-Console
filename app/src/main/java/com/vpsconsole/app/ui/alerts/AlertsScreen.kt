package com.vpsconsole.app.ui.alerts

import com.vpsconsole.app.ui.components.SingleLineTextButton as TextButton
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.vpsconsole.app.ui.components.SingleLineOutlinedButton as OutlinedButton
import androidx.compose.material3.Switch
import com.vpsconsole.app.ui.components.AutoText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vpsconsole.app.core.formatTimestamp
import java.util.Locale
import com.vpsconsole.app.ui.LocalAppContainer
import com.vpsconsole.app.ui.components.EmptyState
import com.vpsconsole.app.ui.components.SectionCard
import kotlinx.coroutines.launch

@Composable
fun AlertsScreen(onOpenServer: (Long) -> Unit) {
    val container = LocalAppContainer.current
    val events by container.alertRepo.events.collectAsState(emptyList())
    val rules by container.alertRepo.rules.collectAsState(emptyList())
    val servers by container.serverRepo.servers.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var unreadOnly by remember { mutableStateOf(false) }

    val names = servers.associateBy({ it.id }, { it.name })
    val visibleEvents = if (unreadOnly) events.filter { !it.acknowledged } else events

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("History") })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Rules") })
            FilterChip(selected = unreadOnly, onClick = { unreadOnly = !unreadOnly }, label = { Text("Unread only") })
        }

        if (tab == 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { scope.launch { container.alertRepo.clearEvents() } },
                    modifier = Modifier.weight(1f)
                ) { Text("Clear history") }
            }
            if (visibleEvents.isEmpty()) {
                EmptyState("No alerts recorded yet.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visibleEvents, key = { it.id }) { event ->
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Text(
                                        "${event.serverName} · ${event.kind}",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        formatTimestamp(event.timestamp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(event.message, style = MaterialTheme.typography.bodyMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButtonCompat("Monitor") { onOpenServer(event.serverId) }
                                    if (!event.acknowledged) {
                                        TextButtonCompat("Acknowledge") {
                                            scope.launch { container.alertRepo.acknowledge(event) }
                                        }
                                    }
                                    TextButtonCompat("Delete") {
                                        scope.launch { container.alertRepo.deleteEvent(event) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            if (rules.isEmpty()) {
                EmptyState("No alert rules. Add rules in the Monitoring screen.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rules, key = { it.id }) { rule ->
                        SectionCard {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(names[rule.serverId] ?: "Server ${rule.serverId}", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${rule.kind} ≥ ${String.format(Locale.US, "%.0f", rule.threshold)}% · cooldown ${rule.cooldownMinutes}m",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = rule.enabled,
                                    onCheckedChange = { scope.launch { container.alertRepo.saveRule(rule.copy(enabled = it)) } }
                                )
                                IconButton(
                                    onClick = { scope.launch { container.alertRepo.deleteRule(rule) } },
                                    modifier = androidx.compose.ui.Modifier.semantics {
                                        contentDescription = "Delete alert rule"
                                    }
                                ) { Text("✕") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextButtonCompat(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label) }
}
