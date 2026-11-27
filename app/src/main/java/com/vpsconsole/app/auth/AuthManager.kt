package com.vpsconsole.app.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.vpsconsole.app.BuildConfig
import com.vpsconsole.app.data.secure.SecretsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

data class AuthState(
    val provider: String = "",
    val email: String = "",
    val displayName: String = "",
    val avatarUrl: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAt: Long = 0L,
    val isLoading: Boolean = false,
    val message: String = ""
) {
    val hasSession: Boolean get() = provider.isNotBlank()
    val isGoogle: Boolean get() = provider.equals("GOOGLE", ignoreCase = true)
}

/**
 * Google sign-in without a backend relay. Uses OAuth 2.0 authorization code +
 * PKCE and stores tokens in the same Keystore-backed encrypted preferences as
 * SSH secrets. Tokens are used here for Drive appDataFolder sync only.
 */
class AuthManager(private val secrets: SecretsStore) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    val isGoogleConfigured: Boolean get() = BuildConfig.GOOGLE_CLIENT_ID.isNotBlank()

    fun continueAsLocal() {
        save(
            AuthState(
                provider = "LOCAL",
                displayName = "Local user",
                email = "",
                message = "Using this device only"
            )
        )
    }

    fun signOut() {
        listOf(
            KEY_PROVIDER, KEY_EMAIL, KEY_NAME, KEY_AVATAR,
            KEY_ACCESS, KEY_REFRESH, KEY_EXPIRES, KEY_PKCE, KEY_OAUTH_STATE
        ).forEach(secrets::remove)
        _state.value = AuthState()
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = "")
    }

    fun startGoogleLogin(context: Context): Result<Unit> {
        if (!isGoogleConfigured) {
            _state.value = _state.value.copy(
                message = "Google sign-in is not configured. Add VPS_CONSOLE_GOOGLE_CLIENT_ID to local.properties."
            )
            return Result.failure(IllegalStateException("Google OAuth client ID is missing"))
        }
        val verifier = randomToken(32)
        val oauthState = randomToken(16)
        secrets.put(KEY_PKCE, verifier)
        secrets.put(KEY_OAUTH_STATE, oauthState)

        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        val uri = Uri.parse("https://accounts.google.com/o/oauth2/v2/auth").buildUpon()
            .appendQueryParameter("client_id", BuildConfig.GOOGLE_CLIENT_ID)
            .appendQueryParameter("redirect_uri", BuildConfig.GOOGLE_REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter(
                "scope",
                "openid email profile https://www.googleapis.com/auth/drive.appdata"
            )
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", oauthState)
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .build()
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            _state.value = _state.value.copy(isLoading = true, message = "Opening Google sign-in…")
        }.onFailure {
            _state.value = _state.value.copy(isLoading = false, message = it.message ?: "Cannot open browser")
        }
    }

    suspend fun handleRedirect(uri: Uri?) {
        if (uri == null) return
        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            val error = uri.getQueryParameter("error") ?: "Google sign-in was cancelled"
            _state.value = _state.value.copy(isLoading = false, message = error)
            return
        }
        val expectedState = secrets.get(KEY_OAUTH_STATE)
        if (expectedState.isNullOrBlank() || expectedState != uri.getQueryParameter("state")) {
            _state.value = _state.value.copy(isLoading = false, message = "Sign-in state mismatch. Try again.")
            return
        }
        _state.value = _state.value.copy(isLoading = true, message = "Completing Google sign-in…")
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val verifier = secrets.get(KEY_PKCE)
                    ?: throw IOException("PKCE verifier is missing. Start sign-in again.")
                val params = mutableMapOf(
                    "client_id" to BuildConfig.GOOGLE_CLIENT_ID,
                    "redirect_uri" to BuildConfig.GOOGLE_REDIRECT_URI,
                    "code" to code,
                    "code_verifier" to verifier,
                    "grant_type" to "authorization_code"
                )
                if (BuildConfig.GOOGLE_CLIENT_SECRET.isNotBlank()) {
                    params["client_secret"] = BuildConfig.GOOGLE_CLIENT_SECRET
                }
                val tokens = JSONObject(postForm("https://oauth2.googleapis.com/token", params))
                val access = tokens.optString("access_token")
                if (access.isBlank()) throw IOException(tokens.optString("error_description", "Token exchange failed"))
                val refresh = tokens.optString("refresh_token").ifBlank { secrets.get(KEY_REFRESH).orEmpty() }
                val expiresIn = tokens.optLong("expires_in", 3600L)
                val profile = runCatching {
                    JSONObject(getJson("https://www.googleapis.com/oauth2/v3/userinfo", access))
                }.getOrDefault(JSONObject())
                AuthState(
                    provider = "GOOGLE",
                    email = profile.optString("email"),
                    displayName = profile.optString("name").ifBlank { profile.optString("email") },
                    avatarUrl = profile.optString("picture"),
                    accessToken = access,
                    refreshToken = refresh,
                    expiresAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(expiresIn),
                    message = "Signed in with Google"
                )
            }
        }
        result.onSuccess { next ->
            save(next.copy(isLoading = false))
            secrets.remove(KEY_PKCE)
            secrets.remove(KEY_OAUTH_STATE)
        }.onFailure { error ->
            Log.e(TAG, "Google sign-in failed", error)
            _state.value = _state.value.copy(
                isLoading = false,
                message = "Google sign-in failed: ${error.message ?: "unknown error"}"
            )
        }
    }

    suspend fun validAccessToken(): String? {
        val current = _state.value
        if (!current.isGoogle) return null
        if (current.accessToken.isNotBlank() && current.expiresAt > System.currentTimeMillis() + 60_000L) {
            return current.accessToken
        }
        return refreshTokens().getOrNull()
    }

    private suspend fun refreshTokens(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val refresh = secrets.get(KEY_REFRESH).orEmpty()
            if (refresh.isBlank()) throw IOException("Google session expired. Sign in again.")
            val params = mutableMapOf(
                "client_id" to BuildConfig.GOOGLE_CLIENT_ID,
                "refresh_token" to refresh,
                "grant_type" to "refresh_token"
            )
            if (BuildConfig.GOOGLE_CLIENT_SECRET.isNotBlank()) {
                params["client_secret"] = BuildConfig.GOOGLE_CLIENT_SECRET
            }
            val tokens = JSONObject(postForm("https://oauth2.googleapis.com/token", params))
            val access = tokens.optString("access_token")
            if (access.isBlank()) throw IOException(tokens.optString("error_description", "Refresh failed"))
            val updated = _state.value.copy(
                accessToken = access,
                expiresAt = System.currentTimeMillis() +
                    TimeUnit.SECONDS.toMillis(tokens.optLong("expires_in", 3600L))
            )
            save(updated)
            access
        }
    }

    suspend fun uploadToDrive(fileName: String, content: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = validAccessToken() ?: throw IOException("Sign in with Google first")
                val boundary = "vpsConsoleBoundary${System.nanoTime()}"
                val metadata = JSONObject()
                    .put("name", fileName)
                    .put("parents", org.json.JSONArray().put("appDataFolder"))
                    .toString()
                val body = (
                    "--$boundary\r\n" +
                        "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
                        "$metadata\r\n" +
                        "--$boundary\r\n" +
                        "Content-Type: application/octet-stream\r\n\r\n" +
                        "$content\r\n" +
                        "--$boundary--\r\n"
                    ).toByteArray(Charsets.UTF_8)
                val response = postBytes(
                    "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart",
                    body,
                    "multipart/related; boundary=$boundary",
                    token
                )
                JSONObject(response).optString("id").ifBlank { throw IOException("Drive upload returned no file id") }
            }
        }

    suspend fun downloadFromDrive(fileName: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = validAccessToken() ?: throw IOException("Sign in with Google first")
                val query = "name = '$fileName' and 'appDataFolder' in parents and trashed = false"
                val searchUrl = "https://www.googleapis.com/drive/v3/files?" +
                    "q=${URLEncoder.encode(query, "UTF-8")}&spaces=appDataFolder&fields=files(id,name,modifiedTime)"
                val list = JSONObject(getJson(searchUrl, token))
                val files = list.optJSONArray("files") ?: org.json.JSONArray()
                if (files.length() == 0) throw IOException("No sync file in Google Drive yet")
                val id = files.getJSONObject(0).optString("id")
                getJson(
                    "https://www.googleapis.com/drive/v3/files/${Uri.encode(id)}?alt=media",
                    token
                )
            }
        }

    private fun load(): AuthState = AuthState(
        provider = secrets.get(KEY_PROVIDER).orEmpty(),
        email = secrets.get(KEY_EMAIL).orEmpty(),
        displayName = secrets.get(KEY_NAME).orEmpty(),
        avatarUrl = secrets.get(KEY_AVATAR).orEmpty(),
        accessToken = secrets.get(KEY_ACCESS).orEmpty(),
        refreshToken = secrets.get(KEY_REFRESH).orEmpty(),
        expiresAt = secrets.get(KEY_EXPIRES)?.toLongOrNull() ?: 0L
    )

    private fun save(state: AuthState) {
        secrets.put(KEY_PROVIDER, state.provider)
        secrets.put(KEY_EMAIL, state.email)
        secrets.put(KEY_NAME, state.displayName)
        secrets.put(KEY_AVATAR, state.avatarUrl)
        secrets.put(KEY_ACCESS, state.accessToken)
        secrets.put(KEY_REFRESH, state.refreshToken)
        secrets.put(KEY_EXPIRES, state.expiresAt.toString())
        _state.value = state
    }

    private fun randomToken(bytes: Int): String {
        val buffer = ByteArray(bytes).also(SecureRandom()::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    private fun postForm(url: String, params: Map<String, String>): String {
        val body = params.map { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }.joinToString("&").toByteArray(Charsets.UTF_8)
        return postBytes(url, body, "application/x-www-form-urlencoded", token = null)
    }

    private fun postBytes(url: String, body: ByteArray, contentType: String, token: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", contentType)
        if (!token.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
        connection.outputStream.use { it.write(body) }
        return readResponse(connection)
    }

    private fun getJson(url: String, token: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        return readResponse(connection)
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val detail = runCatching { JSONObject(text).optString("error_description") }.getOrDefault("")
            throw IOException("HTTP $code${if (detail.isBlank()) "" else ": $detail"}")
        }
        return text
    }

    companion object {
        private const val TAG = "AuthManager"
        private const val KEY_PROVIDER = "__auth_provider"
        private const val KEY_EMAIL = "__auth_email"
        private const val KEY_NAME = "__auth_name"
        private const val KEY_AVATAR = "__auth_avatar"
        private const val KEY_ACCESS = "__google_access_token"
        private const val KEY_REFRESH = "__google_refresh_token"
        private const val KEY_EXPIRES = "__google_expires_at"
        private const val KEY_PKCE = "__google_pkce_verifier"
        private const val KEY_OAUTH_STATE = "__google_oauth_state"
    }
}
