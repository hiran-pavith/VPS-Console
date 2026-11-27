package com.vpsconsole.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.vpsconsole.app.ui.LocalAppContainer
import com.vpsconsole.app.ui.LockScreen
import com.vpsconsole.app.ui.navigation.AppRoot
import com.vpsconsole.app.ui.theme.VpsConsoleTheme

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as VpsConsoleApp).container

        setContent {
            val settings by container.settings.settings.collectAsState()
            var locked by rememberSaveable {
                mutableStateOf(settings.lockEnabled && container.secrets.isPinConfigured())
            }

            VpsConsoleTheme(settings.themeMode) {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        if (locked) {
                            LockScreen(
                                onPinSuccess = { locked = false },
                                onBiometric = { unlockBiometric { locked = false } }
                            )
                        } else {
                            AppRoot()
                        }
                    }
                }
            }
        }
    }

    private fun unlockBiometric(onSuccess: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock VPS Console")
            .setSubtitle("Verify your device to continue")
            .setNegativeButtonText("Use PIN")
            .build()
        runCatching { prompt.authenticate(info) }
    }
}
