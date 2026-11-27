package com.vpsconsole.app.ssh

import com.vpsconsole.app.data.model.MetricEntity

/**
 * Parses the portable /proc-based probe executed over SSH into a MetricEntity.
 * CPU and network values are deltas, so the previous sample is required.
 */
class MetricsCollector {
    fun parse(raw: String, serverId: Long, previous: MetricEntity?, now: Long): MetricEntity {
        val sections = raw.split("@@").mapNotNull {
            val trimmed = it.trim()
            if (trimmed.isEmpty()) null else {
                val cut = trimmed.indexOf('\n')
                if (cut < 0) trimmed to "" else trimmed.substring(0, cut).trim() to trimmed.substring(cut + 1)
            }
        }.toMap()

        fun section(name: String): String = sections[name].orEmpty()

        val cpuValues = section("CPU").lines().firstOrNull()
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotEmpty() }
            ?.drop(1)
            ?.mapNotNull { it.toLongOrNull() }
            .orEmpty()
        val cpuTotal = cpuValues.take(8).sum()
        val cpuIdle = (cpuValues.getOrNull(3) ?: 0L) + (cpuValues.getOrNull(4) ?: 0L)

        val memText = section("MEM")
        val memTotal = Regex("MemTotal:\\s*(\\d+)").find(memText)?.groupValues?.get(1)
            ?.toLongOrNull()?.coerceAtLeast(1L) ?: 1L
        val memAvailable = Regex("MemAvailable:\\s*(\\d+)").find(memText)?.groupValues?.get(1)
            ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val ramRaw = (100f * (memTotal - memAvailable) / memTotal)
        val ram = (if (ramRaw.isFinite()) ramRaw else 0f).coerceIn(0f, 100f)

        val disk = section("DISK").lines().firstOrNull { it.contains("/") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(4)
            ?.removeSuffix("%")
            ?.toFloatOrNull()
            ?.coerceIn(0f, 100f) ?: 0f

        val load = section("LOAD").lines().firstOrNull()
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.toFloatOrNull() ?: 0f

        var rxBytes = 0L
        var txBytes = 0L
        section("NET").lines().forEach { line ->
            val colon = line.indexOf(':')
            if (colon > 0) {
                val iface = line.substring(0, colon).trim()
                if (iface != "lo" && iface.isNotEmpty()) {
                    val fields = line.substring(colon + 1).trim().split(Regex("\\s+"))
                    rxBytes += fields.getOrNull(1)?.toLongOrNull() ?: 0L
                    txBytes += fields.getOrNull(9)?.toLongOrNull() ?: 0L
                }
            }
        }

        val uptime = section("UPT").lines().firstOrNull()
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.toDoubleOrNull()
            ?.toLong() ?: 0L

        val cpu = if (previous != null && previous.cpuTotal > 0 && cpuTotal > previous.cpuTotal) {
            val totalDelta = cpuTotal - previous.cpuTotal
            val idleDelta = (cpuIdle - previous.cpuIdle).coerceAtLeast(0)
            (100f * (1f - idleDelta.toFloat() / totalDelta)).coerceIn(0f, 100f)
        } else 0f

        val seconds = if (previous != null) ((now - previous.timestamp).coerceAtLeast(1) / 1000f) else 0f
        val rxMbps = if (previous != null && seconds > 0) {
            ((rxBytes - previous.rxBytes).coerceAtLeast(0) / seconds / 1_048_576f).coerceAtMost(10_000f)
        } else 0f
        val txMbps = if (previous != null && seconds > 0) {
            ((txBytes - previous.txBytes).coerceAtLeast(0) / seconds / 1_048_576f).coerceAtMost(10_000f)
        } else 0f

        return MetricEntity(
            serverId = serverId,
            timestamp = now,
            cpu = cpu,
            ram = ram,
            disk = disk,
            load = load,
            rxMbps = rxMbps,
            txMbps = txMbps,
            uptimeSec = uptime,
            cpuTotal = cpuTotal,
            cpuIdle = cpuIdle,
            rxBytes = rxBytes,
            txBytes = txBytes
        )
    }

    companion object {
        const val PROBE_COMMAND = "printf '@@CPU\\n'; head -n 1 /proc/stat; " +
            "printf '@@MEM\\n'; grep -E '^(MemTotal|MemAvailable):' /proc/meminfo; " +
            "printf '@@DISK\\n'; df -P / | tail -n 1; " +
            "printf '@@LOAD\\n'; head -n 1 /proc/loadavg; " +
            "printf '@@NET\\n'; cat /proc/net/dev; " +
            "printf '@@UPT\\n'; head -n 1 /proc/uptime; " +
            "printf '@@END\\n' 2>&1"
    }
}
