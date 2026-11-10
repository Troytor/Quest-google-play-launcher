package com.cukies.questgmsbridge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.io.File

object VirtualOsManager {

    data class MiniOsCoreStatus(
        val rootfsReady: Boolean,
        val gsfInstalled: Boolean,
        val gmsInstalled: Boolean,
        val playStoreInstalled: Boolean,
        val playGamesInstalled: Boolean,
        val externalVmContainerPkg: String?,
        val activeGmsPackages: List<String>
    )

    private val ESSENTIAL_GMS_PACKAGES = mapOf(
        "GSF (Services Framework)" to listOf("com.google.android.gsf"),
        "GmsCore (Play Services)" to listOf("com.google.android.gms", "org.microg.gms", "app.revanced.android.gms"),
        "Play Store (Phonesky / Billing)" to listOf("com.android.vending"),
        "Google Play Games (Achievements/Saves)" to listOf("com.google.android.play.games")
    )

    private val REAL_VM_CONTAINERS = listOf(
        "com.gbox.android",           // GBox (Stripped Virtual Android OS for GMS)
        "com.vmos.pro",               // VMOS / Virtual Android Kernel
        "com.clone.android.dual.space"
    )

    /**
     * Initializes the stripped-down Virtual Android directory hierarchy strictly for GMS dependencies.
     */
    fun inspectAndPrepareMiniOs(context: Context): MiniOsCoreStatus {
        val rootfs = File(context.filesDir, "mini_android_os").apply {
            File(this, "system/priv-app/GmsCore").mkdirs()
            File(this, "system/priv-app/Phonesky").mkdirs()
            File(this, "system/priv-app/GoogleServicesFramework").mkdirs()
            File(this, "system/etc/permissions").mkdirs()
            File(this, "data/system/users/0").mkdirs()
        }

        // Write privapp-permissions-google.xml inside the virtual rootfs
        val permXml = File(rootfs, "system/etc/permissions/privapp-permissions-google.xml")
        if (!permXml.exists()) {
            permXml.writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <permissions>
                    <privapp-permissions package="com.google.android.gms">
                        <permission name="android.permission.FAKE_PACKAGE_SIGNATURE"/>
                        <permission name="android.permission.INTERACT_ACROSS_USERS"/>
                        <permission name="android.permission.DUMP"/>
                    </privapp-permissions>
                    <privapp-permissions package="com.android.vending">
                        <permission name="android.permission.FAKE_PACKAGE_SIGNATURE"/>
                        <permission name="android.permission.INSTALL_PACKAGES"/>
                    </privapp-permissions>
                </permissions>
                """.trimIndent()
            )
        }

        val pm = context.packageManager
        val foundPackages = mutableListOf<String>()

        fun isAnyInstalled(candidates: List<String>): Boolean {
            for (pkg in candidates) {
                try {
                    pm.getPackageInfo(pkg, 0)
                    foundPackages.add(pkg)
                    return true
                } catch (_: PackageManager.NameNotFoundException) {}
            }
            return false
        }

        val gsf = isAnyInstalled(ESSENTIAL_GMS_PACKAGES["GSF (Services Framework)"]!!) ||
                File(rootfs, "system/priv-app/GoogleServicesFramework/GoogleServicesFramework.apk").exists()
        val gms = isAnyInstalled(ESSENTIAL_GMS_PACKAGES["GmsCore (Play Services)"]!!) ||
                File(rootfs, "system/priv-app/GmsCore/GmsCore.apk").exists()
        val store = isAnyInstalled(ESSENTIAL_GMS_PACKAGES["Play Store (Phonesky / Billing)"]!!) ||
                File(rootfs, "system/priv-app/Phonesky/Phonesky.apk").exists()
        val games = isAnyInstalled(ESSENTIAL_GMS_PACKAGES["Google Play Games (Achievements/Saves)"]!!)

        val externalVm = REAL_VM_CONTAINERS.firstOrNull { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: Throwable) {
                false
            }
        }

        return MiniOsCoreStatus(
            rootfsReady = rootfs.exists(),
            gsfInstalled = gsf,
            gmsInstalled = gms,
            playStoreInstalled = store,
            playGamesInstalled = games,
            externalVmContainerPkg = externalVm,
            activeGmsPackages = foundPackages.distinct()
        )
    }
}
