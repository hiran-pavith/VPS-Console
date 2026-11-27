package com.vpsconsole.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpsconsole.app.core.TerminalBuffer
import com.vpsconsole.app.ssh.ShellSession
import com.vpsconsole.app.ui.LocalAppContainer
import kotlinx.coroutines.launch

@Composable
fun TerminalScreen(serverId: Long) {
    val container = LocalAppContainer.current
    val settings by container.settings.settings.collectAsState()
    val commands by container.commandRepo.forServer(serverId).collectAsState(emptyList())
    val buffer = remember(serverId) { TerminalBuffer() }
    var rendered by remember(serverId) { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Disconnected") }
    var shell by remember { mutableStateOf<ShellSession?>(null) }
    val history = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableIntStateOf(-1) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(serverId) {
        status = "Connecting…"
        val connect = container.ssh.connect(serverId)
        if (connect.isFailure) {
            status = "Failed: ${connect.exceptionOrNull()?.message ?: "unknown error"}"
            buffer.append("Connection failed: ${connect.exceptionOrNull()?.message}\r\n")
            rendered = buffer.text
            return@LaunchedEffect
        }
        val result = container.ssh.startShell(serverId)
        if (result.isFailure) {
            status = "Failed: ${result.exceptionOrNull()?.message ?: "unknown error"}"
            return@LaunchedEffect
        }
        val session = result.getOrThrow()
        shell = session
        status = "Connected"
        buffer.append("\r\n[SSH session ready]\r\n")
        rendered = buffer.text
        session.output.collect { chunk ->
            buffer.append(chunk)
            rendered = buffer.text
        }
        status = "Disconnected"
        shell = null
    }

    LaunchedEffect(rendered) {
        if (scroll.maxValue > 0) scroll.animateScrollTo(scroll.maxValue)
    }

    fun send(text: String, appendHistory: Boolean = true) {
        if (text.isEmpty()) return
        shell?.write(text)
        if (appendHistory) {
            history.add(text.trimEnd('\n', '\r'))
            historyIndex = history.size
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                status,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = if (status.startsWith("Connected")) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = {
                container.ssh.shellFor(serverId)?.close()
                container.ssh.close(serverId)
                status = "Disconnected"
                shell = null
            }) { Text("Disconnect") }
            OutlinedButton(onClick = { buffer.clear(); rendered = "" }) { Text("Clear") }
        }

        Card(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Text(
                text = rendered.ifEmpty { "Connecting to server…" },
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                fontFamily = FontFamily.Monospace,
                fontSize = settings.terminalFontSize.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "Ctrl+C" to "\u0003", "Tab" to "\t", "Esc" to "\u001B",
                "↑" to "\u001B[A", "↓" to "\u001B[B", "Ctrl+D" to "\u0004"
            ).forEach { (label, sequence) ->
                OutlinedButton(onClick = { send(sequence, appendHistory = false) }) { Text(label) }
            }
        }

        if (commands.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                commands.take(12).forEach { command ->
                    OutlinedButton(onClick = {
                        scope.launch { container.commandRepo.markUsed(command) }
                        send(command.command.ifEndsWithNewline())
                    }) { Text(command.title) }
                }
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Command") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onSend = {
                    val line = input.ifEmpty { "" }
                    send(line.ifEndsWithNewline())
                    input = ""
                }
            ),
            trailingIcon = {
                IconButton(onClick = {
                    send(input.ifEndsWithNewline())
                    input = ""
                }) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
            }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                historyIndex = (historyIndex - 1).coerceAtLeast(0)
                input = history.getOrNull(historyIndex).orEmpty()
            }, modifier = Modifier.weight(1f)) { Text("History ↑") }
            Button(onClick = {
                historyIndex = (historyIndex + 1).coerceAtMost(history.size)
                input = history.getOrNull(historyIndex).orEmpty()
            }, modifier = Modifier.weight(1f)) { Text("History ↓") }
        }
    }
}

private fun String.ifEndsWithNewline(): String = if (endsWith("\n")) this else "$this\n"
