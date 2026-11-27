package com.vpsconsole.app.ssh

import android.content.Context
import com.vpsconsole.app.data.model.MetricEntity
import com.vpsconsole.app.data.model.ServerProfile
import com.vpsconsole.app.data.model.TunnelEntity
import com.vpsconsole.app.data.model.TunnelType
import com.vpsconsole.app.data.repo.KnownHostRepository
import com.vpsconsole.app.data.repo.MetricRepository
import com.vpsconsole.app.data.repo.ServerRepository
import com.vpsconsole.app.data.settings.AppSettings
import com.vpsconsole.app.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * SSHJ uses SpongyCastle as its JCE provider on Android. SC 1.58 does not expose
 * X25519 or Ed25519 JCA mappings, so those algorithms are removed from negotiation.
 * ECDSA/RSA/DH/SHA-256 are provided by SC and remain enabled.
 */
private class AndroidSshConfig : DefaultConfig() {
    init {
        setKeyExchangeFactories(
            keyExchangeFactories.filterNot { it.name.contains("curve25519", ignoreCase = true) }
        )
        setKeyAlgorithms(
            keyAlgorithms.filterNot { it.name.contains("ed25519", ignoreCase = true) }
        )
    }
}

class SshManager(
    context: Context,
    private val serverRepo: ServerRepository,
    private val knownHostRepo: KnownHostRepository,
    private val metricRepo: MetricRepository,
    private val settings: SettingsStore
) {
    private val appContext = context.applicationContext
    private val handles = ConcurrentHashMap<Long, Handle>()
    private val activeTunnels = ConcurrentHashMap<Long, ActiveTunnel>()
    private val lastMetrics = ConcurrentHashMap<Long, MetricEntity>()
    private val collector = MetricsCollector()

    /** Number of in-flight exec/SFTP/probe operations per server. */
    private val inFlight = ConcurrentHashMap<Long, Int>()
    private fun markBusy(serverId: Long) { inFlight.compute(serverId) { _, n -> (n ?: 0) + 1 } }
    private fun markIdle(serverId: Long) {
        inFlight.compute(serverId) { _, n -> if (n == null || n <= 1) null else n - 1 }
    }
    private fun isBusy(serverId: Long) = (inFlight[serverId] ?: 0) > 0

    /** Per-server lock serializing connect/close/collect/shell state mutations. */
    private val serverLocks = ConcurrentHashMap<Long, Any>()
    private fun lockFor(serverId: Long): Any = serverLocks.computeIfAbsent(serverId) { Any() }

    private val _states = MutableStateFlow<Map<Long, ConnectionState>>(emptyMap())
    val states: StateFlow<Map<Long, ConnectionState>> = _states.asStateFlow()

    private val _events = MutableSharedFlow<SshEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SshEvent> = _events.asSharedFlow()

    private data class Handle(
        val server: ServerProfile,
        val client: SSHClient,
        var shell: ShellSession? = null
    )

    private data class ActiveTunnel(
        val serverId: Long,
        var forwarder: LocalPortForwarder? = null,
        var serverSocket: ServerSocket? = null,
        var forward: RemotePortForwarder.Forward? = null,
        var job: Job? = null
    )

    fun stateOf(serverId: Long): ConnectionState = states.value[serverId] ?: ConnectionState.DISCONNECTED

    fun isConnected(serverId: Long): Boolean =
        handles[serverId]?.client?.isConnected == true && handles[serverId]?.client?.isAuthenticated == true

    fun shellFor(serverId: Long): ShellSession? = handles[serverId]?.shell

    private fun setState(serverId: Long, state: ConnectionState, message: String = "") {
        _states.value = _states.value + (serverId to state)
        _events.tryEmit(SshEvent(serverId, state, message))
    }

    suspend fun connect(serverId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        // All suspendable DB reads happen before the lock. Only blocking SSHJ
        // work and the atomic handle swap run inside the critical section.
        val server = serverRepo.get(serverId)
            ?: return@withContext Result.failure(IllegalArgumentException("Server not found"))
        val secret = serverRepo.secretFor(server)
            ?: return@withContext Result.failure(IllegalStateException("No credential stored for this server"))
        if (secret.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No credential stored for this server"))
        }
        val passphrase = if (server.authType == com.vpsconsole.app.data.model.AuthType.KEY) {
            serverRepo.passphraseFor(server)
        } else null
        val policy = settings.settings.value.hostKeyPolicy
        val keepAliveSeconds = settings.settings.value.keepAliveSeconds
        val known = knownHostRepo.forHost(server.host, server.port)
        var verifiedFingerprint: String? = null

        val result = synchronized(lockFor(serverId)) {
            val current = handles[serverId]
            if (current != null && current.client.isConnected && current.client.isAuthenticated) {
                return@synchronized Result.success(Unit)
            }
            // Close only the exact stale handle observed; do not stop tunnels —
            // a reconnect must not tear down the user's active port forwards.
            if (current != null) {
                runCatching { current.shell?.close() }
                runCatching { current.client.disconnect() }
                handles.remove(serverId, current)
            }

            setState(serverId, ConnectionState.CONNECTING)
            val client = SSHClient(AndroidSshConfig())
            runCatching {
                client.transport.timeoutMs = 30_000
                client.addHostKeyVerifier(object : HostKeyVerifier {
                    override fun verify(hostname: String?, port: Int, key: PublicKey?): Boolean {
                        if (key == null) return false
                        val fingerprint = SecurityUtils.getFingerprint(key)
                        verifiedFingerprint = fingerprint
                        val algorithm = key.algorithm ?: "UNKNOWN"
                        val trusted = known.firstOrNull { it.algorithm == algorithm }
                        if (trusted != null) return trusted.fingerprint == fingerprint
                        if (policy == com.vpsconsole.app.data.model.HostKeyPolicy.STRICT) return false
                        return runCatching {
                            runBlocking(Dispatchers.IO) {
                                knownHostRepo.trust(server.host, server.port, algorithm, fingerprint)
                            }
                            true
                        }.getOrDefault(false)
                    }

                    override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> =
                        known.map { it.algorithm }.distinct()
                })

                client.connect(server.host, server.port)
                client.connection.keepAlive.keepAliveInterval = keepAliveSeconds

                if (server.authType == com.vpsconsole.app.data.model.AuthType.KEY) {
                    val passwordFinder = passphrase?.takeIf { it.isNotEmpty() }
                        ?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                    val keyProvider = client.loadKeys(secret.trim(), null, passwordFinder)
                    client.authPublickey(server.username, keyProvider)
                } else {
                    client.authPassword(server.username, secret)
                }
                if (!client.isAuthenticated) throw IllegalStateException("Authentication failed")

                handles[serverId] = Handle(server, client)
                setState(serverId, ConnectionState.CONNECTED)
            }.onFailure { error ->
                runCatching { client.disconnect() }
                setState(serverId, ConnectionState.FAILED, error.message ?: "Connection failed")
            }
        }
        if (result.isSuccess) {
            runCatching { serverRepo.touch(serverId, System.currentTimeMillis()) }
            verifiedFingerprint?.let { fingerprint ->
                runCatching { serverRepo.updateHostKeyFingerprint(serverId, fingerprint) }
            }
        }
        result
    }

    fun close(serverId: Long) {
        synchronized(lockFor(serverId)) {
            val handle = handles.remove(serverId)
            runCatching { handle?.shell?.close() }
            if (handle != null) handle.shell = null
            runCatching { handle?.client?.disconnect() }
            activeTunnels.filter { it.value.serverId == serverId }.keys.forEach(::stopTunnel)
            setState(serverId, ConnectionState.DISCONNECTED)
        }
    }

    /** Close just the interactive shell, keeping the SSH connection. */
    fun closeShell(serverId: Long) {
        synchronized(lockFor(serverId)) {
            val handle = handles[serverId] ?: return@synchronized
            runCatching { handle.shell?.close() }
            handle.shell = null
        }
    }

    /** Close all idle connections, but never interrupt a live shell, tunnel, or transfer. */
    fun closeAll() {
        val busyServers = activeTunnels.values.map { it.serverId }.toSet()
        handles.keys.toList().forEach { id ->
            if (id in busyServers || isBusy(id)) return@forEach
            val handle = handles[id]
            if (handle?.shell?.closed?.value == false) return@forEach
            close(id)
        }
    }

    suspend fun exec(serverId: Long, command: String, timeoutSeconds: Long = 45): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                markBusy(serverId)
                try {
                    val client = ensureConnected(serverId)
                    val session = client.startSession()
                    try {
                        val fullCommand = if (command.contains("2>&1")) command else "$command 2>&1"
                        val cmd = session.exec(fullCommand)
                        // Bound the read itself — cmd.join() after readText() cannot
                        // interrupt a command that never closes its stdout.
                        val output = withTimeout(timeoutSeconds * 1000L) {
                            cmd.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        }
                        runCatching { cmd.join(3, TimeUnit.SECONDS) }
                        output.trimEnd()
                    } finally {
                        runCatching { session.close() }
                    }
                } finally {
                    markIdle(serverId)
                }
            }
        }

    suspend fun <T> withSftp(serverId: Long, block: suspend (SFTPClient) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            runCatching {
                markBusy(serverId)
                try {
                    val client = ensureConnected(serverId)
                    val sftp = client.newSFTPClient()
                    try {
                        block(sftp)
                    } finally {
                        runCatching { sftp.close() }
                    }
                } finally {
                    markIdle(serverId)
                }
            }
        }

    suspend fun startShell(serverId: Long): Result<ShellSession> = withContext(Dispatchers.IO) {
        ensureConnected(serverId)
        synchronized(lockFor(serverId)) {
            runCatching {
                val handle = handles[serverId]
                    ?: throw IllegalStateException("SSH connection is not available")
                handle.shell?.let { existing ->
                    if (!existing.closed.value) return@runCatching existing
                    runCatching { existing.close() }
                    handle.shell = null
                }
                val session = ShellSession(handle.client)
                // Only publish if the handle is still the one we authenticated.
                if (handles[serverId] !== handle) {
                    runCatching { session.close() }
                    throw IllegalStateException("SSH connection changed during shell start")
                }
                handle.shell = session
                session
            }
        }
    }

    suspend fun collect(serverId: Long): Result<MetricEntity> = withContext(Dispatchers.IO) {
        // Probe outside the lock, but make the baseline swap atomic so concurrent
        // worker/UI refreshes cannot compute deltas against the same counters.
        val rawResult = runCatching {
            markBusy(serverId)
            try {
                ensureConnected(serverId).let { client ->
                    val session = client.startSession()
                    try {
                        val cmd = session.exec(MetricsCollector.PROBE_COMMAND)
                        withTimeout(30_000L) {
                            cmd.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        }
                    } finally {
                        runCatching { session.close() }
                    }
                } finally {
                    markIdle(serverId)
                }
            }
        }
        rawResult.mapCatching { raw ->
            val metric = synchronized(lockFor(serverId)) {
                val parsed = collector.parse(raw, serverId, lastMetrics[serverId], System.currentTimeMillis())
                lastMetrics[serverId] = parsed
                parsed
            }
            metricRepo.add(metric)
            metric
        }
    }

    suspend fun startTunnel(tunnel: TunnelEntity): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (activeTunnels.containsKey(tunnel.id)) return@runCatching
            val client = ensureConnected(tunnel.serverId)
            val active = ActiveTunnel(tunnel.serverId)
            if (tunnel.type == TunnelType.LOCAL) {
                val socket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("127.0.0.1", tunnel.localPort))
                }
                val params = Parameters(
                    "127.0.0.1", tunnel.localPort, tunnel.targetHost, tunnel.targetPort
                )
                val forwarder = client.newLocalPortForwarder(params, socket)
                val job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    runCatching { forwarder.listen() }
                }
                active.forwarder = forwarder
                active.serverSocket = socket
                active.job = job
            } else {
                val bindHost = tunnel.remoteBindHost.trim()
                val forward = if (bindHost.isEmpty()) {
                    RemotePortForwarder.Forward(tunnel.remotePort)
                } else {
                    RemotePortForwarder.Forward(bindHost, tunnel.remotePort)
                }
                active.forward = client.remotePortForwarder.bind(
                    forward,
                    SocketForwardingConnectListener(
                        InetSocketAddress(tunnel.targetHost, tunnel.targetPort)
                    )
                )
            }
            activeTunnels[tunnel.id] = active
        }
    }

    fun stopTunnel(tunnelId: Long) {
        val active = activeTunnels.remove(tunnelId) ?: return
        runCatching { active.forwarder?.close() }
        runCatching { active.serverSocket?.close() }
        active.forward?.let { forward ->
            handles[active.serverId]?.client?.let { runCatching { it.remotePortForwarder.cancel(forward) } }
        }
        active.job?.cancel()
    }

    fun isTunnelRunning(tunnelId: Long): Boolean = activeTunnels.containsKey(tunnelId)

    private suspend fun ensureConnected(serverId: Long): SSHClient {
        handles[serverId]?.let { handle ->
            if (handle.client.isConnected && handle.client.isAuthenticated) return handle.client
        }
        val result = connect(serverId)
        if (result.isFailure) throw result.exceptionOrResultFailure()
        return handles[serverId]?.client ?: throw IllegalStateException("SSH connection is not available")
    }

    private fun Result<*>.exceptionOrResultFailure(): Throwable =
        exceptionOrNull() ?: IllegalStateException("Connection failed")

    val currentSettings: AppSettings get() = settings.settings.value
}
