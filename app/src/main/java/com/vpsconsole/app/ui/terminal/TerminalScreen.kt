package com.vpsconsole.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpsconsole.app.core.TerminalBuffer
import com.vpsconsole.app.ssh.ShellSession
import com.vpsconsole.app.ui.LocalAppContainer
import kotlinx.coroutines.launch

@Composable
fun TerminalScreen(serverId: Long, onExit: () -> Unit = {}) {
    val container = LocalAppContainer.current
    val settings by container.settings.settings.collectAsState()
    val commands by container.commandRepo.forServer(serverId).collectAsState(emptyList())
    val buffer = remember(serverId) { TerminalBuffer() }
    var rendered by remember(serverId) { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Disconnected") }
    var shell by remember(serverId) { mutableStateOf<ShellSession?>(null) }
    val history = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableIntStateOf(0) } // history.size = no selection
    var reconnectKey by remember { mutableIntStateOf(0) }
    var reconnectAttempts by remember { mutableIntStateOf(0) }
    var suppressReconnect by remember(serverId) { mutableStateOf(false) }
    // A shell can finish starting after this screen has been disposed; never
    // publish such a shell, or it would keep running with no collector.
    var sessionActive by remember(serverId) { mutableStateOf(true) }
    var directTyping by remember(serverId) { mutableStateOf(true) }
    val inputFocus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(serverId, reconnectKey) {
        sessionActive = true
        suppressReconnect = false
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
        if (!sessionActive) {
            session.close()
            return@LaunchedEffect
        }
        shell = session
        reconnectAttempts = 0
        status = "Connected"
        buffer.append("[SSH session ready]\r\n")
        rendered = buffer.text
        kotlinx.coroutines.coroutineScope {
            launch {
                session.output.collect { chunk ->
                    buffer.append(chunk)
                    rendered = buffer.text
                }
            }
            launch {
                session.errors.collect { msg ->
                    // stderr is normal shell output, not a connection failure.
                    buffer.append("\r\n[stderr] $msg\r\n")
                    rendered = buffer.text
                }
            }
            launch {
                session.closed.collect { isClosed ->
                    if (!isClosed) return@collect
                    if (!status.startsWith("Error")) status = "Disconnected"
                    // A deliberate Disconnect must not be immediately undone by
                    // auto-reconnect.
                    if (suppressReconnect) return@collect
                    if (settings.autoReconnect && reconnectAttempts < 5) {
                        reconnectAttempts++
                        kotlinx.coroutines.delay(2_000)
                        // Re-check: the user may have tapped Disconnect during the delay.
                        if (suppressReconnect) return@collect
                        reconnectKey++
                    }
                }
            }
        }
    }

    DisposableEffect(serverId) {
        sessionActive = true
        onDispose {
            sessionActive = false
            container.ssh.closeShell(serverId)
        }
    }

    LaunchedEffect(rendered) {
        // Int.MAX_VALUE is clamped by ScrollState to the current max, ensuring
        // newly streamed lines are immediately visible instead of sitting just
        // below the viewport after a stale maxValue read.
        scroll.scrollTo(Int.MAX_VALUE)
    }

    fun send(text: String, appendHistory: Boolean = true) {
        if (text.isEmpty() || shell == null) return
        shell?.write(text)
        if (appendHistory) {
            history.add(text.trimEnd('\n', '\r'))
            historyIndex = history.size
        }
    }

    /** Send keystrokes straight to the PTY as the user types. */
    fun typeDirect(next: String) {
        val current = shell ?: run { input = next; return }
        val change = diffInput(input, next)
        repeat(change.backspaces) { current.write("\u007F") }
        if (change.added.isNotEmpty()) current.write(change.added)
        if (change.added.contains('\n')) {
            val line = next.trimEnd('\n', '\r')
            if (line.isNotBlank()) {
                history.add(line)
                historyIndex = history.size
            }
            input = ""
        } else {
            input = next
        }
    }

    /** In direct mode the characters are already in the PTY; only Enter is left. */
    fun submitInput() {
        if (directTyping) {
            if (input.isNotEmpty()) {
                shell?.write("\n")
                input = ""
            }
        } else {
            send(input.ifEndsWithNewline())
            input = ""
        }
    }

    fun runSavedCommand(command: String) {
        if (directTyping) input = ""
        send(command.ifEndsWithNewline())
    }

    fun recallHistory(index: Int) {
        val line = history.getOrNull(index).orEmpty()
        historyIndex = index
        if (directTyping) {
            // Clear the current shell line, then place the recalled command there.
            shell?.write("\u0015")
            if (line.isNotEmpty()) shell?.write(line)
            input = line
        } else {
            input = line
        }
    }

    val scheme = com.vpsconsole.app.ui.theme.TerminalScheme.from(settings.terminalColorScheme)
    Column(
        Modifier.fillMaxSize().padding(8.dp).background(androidx.compose.ui.graphics.Color(scheme.background)),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onExit) { Text("← Back") }
            Text(
                status,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = if (status.startsWith("Connected")) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = {
                suppressReconnect = true
                container.ssh.shellFor(serverId)?.close()
                container.ssh.close(serverId)
                status = "Disconnected"
                shell = null
            }) { Text("Disconnect") }
            OutlinedButton(onClick = { directTyping = !directTyping }) {
                Text(if (directTyping) "Direct: on" else "Direct: off")
            }
            OutlinedButton(onClick = { buffer.clear(); rendered = "" }) { Text("Clear") }
        }

        Card(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .clickable { inputFocus.requestFocus() },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Text(
                text = rendered.ifEmpty { "Connecting to server…" },
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = settings.terminalFontSize.sp,
                color = androidx.compose.ui.graphics.Color(scheme.foreground)
            )
        }

        OutlinedTextField(
            value = input,
            onValueChange = { next -> if (directTyping) typeDirect(next) else input = next },
            label = { Text(if (directTyping) "Type here · direct to shell" else "Command") },
            modifier = Modifier.fillMaxWidth().focusRequester(inputFocus),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { submitInput() }),
            trailingIcon = {
                IconButton(onClick = { submitInput() }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                recallHistory(if (history.isEmpty()) 0 else (historyIndex - 1).coerceAtLeast(0))
            }, modifier = Modifier.weight(1f)) { Text("History ↑") }
            Button(onClick = {
                recallHistory((historyIndex + 1).coerceAtMost(history.size))
            }, modifier = Modifier.weight(1f)) { Text("History ↓") }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
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
                        runSavedCommand(command.command)
                    }) { Text(command.title) }
                }
            }
        }
    }
}

private fun String.ifEndsWithNewline(): String = if (endsWith("\n")) this else "$this\n"

private data class TerminalInputDiff(val backspaces: Int, val added: String)

/** Minimum edit between two text-field values, used for direct PTY typing. */
private fun diffInput(old: String, new: String): TerminalInputDiff {
    var prefix = 0
    while (prefix < old.length && prefix < new.length && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    while (
        suffix < old.length - prefix && suffix < new.length - prefix &&
        old[old.length - 1 - suffix] == new[new.length - 1 - suffix]
    ) suffix++
    return TerminalInputDiff(
        backspaces = old.length - prefix - suffix,
        added = new.substring(prefix, new.length - suffix)
    )
}
