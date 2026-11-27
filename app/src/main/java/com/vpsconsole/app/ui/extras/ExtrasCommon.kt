package com.vpsconsole.app.ui.extras

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vpsconsole.app.AppContainer

@Composable
fun OutputBox(text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            text = text.ifEmpty { "No output." },
            modifier = Modifier.verticalScroll(rememberScrollState()).fillMaxWidth().paddingSafe(),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun Modifier.paddingSafe(): Modifier = this.padding(12.dp)

suspend fun runCommand(container: AppContainer, serverId: Long, command: String): String =
    container.ssh.exec(serverId, command, timeoutSeconds = 90)
        .getOrElse { "Error: ${it.message}" }
