package com.vpsconsole.revssh

import android.app.Application
import android.util.Log
import net.schmizz.sshj.common.SecurityUtils

class RevSshApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android's bundled BouncyCastle is missing JCA mappings SSHJ needs.
        SecurityUtils.setRegisterBouncyCastle(false)
        if (!SecurityUtils.registerSecurityProvider("org.spongycastle.jce.provider.BouncyCastleProvider")) {
            Log.e(TAG, "Failed to register SpongyCastle provider; SSH may fail")
        }
    }

    companion object {
        private const val TAG = "RevSshApp"
    }
}
