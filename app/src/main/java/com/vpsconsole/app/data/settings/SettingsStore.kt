package com.vpsconsole.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.vpsconsole.app.data.model.HostKeyPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val themeMode: String = "system",
    val refreshSeconds: Int = 10,
    val terminalFontSize: Int = 13,
    val hostKeyPolicy: HostKeyPolicy = HostKeyPolicy.TOFU,
    val lockEnabled: Boolean = false,
    val autoLockMinutes: Int = 5,
    val notificationsEnabled: Boolean = true,
    val vibration: Boolean = true,
    val showHiddenFiles: Boolean = false,
    val selectedServerId: Long = 0L,
    val autoReconnect: Boolean = true,
    val keepAliveSeconds: Int = 30,
    val metricHistoryHours: Int = 24,
    val confirmDangerousCommands: Boolean = true,
    val defaultServerSeeded: Boolean = false,
    val seededKeyHash: String = "",
    // Customizations
    val accentColor: String = "blue",
    val terminalColorScheme: String = "default",
    val serverSortOrder: String = "name",
    val dashboardCompact: Boolean = false,
    val sftpDefaultPath: String = "home",
    val sftpCustomPath: String = "/home",
    val showCpuGauge: Boolean = true,
    val showRamGauge: Boolean = true,
    val showDiskGauge: Boolean = true,
    val showNetworkGauge: Boolean = true,
    val notificationSound: Boolean = true
)

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences("vps_console_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load(): AppSettings = AppSettings(
        themeMode = prefs.getString(THEME, "system") ?: "system",
        refreshSeconds = prefs.getInt(REFRESH, 10).coerceIn(2, 300),
        terminalFontSize = prefs.getInt(FONT_SIZE, 13).coerceIn(9, 24),
        hostKeyPolicy = runCatching {
            HostKeyPolicy.valueOf(prefs.getString(HOST_KEY_POLICY, "TOFU") ?: "TOFU")
        }.getOrDefault(HostKeyPolicy.TOFU),
        lockEnabled = prefs.getBoolean(LOCK_ENABLED, false),
        autoLockMinutes = prefs.getInt(AUTO_LOCK, 5).coerceIn(1, 120),
        notificationsEnabled = prefs.getBoolean(NOTIFICATIONS, true),
        vibration = prefs.getBoolean(VIBRATION, true),
        showHiddenFiles = prefs.getBoolean(SHOW_HIDDEN, false),
        selectedServerId = prefs.getLong(SELECTED_SERVER, 0L),
        autoReconnect = prefs.getBoolean(AUTO_RECONNECT, true),
        keepAliveSeconds = prefs.getInt(KEEP_ALIVE, 30).coerceIn(5, 120),
        metricHistoryHours = prefs.getInt(HISTORY_HOURS, 24).coerceIn(1, 168),
        confirmDangerousCommands = prefs.getBoolean(CONFIRM_DANGEROUS, true),
        defaultServerSeeded = prefs.getBoolean(DEFAULT_SERVER_SEEDED, false),
        seededKeyHash = prefs.getString(SEEDED_KEY_HASH, "") ?: "",
        accentColor = prefs.getString(ACCENT_COLOR, "blue") ?: "blue",
        terminalColorScheme = prefs.getString(TERMINAL_SCHEME, "default") ?: "default",
        serverSortOrder = prefs.getString(SERVER_SORT, "name") ?: "name",
        dashboardCompact = prefs.getBoolean(DASHBOARD_COMPACT, false),
        sftpDefaultPath = prefs.getString(SFTP_DEFAULT_PATH, "home") ?: "home",
        sftpCustomPath = prefs.getString(SFTP_CUSTOM_PATH, "/home") ?: "/home",
        showCpuGauge = prefs.getBoolean(SHOW_CPU_GAUGE, true),
        showRamGauge = prefs.getBoolean(SHOW_RAM_GAUGE, true),
        showDiskGauge = prefs.getBoolean(SHOW_DISK_GAUGE, true),
        showNetworkGauge = prefs.getBoolean(SHOW_NETWORK_GAUGE, true),
        notificationSound = prefs.getBoolean(NOTIFICATION_SOUND, true)
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        prefs.edit().apply {
            putString(THEME, next.themeMode)
            putInt(REFRESH, next.refreshSeconds)
            putInt(FONT_SIZE, next.terminalFontSize)
            putString(HOST_KEY_POLICY, next.hostKeyPolicy.name)
            putBoolean(LOCK_ENABLED, next.lockEnabled)
            putInt(AUTO_LOCK, next.autoLockMinutes)
            putBoolean(NOTIFICATIONS, next.notificationsEnabled)
            putBoolean(VIBRATION, next.vibration)
            putBoolean(SHOW_HIDDEN, next.showHiddenFiles)
            putLong(SELECTED_SERVER, next.selectedServerId)
            putBoolean(AUTO_RECONNECT, next.autoReconnect)
            putInt(KEEP_ALIVE, next.keepAliveSeconds)
            putInt(HISTORY_HOURS, next.metricHistoryHours)
            putBoolean(CONFIRM_DANGEROUS, next.confirmDangerousCommands)
            putBoolean(DEFAULT_SERVER_SEEDED, next.defaultServerSeeded)
            putString(SEEDED_KEY_HASH, next.seededKeyHash)
            putString(ACCENT_COLOR, next.accentColor)
            putString(TERMINAL_SCHEME, next.terminalColorScheme)
            putString(SERVER_SORT, next.serverSortOrder)
            putBoolean(DASHBOARD_COMPACT, next.dashboardCompact)
            putString(SFTP_DEFAULT_PATH, next.sftpDefaultPath)
            putString(SFTP_CUSTOM_PATH, next.sftpCustomPath)
            putBoolean(SHOW_CPU_GAUGE, next.showCpuGauge)
            putBoolean(SHOW_RAM_GAUGE, next.showRamGauge)
            putBoolean(SHOW_DISK_GAUGE, next.showDiskGauge)
            putBoolean(SHOW_NETWORK_GAUGE, next.showNetworkGauge)
            putBoolean(NOTIFICATION_SOUND, next.notificationSound)
        }.apply()
        _settings.value = next
    }

    fun refresh() {
        _settings.value = load()
    }

    private companion object {
        const val THEME = "theme_mode"
        const val REFRESH = "refresh_seconds"
        const val FONT_SIZE = "terminal_font_size"
        const val HOST_KEY_POLICY = "host_key_policy"
        const val LOCK_ENABLED = "lock_enabled"
        const val AUTO_LOCK = "auto_lock_minutes"
        const val NOTIFICATIONS = "notifications_enabled"
        const val VIBRATION = "vibration"
        const val SHOW_HIDDEN = "show_hidden_files"
        const val SELECTED_SERVER = "selected_server_id"
        const val AUTO_RECONNECT = "auto_reconnect"
        const val KEEP_ALIVE = "keep_alive_seconds"
        const val HISTORY_HOURS = "history_hours"
        const val CONFIRM_DANGEROUS = "confirm_dangerous"
        const val DEFAULT_SERVER_SEEDED = "default_server_seeded"
        const val SEEDED_KEY_HASH = "seeded_key_hash"
        const val ACCENT_COLOR = "accent_color"
        const val TERMINAL_SCHEME = "terminal_color_scheme"
        const val SERVER_SORT = "server_sort_order"
        const val DASHBOARD_COMPACT = "dashboard_compact"
        const val SFTP_DEFAULT_PATH = "sftp_default_path"
        const val SFTP_CUSTOM_PATH = "sftp_custom_path"
        const val SHOW_CPU_GAUGE = "show_cpu_gauge"
        const val SHOW_RAM_GAUGE = "show_ram_gauge"
        const val SHOW_DISK_GAUGE = "show_disk_gauge"
        const val SHOW_NETWORK_GAUGE = "show_network_gauge"
        const val NOTIFICATION_SOUND = "notification_sound"
    }
}
