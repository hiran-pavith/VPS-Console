package com.vpsconsole.app.ui.files

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import com.vpsconsole.app.ui.components.SingleLineButton as Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.vpsconsole.app.ui.components.SingleLineOutlinedButton as OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.vpsconsole.app.ui.components.AutoText as Text
import com.vpsconsole.app.ui.components.SingleLineTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vpsconsole.app.core.formatBytes
import com.vpsconsole.app.core.formatTimestamp
import com.vpsconsole.app.data.model.FileInfo
import com.vpsconsole.app.ssh.SftpStreams
import com.vpsconsole.app.ui.LocalAppContainer
import com.vpsconsole.app.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.SFTPClient
import java.io.File
import java.io.IOException

@Composable
fun FilesScreen(serverId: Long) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState()
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<FileInfo>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FileInfo?>(null) }
    var editorText by remember { mutableStateOf("") }
    var newItem by remember { mutableStateOf<Boolean?>(null) }
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<FileInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<FileInfo?>(null) }
    var downloadTarget by remember { mutableStateOf<FileInfo?>(null) }
    var chmodTarget by remember { mutableStateOf<FileInfo?>(null) }
    var modeText by remember { mutableStateOf("") }

    suspend fun load() {
        busy = true
        try {
            val result = container.ssh.withSftp(serverId) { sftp ->
                val target = if (path.isBlank()) {
                    when (settings.sftpDefaultPath) {
                        "root" -> "/"
                        "custom" -> settings.sftpCustomPath.ifBlank { "/" }
                        else -> sftp.canonicalize(".")
                    }
                } else path
                listRemote(sftp, target, settings.showHiddenFiles) to target
            }
            result.onSuccess { (listed, target) ->
                entries = listed
                path = target
            }.onFailure { message = it.message ?: "Unable to list directory" }
        } finally {
            busy = false
        }
    }

    // Path changes are driven explicitly by Go/Open/Up and file operations;
    // keying this effect on `path` previously cancelled its own in-flight load.
    LaunchedEffect(serverId, settings.showHiddenFiles) {
        path = ""
        load()
    }

    val pickUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                upload(context, container.ssh, serverId, uri, joinRemote(path, uriFileName(context, uri) ?: "upload"))
            }.onFailure { message = it.message ?: "Upload failed" }
            load()
        }
    }
    val pickDownload = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val target = downloadTarget
        downloadTarget = null
        if (uri != null && target != null) {
            scope.launch {
                runCatching {
                    download(context, container.ssh, serverId, target.path, uri)
                    message = "Downloaded ${target.name}"
                }.onFailure { message = it.message ?: "Download failed" }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(title = "SFTP Browser") {
            Text("Current: $path", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = path, onValueChange = { path = it },
                    label = { Text("Remote path") }, modifier = Modifier.weight(1f), singleLine = true
                )
                Button(onClick = { scope.launch { load() } }, enabled = !busy) { Text("Go") }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = {
                    path = if (path == "/") "/" else path.substringBeforeLast('/').ifEmpty { "/" }
                    scope.launch { load() }
                }) {
                    Text("Up")
                }
                OutlinedButton(onClick = { pickUpload.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Filled.Upload, contentDescription = null); Text("Upload")
                }
                OutlinedButton(onClick = { newItem = true }) { Text("New folder") }
                OutlinedButton(onClick = { newItem = false }) { Text("New file") }
            }
            if (message.isNotBlank()) {
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(entries, key = { it.path }) { item ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (item.isDir) "📁 ${item.name}" else "📄 ${item.name}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "${item.permissions} · ${item.owner}:${item.group} · ${
                                    if (item.isDir) "folder" else formatBytes(item.size)
                                } · ${formatTimestamp(item.modified * 1000)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (item.isDir) {
                            TextButton(onClick = {
                                path = item.path
                                scope.launch { load() }
                            }) { Text("Open") }
                        } else {
                            IconButton(onClick = {
                                scope.launch {
                                    val result = container.ssh.withSftp(serverId) { sftp ->
                                        val cache = File(context.cacheDir, "edit-${System.currentTimeMillis()}")
                                        sftp.get(item.path, cache.absolutePath)
                                        cache.readText(Charsets.UTF_8)
                                    }
                                    result.onSuccess { editorText = it; editing = item }
                                        .onFailure { message = it.message ?: "Read failed" }
                                }
                            }) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
                            IconButton(onClick = {
                                downloadTarget = item
                                pickDownload.launch(item.name)
                            }) { Icon(Icons.Filled.Download, contentDescription = "Download") }
                        }
                        IconButton(
                            onClick = { renaming = item },
                            modifier = Modifier.semantics { contentDescription = "Rename" }
                        ) { Text("✎") }
                        IconButton(
                            onClick = {
                                modeText = (item.octalMode and 0x1FF).toString(8).padStart(3, '0')
                                chmodTarget = item
                            },
                            modifier = Modifier.semantics { contentDescription = "Change permissions" }
                        ) { Text("#") }
                        IconButton(onClick = { deleteTarget = item }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }
        }
    }

    editing?.let { file ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit ${file.name}") },
            text = {
                OutlinedTextField(
                    value = editorText, onValueChange = { editorText = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    minLines = 8
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val result = container.ssh.withSftp(serverId) { sftp ->
                            val cache = File(context.cacheDir, "upload-${System.currentTimeMillis()}")
                            cache.writeText(editorText, Charsets.UTF_8)
                            sftp.put(cache.absolutePath, file.path)
                        }
                        result.onFailure { message = it.message ?: "Save failed" }
                        editing = null
                        load()
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }
        )
    }

    newItem?.let { isFolder ->
        AlertDialog(
            onDismissRequest = { newItem = null },
            title = { Text(if (isFolder) "New folder" else "New file") },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Name") })
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = joinRemote(path, newName)
                    scope.launch {
                        val result = container.ssh.withSftp(serverId) { sftp ->
                            if (isFolder) sftp.mkdir(target)
                            else sftp.put(
                                File(context.cacheDir, "empty-${System.currentTimeMillis()}").apply {
                                    writeBytes(byteArrayOf())
                                }.absolutePath,
                                target
                            )
                        }
                        result.onFailure { message = it.message ?: "Failed" }
                        newName = ""; newItem = null; load()
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { newItem = null }) { Text("Cancel") } }
        )
    }

    renaming?.let { file ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename ${file.name}") },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("New name") }) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val result = container.ssh.withSftp(serverId) { sftp ->
                            sftp.rename(file.path, joinRemote(parentOf(file.path), newName))
                        }
                        result.onFailure { message = it.message ?: "Rename failed" }
                        newName = ""; renaming = null; load()
                    }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }
        )
    }

    chmodTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { chmodTarget = null },
            title = { Text("Permissions · ${file.name}") },
            text = { OutlinedTextField(value = modeText, onValueChange = { modeText = it }, label = { Text("Mode (e.g. 755)") }) },
            confirmButton = {
                TextButton(onClick = {
                    val mode = modeText.toIntOrNull(8)
                    if (mode == null) {
                        message = "Invalid mode — use octal digits (e.g. 755)"
                    } else {
                        message = ""
                        scope.launch {
                            val result = container.ssh.withSftp(serverId) { sftp -> sftp.chmod(file.path, mode) }
                            result.onFailure { message = it.message ?: "chmod failed" }
                            chmodTarget = null; load()
                        }
                    }
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { chmodTarget = null }) { Text("Cancel") } }
        )
    }

    deleteTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${file.name}?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val result = container.ssh.withSftp(serverId) { sftp ->
                            if (file.isDir) sftp.rmdir(file.path) else sftp.rm(file.path)
                        }
                        result.onFailure { message = it.message ?: "Delete failed" }
                        deleteTarget = null; load()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

