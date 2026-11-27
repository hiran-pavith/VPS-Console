package com.vpsconsole.app.adb

import com.vpsconsole.app.ssh.SshManager

/** ADB host configuration. Commands run on the selected VPS over SSH. */
data class AdbConfig(
    val binary: String = "adb",
    val serial: String = "",
    val timeoutSeconds: Long = 60
)

/**
 * Thin wrapper that turns ADB actions into SSH commands executed on the VPS.
 *
 * The device must be connected to the adb server running on that host (for
 * example via `adb pair`/`adb connect` or a USB-attached device). No host PC is
 * required in the app: the app talks to the VPS, and the VPS talks to adbd.
 */
class AdbManager(
    private val ssh: SshManager,
    private val serverId: Long
) {
    suspend fun run(config: AdbConfig, arguments: String, timeoutSeconds: Long = config.timeoutSeconds): Result<String> {
        val binary = quote(config.binary.trim().ifEmpty { "adb" })
        val serial = config.serial.trim()
        val prefix = if (serial.isEmpty()) binary else "$binary -s ${quote(serial)}"
        return ssh.exec(serverId, "$prefix $arguments", timeoutSeconds)
    }

    suspend fun devices(config: AdbConfig) = run(config, "devices -l")

    suspend fun reverseList(config: AdbConfig) = run(config, "reverse --list")
    suspend fun forwardList(config: AdbConfig) = run(config, "forward --list")

    /** Device port is the listener on the phone; host port is the service on the VPS/host PC. */
    suspend fun reverseAdd(config: AdbConfig, devicePort: Int, hostPort: Int) =
        run(config, "reverse tcp:$devicePort tcp:$hostPort")

    /** Host port is the listener on the VPS/host PC; device port is the phone service. */
    suspend fun forwardAdd(config: AdbConfig, hostPort: Int, devicePort: Int) =
        run(config, "forward tcp:$hostPort tcp:$devicePort")

    suspend fun reverseRemove(config: AdbConfig, devicePort: Int) =
        run(config, "reverse --remove tcp:$devicePort")

    suspend fun forwardRemove(config: AdbConfig, hostPort: Int) =
        run(config, "forward --remove tcp:$hostPort")

    suspend fun reverseClear(config: AdbConfig) = run(config, "reverse --remove-all")
    suspend fun forwardClear(config: AdbConfig) = run(config, "forward --remove-all")

    suspend fun shell(config: AdbConfig, command: String) =
        run(config, "shell ${quote(command)}")

    suspend fun packages(config: AdbConfig) = shell(config, "pm list packages -3")

    suspend fun startPackage(config: AdbConfig, packageName: String) =
        shell(config, "monkey -p ${quote(packageName)} -c android.intent.category.LAUNCHER 1")

    suspend fun stopPackage(config: AdbConfig, packageName: String) =
        shell(config, "am force-stop ${quote(packageName)}")

    suspend fun install(config: AdbConfig, remoteApkPath: String) =
        run(config, "install -r ${quote(remoteApkPath)}")

    suspend fun uninstall(config: AdbConfig, packageName: String) =
        run(config, "uninstall ${quote(packageName)}")

    suspend fun logcat(config: AdbConfig, lines: Int = 300) =
        shell(config, "logcat -d -t ${lines.coerceIn(20, 2000)}")

    suspend fun screenshot(config: AdbConfig) =
        run(config, "exec-out screencap -p | base64 | tr -d '\\n'", 90)

    suspend fun inputTap(config: AdbConfig, x: Int, y: Int) =
        shell(config, "input tap $x $y")

    suspend fun inputSwipe(config: AdbConfig, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) =
        shell(config, "input swipe $x1 $y1 $x2 $y2 ${durationMs.coerceIn(0, 5000)}")

    suspend fun inputText(config: AdbConfig, text: String) =
        shell(config, "input text ${quote(text.replace(" ", "%s"))}")

    suspend fun keyEvent(config: AdbConfig, code: Int) =
        shell(config, "input keyevent $code")

    suspend fun deviceInfo(config: AdbConfig) = shell(
        config,
        "echo '--- properties ---'; getprop | head -n 100; " +
            "echo '--- battery ---'; dumpsys battery; " +
            "echo '--- memory ---'; dumpsys meminfo | head -n 40; " +
            "echo '--- network ---'; ip -brief address 2>/dev/null || ifconfig"
    )

    suspend fun processes(config: AdbConfig) =
        shell(config, "top -n 1 -m 30 2>/dev/null || ps -A")

    suspend fun push(config: AdbConfig, hostPath: String, devicePath: String) =
        run(config, "push ${quote(hostPath)} ${quote(devicePath)}")

    suspend fun pull(config: AdbConfig, devicePath: String, hostPath: String) =
        run(config, "pull ${quote(devicePath)} ${quote(hostPath)}")

    companion object {
        /** Shell-safe single quoting for commands sent through SSH. */
        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}

fun Result<String>.adbOutput(): String =
    getOrElse { "Error: ${it.message ?: "ADB command failed"}" }
