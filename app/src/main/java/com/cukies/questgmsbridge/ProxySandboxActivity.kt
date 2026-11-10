package com.cukies.questgmsbridge

import android.app.Activity
import android.content.Intent
import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class ProxySandboxActivity : Activity() {

    private var activePlugin: VirtualApkEngine.LoadedPlugin? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val guestPkg = intent.getStringExtra("GUEST_PKG")
        val guestApk = intent.getStringExtra("GUEST_APK")

        if (guestPkg != null && guestApk != null) {
            activePlugin = VirtualApkEngine.loadPluginApk(this, guestApk, guestPkg)
        }

        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B0E17"))
            setPadding(64, 64, 64, 64)
        }

        val badge = TextView(this).apply {
            text = "iOS 27 SPATIAL SANDBOX • :sandbox_p0"
            textSize = 13f
            setTextColor(Color.parseColor("#38EF7D"))
            setTypeface(typeface, Typeface.BOLD)
        }

        val title = TextView(this).apply {
            text = "Mounted: ${guestPkg ?: "Unknown"}"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 12)
            setTypeface(typeface, Typeface.BOLD)
        }

        val details = TextView(this).apply {
            text = "Entry Activity: ${activePlugin?.mainActivityClass}\n" +
                    "Native Libs Extracted: ${activePlugin?.nativeLibDir}\n" +
                    "Split APKs Mounted: ${activePlugin?.splitPaths?.size ?: 0}\n" +
                    "GMS & Play Billing Binder Bridge: ACTIVE"
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, 32)
        }

        val launchBridgedWarmBtn = Button(this).apply {
            text = "Launch Window with Warm GMS Bridge Session"
            setOnClickListener {
                if (guestPkg != null) {
                    packageManager.getLaunchIntentForPackage(guestPkg)?.let { launch ->
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launch)
                        finish()
                    }
                }
            }
        }

        root.addView(badge)
        root.addView(title)
        root.addView(details)
        root.addView(launchBridgedWarmBtn)
        setContentView(root)
    }

    override fun getResources(): Resources {
        return activePlugin?.resources ?: super.getResources()
    }

    override fun getAssets(): AssetManager {
        return activePlugin?.resources?.assets ?: super.getAssets()
    }

    override fun getClassLoader(): ClassLoader {
        return activePlugin?.classLoader ?: super.getClassLoader()
    }
}
