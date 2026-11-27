package com.vpsconsole.app.ui.adb

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vpsconsole.app.adb.AdbConfig
import com.vpsconsole.app.adb.AdbManager
import com.vpsconsole.app.adb.adbOutput
import com.vpsconsole.app.ssh.SftpStreams
import com.vpsconsole.app.ui.LocalAppContainer
import com.vpsconsole.app.ui.components.SectionCard
import com.vpsconsole.app.ui.extras.OutputBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private val adbTabs = listOf("Ports", "Shell", "Apps", "Logs", "Input", "Files", "Info")

@Composable
fun AdbScreen(serverId: Long) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val settings by container.settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val adb = remember(serverId) { AdbManager(container.ssh, serverId) }
    val config = AdbConfig(
        binary = settings.adbBinary,
        serial = settings.adbSerial,
        timeoutSeconds = settings.adbTimeoutSeconds.toLong()
    )

    var tab by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    var portOutput by remember { mutableStateOf("") }
    var reverseDevicePort by remember { mutableStateOf("8080") }
    var reverseHostPort by remember { mutableStateOf("8080") }
    var forwardHostPort by remember { mutableStateOf("8081") }
    var forwardDevicePort by remember { mutableStateOf("8080") }
    var removePort by remember { mutableStateOf("") }

    var shellCommand by remember { mutableStateOf("") }
    var shellOutput by remember { mutableStateOf("") }

    var packageName by remember { mutableStateOf("") }
    var apkPath by remember { mutableStateOf("/sdcard/Download/app.apk") }
    var packageOutput by remember { mutableStateOf("") }

    var logOutput by remember { mutableStateOf("") }
    var logLines by remember { mutableStateOf("300") }
    var liveLogs by remember { mutableStateOf(false) }
    var screenshotOutput by remember { mutableStateOf("") }
    var screenshotBytes by remember { mutableStateOf<ByteArray?>(null) }
    val screenshotBitmap = remember(screenshotBytes) {
        screenshotBytes?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        }
    }

    var inputX by remember { mutableStateOf("500") }
    var inputY by remember { mutableStateOf("1000") }
    var inputEndX by remember { mutableStateOf("500") }
    var inputEndY by remember { mutableStateOf("300") }
    var inputTextValue by remember { mutableStateOf("") }
    var keyCode by remember { mutableStateOf("66") }
    var inputOutput by remember { mutableStateOf("") }

    var devicePath by remember { mutableStateOf("/sdcard/Download/") }
    var fileOutput by remember { mutableStateOf("") }

    var infoOutput by remember { mutableStateOf("") }
    var processOutput by remember { mutableStateOf("") }

    fun runAdb(task: suspend () -> Result<String>, onResult: (String) -> Unit) {
        scope.launch {
            busy = true
            try {
                val result = runCatching { task() }.getOrElse { Result.failure(it) }
                onResult(result.adbOutput())
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(serverId, config) {
        portOutput = listOf(
            adb.devices(config).adbOutput(),
            "--- reverse ---\n${adb.reverseList(config).adbOutput()}",
            "--- forward ---\n${adb.forwardList(config).adbOutput()}"
        ).joinToString("\n\n")
    }

    LaunchedEffect(liveLogs, config) {
        if (!liveLogs) return@LaunchedEffect
        while (liveLogs) {
            val lines = logLines.toIntOrNull()?.coerceIn(20, 2000) ?: 300
            logOutput = adb.logcat(config, lines).adbOutput()
            delay(2_000)
        }
    }

    val pickPush = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val target = devicePath.trim()
                val hostTemp = "/tmp/vps-console-adb-push-${System.currentTimeMillis()}"
                val message = runCatching<String> {
                    withContext<Unit>(Dispatchers.IO) {
                        val stream = context.contentResolver.openInputStream(uri)
                            ?: throw IOException("Cannot open the selected file")
                        stream.use { input ->
                            container.ssh.withSftp(serverId) { sftp ->
                                SftpStreams.upload(input, sftp, hostTemp)
                            }.getOrThrow()
                        }
                    }
                    val pushed = adb.push(config, hostTemp, target).getOrThrow()
                    container.ssh.exec(serverId, "rm -f ${AdbManager.quote(hostTemp)}")
                    "Pushed to $target\n$pushed"
                }.getOrElse { "Error: ${it.message}" }
                fileOutput = message
            } finally {
                busy = false
            }
        }
    }

    val pickPull = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val source = devicePath.trim()
                val hostTemp = "/tmp/vps-console-adb-pull-${System.currentTimeMillis()}"
                val message = runCatching<String> {
                    val pulled = adb.pull(config, source, hostTemp).getOrThrow()
                    withContext<Unit>(Dispatchers.IO) {
                        val stream = context.contentResolver.openOutputStream(uri)
                            ?: throw IOException("Cannot open the destination file")
                        stream.use { output ->
                            container.ssh.withSftp(serverId) { sftp ->
                                SftpStreams.download(sftp, hostTemp, output)
                            }.getOrThrow()
                        }
                    }
                    container.ssh.exec(serverId, "rm -f ${AdbManager.quote(hostTemp)}")
                    "Pulled $source\n$pulled"
                }.getOrElse { "Error: ${it.message}" }
                fileOutput = message
            } finally {
                busy = false
            }
        }
    }

    val saveScreenshot = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bytes = screenshotBytes ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            screenshotOutput = withContext<String>(Dispatchers.IO) {
                runCatching<String> {
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: throw IOException("Cannot open the destination file")
                    stream.use { it.write(bytes) }
                    "Screenshot saved"
                }.getOrElse { "Save failed: ${it.message}" }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ADB Console", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Default)
        Text(
            "ADB runs on the selected VPS over SSH. Connect the phone to that adb server first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            adbTabs.forEachIndexed { index, label ->
                FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(label) })
            }
        }

        when (tab) {
            0 -> SectionCard(title = "Reverse / forward ports") {
                Text(
                    "Reverse: VPS/host service → phone port. Forward: phone service → VPS/host port.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = reverseDevicePort, onValueChange = { reverseDevicePort = it },
                        label = { Text("Device port") }, modifier = Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = reverseHostPort, onValueChange = { reverseHostPort = it },
                        label = { Text("Host port") }, modifier = Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val d = reverseDevicePort.toIntOrNull()
                        val h = reverseHostPort.toIntOrNull()
                        if (d != null && h != null && d in 1..65535 && h in 1..65535) {
                            runAdb({ adb.reverseAdd(config, d, h) }) { portOutput = it }
                        } else portOutput = "Enter ports between 1 and 65535"
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Add reverse") }
                    OutlinedButton(onClick = {
                        val d = removePort.toIntOrNull()
                        if (d != null && d in 1..65535) {
                            runAdb({ adb.reverseRemove(config, d) }) { portOutput = it }
                        } else portOutput = "Enter a valid device port"
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Remove reverse") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = forwardHostPort, onValueChange = { forwardHostPort = it },
                        label = { Text("Host listen port") }, modifier = Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = forwardDevicePort, onValueChange = { forwardDevicePort = it },
                        label = { Text("Device target port") }, modifier = Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val h = forwardHostPort.toIntOrNull()
                        val d = forwardDevicePort.toIntOrNull()
                        if (h != null && d != null && h in 1..65535 && d in 1..65535) {
                            runAdb({ adb.forwardAdd(config, h, d) }) { portOutput = it }
                        } else portOutput = "Enter ports between 1 and 65535"
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Add forward") }
                    OutlinedButton(onClick = {
                        val h = removePort.toIntOrNull()
                        if (h != null && h in 1..65535) {
                            runAdb({ adb.forwardRemove(config, h) }) { portOutput = it }
                        } else portOutput = "Enter a valid host port"
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Remove forward") }
                }
                OutlinedTextField(
                    value = removePort, onValueChange = { removePort = it },
                    label = { Text("Port to remove (device for reverse, host for forward)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        runAdb({ adb.devices(config) }) { portOutput = it }
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Devices") }
                    OutlinedButton(onClick = {
                        runAdb({
                            val r = adb.reverseList(config).adbOutput()
                            val f = adb.forwardList(config).adbOutput()
                            Result.success("--- reverse ---\n$r\n\n--- forward ---\n$f")
                        }) { portOutput = it }
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("List") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        runAdb({ adb.reverseClear(config) }) { portOutput = it }
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Clear reverse") }
                    OutlinedButton(onClick = {
                        runAdb({ adb.forwardClear(config) }) { portOutput = it }
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Clear forward") }
                }
                OutputBox(portOutput, Modifier.heightIn(min = 180.dp))
            }

            1 -> SectionCard(title = "Shell") {
                OutlinedTextField(
                    value = shellCommand, onValueChange = { shellCommand = it },
                    label = { Text("adb shell command") }, modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (shellCommand.isBlank()) return@Button
                        runAdb({ adb.shell(config, shellCommand) }) { shellOutput = it }
                    }, enabled = !busy) { Text("Run") }
                    OutlinedButton(onClick = { shellOutput = "" }) { Text("Clear output") }
                }
                OutputBox(shellOutput, Modifier.heightIn(min = 220.dp))
            }

            2 -> SectionCard(title = "Packages") {
                OutlinedTextField(
                    value = packageName, onValueChange = { packageName = it },
                    label = { Text("Package name") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                OutlinedTextField(
                    value = apkPath, onValueChange = { apkPath = it },
                    label = { Text("APK path on VPS host") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        runAdb({ adb.packages(config) }) { packageOutput = it }
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("List") }
                    OutlinedButton(onClick = {
                        if (packageName.isNotBlank()) runAdb({ adb.startPackage(config, packageName.trim()) }) { packageOutput = it }
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Start") }
                    OutlinedButton(onClick = {
                        if (packageName.isNotBlank()) runAdb({ adb.stopPackage(config, packageName.trim()) }) { packageOutput = it }
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Stop") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        if (apkPath.isNotBlank()) runAdb({ adb.install(config, apkPath.trim()) }) { packageOutput = it }
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Install") }
                    OutlinedButton(onClick = {
                        if (packageName.isNotBlank()) runAdb({ adb.uninstall(config, packageName.trim()) }) { packageOutput = it }
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Uninstall") }
                }
                OutputBox(packageOutput, Modifier.heightIn(min = 200.dp))
            }

            3 -> SectionCard(title = "Logs & screenshots") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = logLines, onValueChange = { logLines = it },
                        label = { Text("Lines") }, modifier = Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Switch(checked = liveLogs, onCheckedChange = { liveLogs = it })
                    Text("Live")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val lines = logLines.toIntOrNull()?.coerceIn(20, 2000) ?: 300
                        runAdb({ adb.logcat(config, lines) }) { logOutput = it }
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Refresh log") }
                    OutlinedButton(onClick = { logOutput = "" }, modifier = Modifier.weight(1f)) { Text("Clear") }
                }
                OutputBox(logOutput, Modifier.heightIn(min = 180.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val result = runCatching {
                                    val encoded = adb.screenshot(config).getOrThrow()
                                    val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                                    screenshotBytes = bytes
                                    "Captured ${bytes.size} bytes"
                                }
                                result.onSuccess { screenshotOutput = it }
                                    .onFailure { screenshotOutput = "Error: ${it.message}" }
                            } finally {
                                busy = false
                            }
                        }
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Screenshot") }
                    OutlinedButton(onClick = { saveScreenshot.launch("screenshot.png") },
                        enabled = screenshotBytes != null, modifier = Modifier.weight(1f)) { Text("Save PNG") }
                }
                Text(screenshotOutput, style = MaterialTheme.typography.bodySmall)
                screenshotBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Device screenshot",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                    )
                }
            }

            4 -> SectionCard(title = "Input") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = inputX, onValueChange = { inputX = it }, label = { Text("X") },
                        modifier = Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = inputY, onValueChange = { inputY = it }, label = { Text("Y") },
                        modifier = Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = inputEndX, onValueChange = { inputEndX = it }, label = { Text("End X") },
                        modifier = Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = inputEndY, onValueChange = { inputEndY = it }, label = { Text("End Y") },
                        modifier = Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val x = inputX.toIntOrNull(); val y = inputY.toIntOrNull()
                        if (x != null && y != null) runAdb({ adb.inputTap(config, x, y) }) { inputOutput = it }
                        else inputOutput = "Enter numeric coordinates"
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Tap") }
                    OutlinedButton(onClick = {
                        val x1 = inputX.toIntOrNull(); val y1 = inputY.toIntOrNull()
                        val x2 = inputEndX.toIntOrNull(); val y2 = inputEndY.toIntOrNull()
                        if (x1 != null && y1 != null && x2 != null && y2 != null) {
                            runAdb({ adb.inputSwipe(config, x1, y1, x2, y2, 300) }) { inputOutput = it }
                        } else inputOutput = "Enter numeric coordinates"
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Swipe") }
                }
                OutlinedTextField(
                    value = inputTextValue, onValueChange = { inputTextValue = it },
                    label = { Text("Text to type") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        runAdb({ adb.inputText(config, inputTextValue) }) { inputOutput = it }
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Type text") }
                    OutlinedTextField(
                        value = keyCode, onValueChange = { keyCode = it }, label = { Text("Key code") },
                        modifier = Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedButton(onClick = {
                        val code = keyCode.toIntOrNull()
                        if (code != null) runAdb({ adb.keyEvent(config, code) }) { inputOutput = it }
                        else inputOutput = "Enter a numeric key code"
                    }, enabled = !busy) { Text("Key") }
                }
                Text("Common keys: 3 Home · 4 Back · 66 Enter · 187 Recent", style = MaterialTheme.typography.bodySmall)
                OutputBox(inputOutput, Modifier.heightIn(min = 140.dp))
            }

            5 -> SectionCard(title = "Files") {
                OutlinedTextField(
                    value = devicePath, onValueChange = { devicePath = it },
                    label = { Text("Device path") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pickPush.launch(arrayOf("*/*")) }, enabled = !busy,
                        modifier = Modifier.weight(1f)) { Text("Push local file") }
                    OutlinedButton(onClick = { pickPull.launch("download.bin") }, enabled = !busy,
                        modifier = Modifier.weight(1f)) { Text("Pull to local file") }
                }
                Text(
                    "Files are streamed through the VPS: local → SFTP → adb push, and adb pull → SFTP → local.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutputBox(fileOutput, Modifier.heightIn(min = 160.dp))
            }

            else -> SectionCard(title = "Device info") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        runAdb({ adb.deviceInfo(config) }) { infoOutput = it }
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Device info") }
                    OutlinedButton(onClick = {
                        runAdb({ adb.processes(config) }) { processOutput = it }
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Processes") }
                }
                OutputBox(infoOutput, Modifier.heightIn(min = 220.dp))
                OutputBox(processOutput, Modifier.heightIn(min = 220.dp))
            }
        }
    }
}
