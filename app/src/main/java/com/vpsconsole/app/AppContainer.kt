package com.vpsconsole.app

import android.content.Context
import com.vpsconsole.app.auth.AuthManager
import com.vpsconsole.app.core.AlertsNotifier
import com.vpsconsole.app.data.DefaultServer
import com.vpsconsole.app.data.db.AppDatabase
import com.vpsconsole.app.data.repo.AlertRepository
import com.vpsconsole.app.data.repo.CommandRepository
import com.vpsconsole.app.data.repo.KnownHostRepository
import com.vpsconsole.app.data.repo.MetricRepository
import com.vpsconsole.app.data.repo.NoteRepository
import com.vpsconsole.app.data.repo.ServerRepository
import com.vpsconsole.app.data.repo.TunnelRepository
import com.vpsconsole.app.data.secure.SecretsStore
import com.vpsconsole.app.data.settings.SettingsStore
import com.vpsconsole.app.ssh.SshManager
import com.vpsconsole.app.sync.SyncManager

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val db: AppDatabase = AppDatabase.build(appContext)
    val secrets = SecretsStore(appContext)
    val settings = SettingsStore(appContext)

    val serverRepo = ServerRepository(db.serverDao(), secrets)
    val metricRepo = MetricRepository(db.metricDao())
    val alertRepo = AlertRepository(db.alertDao())
    val commandRepo = CommandRepository(db.commandDao())
    val knownHostRepo = KnownHostRepository(db.knownHostDao())
    val tunnelRepo = TunnelRepository(db.tunnelDao())
    val noteRepo = NoteRepository(db.noteDao())

    val auth = AuthManager(secrets)
    val sync = SyncManager(appContext, this, auth)

    val ssh = SshManager(appContext, serverRepo, knownHostRepo, metricRepo, settings)
    val notifier = AlertsNotifier(appContext)

    /** Seeds the preconfigured hosting VPS once, refreshing its key if the build-time key changed. */
    suspend fun seedDefaultServerIfNeeded() {
        val currentHash = com.vpsconsole.app.BuildConfig.DEFAULT_SERVER_KEY_HASH
        val s = settings.settings.value
        if (s.defaultServerSeeded && s.seededKeyHash == currentHash) {
            // Upgrades from builds that did not create threshold rules need them now.
            serverRepo.all().forEach { alertRepo.syncServerThresholdRules(it) }
            return
        }

        // Find the previously seeded server by its known host/user (survives DB id changes).
        val existing = serverRepo.all().firstOrNull {
            it.host == DefaultServer.HOST && it.username == DefaultServer.USERNAME &&
                (it.name == DefaultServer.NAME || it.note == "Preconfigured VPS hosting this app")
        }
        val id = serverRepo.save(
            (existing ?: DefaultServer.profile()).copy(
                name = DefaultServer.NAME,
                host = DefaultServer.HOST,
                port = DefaultServer.PORT,
                username = DefaultServer.USERNAME,
                authType = com.vpsconsole.app.data.model.AuthType.KEY,
                note = "Preconfigured VPS hosting this app"
            ),
            DefaultServer.PRIVATE_KEY,
            null
        )
        serverRepo.get(id)?.let { alertRepo.syncServerThresholdRules(it) }
        settings.update {
            it.copy(
                // Never override a server the user explicitly selected.
                selectedServerId = if (it.selectedServerId == 0L) id else it.selectedServerId,
                defaultServerSeeded = true,
                seededKeyHash = currentHash
            )
        }
    }
}