private suspend fun upload(
    context: Context,
    manager: com.vpsconsole.app.ssh.SshManager,
    serverId: Long,
    uri: Uri,
    remotePath: String
) {
    withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open the selected file")
        stream.use { input ->
            manager.withSftp(serverId) { sftp -> SftpStreams.upload(input, sftp, remotePath) }
        }
    }.getOrThrow()
}

private suspend fun download(
    context: Context,
    manager: com.vpsconsole.app.ssh.SshManager,
    serverId: Long,
    remotePath: String,
    uri: Uri
) {
    manager.withSftp(serverId) { sftp ->
        withContext(Dispatchers.IO) {
            val stream = context.contentResolver.openOutputStream(uri)
                ?: throw IOException("Cannot open the destination file")
            stream.use { output -> SftpStreams.download(sftp, remotePath, output) }
        }
    }.getOrThrow()
}

private fun listRemote(sftp: SFTPClient, path: String, showHidden: Boolean): List<FileInfo> = sftp.ls(path)
    .filter { item ->
        val name = item.name
        name != "." && name != ".." && (showHidden || !name.startsWith("."))
    }
    .map { item ->
    val attrs = item.attributes
    FileInfo(
        name = item.name,
        path = if (path == "/" || path == "~") joinRemote(path, item.name) else "$path/${item.name}",
        isDir = attrs.type == FileMode.Type.DIRECTORY,
        size = attrs.getSize(),
        modified = attrs.getMtime(),
        permissions = attrs.getPermissions()
            .map { it.name.replace("USER", "U").replace("GROUP", "G").replace("OTHERS", "O") }
            .joinToString(",")
            .ifEmpty { attrs.getType().name },
        octalMode = net.schmizz.sshj.xfer.FilePermission.toMask(attrs.getPermissions()) and 0x1FF,
        owner = attrs.getUID().toString(),
        group = attrs.getGID().toString()
    )
}.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))

private fun joinRemote(base: String, name: String): String = when {
    name == "/" -> "/"
    base == "/" -> "/$name"
    base == "~" -> "~/$name"
    else -> "$base/$name"
}

private fun parentOf(path: String): String {
    val trimmed = path.trimEnd('/')
    val index = trimmed.lastIndexOf('/')
    return if (index <= 0) "/" else trimmed.substring(0, index)
}

private fun uriFileName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull()
