package com.vpsconsole.revssh

import android.app.Application
import android.util.Log
import net.schmizz.sshj.common.SecurityUtils

class RevSshApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android's bundled BouncyCastle is missing JCA mappings SSHJ needs.
        // SSHJ 0.38 also brings its own BC 1.75 which supplies the classes.
        SecurityUtils.setRegisterBouncyCastle(false)
        val ok = SecurityUtils.registerSecurityProvider("org.spongycastle.jce.provider.BouncyCastleProvider")
        Log.i(TAG, "SpongyCastle registered=$ok")

        // Capture ANY uncaught crash so the UI can show why the tunnel closed
        // instead of silently disappearing.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val detail = error.message ?: error.javaClass.simpleName
            Log.e(TAG, "CRASH on ${thread.name}: $detail", error)
            TunnelState.status.value = "Error: crash — $detail"
            TunnelState.log("CRASH [${error.javaClass.simpleName}]: $detail")
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        private const val TAG = "RevSshApp"
    }
}
