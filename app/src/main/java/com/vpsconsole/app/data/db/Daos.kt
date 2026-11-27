package com.vpsconsole.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.vpsconsole.app.data.model.AlertEventEntity
import com.vpsconsole.app.data.model.AlertRuleEntity
import com.vpsconsole.app.data.model.CommandEntity
import com.vpsconsole.app.data.model.KnownHostEntity
import com.vpsconsole.app.data.model.MetricEntity
import com.vpsconsole.app.data.model.NoteEntity
import com.vpsconsole.app.data.model.ServerProfile
import com.vpsconsole.app.data.model.TunnelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ServerProfile>>

    @Query("SELECT * FROM servers WHERE id = :id")
    fun observe(id: Long): Flow<ServerProfile?>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun get(id: Long): ServerProfile?

    @Query("SELECT * FROM servers")
    suspend fun all(): List<ServerProfile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(server: ServerProfile): Long

    @Update
    suspend fun update(server: ServerProfile)

    @Delete
    suspend fun delete(server: ServerProfile)

    @Query("UPDATE servers SET lastConnectedAt = :time WHERE id = :id")
    suspend fun updateLastConnected(id: Long, time: Long)

    @Query("UPDATE servers SET hostKeyFingerprint = :fingerprint WHERE id = :id")
    suspend fun updateHostKeyFingerprint(id: Long, fingerprint: String)
}

@Dao
interface MetricDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(metric: MetricEntity): Long

    @Query("SELECT * FROM metrics WHERE serverId = :serverId AND timestamp >= :since ORDER BY timestamp ASC")
    fun observeHistory(serverId: Long, since: Long): Flow<List<MetricEntity>>

    @Query("SELECT * FROM metrics WHERE serverId = :serverId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun latest(serverId: Long, limit: Int): List<MetricEntity>

    @Query("DELETE FROM metrics WHERE timestamp < :before")
    suspend fun prune(before: Long)
}

@Dao
interface AlertDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: AlertRuleEntity): Long

    @Update
    suspend fun updateRule(rule: AlertRuleEntity)

    @Delete
    suspend fun deleteRule(rule: AlertRuleEntity)

    @Query("SELECT * FROM alert_rules ORDER BY serverId, kind")
    fun observeRules(): Flow<List<AlertRuleEntity>>

    @Query("SELECT * FROM alert_rules WHERE serverId = :serverId")
    suspend fun rulesFor(serverId: Long): List<AlertRuleEntity>

    @Query("SELECT * FROM alert_rules WHERE enabled = 1")
    suspend fun enabledRules(): List<AlertRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: AlertEventEntity): Long

    @Query("SELECT * FROM alert_events ORDER BY timestamp DESC LIMIT 300")
    fun observeEvents(): Flow<List<AlertEventEntity>>

    @Update
    suspend fun updateEvent(event: AlertEventEntity)

    @Delete
    suspend fun deleteEvent(event: AlertEventEntity)

    @Query("DELETE FROM alert_events")
    suspend fun clearEvents()
}

@Dao
interface CommandDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(command: CommandEntity): Long

    @Update
    suspend fun update(command: CommandEntity)

    @Delete
    suspend fun delete(command: CommandEntity)

    @Query("SELECT * FROM commands ORDER BY useCount DESC, title COLLATE NOCASE")
    fun observeAll(): Flow<List<CommandEntity>>

    @Query("SELECT * FROM commands WHERE serverId = 0 OR serverId = :serverId ORDER BY useCount DESC")
    fun observeFor(serverId: Long): Flow<List<CommandEntity>>

    @Query("SELECT * FROM commands")
    suspend fun all(): List<CommandEntity>
}

@Dao
interface NoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    @Delete
    suspend fun delete(note: NoteEntity)

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE serverId = 0 OR serverId = :serverId ORDER BY updatedAt DESC")
    fun observeFor(serverId: Long): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes")
    suspend fun all(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: Long): NoteEntity?
}

@Dao
interface KnownHostDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(host: KnownHostEntity)

    @Query("SELECT * FROM known_hosts WHERE host = :host AND port = :port")
    suspend fun forHost(host: String, port: Int): List<KnownHostEntity>

    @Query("SELECT * FROM known_hosts")
    fun observeAll(): Flow<List<KnownHostEntity>>

    @Query("DELETE FROM known_hosts WHERE host = :host AND port = :port")
    suspend fun delete(host: String, port: Int)
}

@Dao
interface TunnelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tunnel: TunnelEntity): Long

    @Delete
    suspend fun delete(tunnel: TunnelEntity)

    @Query("SELECT * FROM tunnels ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TunnelEntity>>
}
