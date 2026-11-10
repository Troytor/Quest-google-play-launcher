package com.cukies.questgmsbridge

import android.app.Application
import android.content.Context
import android.util.Log
import me.weishu.reflection.Reflection

class BridgeApp : Application() {

    companion object {
        const val TAG = "SpatialGMS_OS27"
        lateinit var instance: BridgeApp
            private set
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        instance = this
        try {
            // Unseal hidden Android 12L/14 APIs on Meta Horizon OS
            Reflection.unseal(base)
            // Hook ActivityManager & PackageManager for GMS/Play Billing routing
            GmsBinderRouter.installHooks(base)
            // Hook Instrumentation for in-process APK loading inside :sandbox_p0
            VirtualApkEngine.hookInstrumentation()
            Log.i(TAG, "SpatialGMS OS 27 Engine initialized successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Engine initialization error", e)
        }
    }
}
