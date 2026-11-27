package com.vpsconsole.app

import android.content.Context
import com.vpsconsole.app.core.AlertsNotifier
import com.vpsconsole.app.data.DefaultServer
import com.vpsconsole.app.data.db.AppDatabase
import com.vpsconsole.app.data.repo.AlertRepository
import com.vpsconsole.app.data.repo.CommandRepository
import com.vpsconsole.app.data.repo.KnownHostRepository
import com.vpsconsole.app.data.repo.MetricRepository
import com.vpsconsole.app.data.repo.ServerRepository
import com.vpsconsole.app.data.repo.TunnelRepository
import com.vpsconsole.app.data.secure.SecretsStore
import com.vpsconsole.app.data.settings.SettingsStore
import com.vpsconsole.app.ssh.SshManager

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

    val ssh = SshManager(appContext, serverRepo, knownHostRepo, metricRepo, settings)
    val notifier = AlertsNotifier(appContext)

    /** Seeds the preconfigured hosting VPS on first launch if no servers exist. */
    suspend fun seedDefaultServerIfNeeded() {
        if (serverRepo.all().isEmpty()) {
            serverRepo.save(DefaultServer.profile(), DefaultServer.PRIVATE_KEY, null)
        }
    }
}
