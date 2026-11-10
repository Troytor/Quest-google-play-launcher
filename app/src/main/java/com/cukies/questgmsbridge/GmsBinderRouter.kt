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

    private val GMS_PROVIDER_PACKAGES = listOf(
        "com.google.android.gms",
        "org.microg.gms",
        "app.revanced.android.gms",
        "com.mgoogle.android.gms"
    )

    private val VENDING_PROVIDER_PACKAGES = listOf(
        "com.android.vending",
        "com.android.vending.billing"
    )

    private val VIRTUALIZED_GMS_PACKAGES = setOf(
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.vending"
    )

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun installHooks(context: Context) {
        hookActivityManager(context)
        hookPackageManager(context)
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun hookActivityManager(context: Context) {
        try {
            val activityManagerClass = Class.forName("android.app.ActivityManager")
            val singletonField = activityManagerClass.getDeclaredField("IActivityManagerSingleton").apply {
                isAccessible = true
            }
            val singleton = singletonField.get(null)
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
                ActivityManagerProxy(context, originalAm)
            )
            mInstanceField.set(singleton, proxy)
        } catch (t: Throwable) {
            Log.e(BridgeApp.TAG, "Failed to hook IActivityManagerSingleton", t)
        }
    }

    /**
     * Hooks ActivityThread.sPackageManager so when a bridged app calls
     * GoogleApiAvailability.isGooglePlayServicesAvailable(), Quest 3 reports GMS is installed and active.
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun hookPackageManager(context: Context) {
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThread = activityThreadClass.getDeclaredMethod("currentActivityThread").apply {
                isAccessible = true
            }.invoke(null)

            val sPackageManagerField = activityThreadClass.getDeclaredField("sPackageManager").apply {
                isAccessible = true
            }
            val originalPm = sPackageManagerField.get(currentActivityThread) ?: return
            val iPmInterface = Class.forName("android.content.pm.IPackageManager")

            val proxy = Proxy.newProxyInstance(
                context.classLoader,
                arrayOf(iPmInterface),
                PackageManagerProxy(context, originalPm)
            )
            sPackageManagerField.set(currentActivityThread, proxy)

            // Also update ContextImpl's PackageManager mPM field if initialized
            val pm = context.packageManager
            try {
                val mPmField = pm.javaClass.getDeclaredField("mPM").apply { isAccessible = true }
                mPmField.set(pm, proxy)
            } catch (_: Throwable) {}
        } catch (t: Throwable) {
            Log.e(BridgeApp.TAG, "Failed to hook sPackageManager", t)
        }
    }

    fun resolveInstalledGmsPackage(pm: PackageManager): String? {
        for (pkg in GMS_PROVIDER_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {}
        }
        return null
    }

    fun resolveInstalledVendingPackage(pm: PackageManager): String? {
        for (pkg in VENDING_PROVIDER_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {}
        }
        return null
    }

    private class ActivityManagerProxy(
        private val context: Context,
        private val base: Any
    ) : InvocationHandler {

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (args != null && (method.name == "bindService" ||
                        method.name == "bindServiceInstance" ||
                        method.name == "bindIsolatedService" ||
                        method.name == "startService")
            ) {
                rerouteGmsAndBillingIntents(args)
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

            // Route Google Play Services & Auth / Games bindings
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

            // Route Google Play Billing (IAP v3-v7) & Licensing Service bindings
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

    private class PackageManagerProxy(
        private val context: Context,
        private val base: Any
    ) : InvocationHandler {

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (args != null && args.isNotEmpty()) {
                when (method.name) {
                    "getPackageInfo" -> {
                        val pkgName = args[0] as? String
                        if (pkgName != null && pkgName in VIRTUALIZED_GMS_PACKAGES) {
                            val realOrSynthesized = try {
                                method.invoke(base, *args) as? PackageInfo
                            } catch (_: Throwable) {
                                null
                            }
                            if (realOrSynthesized != null) return realOrSynthesized
                            return synthesizeGmsPackageInfo(pkgName)
                        }
                    }
                    "getApplicationInfo" -> {
                        val pkgName = args[0] as? String
                        if (pkgName != null && pkgName in VIRTUALIZED_GMS_PACKAGES) {
                            val realInfo = try {
                                method.invoke(base, *args) as? ApplicationInfo
                            } catch (_: Throwable) {
                                null
                            }
                            if (realInfo != null) return realInfo
                            return synthesizeGmsApplicationInfo(pkgName)
                        }
                    }
                    "getInstallerPackageName" -> {
                        return "com.android.vending"
                    }
                }
            }
            return try {
                method.invoke(base, *(args ?: emptyArray()))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        }

        private fun synthesizeGmsPackageInfo(pkgName: String): PackageInfo {
            return PackageInfo().apply {
                packageName = pkgName
                versionName = "24.36.35 (190400-675802152)"
                @Suppress("DEPRECATION")
                versionCode = 243635038
                applicationInfo = synthesizeGmsApplicationInfo(pkgName)
            }
        }

        private fun synthesizeGmsApplicationInfo(pkgName: String): ApplicationInfo {
            return ApplicationInfo(context.applicationInfo).apply {
                packageName = pkgName
                name = "Google Play Services (Spatial TinyVM)"
                enabled = true
                metaData = android.os.Bundle().apply {
                    putInt("com.google.android.gms.version", 243635000)
                }
            }
        }
    }
}
