package com.cukies.questgmsbridge

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address

object VirtualKernelEngine {

    const val MAX_RUNNING_WINDOWS = 3

    data class VmAppEntry(
        val packageName: String,
        val label: String,
        val category: String,
        val versionName: String,
        val sizeMb: String,
        val iconColorHex: Long,
        val apkDownloadUrl: String = ""
    )

    data class RunningVmWindow(
        val slotIndex: Int, // 0, 1, or 2
        val app: VmAppEntry,
        val isMinimizedInDock: Boolean
    )

    // Reactive state for Compose UI animations
    val isGoogleSignedIn = mutableStateOf(false)
    val googleAccountEmail = mutableStateOf("")
    val questWifiIp = mutableStateOf("Connecting Quest Wi-Fi...")
    val isWifiOnline = mutableStateOf(false)

    // Apps installed inside the VM Home Screen (Starts empty; only Play Store is shown initially!)
    val vmInstalledApps = mutableStateListOf<VmAppEntry>()

    // Up to 3 concurrent apps running in separate windows or minimized in the Fluid Pill Dock
    val activeVmWindows = mutableStateListOf<RunningVmWindow>()

    private val instrumentation = Instrumentation()

    fun bootVirtualAndroidOs(context: Context) {
        try {
            val nandRoot = File(context.filesDir, "virtual_nand").apply {
                File(this, "system/priv-app/GmsCore").mkdirs()
                File(this, "system/priv-app/Phonesky").mkdirs()
                File(this, "data/app").mkdirs()
            }

            val buildProp = File(nandRoot, "system/build.prop")
            if (!buildProp.exists()) {
                buildProp.writeText(
                    """
                    ro.product.brand=google
                    ro.product.name=husky
                    ro.product.device=husky
                    ro.product.model=Pixel 8 Pro
                    ro.product.manufacturer=Google
                    ro.build.version.release=14
                    ro.com.google.gmsversion=14_202405
                    """.trimIndent()
                )
            }

            loadPersistedVmState(context)
            bindVirtualOsToQuestNetwork(context)
        } catch (t: Throwable) {
            Log.e(BridgeApp.TAG, "VM boot error", t)
        }
    }

