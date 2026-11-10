package com.cukies.questgmsbridge

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

object VirtualApkEngine {

    private val classLoaderCache = mutableMapOf<String, DexClassLoader>()

    data class LoadedPlugin(
        val packageName: String,
        val appLabel: String,
        val apkPath: String,
        val splitCount: Int,
        val extractedSoFiles: List<String>,
        val nativeLibDir: String,
        val mainActivityClass: String,
        val gmsServicesDetected: Int
    )

    fun loadPluginApk(hostContext: Context, sourceApkPath: String, pkgName: String): LoadedPlugin? {
        return try {
            val pm = hostContext.packageManager
            val appInfo = try {
                pm.getApplicationInfo(pkgName, PackageManager.GET_META_DATA)
            } catch (_: Throwable) {
                null
            }

            val appLabel = if (appInfo != null) {
                try { pm.getApplicationLabel(appInfo).toString() } catch (_: Throwable) { pkgName }
            } else {
                pkgName
            }

            val sandboxDir = File(hostContext.filesDir, "vm_apps/$pkgName").apply { mkdirs() }
            val odexDir = File(sandboxDir, "odex").apply { mkdirs() }
            val nativeLibDir = File(sandboxDir, "lib/arm64").apply { mkdirs() }

            val srcBase = File(sourceApkPath)
            val extractedSoNames = mutableListOf<String>()

            if (srcBase.exists() && srcBase.canRead()) {
                extractNativeSoLibraries(srcBase, nativeLibDir, extractedSoNames)
            }

            val splitDirs = appInfo?.splitSourceDirs ?: emptyArray()
            splitDirs.forEach { splitPath ->
                val splitFile = File(splitPath)
                if (splitFile.exists() && splitFile.canRead()) {
                    extractNativeSoLibraries(splitFile, nativeLibDir, extractedSoNames)
                }
            }

            // Prepare isolated DexClassLoader directly from installed readable sourceDir (fast & zero-copy)
            if (srcBase.exists() && srcBase.canRead()) {
                val fullDexPath = (listOf(srcBase.absolutePath) + splitDirs.toList()).joinToString(File.pathSeparator)
                try {
                    classLoaderCache.getOrPut(pkgName) {
                        DexClassLoader(
                            fullDexPath,
                            odexDir.absolutePath,
                            nativeLibDir.absolutePath,
                            hostContext.classLoader
                        )
                    }
                } catch (t: Throwable) {
                    Log.w(BridgeApp.TAG, "DexClassLoader pre-warm note: ${t.message}")
                }
            }

            val pkgInfo: PackageInfo? = try {
                pm.getPackageInfo(pkgName, PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES)
            } catch (_: Throwable) {
                pm.getPackageArchiveInfo(sourceApkPath, PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES)
            }

            val launchIntent = pm.getLaunchIntentForPackage(pkgName)
            val entryActivity = launchIntent?.component?.className
                ?: pkgInfo?.activities?.firstOrNull()?.name
                ?: "DefaultLauncherActivity"

            val gmsServiceCount = pkgInfo?.services?.count {
                it.name.contains("gms", ignoreCase = true) ||
                it.name.contains("google", ignoreCase = true) ||
                it.name.contains("billing", ignoreCase = true) ||
                it.name.contains("firebase", ignoreCase = true)
            } ?: 0

            LoadedPlugin(
                packageName = pkgName,
                appLabel = appLabel,
                apkPath = sourceApkPath,
                splitCount = splitDirs.size,
                extractedSoFiles = extractedSoNames.distinct(),
                nativeLibDir = nativeLibDir.absolutePath,
                mainActivityClass = entryActivity,
                gmsServicesDetected = gmsServiceCount
            )
        } catch (e: Throwable) {
            Log.e(BridgeApp.TAG, "Safe fallback in loadPluginApk for $pkgName", e)
            LoadedPlugin(
                packageName = pkgName,
                appLabel = pkgName,
                apkPath = sourceApkPath,
                splitCount = 0,
                extractedSoFiles = emptyList(),
                nativeLibDir = hostContext.filesDir.absolutePath,
                mainActivityClass = "MainActivity",
                gmsServicesDetected = 0
            )
        }
    }

    private fun extractNativeSoLibraries(apkFile: File, targetLibDir: File, outNames: MutableList<String>) {
        try {
            ZipFile(apkFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && entry.name.startsWith("lib/arm64-v8a/") && entry.name.endsWith(".so")) {
                        val soFileName = entry.name.substringAfterLast('/')
                        outNames.add(soFileName)
                        val outFile = File(targetLibDir, soFileName)
                        if (!outFile.exists() || outFile.length() != entry.size) {
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
            }
        } catch (e: Throwable) {
            Log.w(BridgeApp.TAG, "Skipped .so unpack for ${apkFile.name}: ${e.message}")
        }
    }
}
