package com.cukies.questgmsbridge

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
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
        installCrashShield()
        try {
            Reflection.unseal(base)
            GmsBinderRouter.installHooks(base)
        } catch (t: Throwable) {
            Log.e(TAG, "Reflection init note", t)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Boot the self-contained Virtual Android OS & bind directly to Quest 3 Wi-Fi
        VirtualKernelEngine.bootVirtualAndroidOs(this)
    }

    private fun installCrashShield() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Caught unhandled exception in ${thread.name}", throwable)
            if (thread == Looper.getMainLooper().thread) {
                try {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            this,
                            "VM Shield: ${throwable.javaClass.simpleName}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    while (true) {
                        try {
                            Looper.loop()
                        } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {
                    defaultHandler?.uncaughtException(thread, throwable)
                }
            }
        }
    }
}