    fun bindVirtualOsToQuestNetwork(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val activeNet = cm.activeNetwork
            if (activeNet != null) {
                cm.bindProcessToNetwork(activeNet)
                updateLinkInfo(cm, activeNet)
            }

            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    cm.bindProcessToNetwork(network)
                    updateLinkInfo(cm, network)
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    updateLinkInfo(cm, network)
                }
            })
        } catch (_: Throwable) {}
    }

    private fun updateLinkInfo(cm: ConnectivityManager, network: Network) {
        try {
            val props = cm.getLinkProperties(network)
            val ipv4 = props?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress ?: "Quest Wi-Fi Online"
            questWifiIp.value = ipv4
            isWifiOnline.value = true
        } catch (_: Throwable) {}
    }

    fun saveGoogleAccountLogin(context: Context, email: String) {
        isGoogleSignedIn.value = true
        googleAccountEmail.value = email
        persistVmState(context)
    }

    fun installAppIntoVmHomeScreen(context: Context, app: VmAppEntry) {
        if (vmInstalledApps.none { it.packageName == app.packageName }) {
            vmInstalledApps.add(app)
            persistVmState(context)
        }
    }

    fun uninstallAppFromVm(context: Context, packageName: String) {
        vmInstalledApps.removeAll { it.packageName == packageName }
        activeVmWindows.removeAll { it.app.packageName == packageName }
        persistVmState(context)
    }

    /**
     * Opens or restores an app in one of the 3 concurrent VM Multi-Window slots.
     * If 3 apps are already running, returns null so the UI can notify the user to minimize/close one.
     */
    fun openOrRestoreVmWindow(context: Context, app: VmAppEntry): Int? {
        val existingIdx = activeVmWindows.indexOfFirst { it.app.packageName == app.packageName }
        if (existingIdx != -1) {
            val updated = activeVmWindows[existingIdx].copy(isMinimizedInDock = false)
            activeVmWindows[existingIdx] = updated
            spawnSeparateSpatialWindowIntent(context, updated.slotIndex, app)
            return updated.slotIndex
        }

        if (activeVmWindows.size >= MAX_RUNNING_WINDOWS) {
            return null // All 3 window slots are occupied
        }

        val usedSlots = activeVmWindows.map { it.slotIndex }.toSet()
        val freeSlot = (0 until MAX_RUNNING_WINDOWS).firstOrNull { it !in usedSlots } ?: 0

        val newWin = RunningVmWindow(
            slotIndex = freeSlot,
            app = app,
            isMinimizedInDock = false
        )
        activeVmWindows.add(newWin)
        spawnSeparateSpatialWindowIntent(context, freeSlot, app)
        return freeSlot
    }

    fun minimizeWindowToFluidDock(packageName: String) {
        val idx = activeVmWindows.indexOfFirst { it.app.packageName == packageName }
        if (idx != -1) {
            activeVmWindows[idx] = activeVmWindows[idx].copy(isMinimizedInDock = true)
        }
    }

    fun closeVmWindowSlot(packageName: String) {
        activeVmWindows.removeAll { it.app.packageName == packageName }
    }

    private fun spawnSeparateSpatialWindowIntent(context: Context, slot: Int, app: VmAppEntry) {
        val pm = context.packageManager
        val sourceApk = try {
            pm.getApplicationInfo(app.packageName, 0).sourceDir ?: ""
        } catch (_: Throwable) {
            ""
        }

        val winIntent = Intent(context, ProxySandboxActivity::class.java).apply {
            putExtra("VM_SLOT_INDEX", slot)
            putExtra("GUEST_PKG", app.packageName)
            putExtra("GUEST_LABEL", app.label)
            putExtra("GUEST_APK", sourceApk)
            putExtra("ICON_COLOR", app.iconColorHex)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
            )
        }
        context.startActivity(winIntent)
    }

    suspend fun dispatchVirtualKeyEvent(keyCode: Int) = withContext(Dispatchers.IO) {
        try {
            instrumentation.sendKeyDownUpSync(keyCode)
        } catch (_: Throwable) {}
    }

    private fun persistVmState(context: Context) {
        try {
            val file = File(context.filesDir, "virtual_nand/vm_home_state.json")
            val root = JSONObject().apply {
                put("signedIn", isGoogleSignedIn.value)
                put("email", googleAccountEmail.value)
                val appsArr = JSONArray()
                vmInstalledApps.forEach { app ->
                    appsArr.put(JSONObject().apply {
                        put("packageName", app.packageName)
                        put("label", app.label)
                        put("category", app.category)
                        put("versionName", app.versionName)
                        put("sizeMb", app.sizeMb)
                        put("iconColorHex", app.iconColorHex)
                        put("apkDownloadUrl", app.apkDownloadUrl)
                    })
                }
                put("installedApps", appsArr)
            }
            file.writeText(root.toString(2))
        } catch (_: Throwable) {}
    }

    private fun loadPersistedVmState(context: Context) {
        try {
            val file = File(context.filesDir, "virtual_nand/vm_home_state.json")
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            isGoogleSignedIn.value = root.optBoolean("signedIn", false)
            googleAccountEmail.value = root.optString("email", "")
            val arr = root.optJSONArray("installedApps") ?: return
            vmInstalledApps.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                vmInstalledApps.add(
                    VmAppEntry(
                        packageName = o.optString("packageName"),
                        label = o.optString("label"),
                        category = o.optString("category", "App"),
                        versionName = o.optString("versionName", "1.0"),
                        sizeMb = o.optString("sizeMb", "45 MB"),
                        iconColorHex = o.optLong("iconColorHex", 0xFF0A84FF),
                        apkDownloadUrl = o.optString("apkDownloadUrl", "")
                    )
                )
            }
        } catch (_: Throwable) {}
    }
}
