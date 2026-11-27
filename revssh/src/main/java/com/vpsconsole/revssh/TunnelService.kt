package com.vpsconsole.revssh

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.net.InetSocketAddress
import java.security.PublicKey

/** SSHJ config without X25519/Ed25519 — SpongyCastle 1.58 has no JCA mappings for them. */
private class RevSshConfig : DefaultConfig() {
    init {
        setKeyExchangeFactories(
            keyExchangeFactories.filterNot { it.name.contains("curve25519", ignoreCase = true) }
        )
        setKeyAlgorithms(
            keyAlgorithms.filterNot { it.name.contains("ed25519", ignoreCase = true) }
        )
    }
}

class TunnelService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false
    private var client: SSHClient? = null
    private var forward: RemotePortForwarder.Forward? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!running) {
                    running = true
                    startForeground()
                    TunnelState.log("Starting reverse tunnel")
                    scope.launch { runTunnel() }
                }
            }
            ACTION_STOP -> {
                running = false
                TunnelState.status.value = "Stopping…"
                TunnelState.log("Stop requested")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        cleanup()
        scope.cancel()
        TunnelState.status.value = "Stopped"
        super.onDestroy()
    }

    private suspend fun runTunnel() {
        val configStore = ConfigStore(applicationContext)
        val config = configStore.load()
        val remotePort = config.remotePort.trim().toIntOrNull()
        val targetPort = config.targetPort.trim().toIntOrNull()
        val sshPort = config.port.trim().toIntOrNull() ?: 22
        val keepAlive = config.keepAlive.trim().toIntOrNull()?.coerceIn(5, 120) ?: 30

        if (config.host.isBlank() || config.username.isBlank() ||
            remotePort == null || remotePort !in 1..65535 ||
            targetPort == null || targetPort !in 1..65535
        ) {
            TunnelState.status.value = "Error: invalid configuration"
            TunnelState.log("Check host, username, remote port and target port")
            running = false
            stopSelf()
            return
        }

        while (running) {
            var connected = false
            try {
                TunnelState.status.value = "Connecting to ${config.host}:$sshPort…"
                val ssh = SSHClient(RevSshConfig())
                client = ssh
                ssh.transport.timeoutMs = 30_000
                ssh.addHostKeyVerifier(object : HostKeyVerifier {
                    override fun verify(hostname: String?, port: Int, key: PublicKey?): Boolean {
                        if (key == null) return false
                        val fingerprint = SecurityUtils.getFingerprint(key)
                        val algorithm = key.algorithm ?: "UNKNOWN"
                        val trusted = configStore.knownFingerprint(config.host, sshPort, algorithm)
                        if (trusted != null) return trusted == fingerprint
                        // Trust on first use.
                        configStore.trust(config.host, sshPort, algorithm, fingerprint)
                        TunnelState.log("Trusted new host key: $algorithm $fingerprint")
                        return true
                    }

                    override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> =
                        configStore.knownAlgorithms(config.host, sshPort)
                })

                ssh.connect(config.host.trim(), sshPort)
                ssh.connection.keepAlive.keepAliveInterval = keepAlive

                if (config.authType == "KEY") {
                    if (config.privateKey.isBlank()) throw IllegalStateException("No private key configured")
                    val passphrase = config.passphrase.takeIf { it.isNotEmpty() }
                        ?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                    ssh.authPublickey(config.username.trim(), ssh.loadKeys(config.privateKey.trim(), null, passphrase))
                } else {
                    ssh.authPassword(config.username.trim(), config.password)
                }
                if (!ssh.isAuthenticated) throw IllegalStateException("Authentication failed")

                val bindHost = config.remoteBindHost.trim()
                val fwd = if (bindHost.isEmpty()) {
                    RemotePortForwarder.Forward(remotePort)
                } else {
                    RemotePortForwarder.Forward(bindHost, remotePort)
                }
                ssh.remotePortForwarder.bind(
                    fwd,
                    SocketForwardingConnectListener(InetSocketAddress(config.targetHost.trim(), targetPort))
                )
                forward = fwd
                connected = true

                val displayBind = if (bindHost.isEmpty()) "localhost" else bindHost
                TunnelState.status.value =
                    "Connected — ${config.host}:$displayBind:$remotePort → ${config.targetHost}:$targetPort"
                TunnelState.log("Reverse tunnel active")
                updateNotification(TunnelState.status.value)

                while (running && ssh.isConnected) delay(1_000)
                if (running) throw IllegalStateException("SSH connection lost")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                TunnelState.status.value = "Error: ${e.message ?: "tunnel failed"}"
                TunnelState.log("Tunnel error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                if (connected) TunnelState.log("Tunnel disconnected")
                cleanup()
            }

            if (!running || !config.autoReconnect) break
            TunnelState.status.value = "Retrying in 5s…"
            delay(5_000)
        }

        TunnelState.status.value = "Stopped"
        stopSelf()
    }

    private fun cleanup() {
        val fwd = forward
        forward = null
        if (fwd != null) {
            runCatching { client?.remotePortForwarder?.cancel(fwd) }
        }
        runCatching { client?.disconnect() }
        client = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm?.getNotificationChannel(CHANNEL) == null) {
                nm?.createNotificationChannel(
                    NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
                        .apply { description = getString(R.string.channel_desc) }
                )
            }
        }
    }

    private fun startForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(TunnelState.status.value),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        runCatching { nm?.notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TunnelService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Reverse SSH Tunnel")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_START = "com.vpsconsole.revssh.START"
        const val ACTION_STOP = "com.vpsconsole.revssh.STOP"
        private const val CHANNEL = "revssh_tunnel"
        private const val NOTIFICATION_ID = 4242

        fun start(context: Context) {
            val intent = Intent(context, TunnelService::class.java).apply { action = ACTION_START }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TunnelService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(intent) }
        }
    }
}
