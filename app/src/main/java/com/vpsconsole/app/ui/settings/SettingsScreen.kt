package com.vpsconsole.app.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vpsconsole.app.core.formatTimestamp
import com.vpsconsole.app.data.model.HostKeyPolicy
import com.vpsconsole.app.ui.LocalAppContainer
import com.vpsconsole.app.ui.components.ConfirmDialog
import com.vpsconsole.app.ui.components.SectionCard
import com.vpsconsole.app.ui.theme.AccentTheme
import com.vpsconsole.app.ui.theme.TerminalScheme
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val container = LocalAppContainer.current
    val settings by container.settings.settings.collectAsState()
    val knownHosts by container.knownHostRepo.hosts.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var showPinDialog by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var pinConfirm by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionCard(title = "Appearance · Theme") {
            Text("Mode", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
                    FilterChip(
                        selected = settings.themeMode == value,
                        onClick = { container.settings.update { it.copy(themeMode = value) } },
                        label = { Text(label) }
                    )
                }
            }
            Text("Accent color", style = MaterialTheme.typography.bodyMedium)
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AccentTheme.entries.forEach { theme ->
                    FilterChip(
                        selected = settings.accentColor.equals(theme.name, ignoreCase = true),
                        onClick = { container.settings.update { it.copy(accentColor = theme.name) } },
                        label = { Text(theme.label) }
                    )
                }
            }
        }

        SectionCard(title = "Terminal") {
            Text("Color scheme", style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TerminalScheme.entries.forEach { scheme ->
                    FilterChip(
                        selected = settings.terminalColorScheme.equals(scheme.name, ignoreCase = true),
                        onClick = { container.settings.update { it.copy(terminalColorScheme = scheme.name) } },
                        label = { Text(scheme.label) }
                    )
                }
            }
            Text("Font size: ${settings.terminalFontSize} sp")
            Slider(
                value = settings.terminalFontSize.toFloat(),
                onValueChange = { container.settings.update { s -> s.copy(terminalFontSize = it.toInt()) } },
                valueRange = 9f..24f,
                steps = 14
            )
        }

        SectionCard(title = "Dashboard & Servers") {
            SwitchRow("Compact server cards", settings.dashboardCompact) {
                container.settings.update { s -> s.copy(dashboardCompact = it) }
            }
            Text("Sort servers by", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("name" to "Name", "host" to "Host", "group" to "Group").forEach { (value, label) ->
                    FilterChip(
                        selected = settings.serverSortOrder == value,
                        onClick = { container.settings.update { it.copy(serverSortOrder = value) } },
                        label = { Text(label) }
                    )
                }
            }
        }

        SectionCard(title = "Monitoring") {
            Text("Refresh interval: ${settings.refreshSeconds} seconds")
            Slider(
                value = settings.refreshSeconds.toFloat(),
                onValueChange = { container.settings.update { s -> s.copy(refreshSeconds = it.toInt()) } },
                valueRange = 5f..120f,
                steps = 22
            )
            Text("Metric history: ${settings.metricHistoryHours} hours")
            Slider(
                value = settings.metricHistoryHours.toFloat(),
                onValueChange = { container.settings.update { s -> s.copy(metricHistoryHours = it.toInt()) } },
                valueRange = 1f..168f,
                steps = 23
            )
            SwitchRow("Notifications", settings.notificationsEnabled) {
                container.settings.update { s -> s.copy(notificationsEnabled = it) }
            }
            SwitchRow("Vibration", settings.vibration) {
                container.settings.update { s -> s.copy(vibration = it) }
            }
            SwitchRow("Auto-reconnect", settings.autoReconnect) {
                container.settings.update { s -> s.copy(autoReconnect = it) }
            }
            SwitchRow("Notification sound", settings.notificationSound) {
                container.settings.update { s -> s.copy(notificationSound = it) }
            }
            Text("Keep-alive interval: ${settings.keepAliveSeconds}s")
            Slider(
                value = settings.keepAliveSeconds.toFloat(),
                onValueChange = { container.settings.update { s -> s.copy(keepAliveSeconds = it.toInt()) } },
                valueRange = 5f..120f,
                steps = 23
            )
        }

        SectionCard(title = "Monitor gauges") {
            SwitchRow("Show CPU gauge", settings.showCpuGauge) {
                container.settings.update { s -> s.copy(showCpuGauge = it) }
            }
            SwitchRow("Show RAM gauge", settings.showRamGauge) {
                container.settings.update { s -> s.copy(showRamGauge = it) }
            }
            SwitchRow("Show Disk gauge", settings.showDiskGauge) {
                container.settings.update { s -> s.copy(showDiskGauge = it) }
            }
            SwitchRow("Show Network gauges", settings.showNetworkGauge) {
                container.settings.update { s -> s.copy(showNetworkGauge = it) }
            }
        }

        SectionCard(title = "Security") {
            Text("Host key policy", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HostKeyPolicy.entries.forEach { policy ->
                    FilterChip(
                        selected = settings.hostKeyPolicy == policy,
                        onClick = { container.settings.update { it.copy(hostKeyPolicy = policy) } },
                        label = { Text(if (policy == HostKeyPolicy.TOFU) "Trust on first use" else "Strict") }
                    )
                }
            }
            Text(
                "Strict mode rejects unknown host keys. Trust-on-first-use stores the first key seen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SwitchRow("App lock (PIN / biometrics)", settings.lockEnabled) { enabled ->
                if (enabled && !container.secrets.isPinConfigured()) showPinDialog = true
                else container.settings.update { it.copy(lockEnabled = enabled) }
            }
            if (container.secrets.isPinConfigured()) {
                OutlinedButton(onClick = { showPinDialog = true }) { Text("Change PIN") }
            }
            Text("Auto-lock after ${settings.autoLockMinutes} minutes")
            Slider(
                value = settings.autoLockMinutes.toFloat(),
                onValueChange = { container.settings.update { s -> s.copy(autoLockMinutes = it.toInt()) } },
                valueRange = 1f..120f,
                steps = 29
            )
            SwitchRow("Confirm dangerous commands", settings.confirmDangerousCommands) {
                container.settings.update { s -> s.copy(confirmDangerousCommands = it) }
            }
        }

        SectionCard(title = "Services tab") {
            Text("Default tab", style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("SERVICES", "PROCESSES", "DOCKER").forEach { value ->
                    FilterChip(
                        selected = settings.servicesDefaultTab == value,
                        onClick = { container.settings.update { it.copy(servicesDefaultTab = value) } },
                        label = { Text(value.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
            Text("Processes to show: ${settings.processesLimit}")
            Slider(
                value = settings.processesLimit.toFloat(),
                onValueChange = { container.settings.update { s -> s.copy(processesLimit = it.toInt()) } },
                valueRange = 10f..200f,
                steps = 18
            )
        }

        SectionCard(title = "File manager") {
            SwitchRow("Show hidden files", settings.showHiddenFiles) {
                container.settings.update { s -> s.copy(showHiddenFiles = it) }
            }
            Text("Default remote path", style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("home" to "User home", "root" to "Root (/)", "custom" to "Custom").forEach { (value, label) ->
                    FilterChip(
                        selected = settings.sftpDefaultPath == value,
                        onClick = { container.settings.update { it.copy(sftpDefaultPath = value) } },
                        label = { Text(label) }
                    )
                }
            }
            if (settings.sftpDefaultPath == "custom") {
                OutlinedTextField(
                    value = settings.sftpCustomPath,
                    onValueChange = { container.settings.update { s -> s.copy(sftpCustomPath = it) } },
                    label = { Text("Custom path") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }

        SectionCard(title = "Known host keys") {
            if (knownHosts.isEmpty()) {
                Text("No trusted host keys stored.", style = MaterialTheme.typography.bodySmall)
            } else {
                knownHosts.forEach { host ->
                    Column(Modifier.fillMaxWidth()) {
                        Text("${host.host}:${host.port} · ${host.algorithm}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            host.fingerprint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Added ${formatTimestamp(host.addedAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = {
                            scope.launch { container.knownHostRepo.forget(host.host, host.port) }
                        }) { Text("Forget") }
                    }
                }
            }
        }

        SectionCard(title = "Data") {
            OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Clear alert history")
            }
            Text(
                "Server credentials are encrypted using Android Keystore and are never sent anywhere except the VPS you configure.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text("VPS Console 1.0.0 · Direct SSH over TCP", style = MaterialTheme.typography.labelSmall)
    }

    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text("Set app PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { pinInput = it.filter(Char::isDigit).take(8) },
                        label = { Text("PIN (4–8 digits)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation()
                    )
                    OutlinedTextField(
                        value = pinConfirm,
                        onValueChange = { pinConfirm = it.filter(Char::isDigit).take(8) },
                        label = { Text("Confirm PIN") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation()
                    )
                    pinError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when {
                        pinInput.length < 4 -> pinError = "PIN must be at least 4 digits"
                        pinInput != pinConfirm -> pinError = "PINs do not match"
                        else -> {
                            container.secrets.setPin(pinInput)
                            container.settings.update { it.copy(lockEnabled = true) }
                            pinInput = ""; pinConfirm = ""; pinError = null; showPinDialog = false
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showPinDialog = false }) { Text("Cancel") } }
        )
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear alert history?",
            text = "All recorded alert events will be deleted. Rules are kept.",
            confirmLabel = "Clear",
            onConfirm = { scope.launch { container.alertRepo.clearEvents() } },
            onDismiss = { confirmClear = false }
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
