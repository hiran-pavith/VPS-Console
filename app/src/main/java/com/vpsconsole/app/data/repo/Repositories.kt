package com.vpsconsole.app.data.repo

import com.vpsconsole.app.data.db.AlertDao
import com.vpsconsole.app.data.db.CommandDao
import com.vpsconsole.app.data.db.KnownHostDao
import com.vpsconsole.app.data.db.MetricDao
import com.vpsconsole.app.data.db.NoteDao
import com.vpsconsole.app.data.db.ServerDao
import com.vpsconsole.app.data.db.TunnelDao
import com.vpsconsole.app.data.model.AlertEventEntity
import com.vpsconsole.app.data.model.AlertRuleEntity
import com.vpsconsole.app.data.model.CommandEntity
import com.vpsconsole.app.data.model.KnownHostEntity
import com.vpsconsole.app.data.model.MetricEntity
import com.vpsconsole.app.data.model.MetricKind
import com.vpsconsole.app.data.model.NoteEntity
import com.vpsconsole.app.data.model.ServerProfile
import com.vpsconsole.app.data.model.TunnelEntity
import com.vpsconsole.app.data.secure.SecretsStore
import kotlinx.coroutines.flow.Flow

class ServerRepository(
    private val dao: ServerDao,
    private val secrets: SecretsStore
) {
    val servers: Flow<List<ServerProfile>> = dao.observeAll()

    fun observe(id: Long): Flow<ServerProfile?> = dao.observe(id)

    suspend fun get(id: Long): ServerProfile? = dao.get(id)
    suspend fun all(): List<ServerProfile> = dao.all()

    /** Pass null for a secret to keep the currently stored one. */
    suspend fun save(server: ServerProfile, secret: String?, passphrase: String?): Long {
        val id = if (server.id == 0L) {
            dao.insert(server.copy(secretRef = "", keyPassphraseRef = null))
        } else {
            dao.update(server)
            server.id
        }
        val secretRef = if (server.secretRef.isBlank()) SecretsStore.secretRefFor(id) else server.secretRef
        val passRef = if (server.keyPassphraseRef.isNullOrBlank()) {
            SecretsStore.passphraseRefFor(id)
        } else {
            server.keyPassphraseRef
        }
        secret?.let { if (it.isNotEmpty()) secrets.put(secretRef, it) }
        passphrase?.let { if (it.isNotEmpty()) secrets.put(passRef, it) }
        dao.update(server.copy(id = id, secretRef = secretRef, keyPassphraseRef = passRef))
        return id
    }

    suspend fun delete(server: ServerProfile) {
        dao.delete(server)
        secrets.remove(server.secretRef)
        server.keyPassphraseRef?.let(secrets::remove)
    }

    suspend fun touch(id: Long, time: Long) = dao.updateLastConnected(id, time)

    suspend fun updateHostKeyFingerprint(id: Long, fingerprint: String) =
        dao.updateHostKeyFingerprint(id, fingerprint)

    suspend fun secretFor(server: ServerProfile): String? =
        server.secretRef.takeIf { it.isNotBlank() }?.let(secrets::get)

    suspend fun passphraseFor(server: ServerProfile): String? =
        server.keyPassphraseRef?.let(secrets::get)
}

class MetricRepository(private val dao: MetricDao) {
    fun history(serverId: Long, since: Long): Flow<List<MetricEntity>> =
        dao.observeHistory(serverId, since)

    suspend fun latest(serverId: Long, limit: Int = 200): List<MetricEntity> = dao.latest(serverId, limit)

    suspend fun add(metric: MetricEntity) = dao.insert(metric)

    suspend fun prune(before: Long) = dao.prune(before)
}

class AlertRepository(private val dao: AlertDao) {
    val rules: Flow<List<AlertRuleEntity>> = dao.observeRules()
    val events: Flow<List<AlertEventEntity>> = dao.observeEvents()

