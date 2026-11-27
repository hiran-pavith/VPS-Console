package com.vpsconsole.revssh

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow

/** Everything needed to establish one reverse SSH tunnel. Ports are strings so the UI can hold partial input. */
data class TunnelConfig(
    val host: String = "54.253.196.217",
    val port: String = "22",
    val username: String = "admin",
    val authType: String = "KEY", // KEY or PASSWORD
    val password: String = "",
    val privateKey: String = BuildConfig.DEFAULT_SERVER_KEY,
    val passphrase: String = "",
    val remoteBindHost: String = "",
    val remotePort: String = "",
    val targetHost: String = "127.0.0.1",
    val targetPort: String = "",
    val keepAlive: String = "30",
    val autoReconnect: Boolean = true
)

/** Persists configuration with EncryptedSharedPreferences, falling back to plain prefs if keystore init fails. */
class ConfigStore(context: Context) {
    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "revssh_config_v1",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse { context.getSharedPreferences("revssh_config_plain", Context.MODE_PRIVATE) }

    fun load(): TunnelConfig = TunnelConfig(
        host = prefs.getString("host", "54.253.196.217") ?: "54.253.196.217",
        port = prefs.getString("port", "22") ?: "22",
        username = prefs.getString("username", "admin") ?: "admin",
        authType = prefs.getString("auth_type", "KEY") ?: "KEY",
        password = prefs.getString("password", "") ?: "",
        privateKey = prefs.getString("private_key", BuildConfig.DEFAULT_SERVER_KEY) ?: BuildConfig.DEFAULT_SERVER_KEY,
        passphrase = prefs.getString("passphrase", "") ?: "",
        remoteBindHost = prefs.getString("remote_bind_host", "") ?: "",
        remotePort = prefs.getString("remote_port", "") ?: "",
        targetHost = prefs.getString("target_host", "127.0.0.1") ?: "127.0.0.1",
        targetPort = prefs.getString("target_port", "") ?: "",
        keepAlive = prefs.getString("keep_alive", "30") ?: "30",
        autoReconnect = prefs.getBoolean("auto_reconnect", true)
    )

    fun save(c: TunnelConfig) {
        prefs.edit().apply {
            putString("host", c.host)
            putString("port", c.port)
            putString("username", c.username)
            putString("auth_type", c.authType)
            putString("password", c.password)
            putString("private_key", c.privateKey)
            putString("passphrase", c.passphrase)
            putString("remote_bind_host", c.remoteBindHost)
            putString("remote_port", c.remotePort)
            putString("target_host", c.targetHost)
            putString("target_port", c.targetPort)
            putString("keep_alive", c.keepAlive)
            putBoolean("auto_reconnect", c.autoReconnect)
        }.apply()
    }

    /** TOFU host keys, stored as lines: host|port|algorithm|fingerprint. */
    fun knownFingerprint(host: String, port: Int, algorithm: String): String? {
        val lines = (prefs.getString("known_hosts", "") ?: "").lines()
        return lines.firstOrNull { line ->
            val parts = line.split("|")
            parts.size == 4 && parts[0] == host && parts[1] == "$port" && parts[2] == algorithm
        }?.let { it.split("|")[3] }
    }

    fun knownAlgorithms(host: String, port: Int): List<String> =
        (prefs.getString("known_hosts", "") ?: "").lines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size == 4 && parts[0] == host && parts[1] == "$port") parts[2] else null
        }.distinct()

    fun trust(host: String, port: Int, algorithm: String, fingerprint: String) {
        val entry = "$host|$port|$algorithm|$fingerprint"
        val lines = (prefs.getString("known_hosts", "") ?: "").lines().filter { it.isNotBlank() }
        if (entry in lines) return
        prefs.edit().putString("known_hosts", (lines + entry).joinToString("\n")).apply()
    }
}

/** Shared state between the foreground service and the UI. */
object TunnelState {
    val status = MutableStateFlow("Stopped")
    val logs = MutableStateFlow<List<String>>(emptyList())

    fun log(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        logs.value = (logs.value + "$time  $message").takeLast(200)
    }

    fun clearLogs() {
        logs.value = emptyList()
    }
}
