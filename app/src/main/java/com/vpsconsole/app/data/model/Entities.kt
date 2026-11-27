package com.vpsconsole.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AuthType { PASSWORD, KEY }
enum class HostKeyPolicy { TOFU, STRICT }
enum class MetricKind { CPU, RAM, DISK, LOAD, NET }
enum class TunnelType { LOCAL, REMOTE }

@Entity(tableName = "servers")
data class ServerProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: AuthType = AuthType.KEY,
    /** Reference name of the secret stored in EncryptedSharedPreferences. */
    val secretRef: String = "",
    val keyPassphraseRef: String? = null,
    val group: String = "",
    val colorArgb: Long = 0xFF3FB950,
    val note: String = "",
    val monitoringEnabled: Boolean = true,
    val cpuThreshold: Float = 85f,
    val ramThreshold: Float = 90f,
    val diskThreshold: Float = 90f,
    val createdAt: Long = System.currentTimeMillis(),
    val lastConnectedAt: Long? = null,
    val hostKeyFingerprint: String? = null
)

@Entity(
    tableName = "metrics",
    indices = [Index(value = ["serverId", "timestamp"])]
)
data class MetricEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val cpu: Float,
    val ram: Float,
    val disk: Float,
    val load: Float,
    val rxMbps: Float,
    val txMbps: Float,
    val uptimeSec: Long,
    val cpuTotal: Long = 0,
    val cpuIdle: Long = 0,
    val rxBytes: Long = 0,
    val txBytes: Long = 0
)

@Entity(tableName = "alert_rules")
data class AlertRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    val kind: MetricKind,
    val threshold: Float,
    val enabled: Boolean = true,
    val cooldownMinutes: Int = 15,
    val lastTriggeredAt: Long = 0L,
    /** True for rules auto-created from the per-server monitoring thresholds. */
    @ColumnInfo(name = "isServerThreshold") val isServerThreshold: Boolean = false
)

@Entity(tableName = "alert_events")
data class AlertEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    val serverName: String,
    val kind: MetricKind,
    val message: String,
    val value: Float,
    val timestamp: Long = System.currentTimeMillis(),
    val acknowledged: Boolean = false
)

@Entity(tableName = "commands")
data class CommandEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long = 0,
    val title: String,
    val command: String,
    val confirm: Boolean = false,
    val useCount: Int = 0
)

@Entity(tableName = "known_hosts", primaryKeys = ["host", "port", "algorithm"])
data class KnownHostEntity(
    val host: String,
    val port: Int,
    val algorithm: String,
    val fingerprint: String,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "tunnels")
data class TunnelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    val type: TunnelType,
    val localPort: Int,
    val remotePort: Int,
    val targetHost: String,
    val targetPort: Int,
    val remoteBindHost: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
