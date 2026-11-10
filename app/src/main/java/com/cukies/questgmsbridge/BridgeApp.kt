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
            Log.i(TAG, "SpatialGMS OS 27 Core initialized cleanly.")
        } catch (t: Throwable) {
            Log.e(TAG, "Non-fatal reflection init warning", t)
        }
    }

    private fun installCrashShield() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Caught unhandled exception in thread ${thread.name}", throwable)
            if (thread == Looper.getMainLooper().thread) {
                try {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            this,
                            "Sandbox Shield Caught Error: ${throwable.javaClass.simpleName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    // Keep the main Looper alive if a sandboxed call throws an exception
                    while (true) {
                        try {
                            Looper.loop()
                        } catch (e: Throwable) {
                            Log.e(TAG, "Secondary loop shield caught: ${e.message}")
                        }
                    }
                } catch (_: Throwable) {
                    defaultHandler?.uncaughtException(thread, throwable)
                }
            } else {
                // Background thread exception: log and prevent process death
                Log.w(TAG, "Suppressed background thread crash: ${throwable.message}")
            }
        }
    }
}
