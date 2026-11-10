package com.cukies.questgmsbridge

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

object VirtualApkEngine {

    private val classLoaderCache = mutableMapOf<String, DexClassLoader>()
    private val resourcesCache = mutableMapOf<String, Resources>()

    data class LoadedPlugin(
        val packageName: String,
        val apkPath: String,
        val splitPaths: List<String>,
        val nativeLibDir: String,
        val mainActivityClass: String,
        val classLoader: DexClassLoader,
        val resources: Resources
    )

    @SuppressLint("DiscouragedPrivateApi")
    fun loadPluginApk(hostContext: Context, sourceApkPath: String, pkgName: String): LoadedPlugin? {
        try {
            val sandboxDir = File(hostContext.filesDir, "vm_apps/$pkgName").apply { mkdirs() }
            val stagedBaseApk = File(sandboxDir, "base.apk")
            val odexDir = File(sandboxDir, "odex").apply { mkdirs() }
            val nativeLibDir = File(sandboxDir, "lib/arm64").apply { mkdirs() }

            val srcBase = File(sourceApkPath)
            if (!stagedBaseApk.exists() || stagedBaseApk.length() != srcBase.length()) {
                if (stagedBaseApk.exists()) stagedBaseApk.setWritable(true)
                srcBase.copyTo(stagedBaseApk, overwrite = true)
                extractNativeSoLibraries(stagedBaseApk, nativeLibDir)
            }
            stagedBaseApk.setReadOnly()

            val stagedSplits = mutableListOf<String>()
            try {
                val appInfo = hostContext.packageManager.getApplicationInfo(pkgName, 0)
                appInfo.splitSourceDirs?.forEachIndexed { idx, splitSrcPath ->
                    val splitFile = File(splitSrcPath)
                    val stagedSplit = File(sandboxDir, "split_$idx.apk")
                    if (!stagedSplit.exists() || stagedSplit.length() != splitFile.length()) {
                        if (stagedSplit.exists()) stagedSplit.setWritable(true)
                        splitFile.copyTo(stagedSplit, overwrite = true)
                        extractNativeSoLibraries(stagedSplit, nativeLibDir)
                    }
                    stagedSplit.setReadOnly()
                    stagedSplits.add(stagedSplit.absolutePath)
                }
            } catch (_: Exception) {}

            val fullDexPath = (listOf(stagedBaseApk.absolutePath) + stagedSplits).joinToString(File.pathSeparator)

            val dexClassLoader = classLoaderCache.getOrPut(pkgName) {
                DexClassLoader(
                    fullDexPath,
                    odexDir.absolutePath,
                    nativeLibDir.absolutePath,
                    hostContext.classLoader
                )
            }

            val pluginResources = resourcesCache.getOrPut(pkgName) {
                val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
                val addAssetPath = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java).apply {
                    isAccessible = true
                }
                addAssetPath.invoke(assetManager, stagedBaseApk.absolutePath)
                stagedSplits.forEach { splitPath ->
                    addAssetPath.invoke(assetManager, splitPath)
                }

                @Suppress("DEPRECATION")
                Resources(
                    assetManager,
                    hostContext.resources.displayMetrics,
                    hostContext.resources.configuration
                )
            }

            val pkgInfo: PackageInfo? = hostContext.packageManager.getPackageArchiveInfo(
                stagedBaseApk.absolutePath,
                PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES
            )

            val launchIntent = hostContext.packageManager.getLaunchIntentForPackage(pkgName)
            val entryActivity = launchIntent?.component?.className
                ?: pkgInfo?.activities?.firstOrNull()?.name
                ?: return null

            return LoadedPlugin(
                packageName = pkgName,
                apkPath = stagedBaseApk.absolutePath,
                splitPaths = stagedSplits,
                nativeLibDir = nativeLibDir.absolutePath,
                mainActivityClass = entryActivity,
                classLoader = dexClassLoader,
                resources = pluginResources
            )
        } catch (e: Exception) {
            Log.e(BridgeApp.TAG, "Failed to mount APK into TinyVM: $pkgName", e)
            return null
        }
    }

    private fun extractNativeSoLibraries(apkFile: File, targetLibDir: File) {
        try {
            ZipFile(apkFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && entry.name.startsWith("lib/arm64-v8a/") && entry.name.endsWith(".so")) {
                        val soFileName = entry.name.substringAfterLast('/')
                        val outFile = File(targetLibDir, soFileName)
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(outFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        outFile.setReadable(true, false)
                        outFile.setExecutable(true, false)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(BridgeApp.TAG, "Native .so extraction warning for ${apkFile.name}: ${e.message}")
        }
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun hookInstrumentation() {
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThread = activityThreadClass.getDeclaredMethod("currentActivityThread").apply {
                isAccessible = true
            }.invoke(null)

            val mInstrumentationField = activityThreadClass.getDeclaredField("mInstrumentation").apply {
                isAccessible = true
            }
            val baseInstrumentation = mInstrumentationField.get(currentActivityThread) as Instrumentation
            mInstrumentationField.set(currentActivityThread, SandboxInstrumentation(baseInstrumentation))
        } catch (t: Throwable) {
            Log.e(BridgeApp.TAG, "Failed to hook Instrumentation", t)
        }
    }

    class SandboxInstrumentation(private val base: Instrumentation) : Instrumentation() {
        override fun newActivity(cl: ClassLoader?, className: String?, intent: Intent?): Activity {
            if (className == ProxySandboxActivity::class.java.name && intent != null) {
                val guestPkg = intent.getStringExtra("GUEST_PKG")
                val guestApk = intent.getStringExtra("GUEST_APK")
                val guestTargetClass = intent.getStringExtra("GUEST_CLASS")

                if (guestPkg != null && guestApk != null && guestTargetClass != null) {
                    val plugin = loadPluginApk(BridgeApp.instance, guestApk, guestPkg)
                    if (plugin != null) {
                        return try {
                            val clazz = plugin.classLoader.loadClass(guestTargetClass)
                            (clazz.getDeclaredConstructor().newInstance() as Activity).also {
                                Log.i(BridgeApp.TAG, "Instantiated Guest Activity in :sandbox_p0 -> $guestTargetClass")
                            }
                        } catch (e: Throwable) {
                            Log.w(BridgeApp.TAG, "Guest Activity required fallback shell: ${e.message}")
                            super.newActivity(cl, className, intent)
                        }
                    }
                }
            }
            return super.newActivity(cl, className, intent)
        }

        override fun callActivityOnCreate(activity: Activity?, icicle: Bundle?) {
            activity?.applicationContext?.let { GmsBinderRouter.installHooks(it) }
            base.callActivityOnCreate(activity, icicle)
        }
    }
}