    suspend fun saveRule(rule: AlertRuleEntity) {
        if (rule.id == 0L) dao.insertRule(rule) else dao.updateRule(rule)
    }

    suspend fun deleteRule(rule: AlertRuleEntity) = dao.deleteRule(rule)
    suspend fun rulesFor(serverId: Long) = dao.rulesFor(serverId)
    suspend fun enabledRules() = dao.enabledRules()

    suspend fun record(event: AlertEventEntity): Long = dao.insertEvent(event)
    suspend fun acknowledge(event: AlertEventEntity) = dao.updateEvent(event.copy(acknowledged = true))
    suspend fun deleteEvent(event: AlertEventEntity) = dao.deleteEvent(event)
    suspend fun clearEvents() = dao.clearEvents()

    /** Keep the auto-created threshold rules in sync with the server profile. */
    suspend fun syncServerThresholdRules(server: ServerProfile) {
        val existing = dao.rulesFor(server.id)
        val thresholds = listOf(
            MetricKind.CPU to server.cpuThreshold,
            MetricKind.RAM to server.ramThreshold,
            MetricKind.DISK to server.diskThreshold
        )
        thresholds.forEach { (kind, rawThreshold) ->
            val threshold = rawThreshold.takeIf { it.isFinite() }?.coerceIn(1f, 100f) ?: return@forEach
            val current = existing.firstOrNull { it.kind == kind && it.isServerThreshold }
            when {
                current == null -> dao.insertRule(
                    AlertRuleEntity(
                        serverId = server.id,
                        kind = kind,
                        threshold = threshold,
                        isServerThreshold = true
                    )
                )
                current.threshold != threshold -> dao.updateRule(current.copy(threshold = threshold))
            }
        }
    }
}

class CommandRepository(private val dao: CommandDao) {
    val commands: Flow<List<CommandEntity>> = dao.observeAll()

    fun forServer(serverId: Long): Flow<List<CommandEntity>> = dao.observeFor(serverId)

    suspend fun all(): List<CommandEntity> = dao.all()

    suspend fun save(command: CommandEntity) {
        if (command.id == 0L) dao.insert(command) else dao.update(command)
    }

    suspend fun delete(command: CommandEntity) = dao.delete(command)

    suspend fun markUsed(command: CommandEntity) =
        dao.update(command.copy(useCount = command.useCount + 1))
}

class KnownHostRepository(private val dao: KnownHostDao) {
    val hosts: Flow<List<KnownHostEntity>> = dao.observeAll()

    suspend fun forHost(host: String, port: Int) = dao.forHost(host, port)

    suspend fun trust(host: String, port: Int, algorithm: String, fingerprint: String) =
        dao.insert(KnownHostEntity(host, port, algorithm, fingerprint))

    suspend fun forget(host: String, port: Int) = dao.delete(host, port)
}

class TunnelRepository(private val dao: TunnelDao) {
    val tunnels: Flow<List<TunnelEntity>> = dao.observeAll()

    suspend fun save(tunnel: TunnelEntity): Long = dao.insert(tunnel.copy(id = 0))

    suspend fun delete(tunnel: TunnelEntity) = dao.delete(tunnel)
}

class NoteRepository(private val dao: NoteDao) {
    val notes: Flow<List<NoteEntity>> = dao.observeAll()

    fun forServer(serverId: Long): Flow<List<NoteEntity>> = dao.observeFor(serverId)

    suspend fun all(): List<NoteEntity> = dao.all()

    suspend fun get(id: Long): NoteEntity? = dao.get(id)

    suspend fun save(note: NoteEntity): Long =
        if (note.id == 0L) dao.insert(note.copy(id = 0, updatedAt = System.currentTimeMillis()))
        else {
            dao.update(note.copy(updatedAt = System.currentTimeMillis()))
            note.id
        }

    suspend fun delete(note: NoteEntity) = dao.delete(note)
}
