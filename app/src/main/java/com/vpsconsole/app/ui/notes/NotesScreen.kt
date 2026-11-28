package com.vpsconsole.app.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import com.vpsconsole.app.ui.components.SingleLineButton as Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import com.vpsconsole.app.ui.components.SingleLineOutlinedButton as OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import com.vpsconsole.app.ui.components.AutoText as Text
import com.vpsconsole.app.ui.components.SingleLineTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vpsconsole.app.core.formatTimestamp
import com.vpsconsole.app.data.model.NoteEntity
import com.vpsconsole.app.ui.LocalAppContainer
import com.vpsconsole.app.ui.components.ConfirmDialog
import com.vpsconsole.app.ui.components.EmptyState
import com.vpsconsole.app.ui.components.SectionCard
import kotlinx.coroutines.launch

@Composable
fun NotesScreen() {
    val container = LocalAppContainer.current
    val notes by container.noteRepo.notes.collectAsState(emptyList())
    val servers by container.serverRepo.servers.collectAsState(emptyList())
    val settings by container.settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<NoteEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<NoteEntity?>(null) }

    val selectedServer = servers.firstOrNull { it.id == settings.selectedServerId }
    val visible = notes.filter { note ->
        val term = query.trim()
        term.isBlank() || listOf(note.title, note.body, note.tags)
            .any { it.contains(term, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Notes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        SectionCard {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search notes") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(onClick = {
                editing = NoteEntity(
                    title = "",
                    body = "",
                    serverId = 0L
                )
            }, modifier = Modifier.fillMaxWidth()) { Text("New note") }
        }
        if (visible.isEmpty()) {
            EmptyState(if (notes.isEmpty()) "No notes yet. Add one for a server or keep it global." else "No notes match your search.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.id }) { note ->
                    val serverName = servers.firstOrNull { it.id == note.serverId }?.name
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(note.title, style = MaterialTheme.typography.titleMedium)
                            if (note.body.isNotBlank()) {
                                Text(note.body, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (note.tags.isNotBlank()) {
                                Text(note.tags, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            Text(
                                listOfNotNull(serverName, "Updated ${formatTimestamp(note.updatedAt)}")
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { editing = note.copy() }) { Text("Edit") }
                                TextButton(onClick = { pendingDelete = note }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { note ->
        NoteEditorDialog(
            note = note,
            defaultServerId = selectedServer?.id ?: 0L,
            hasServer = selectedServer != null,
            onDismiss = { editing = null },
            onSave = { saved ->
                scope.launch { container.noteRepo.save(saved) }
                editing = null
            }
        )
    }
    pendingDelete?.let { note ->
        ConfirmDialog(
            title = "Delete note?",
            text = note.title,
            confirmLabel = "Delete",
            onConfirm = {
                scope.launch { container.noteRepo.delete(note) }
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun NoteEditorDialog(
    note: NoteEntity,
    defaultServerId: Long,
    hasServer: Boolean,
    onDismiss: () -> Unit,
    onSave: (NoteEntity) -> Unit
) {
    var title by remember { mutableStateOf(note.title) }
    var body by remember { mutableStateOf(note.body) }
    var tags by remember { mutableStateOf(note.tags) }
    var attach by remember { mutableStateOf(note.id != 0L && note.serverId != 0L) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (note.id == 0L) "New note" else "Edit note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 12
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("Tags (comma separated)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Attach to selected server", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(
                        checked = attach && hasServer,
                        onCheckedChange = { attach = it },
                        enabled = hasServer
                    )
                }
                if (attach && !hasServer) {
                    Text("Select a server first to attach this note.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        onSave(
                            note.copy(
                                title = title.trim(),
                                body = body,
                                tags = tags.trim(),
                                serverId = if (attach && hasServer) defaultServerId else 0L
                            )
                        )
                    }
                },
                enabled = title.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
