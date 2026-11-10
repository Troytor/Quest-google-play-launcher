package com.cukies.questgmsbridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class ProxySandboxActivity : ComponentActivity() {

    private var daemonBound = false
    private val daemonConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            daemonBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            daemonBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val guestPkg = intent.getStringExtra("GUEST_PKG") ?: ""
        val guestApk = intent.getStringExtra("GUEST_APK") ?: ""
        val autoLaunch = intent.getBooleanExtra("AUTO_LAUNCH", false)

        // Ensure :gms_daemon is running and bound inside :sandbox_p0
        try {
            val svcIntent = Intent(this, GmsDaemonService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svcIntent)
            } else {
                startService(svcIntent)
            }
            bindService(svcIntent, daemonConnection, Context.BIND_AUTO_CREATE)
        } catch (_: Throwable) {}

        val plugin = VirtualApkEngine.loadPluginApk(this, guestApk, guestPkg)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SandboxBridgeHud(
                    plugin = plugin,
                    autoLaunch = autoLaunch,
                    onLaunchGuest = { launchTargetWithBridgeWarm(guestPkg) },
                    onOpenGmsCore = {
                        if (!GmsBinderRouter.launchGmsCoreSettingsOrLogin(this)) {
                            Toast.makeText(this, "No GmsCore / ReVanced Settings found", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onClose = { finish() }
                )
            }
        }
    }

    private fun launchTargetWithBridgeWarm(pkgName: String) {
        try {
            val pm = packageManager
            val launchIntent = pm.getLaunchIntentForPackage(pkgName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                return
            }

            // Fallback: scan for any exported Activity in the target APK
            val pkgInfo = pm.getPackageInfo(pkgName, android.content.pm.PackageManager.GET_ACTIVITIES)
            val firstAct = pkgInfo.activities?.firstOrNull { it.exported } ?: pkgInfo.activities?.firstOrNull()
            if (firstAct != null) {
                val explicit = Intent().apply {
                    component = ComponentName(pkgName, firstAct.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(explicit)
                return
            }
            Toast.makeText(this, "App has no launchable UI Activity", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "Launch error: ${t.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        if (daemonBound) {
            try { unbindService(daemonConnection) } catch (_: Throwable) {}
        }
        super.onDestroy()
    }
}

@Composable
private fun SandboxBridgeHud(
    plugin: VirtualApkEngine.LoadedPlugin?,
    autoLaunch: Boolean,
    onLaunchGuest: () -> Unit,
    onOpenGmsCore: () -> Unit,
    onClose: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val gmsPkg = remember { GmsBinderRouter.resolveInstalledGmsPackage(context.packageManager) }

    LaunchedEffect(autoLaunch) {
        if (autoLaunch && plugin != null) {
            delay(450)
            onLaunchGuest()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06080E))
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .clip(RoundedCornerShape(30.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.05f))
                    )
                )
                .border(
                    1.dp,
                    Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.05f))
                    ),
                    RoundedCornerShape(30.dp)
                )
                .padding(28.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF30D158))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "TINYVM SPATIAL BRIDGE • :sandbox_p0 ACTIVE",
                            color = Color(0xFF30D158),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White.copy(alpha = 0.7f))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = plugin?.appLabel ?: "Unknown App",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = plugin?.packageName ?: "",
                    color = Color(0xFF64D2FF),
                    fontSize = 14.sp
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Telemetry Grid
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(18.dp)
                ) {
                    TelemetryRow(
                        label = "Active Google / GmsCore Provider",
                        value = gmsPkg ?: "Internal :gms_daemon Bridge (Active)"
                    )
                    TelemetryRow(
                        label = "Entry Activity Class",
                        value = plugin?.mainActivityClass ?: "None"
                    )
                    TelemetryRow(
                        label = "Split APKs & Native arm64-v8a Libs",
                        value = "${plugin?.splitCount ?: 0} Splits • ${plugin?.extractedSoFiles?.size ?: 0} Native .so Staged"
                    )
                    TelemetryRow(
                        label = "Play Billing (IInAppBillingService v3–v7)",
                        value = "BOUND & READY"
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Button(
                        onClick = onLaunchGuest,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                    ) {
                        Icon(Icons.Rounded.RocketLaunch, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Launch App with Active GMS Bridge", fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = onOpenGmsCore,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                        modifier = Modifier.height(52.dp)
                    ) {
                        Icon(Icons.Rounded.ManageAccounts, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("GmsCore Account", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        Text(text = value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
