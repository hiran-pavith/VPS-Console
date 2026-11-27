package com.vpsconsole.app

import android.app.Application
import com.vpsconsole.app.worker.MonitorWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.schmizz.sshj.common.SecurityUtils

class VpsConsoleApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Use SpongyCastle instead of the broken Android BC JCE provider.
        // It exposes the JCA algorithms SSHJ asks for by name (SHA-256, ECDSA KeyFactory, ...).
        // curve25519/Ed25519 are still filtered out in SshManager because SC 1.58 does not
        // provide those mappings.
        SecurityUtils.setRegisterBouncyCastle(false)
        SecurityUtils.registerSecurityProvider("org.spongycastle.jce.provider.BouncyCastleProvider")
        container = AppContainer(this)
        MonitorWorker.schedule(this)
        appScope.launch { runCatching { container.seedDefaultServerIfNeeded() } }
    }

    override fun onTerminate() {
        runCatching { container.ssh.closeAll() }
        super.onTerminate()
    }
}
