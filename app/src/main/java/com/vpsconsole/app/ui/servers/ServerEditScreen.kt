package com.vpsconsole.app.ui.servers

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vpsconsole.app.core.isValidHost
import com.vpsconsole.app.core.isValidPort
import com.vpsconsole.app.data.model.AuthType
import com.vpsconsole.app.data.model.ServerProfile
import com.vpsconsole.app.ui.LocalAppContainer
import com.vpsconsole.app.ui.components.LabeledField
import com.vpsconsole.app.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ServerEditScreen(serverId: Long, onSaved: () -> Unit, onCancel: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val existing by container.serverRepo.observe(serverId).collectAsState(null)
    val scope = rememberCoroutineScope()

    var initialized by remember(serverId) { mutableStateOf(serverId == 0L) }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("root") }
    var authType by remember { mutableStateOf(AuthType.KEY) }
    var secret by remember { mutableStateOf("") }
    var keyFileName by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var monitoring by remember { mutableStateOf(true) }
    var cpuThreshold by remember { mutableStateOf("85") }
    var ramThreshold by remember { mutableStateOf("90") }
    var diskThreshold by remember { mutableStateOf("90") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }
            }.onSuccess { text ->
                val key = text?.trim().orEmpty()
                when {
                    key.isEmpty() -> error = "The selected key file is empty."
                    !key.startsWith("-----BEGIN") && !key.startsWith("ssh-") ->
                        error = "Unsupported key format. Use a PEM or OpenSSH private key."
                    else -> {
                        secret = key
                        keyFileName = uri.lastPathSegment ?: "private_key"
                        error = null
                    }
                }
            }.onFailure { throwable ->
                error = "Could not read key file: ${throwable.message}"
            }
        }
    }

    LaunchedEffect(existing) {
        val server = existing ?: return@LaunchedEffect
        if (initialized) return@LaunchedEffect
        name = server.name
        host = server.host
        port = server.port.toString()
        username = server.username
        authType = server.authType
        group = server.group
        note = server.note
        monitoring = server.monitoringEnabled
        cpuThreshold = server.cpuThreshold.toInt().toString()
        ramThreshold = server.ramThreshold.toInt().toString()
        diskThreshold = server.diskThreshold.toInt().toString()
        initialized = true
    }

    fun buildServer(): ServerProfile? {
        if (name.isBlank() || !isValidHost(host) || !isValidPort(port) || username.isBlank()) {
            error = "Name, valid host, port and username are required."
            return null
        }
        if (serverId == 0L && secret.isBlank()) {
            error = "Provide a password or private key."
            return null
        }
        return ServerProfile(
            id = serverId,
            name = name.trim(),
            host = host.trim(),
            port = port.toInt(),
            username = username.trim(),
            authType = authType,
            secretRef = existing?.secretRef ?: "",
            keyPassphraseRef = if (authType == AuthType.KEY) existing?.keyPassphraseRef else null,
            group = group.trim(),
            note = note.trim(),
            monitoringEnabled = monitoring,
            cpuThreshold = cpuThreshold.toFloatOrNull()?.coerceIn(1f, 100f) ?: 85f,
            ramThreshold = ramThreshold.toFloatOrNull()?.coerceIn(1f, 100f) ?: 90f,
            diskThreshold = diskThreshold.toFloatOrNull()?.coerceIn(1f, 100f) ?: 90f,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            lastConnectedAt = existing?.lastConnectedAt,
            hostKeyFingerprint = existing?.hostKeyFingerprint
        )
    }

    suspend fun save(): Long? {
        val server = buildServer() ?: return null
        val id = container.serverRepo.save(
            server,
            secret.takeIf { it.isNotBlank() },
            passphrase.takeIf { it.isNotBlank() }
        )
        container.settings.update { settings ->
            if (settings.selectedServerId == 0L) settings.copy(selectedServerId = id) else settings
        }
        return id
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            if (serverId == 0L) "Add a VPS" else "Edit ${existing?.name ?: "server"}",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        SectionCard(title = "Connection") {
            LabeledField("Display name", name, { name = it })
            LabeledField("Hostname or IP", host, { host = it })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LabeledField("SSH port", port, { port = it }, Modifier.weight(1f))
                LabeledField("Username", username, { username = it }, Modifier.weight(1f))
            }
            LabeledField("Group (optional)", group, { group = it })
        }

        SectionCard(title = "Authentication") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = authType == AuthType.KEY,
                    onClick = { authType = AuthType.KEY },
                    label = { Text("Private key") }
                )
                FilterChip(
                    selected = authType == AuthType.PASSWORD,
                    onClick = { authType = AuthType.PASSWORD },
                    label = { Text("Password") }
                )
            }
            if (authType == AuthType.KEY) {
                OutlinedButton(
                    onClick = { pickKey.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (keyFileName.isBlank()) "Upload private key file" else "Key file: $keyFileName")
                }
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text("Private key (PEM / OpenSSH)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    supportingText = { Text("Leave blank to keep the stored key when editing.") }
                )
                LabeledField(
                    "Key passphrase (optional)",
                    passphrase,
                    { passphrase = it },
                    isPassword = true
                )
            } else {
                LabeledField("Password", secret, { secret = it }, isPassword = true)
            }
            Text(
                "Credentials are encrypted with Android Keystore before being stored.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard(title = "Monitoring") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Enable monitoring", Modifier.weight(1f))
                Switch(checked = monitoring, onCheckedChange = { monitoring = it })
            }
            if (monitoring) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledField("CPU %", cpuThreshold, { cpuThreshold = it }, Modifier.weight(1f))
                    LabeledField("RAM %", ramThreshold, { ramThreshold = it }, Modifier.weight(1f))
                    LabeledField("Disk %", diskThreshold, { diskThreshold = it }, Modifier.weight(1f))
                }
            }
            existing?.hostKeyFingerprint?.let {
                Text("Known host key: $it", style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionCard(title = "Notes") {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    scope.launch { if (save() != null) onSaved() }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) { Text("Save") }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        busy = true
                        val id = save()
                        if (id != null) {
                            val result = container.ssh.connect(id)
                            error = result.exceptionOrNull()?.message ?: "Connection successful"
                        }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) { Text("Save & test") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
