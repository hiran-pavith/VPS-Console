package com.vpsconsole.app.data.secure

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Stores private keys, passwords and the optional app PIN in a Keystore-backed
 * EncryptedSharedPreferences file. Secrets never leave this store in plain text.
 */
class SecretsStore(context: Context) {
    private val masterKey = MasterKey.Builder(context.applicationContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        "vps_console_secrets",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun put(ref: String, value: String) {
        if (value.isEmpty()) prefs.edit().remove(ref).apply() else prefs.edit().putString(ref, value).apply()
    }

    fun get(ref: String): String? = prefs.getString(ref, null)

    fun remove(ref: String) = prefs.edit().remove(ref).apply()

    fun removeAll(refs: Collection<String>) {
        val editor = prefs.edit()
        refs.forEach(editor::remove)
        editor.apply()
    }

    fun has(ref: String): Boolean = prefs.contains(ref) && !get(ref).isNullOrEmpty()

    fun setPin(pin: String) {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val hash = hash(pin, salt)
        prefs.edit()
            .putString(PIN_SALT, Base64.getEncoder().encodeToString(salt))
            .putString(PIN_HASH, hash)
            .apply()
    }

    fun verifyPin(pin: String): Boolean {
        val saltEncoded = prefs.getString(PIN_SALT, null) ?: return false
        val expected = prefs.getString(PIN_HASH, null) ?: return false
        val salt = Base64.getDecoder().decode(saltEncoded)
        return hash(pin, salt) == expected
    }

    fun isPinConfigured(): Boolean =
        !prefs.getString(PIN_HASH, null).isNullOrEmpty()

    fun clearPin() = prefs.edit().remove(PIN_SALT).remove(PIN_HASH).apply()

    fun clearEverything() = prefs.edit().clear().apply()

    private fun hash(pin: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        return Base64.getEncoder().encodeToString(digest.digest(pin.toByteArray(Charsets.UTF_8)))
    }

    companion object {
        private const val PIN_SALT = "__pin_salt"
        private const val PIN_HASH = "__pin_hash"
        fun secretRefFor(serverId: Long) = "server_${serverId}_secret"
        fun passphraseRefFor(serverId: Long) = "server_${serverId}_passphrase"
    }
}
