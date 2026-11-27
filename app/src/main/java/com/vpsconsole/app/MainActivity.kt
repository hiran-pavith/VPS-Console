package com.vpsconsole.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vpsconsole.app.ui.LocalAppContainer
import com.vpsconsole.app.ui.LockScreen
import com.vpsconsole.app.ui.navigation.AppRoot
import com.vpsconsole.app.ui.theme.VpsConsoleTheme

class MainActivity : FragmentActivity() {
    companion object {
        private const val KEY_LOCKED = "locked"
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied; notifications simply remain off if denied */ }

    private val lockedState = mutableStateOf(false)
    private var backgroundedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as VpsConsoleApp).container
        val settingsAtStart = container.settings.settings.value
        lockedState.value = savedInstanceState?.getBoolean(KEY_LOCKED)
            ?: (settingsAtStart.lockEnabled && container.secrets.isPinConfigured())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val settings by container.settings.settings.collectAsState()
            var locked by lockedState

            VpsConsoleTheme(settings.themeMode, settings.accentColor) {
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

        // Auto-lock after the configured idle period in the background.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            val settingsNow = container.settings.settings.value
            when (event) {
                Lifecycle.Event.ON_STOP -> backgroundedAt = System.currentTimeMillis()
                Lifecycle.Event.ON_START -> {
                    val startedAt = backgroundedAt
                    backgroundedAt = 0L
                    if (startedAt > 0L && settingsNow.lockEnabled && container.secrets.isPinConfigured()) {
                        val idleMinutes = (System.currentTimeMillis() - startedAt) / 60_000L
                        if (idleMinutes >= settingsNow.autoLockMinutes) lockedState.value = true
                    }
                }
                else -> Unit
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_LOCKED, lockedState.value)
        super.onSaveInstanceState(outState)
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
