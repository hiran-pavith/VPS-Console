package com.vpsconsole.app.data

import com.vpsconsole.app.BuildConfig
import com.vpsconsole.app.data.model.AuthType
import com.vpsconsole.app.data.model.ServerProfile

/**
 * Preconfigured server entry for the VPS hosting this app.
 *
 * The private key is NOT checked in. It is injected at build time from the
 * gitignored `local.properties` entry `VPS_CONSOLE_DEFAULT_KEY` (newlines
 * escaped as `\n`) via `BuildConfig.DEFAULT_SERVER_KEY`.
 */
object DefaultServer {
    const val HOST = "54.253.196.217"
    const val PORT = 22
    const val USERNAME = "admin"
    const val NAME = "My VPS (54.253.196.217)"

    val PRIVATE_KEY: String = BuildConfig.DEFAULT_SERVER_KEY

    fun profile(): ServerProfile = ServerProfile(
        name = NAME,
        host = HOST,
        port = PORT,
        username = USERNAME,
        authType = AuthType.KEY,
        group = "My Servers",
        colorArgb = 0xFF3FB950,
        note = "Preconfigured VPS hosting this app"
    )
}
