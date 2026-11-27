package com.vpsconsole.app.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.CoroutineWorker
import com.vpsconsole.app.VpsConsoleApp
import com.vpsconsole.app.data.model.AlertEventEntity
import com.vpsconsole.app.data.model.MetricKind
import com.vpsconsole.app.ssh.SshManager
import java.util.Locale
import java.util.concurrent.TimeUnit

class MonitorWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as VpsConsoleApp).container
        val servers = container.serverRepo.all().filter { it.monitoringEnabled }
        if (servers.isEmpty()) return Result.success()

        servers.forEach { server ->
            runCatching {
                container.ssh.connect(server.id)
                val metric = container.ssh.collect(server.id).getOrThrow()
                val rules = container.alertRepo.rulesFor(server.id).filter { it.enabled }
                rules.forEach { rule ->
                    val value = when (rule.kind) {
                        MetricKind.CPU -> metric.cpu
                        MetricKind.RAM -> metric.ram
                        MetricKind.DISK -> metric.disk
                        MetricKind.LOAD -> metric.load
                        MetricKind.NET -> maxOf(metric.rxMbps, metric.txMbps)
                    }
                    val cooldownMs = rule.cooldownMinutes * 60_000L
                    if (value >= rule.threshold && System.currentTimeMillis() - rule.lastTriggeredAt >= cooldownMs) {
                        val message = "${rule.kind} is ${String.format(Locale.US, "%.1f", value)}% " +
                            "(threshold ${String.format(Locale.US, "%.1f", rule.threshold)}%)"
                        val eventId = container.alertRepo.record(
                            AlertEventEntity(
                                serverId = server.id,
                                serverName = server.name,
                                kind = rule.kind,
                                message = message,
                                value = value
                            )
                        )
                        container.alertRepo.saveRule(
                            rule.copy(lastTriggeredAt = System.currentTimeMillis())
                        )
                        if (container.settings.settings.value.notificationsEnabled) {
                            container.notifier.alert(
                                id = eventId,
                                title = "${server.name}: ${rule.kind} alert",
                                body = message,
                                vibration = container.settings.settings.value.vibration,
                                sound = container.settings.settings.value.notificationSound
                            )
                        }
                    }
                }
            }
        }
        container.ssh.closeAll()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "vps_console_monitor"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<MonitorWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
