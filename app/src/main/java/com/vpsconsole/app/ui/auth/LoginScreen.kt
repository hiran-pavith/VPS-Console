package com.vpsconsole.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudSync
import com.vpsconsole.app.ui.components.SingleLineButton as Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.vpsconsole.app.ui.components.SingleLineOutlinedButton as OutlinedButton
import com.vpsconsole.app.ui.components.AutoText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vpsconsole.app.ui.LocalAppContainer
import com.vpsconsole.app.ui.components.BusyIndicator
import com.vpsconsole.app.ui.components.SectionCard

@Composable
fun LoginScreen() {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val authState by container.auth.state.collectAsState()
    val configured = container.auth.isGoogleConfigured

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.AccountCircle,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(12.dp))
        Text("Welcome to VPS Console", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Sign in to sync encrypted server configs, commands, and notes across devices.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        SectionCard {
            Button(
                onClick = { container.auth.startGoogleLogin(context) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !authState.isLoading
            ) {
                Icon(Icons.Filled.CloudSync, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Continue with Google")
            }
            OutlinedButton(
                onClick = { container.auth.continueAsLocal() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !authState.isLoading
            ) {
                Text("Continue on this device only")
            }
            if (authState.isLoading) BusyIndicator()
            if (!configured) {
                Text(
                    "Google sign-in needs an OAuth client ID. Set VPS_CONSOLE_GOOGLE_CLIENT_ID in local.properties and rebuild.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (authState.message.isNotBlank()) {
                Text(
                    authState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
