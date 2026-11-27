package com.vpsconsole.app.sync

import android.content.Context
import android.net.Uri
import com.vpsconsole.app.AppContainer
import com.vpsconsole.app.auth.AuthManager
import com.vpsconsole.app.data.model.AuthType
import com.vpsconsole.app.data.model.CommandEntity
import com.vpsconsole.app.data.model.NoteEntity
import com.vpsconsole.app.data.model.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class SyncSummary(val servers: Int, val commands: Int, val notes: Int) {
    override fun toString() = "Imported $servers server(s), $commands command(s), $notes note(s)"
}

/**
 * Exports servers, commands and notes into a portable encrypted bundle. The
 * passphrase is supplied by the user so the same file can be restored on
 * another device; Google Drive is only a transport, not the encryption key.
 */
class SyncManager(
    private val context: Context,
    private val container: AppContainer,
    private val auth: AuthManager
) {
    suspend fun exportEncrypted(passphrase: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(passphrase.length >= 6) { "Sync passphrase must be at least 6 characters" }
            val bundle = JSONObject()
                .put("format", FORMAT)
                .put("version", 1)
                .put("exportedAt", System.currentTimeMillis())
            val servers = JSONArray()
            container.serverRepo.all().forEach { server ->
                servers.put(
                    JSONObject()
                        .put("name", server.name)
                        .put("host", server.host)
                        .put("port", server.port)
                        .put("username", server.username)
                        .put("authType", server.authType.name)
                        .put("group", server.group)
                        .put("colorArgb", server.colorArgb)
                        .put("note", server.note)
                        .put("monitoringEnabled", server.monitoringEnabled)
                        .put("cpuThreshold", server.cpuThreshold.toDouble())
                        .put("ramThreshold", server.ramThreshold.toDouble())
                        .put("diskThreshold", server.diskThreshold.toDouble())
                        .put("hostKeyFingerprint", server.hostKeyFingerprint.orEmpty())
                        .put("secret", container.serverRepo.secretFor(server).orEmpty())
                        .put("passphrase", container.serverRepo.passphraseFor(server).orEmpty())
                )
            }
            bundle.put("servers", servers)

            val commands = JSONArray()
            container.commandRepo.all().forEach { command ->
                commands.put(
                    JSONObject()
                        .put("title", command.title)
                        .put("command", command.command)
                        .put("confirm", command.confirm)
                        .put("useCount", command.useCount)
                )
            }
            bundle.put("commands", commands)

            val notes = JSONArray()
            container.noteRepo.all().forEach { note ->
                notes.put(
                    JSONObject()
                        .put("title", note.title)
                        .put("body", note.body)
                        .put("tags", note.tags)
                        .put("serverId", note.serverId)
                )
            }
            bundle.put("notes", notes)

            val settings = container.settings.settings.value
            bundle.put(
                "settings",
                JSONObject()
                    .put("selectedServerId", settings.selectedServerId)
                    .put("themeMode", settings.themeMode)
                    .put("accentColor", settings.accentColor)
                    .put("terminalFontSize", settings.terminalFontSize)
                    .put("monitorWidgetOrder", settings.monitorWidgetOrder)
                    .put("monitorWidgetSize", settings.monitorWidgetSize)
            )
            encrypt(bundle.toString(), passphrase)
        }
    }

    suspend fun importEncrypted(payload: String, passphrase: String): Result<SyncSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(passphrase.length >= 6) { "Sync passphrase must be at least 6 characters" }
                val json = JSONObject(decrypt(payload, passphrase))
                if (json.optString("format") != FORMAT) throw IOException("Unknown sync file format")

                val existingServers = container.serverRepo.all()
                var serverCount = 0
                val serverArray = json.optJSONArray("servers") ?: JSONArray()
                for (i in 0 until serverArray.length()) {
                    val item = serverArray.getJSONObject(i)
                    val host = item.optString("host").trim()
                    val username = item.optString("username").trim()
                    if (host.isBlank() || username.isBlank()) continue
                    val port = item.optInt("port", 22).coerceIn(1, 65535)
                    val authType = runCatching { AuthType.valueOf(item.optString("authType", "KEY")) }
                        .getOrDefault(AuthType.KEY)
                    val match = existingServers.firstOrNull {
                        it.host.equals(host, ignoreCase = true) && it.port == port &&
                            it.username.equals(username, ignoreCase = true)
                    }
                    val profile = ServerProfile(
                        id = match?.id ?: 0L,
                        name = item.optString("name").ifBlank { "$username@$host" },
                        host = host,
                        port = port,
                        username = username,
                        authType = authType,
                        secretRef = match?.secretRef.orEmpty(),
                        keyPassphraseRef = match?.keyPassphraseRef,
                        group = item.optString("group"),
                        colorArgb = item.optLong("colorArgb", 0xFF3FB950),
                        note = item.optString("note"),
                        monitoringEnabled = item.optBoolean("monitoringEnabled", true),
                        cpuThreshold = item.optDouble("cpuThreshold", 85.0).toFloat().coerceIn(1f, 100f),
                        ramThreshold = item.optDouble("ramThreshold", 90.0).toFloat().coerceIn(1f, 100f),
                        diskThreshold = item.optDouble("diskThreshold", 90.0).toFloat().coerceIn(1f, 100f),
                        hostKeyFingerprint = item.optString("hostKeyFingerprint").ifBlank { null }
                    )
                    container.serverRepo.save(
                        profile,
                        item.optString("secret"),
                        item.optString("passphrase")
                    )
                    serverCount++
                }

                val existingCommands = container.commandRepo.all()
                var commandCount = 0
                val commandArray = json.optJSONArray("commands") ?: JSONArray()
                for (i in 0 until commandArray.length()) {
                    val item = commandArray.getJSONObject(i)
                    val title = item.optString("title").trim()
                    val command = item.optString("command").trim()
                    if (title.isBlank() || command.isBlank()) continue
                    if (existingCommands.any {
                        it.title.equals(title, ignoreCase = true) && it.command == command
                    }) continue
                    container.commandRepo.save(
                        CommandEntity(
                            title = title,
                            command = command,
                            confirm = item.optBoolean("confirm", false),
                            useCount = item.optInt("useCount", 0).coerceAtLeast(0)
                        )
                    )
                    commandCount++
                }

                val existingNotes = container.noteRepo.all()
                var noteCount = 0
                val noteArray = json.optJSONArray("notes") ?: JSONArray()
                for (i in 0 until noteArray.length()) {
                    val item = noteArray.getJSONObject(i)
                    val title = item.optString("title").trim()
                    if (title.isBlank()) continue
                    val match = existingNotes.firstOrNull { it.title.equals(title, ignoreCase = true) }
                    container.noteRepo.save(
                        NoteEntity(
                            id = match?.id ?: 0L,
                            title = title,
                            body = item.optString("body"),
                            tags = item.optString("tags"),
                            serverId = match?.serverId ?: item.optLong("serverId", 0L)
                        )
                    )
                    noteCount++
                }

                json.optJSONObject("settings")?.let { s ->
                    container.settings.update { current ->
                        current.copy(
                            selectedServerId = s.optLong("selectedServerId", current.selectedServerId),
                            themeMode = s.optString("themeMode", current.themeMode),
                            accentColor = s.optString("accentColor", current.accentColor),
                            terminalFontSize = s.optInt("terminalFontSize", current.terminalFontSize)
                                .coerceIn(9, 24),
                            monitorWidgetOrder = s.optString("monitorWidgetOrder", current.monitorWidgetOrder),
                            monitorWidgetSize = s.optString("monitorWidgetSize", current.monitorWidgetSize)
                        )
                    }
                }
                SyncSummary(serverCount, commandCount, noteCount)
            }
        }

    suspend fun writeToFile(uri: Uri, payload: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val out = context.contentResolver.openOutputStream(uri)
                ?: throw IOException("Cannot open the destination file")
            out.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        }
    }

    suspend fun readFromFile(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot open the selected file")
            input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }

    suspend fun uploadToGoogle(payload: String): Result<String> =
        auth.uploadToDrive(DRIVE_FILE_NAME, payload)

    suspend fun downloadFromGoogle(passphrase: String): Result<SyncSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = auth.downloadFromDrive(DRIVE_FILE_NAME).getOrThrow()
            importEncrypted(payload, passphrase).getOrThrow()
        }
    }

    private fun encrypt(plain: String, passphrase: String): String {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return listOf(
            MAGIC,
            Base64.getEncoder().encodeToString(salt),
            Base64.getEncoder().encodeToString(iv),
            Base64.getEncoder().encodeToString(cipherText)
        ).joinToString("\n")
    }

    private fun decrypt(payload: String, passphrase: String): String {
        val lines = payload.trim().lines()
        if (lines.size != 4 || lines[0] != MAGIC) throw IOException("Not a VPS Console sync file")
        val salt = Base64.getDecoder().decode(lines[1])
        val iv = Base64.getDecoder().decode(lines[2])
        val cipherText = Base64.getDecoder().decode(lines[3])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(
            passphrase.toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            256
        )
        val secret = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        return SecretKeySpec(secret.encoded, "AES")
    }

    companion object {
        private const val FORMAT = "vps-console-sync"
        private const val MAGIC = "VPSCONSOLE1"
        private const val PBKDF2_ITERATIONS = 120_000
        const val DRIVE_FILE_NAME = "vps-console-sync.vpsc"
    }
}
