package com.cukies.questgmsbridge

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

object GmsBinderRouter {

    private val KNOWN_GMS_PACKAGES = listOf(
        "app.revanced.android.gms",
        "com.mgoogle.android.gms",
        "org.microg.gms",
        "com.google.android.gms",
        "com.vanced.android.gms"
    )

    private val KNOWN_GMS_ACTIVITIES = listOf(
        "org.microg.gms.ui.SettingsActivity",
        "org.microg.gms.ui.MainActivity",
        "org.microg.gms.auth.login.LoginActivity",
        "com.google.android.gms.app.settings.GoogleSettingsActivity"
    )

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun installHooks(context: Context) {
        try {
            val activityManagerClass = Class.forName("android.app.ActivityManager")
            val singletonField = activityManagerClass.getDeclaredField("IActivityManagerSingleton").apply {
                isAccessible = true
            }
            val singleton = singletonField.get(null) ?: return
            val singletonClass = Class.forName("android.util.Singleton")
            val mInstanceField = singletonClass.getDeclaredField("mInstance").apply {
                isAccessible = true
            }
            val getMethod = singletonClass.getDeclaredMethod("get").apply {
                isAccessible = true
            }
            val originalAm = getMethod.invoke(singleton) ?: return
            val iAmInterface = Class.forName("android.app.IActivityManager")

            val proxy = Proxy.newProxyInstance(
                context.classLoader,
                arrayOf(iAmInterface),
                ActivityManagerProxy(context.applicationContext, originalAm)
            )
            mInstanceField.set(singleton, proxy)
        } catch (t: Throwable) {
            Log.w(BridgeApp.TAG, "IActivityManager hook skipped: ${t.message}")
        }
    }

    fun resolveInstalledGmsPackage(pm: PackageManager): String? {
        for (pkg in KNOWN_GMS_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Throwable) {}
        }

        return try {
            val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val match = allApps.firstOrNull { app ->
                val pkg = app.packageName.lowercase()
                val label = try { pm.getApplicationLabel(app).toString().lowercase() } catch (_: Throwable) { "" }
                (pkg != "com.android.vending" && pkg != "com.cukies.questgmsbridge") && (
                    pkg.endsWith(".android.gms") ||
                    pkg.contains("microg") ||
                    (pkg.contains("revanced") && pkg.contains("gms")) ||
                    label.contains("microg") ||
                    label.contains("gmscore")
                )
            }
            match?.packageName
        } catch (_: Throwable) {
            null
        }
    }

    fun resolveInstalledVendingPackage(pm: PackageManager): String {
        return "com.android.vending"
    }

    fun launchGmsCoreSettingsOrLogin(context: Context): Boolean {
        val pm = context.packageManager
        val gmsPkg = resolveInstalledGmsPackage(pm) ?: return false

        try {
            val stdIntent = pm.getLaunchIntentForPackage(gmsPkg)
            if (stdIntent != null) {
                stdIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(stdIntent)
                return true
            }
        } catch (_: Throwable) {}

        try {
            val pkgInfo: PackageInfo = pm.getPackageInfo(gmsPkg, PackageManager.GET_ACTIVITIES)
            val activities = pkgInfo.activities ?: emptyArray()

            val targetActivity = activities.firstOrNull { act ->
                act.name.contains("SettingsActivity", ignoreCase = true) ||
                act.name.contains("MainActivity", ignoreCase = true) ||
                act.name.contains("LoginActivity", ignoreCase = true)
            } ?: activities.firstOrNull { it.exported }

            if (targetActivity != null) {
                val explicitIntent = Intent().apply {
                    component = ComponentName(gmsPkg, targetActivity.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(explicitIntent)
                return true
            }
        } catch (_: Throwable) {}

        for (actName in KNOWN_GMS_ACTIVITIES) {
            try {
                val intent = Intent().apply {
                    component = ComponentName(gmsPkg, actName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            } catch (_: Throwable) {}
        }

        return try {
            val appDetails = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$gmsPkg")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(appDetails)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private class ActivityManagerProxy(
        private val context: Context,
        private val base: Any
    ) : InvocationHandler {

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            return try {
                method.invoke(base, *(args ?: emptyArray()))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        }
    }
}
