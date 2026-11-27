package com.vpsconsole.app.core

import java.util.Locale

fun formatUptime(seconds: Long): String {
    if (seconds <= 0) return "—"
    val d = seconds / 86400
    val h = (seconds % 86400) / 3600
    val m = (seconds % 3600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return String.format(Locale.US, "%.1f %s", value, units[index])
}

fun formatRate(mbps: Float): String = String.format(Locale.US, "%.2f MB/s", mbps)

fun formatTimestamp(ts: Long): String {
    val s = java.text.SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    return s.format(java.util.Date(ts))
}

fun percent(value: Float): String = String.format(Locale.US, "%.1f%%", value)

fun isValidHost(value: String): Boolean {
    val host = value.trim()
    if (host.isEmpty() || host.length > 253) return false
    return host.matches(Regex("^[a-zA-Z0-9._-]+$"))
}

fun isValidPort(value: String): Boolean =
    value.toIntOrNull()?.let { it in 1..65535 } ?: false
