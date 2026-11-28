package com.vpsconsole.app.ui.sync

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.vpsconsole.app.ui.components.SingleLineButton as Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vpsconsole.app.ui.LocalAppContainer
import com.vpsconsole.app.ui.components.BusyIndicator
import com.vpsconsole.app.ui.components.SectionCard
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SyncScreen() {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authState by container.auth.state.collectAsState()
    var passphrase by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun fail(error: Throwable?): String = error?.message ?: "Unknown error"

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            status = runCatching {
                val payload = container.sync.exportEncrypted(passphrase).getOrThrow()
                container.sync.writeToFile(uri, payload).getOrThrow()
                "Encrypted sync file saved"
            }.getOrElse { "Export failed: ${fail(it)}" }
            busy = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            status = runCatching {
                val payload = container.sync.readFromFile(uri).getOrThrow()
                val summary = container.sync.importEncrypted(payload, passphrase).getOrThrow()
                summary.toString()
            }.getOrElse { "Import failed: ${fail(it)}" }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Account & Sync", style = MaterialTheme.typography.headlineSmall)
        SectionCard(title = "Google account") {
            if (authState.hasSession) {
                Text(
                    when {
                        authState.isGoogle -> authState.email.ifBlank { authState.displayName }
                        else -> "Local device session"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Text("Provider: ${authState.provider}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { container.auth.signOut() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign out")
                }
            } else {
                Button(
                    onClick = { container.auth.startGoogleLogin(context) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Sign in with Google") }
            }
            Text(
                "Google Drive is used only to store one encrypted appDataFolder file. Your servers, keys, commands and notes stay encrypted with the passphrase below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard(title = "Encryption passphrase") {
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Sync passphrase") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true
            )
            Text(
                "Use the same passphrase on every device. It is not stored in the app; losing it means the sync file cannot be restored.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard(title = "Sync file") {
            Button(
                onClick = {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    exportLauncher.launch("vps-console-sync-$stamp.vpsc")
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && passphrase.length >= 6
            ) { Text("Export encrypted file") }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && passphrase.length >= 6
            ) { Text("Import encrypted file") }
            Text(
                "You can save to or open from Google Drive with the system file picker.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard(title = "Google Drive sync") {
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        status = runCatching {
                            val payload = container.sync.exportEncrypted(passphrase).getOrThrow()
                            val id = container.sync.uploadToGoogle(payload).getOrThrow()
                            "Uploaded encrypted sync file (Drive id $id)"
                        }.getOrElse { "Upload failed: ${fail(it)}" }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && passphrase.length >= 6 && authState.isGoogle
            ) { Text("Upload to Google Drive") }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        busy = true
                        status = runCatching {
                            container.sync.downloadFromGoogle(passphrase).getOrThrow().toString()
                        }.getOrElse { "Download failed: ${fail(it)}" }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && passphrase.length >= 6 && authState.isGoogle
            ) { Text("Download from Google Drive") }
            if (!authState.isGoogle) {
                Text("Sign in with Google to enable Drive sync.", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (busy) BusyIndicator()
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodyMedium)
    }
}
