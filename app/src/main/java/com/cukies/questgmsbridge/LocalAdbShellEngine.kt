package com.cukies.questgmsbridge

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object LocalAdbShellEngine {

    private var activeDadb: Dadb? = null
    var isConnected: Boolean = false
        private set
    var lastStatusMessage: String = "ADB Disconnected (Port 5555)"
        private set

    private fun getOrCreateKeyPair(context: Context): AdbKeyPair {
        val keyDir = File(context.filesDir, "adb_keys").apply { mkdirs() }
        val privKey = File(keyDir, "adbkey")
        val pubKey = File(keyDir, "adbkey.pub")
        if (!privKey.exists() || !pubKey.exists()) {
            AdbKeyPair.generate(privKey, pubKey)
        }
        return AdbKeyPair.read(privKey, pubKey)
    }

    /**
     * Connects to the Quest 3's internal Wireless ADB daemon on 127.0.0.1 (default port 5555).
     */
    suspend fun connectLocalAdb(context: Context, port: Int = 5555): Boolean = withContext(Dispatchers.IO) {
        try {
            activeDadb?.close()
            val keyPair = getOrCreateKeyPair(context)
            val dadb = Dadb.create("127.0.0.1", port, keyPair)
            val resp = dadb.shell("id")
            if (resp.output.contains("uid=2000(shell)")) {
                activeDadb = dadb
                isConnected = true
                lastStatusMessage = "Wireless ADB Active (uid=2000 shell)"
                Log.i(BridgeApp.TAG, "Connected to Quest 3 Local ADB: ${resp.output.trim()}")
                return@withContext true
            }
            dadb.close()
            isConnected = false
            lastStatusMessage = "ADB Auth Pending (Accept prompt inside headset)"
            false
        } catch (t: Throwable) {
            isConnected = false
            lastStatusMessage = "ADB Offline (${t.javaClass.simpleName}: Enable TCP 5555)"
            Log.w(BridgeApp.TAG, "Local ADB connect warning: ${t.message}")
            false
        }
    }

    /**
     * Uses ADB uid=2000 privileges to configure Horizon OS for running the lightweight GMS stack
     * and stamps the target game with official Google Play Store installer provenance.
     */
    suspend fun prepareOsAndLaunchGameWithGms(
        context: Context,
        targetGamePkg: String,
        gmsPackages: List<String>
    ): String = withContext(Dispatchers.IO) {
        if (!isConnected && !connectLocalAdb(context, 5555)) {
            return@withContext "ADB not connected on 127.0.0.1:5555"
        }
        val dadb = activeDadb ?: return@withContext "No ADB session"
        val logBuilder = StringBuilder()

        try {
            // 1. Disable Android 12L/14 Phantom Process Killer on Quest 3 so background GMS VM is never killed
            dadb.shell("device_config set_sync_disabled_for_tests persistent")
            dadb.shell("device_config put activity_manager max_phantom_processes 2147483647")
            dadb.shell("settings put global settings_enable_monitor_phantom_procs false")
            logBuilder.append("• Unlocked Quest 3 background process limits\n")

            // 2. Stamp the target game's OS installer record as com.android.vending (Google Play Store)
            dadb.shell("pm set-installer $targetGamePkg com.android.vending")
            dadb.shell("appops set $targetGamePkg REQUEST_INSTALL_PACKAGES allow")
            logBuilder.append("• Stamped $targetGamePkg installer -> com.android.vending\n")

            // 3. Elevate all installed GMS / microG / Play Store / Play Games packages via ADB shell
            val allGmsTargets = (gmsPackages + listOf(
                "com.google.android.gms",
                "com.google.android.gsf",
                "com.android.vending",
                "com.google.android.play.games",
                "app.revanced.android.gms",
                "org.microg.gms"
            )).distinct()

            for (pkg in allGmsTargets) {
                // Whitelist from Quest 3 Doze / battery optimization so GMS never sleeps during gameplay
                dadb.shell("dumpsys deviceidle whitelist +$pkg")
                dadb.shell("cmd appops set $pkg RUN_IN_BACKGROUND allow")
                dadb.shell("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")
                dadb.shell("cmd appops set $pkg SYSTEM_alert_WINDOW allow")
                dadb.shell("cmd appops set $pkg GET_USAGE_STATS allow")

                // Grant standard & restricted permissions
                val perms = listOf(
                    "android.permission.GET_ACCOUNTS",
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE",
                    "android.permission.POST_NOTIFICATIONS"
                )
                perms.forEach { perm ->
                    try { dadb.shell("pm grant $pkg $perm") } catch (_: Throwable) {}
                }
            }
            logBuilder.append("• Elevated GMS & Play Games background services via ADB\n")

            // 4. Pre-warm Google Play Services & Play Games background authentication daemons
            dadb.shell("am startservice -a com.google.android.gms.auth.service.START")
            dadb.shell("am startservice -a com.google.android.gms.games.service.START")
            dadb.shell("am startservice -a com.android.vending.billing.InAppBillingService.BIND")

            // 5. Launch the game via privileged ADB monkey/am start so it inherits clean window state
            dadb.shell("monkey -p $targetGamePkg -c android.intent.category.LAUNCHER 1")
            logBuilder.append("• Launched $targetGamePkg with active GMS stack")
            return@withContext logBuilder.toString()
        } catch (t: Throwable) {
            Log.e(BridgeApp.TAG, "ADB execution error", t)
            return@withContext "ADB Error: ${t.message}"
        }
    }
}
