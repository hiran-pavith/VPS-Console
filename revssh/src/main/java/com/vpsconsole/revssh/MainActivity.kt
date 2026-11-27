package com.vpsconsole.revssh

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                100
            )
        }

        val store = ConfigStore(this)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TunnelScreen(
                        initial = store.load(),
                        onSave = { store.save(it) },
                        onStart = { config ->
                            runCatching { store.save(config) }
                                .onFailure { TunnelState.log("Save failed: ${it.message}") }
                            if (!validate(config)) return@TunnelScreen
                            TunnelState.clearLogs()
                            TunnelState.status.value = "Starting service…"
                            runCatching { TunnelService.start(this) }
                                .onFailure { e ->
                                    TunnelState.status.value = "Error: start failed — ${e.message}"
                                    TunnelState.log("Start failed [${e.javaClass.simpleName}]: ${e.message}")
                                }
                        },
                        onStop = { TunnelService.stop(this) }
                    )
                }
            }
        }
    }

    private fun validate(config: TunnelConfig): Boolean {
        val rp = config.remotePort.trim().toIntOrNull()
        val tp = config.targetPort.trim().toIntOrNull()
        val error = when {
            config.host.isBlank() -> "server host is required"
            config.username.isBlank() -> "SSH username is required"
            rp == null || rp !in 1..65535 -> "remote port must be 1-65535"
            tp == null || tp !in 1..65535 -> "target port must be 1-65535"
            config.authType == "KEY" && config.privateKey.isBlank() -> "private key is required"
            config.authType == "PASSWORD" && config.password.isBlank() -> "password is required"
            else -> null
        }
        if (error != null) {
            TunnelState.status.value = "Error: $error"
            TunnelState.log("Configuration error: $error")
            return false
        }
        return true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TunnelScreen(
    initial: TunnelConfig,
    onSave: (TunnelConfig) -> Unit,
    onStart: (TunnelConfig) -> Unit,
    onStop: () -> Unit
) {
    var config by remember { mutableStateOf(initial) }
    val status by TunnelState.status.collectAsState()
    val logs by TunnelState.logs.collectAsState()
    val running = status != "Stopped" && !status.startsWith("Error")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {

        TopAppBar(title = { Text("Reverse SSH Tunnel") })

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(status, style = MaterialTheme.typography.titleMedium,
                    color = if (running) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
                if (logs.isNotEmpty()) {
                    Text(
                        logs.takeLast(40).joinToString("\n"),
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SSH server", style = MaterialTheme.typography.titleSmall)
                Field("Host", config.host) { config = config.copy(host = it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Port", config.port, Modifier.weight(1f)) { config = config.copy(port = it) }
                    Field("Username", config.username, Modifier.weight(2f)) { config = config.copy(username = it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = config.authType == "KEY", onClick = {
                        config = config.copy(authType = "KEY")
                    }, label = { Text("Private key") })
                    FilterChip(selected = config.authType == "PASSWORD", onClick = {
                        config = config.copy(authType = "PASSWORD")
                    }, label = { Text("Password") })
                }
                if (config.authType == "KEY") {
                    Field("Private key (PEM / OpenSSH)", config.privateKey, minLines = 3) {
                        config = config.copy(privateKey = it)
                    }
                    Field("Key passphrase (optional)", config.passphrase, password = true) {
                        config = config.copy(passphrase = it)
                    }
                } else {
                    Field("Password", config.password, password = true) {
                        config = config.copy(password = it)
                    }
                }
                Field("Keep-alive seconds", config.keepAlive) { config = config.copy(keepAlive = it) }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Reverse tunnel", style = MaterialTheme.typography.titleSmall)
                Text("Remote port on the VPS forwards back to a host/port reachable from this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Field("Remote bind host (blank = VPS localhost, 0.0.0.0 = public)",
                    config.remoteBindHost) { config = config.copy(remoteBindHost = it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Remote port", config.remotePort, Modifier.weight(1f)) {
                        config = config.copy(remotePort = it)
                    }
                    Field("Target host", config.targetHost, Modifier.weight(1f)) {
                        config = config.copy(targetHost = it)
                    }
                    Field("Target port", config.targetPort, Modifier.weight(1f)) {
                        config = config.copy(targetPort = it)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto-reconnect", Modifier.weight(1f))
                    Switch(checked = config.autoReconnect,
                        onCheckedChange = { config = config.copy(autoReconnect = it) })
                }
            }
        }

        if (status.startsWith("Error")) {
            Text(status, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onSave(config) }, Modifier.weight(1f)) { Text("Save") }
            if (running) {
                Button(onClick = onStop, Modifier.weight(1f)) { Text("Stop tunnel") }
            } else {
                Button(onClick = { onStart(config) }, Modifier.weight(1f)) { Text("Start tunnel") }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    minLines: Int = 1,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = minLines == 1,
        minLines = minLines,
        visualTransformation = if (password) PasswordVisualTransformation() else
            androidx.compose.ui.text.input.VisualTransformation.None
    )
}
