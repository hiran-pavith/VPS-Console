package com.vpsconsole.app

import android.app.Application
import android.util.Log
import com.vpsconsole.app.core.CrashReport
import com.vpsconsole.app.worker.MonitorWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.SecurityUtils

class VpsConsoleApp : Application() {
    companion object {
        private const val TAG = "VpsConsoleApp"
    }

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
        // Use SpongyCastle instead of the broken Android BC JCE provider.
        // It exposes the JCA algorithms SSHJ asks for by name (SHA-256, ECDSA KeyFactory, ...).
        // curve25519/Ed25519 are still filtered out in SshManager because SC 1.58 does not
        // provide those mappings.
        SecurityUtils.setRegisterBouncyCastle(false)
        if (!SecurityUtils.registerSecurityProvider("org.spongycastle.jce.provider.BouncyCastleProvider")) {
            Log.e(TAG, "Failed to register SpongyCastle JCE provider; SSH may fail on this device")
        }
        container = AppContainer(this)
        // Seed before the UI is created so a background write cannot overwrite
        // a server the user just selected or edited.
        runCatching {
            runBlocking(Dispatchers.IO) { container.seedDefaultServerIfNeeded() }
        }.onFailure { Log.e(TAG, "Failed to seed default server", it) }
        MonitorWorker.schedule(this)
    }

    override fun onTerminate() {
        runCatching { container.ssh.closeAll() }
        super.onTerminate()
    }
}
