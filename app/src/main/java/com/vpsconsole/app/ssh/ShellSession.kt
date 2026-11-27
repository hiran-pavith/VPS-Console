package com.vpsconsole.app.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.InputStreamReader

/** An interactive remote shell attached to one SSH session. */
class ShellSession(client: SSHClient) {
    private val session: Session = client.startSession().also { it.allocateDefaultPTY() }
    private val shell: Session.Shell = session.startShell()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Replay is essential: the reader starts in init(), but the Compose
    // collector only subscribes after startShell() returns. Without replay the
    // login banner and first prompt are emitted before anyone is listening and
    // are lost, which makes the terminal look dead until the first command.
    // DROP_OLDEST keeps the freshest terminal text when the UI is briefly busy.
    val output = MutableSharedFlow<String>(
        replay = 256,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val errors = MutableSharedFlow<String>(
        replay = 32,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val closed = MutableStateFlow(false)

    init {
        scope.launch {
            val buffer = CharArray(2048)
            val reader = InputStreamReader(shell.inputStream, Charsets.UTF_8)
            runCatching {
                while (!closed.value) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    if (read > 0) output.emit(String(buffer, 0, read))
                }
            }.onFailure { errors.emit(it.message ?: "Shell read failed") }
            closed.value = true
        }
        scope.launch {
            val buffer = CharArray(1024)
            val reader = InputStreamReader(shell.errorStream, Charsets.UTF_8)
            runCatching {
                while (!closed.value) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    if (read > 0) errors.emit(String(buffer, 0, read))
                }
            }.onFailure { errors.emit(it.message ?: "Shell error read failed") }
        }
    }

    fun write(text: String) {
        runCatching {
            shell.outputStream.write(text.toByteArray(Charsets.UTF_8))
            shell.outputStream.flush()
        }.onFailure { errors.tryEmit(it.message ?: "Write failed") }
    }

    fun resize(cols: Int, rows: Int) {
        runCatching { shell.changeWindowDimensions(cols, rows, 0, 0) }
    }

    fun close() {
        closed.value = true
        runCatching { shell.close() }
        runCatching { session.close() }
        scope.cancel()
    }
}
