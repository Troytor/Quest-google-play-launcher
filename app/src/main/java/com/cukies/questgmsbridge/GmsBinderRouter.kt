package com.cukies.questgmsbridge

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
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
        "com.vanced.android.gms",
        "io.github.vvb2060.ims"
    )

    private val KNOWN_GMS_ACTIVITIES = listOf(
        "org.microg.gms.ui.SettingsActivity",
        "org.microg.gms.ui.MainActivity",
        "org.microg.gms.auth.login.LoginActivity",
        "com.google.android.gms.app.settings.GoogleSettingsActivity",
        "com.google.android.gms.common.settings.GoogleSettingsActivity"
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

    /**
     * Scans all installed packages on the Quest 3 to find ReVanced GmsCore or microG,
     * even if it uses a custom package prefix or hides its launcher activity.
     */
    fun resolveInstalledGmsPackage(pm: PackageManager): String? {
        // 1. Check exact known package names first
        for (pkg in KNOWN_GMS_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Throwable) {}
        }

        // 2. Deep scan all installed user packages for ReVanced / microG / GmsCore signatures
        return try {
            val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val match = allApps.firstOrNull { app ->
                val pkg = app.packageName.lowercase()
                val label = try { pm.getApplicationLabel(app).toString().lowercase() } catch (_: Throwable) { "" }
                (pkg != "com.cukies.questgmsbridge") && (
                    pkg.endsWith(".android.gms") ||
                    pkg.contains("microg") ||
                    pkg.contains("revanced") && pkg.contains("gms") ||
                    label.contains("microg") ||
                    label.contains("gmscore")
                )
            }
            match?.packageName
        } catch (_: Throwable) {
            null
        }
    }

    fun resolveInstalledVendingPackage(pm: PackageManager): String? {
        val candidates = listOf("com.android.vending", "com.android.vending.billing")
        for (pkg in candidates) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Throwable) {}
        }
        return null
    }

    /**
     * Launches ReVanced GmsCore or microG Settings/Login even when it has no Launcher icon.
     */
    fun launchGmsCoreSettingsOrLogin(context: Context): Boolean {
        val pm = context.packageManager
        val gmsPkg = resolveInstalledGmsPackage(pm) ?: return false

        // Strategy 1: Standard Launch Intent
        try {
            val stdIntent = pm.getLaunchIntentForPackage(gmsPkg)
            if (stdIntent != null) {
                stdIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(stdIntent)
                return true
            }
        } catch (_: Throwable) {}

        // Strategy 2: Query all exported and internal Activities inside the GmsCore APK
        try {
            val pkgInfo: PackageInfo = pm.getPackageInfo(gmsPkg, PackageManager.GET_ACTIVITIES)
            val activities = pkgInfo.activities ?: emptyArray()

            // Look for SettingsActivity or LoginActivity first
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
        } catch (t: Throwable) {
            Log.w(BridgeApp.TAG, "Activity scan fallback for $gmsPkg: ${t.message}")
        }

        // Strategy 3: Try known hardcoded microG / ReVanced activity class names
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

        // Strategy 4: Try microG Settings Action Intent
        try {
            val actionIntent = Intent("org.microg.gms.action.SETTINGS").apply {
                setPackage(gmsPkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(actionIntent)
            return true
        } catch (_: Throwable) {}

        // Strategy 5: Open Android App Info page for GmsCore so user can tap "Open" or "Permissions"
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
            try {
                if (args != null && (method.name == "bindService" ||
                            method.name == "bindServiceInstance" ||
                            method.name == "bindIsolatedService" ||
                            method.name == "startService")
                ) {
                    rerouteGmsAndBillingIntents(args)
                }
            } catch (t: Throwable) {
                Log.w(BridgeApp.TAG, "Non-fatal router intercept: ${t.message}")
            }
            return try {
                method.invoke(base, *(args ?: emptyArray()))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        }

        private fun rerouteGmsAndBillingIntents(args: Array<out Any?>) {
            val intentIdx = args.indexOfFirst { it is Intent }
            if (intentIdx == -1) return

            val intent = args[intentIdx] as Intent
            val targetPkg = intent.`package` ?: intent.component?.packageName
            val action = intent.action ?: ""
            val pm = context.packageManager

            if (targetPkg == "com.google.android.gms" ||
                targetPkg == "com.google.android.gsf" ||
                action.startsWith("com.google.android.gms")
            ) {
                val hostGms = resolveInstalledGmsPackage(pm)
                if (hostGms != null && hostGms != "com.google.android.gms") {
                    intent.setPackage(hostGms)
                    if (intent.component != null) {
                        intent.component = ComponentName(hostGms, intent.component!!.className)
                    }
                } else if (hostGms == null) {
                    intent.component = ComponentName(context.packageName, GmsDaemonService::class.java.name)
                }
            }

            if (targetPkg == "com.android.vending" ||
                action.contains("com.android.vending.billing.InAppBillingService.BIND") ||
                action.contains("com.android.vending.licensing.ILicensingService")
            ) {
                val hostVending = resolveInstalledVendingPackage(pm)
                if (hostVending == null) {
                    intent.component = ComponentName(context.packageName, GmsDaemonService::class.java.name)
                }
            }
        }
    }
}
