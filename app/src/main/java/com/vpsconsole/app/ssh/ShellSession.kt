package com.vpsconsole.app.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
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

    private val writeChannel = Channel<String>(Channel.UNLIMITED)

    init {
        scope.launch {
            val buffer = CharArray(2048)
            val reader = InputStreamReader(shell.inputStream, Charsets.UTF_8)
            try {
                while (!closed.value) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    if (read > 0) output.emit(String(buffer, 0, read))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors.emit(e.message ?: "Shell read failed")
            } finally {
                closed.value = true
            }
        }
        scope.launch {
            val buffer = CharArray(1024)
            val reader = InputStreamReader(shell.errorStream, Charsets.UTF_8)
            try {
                while (!closed.value) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    if (read > 0) errors.emit(String(buffer, 0, read))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors.emit(e.message ?: "Shell error read failed")
            }
        }
        // Writes are serialized on IO so a slow SSH window can never block the
        // Main thread (and therefore freeze Compose collection/rendering).
        scope.launch {
            for (text in writeChannel) {
                runCatching {
                    shell.outputStream.write(text.toByteArray(Charsets.UTF_8))
                    shell.outputStream.flush()
                }.onFailure { errors.tryEmit(it.message ?: "Write failed") }
            }
        }
    }

    fun write(text: String) {
        if (closed.value) return
        writeChannel.trySend(text)
    }

    fun resize(cols: Int, rows: Int) {
        runCatching { shell.changeWindowDimensions(cols, rows, 0, 0) }
    }

    fun close() {
        if (closed.value) return
        closed.value = true
        writeChannel.close()
        runCatching { shell.close() }
        runCatching { session.close() }
        scope.cancel()
    }
}
