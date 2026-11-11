package com.cukies.questgmsbridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        window.setBackgroundDrawableResource(android.R.color.transparent)

        val guestPkg = intent.getStringExtra("GUEST_PKG") ?: ""
        val guestApk = intent.getStringExtra("GUEST_APK") ?: ""
        val autoLaunch = intent.getBooleanExtra("AUTO_LAUNCH", true)

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
        val iconBitmap = try {
            packageManager.getApplicationIcon(guestPkg).toBitmapSafe(128, 128)?.asImageBitmap()
        } catch (_: Throwable) {
            null
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SpatialFullscreenWindowWithFluidPill(
                    plugin = plugin,
                    iconBitmap = iconBitmap,
                    autoLaunch = autoLaunch,
                    onSpawnAdjacentFullscreenWindow = { spawnSeparateNativeQuestWindow(guestPkg) },
                    onOpenInAppPurchaseOverlay = {
                        val sheetIntent = Intent(this, BillingPurchaseSheetActivity::class.java).apply {
                            putExtra("REQUESTED_PKG", guestPkg)
                            putExtra("REQUESTED_SKU", "full_unlock_entitlement")
                            putExtra("ITEM_TYPE", "inapp")
                        }
                        startActivity(sheetIntent)
                    },
                    onOpenGmsCoreAuth = {
                        if (!GmsBinderRouter.launchGmsCoreSettingsOrLogin(this)) {
                            Toast.makeText(this, "Open GBox GMS Core tab to configure OAuth", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onCloseSpatialSession = { finish() }
                )
            }
        }
    }

    private fun spawnSeparateNativeQuestWindow(pkgName: String) {
        try {
            // Ensure Virtual Machine is bound to Quest 3 Wi-Fi before spawning the app window
            VirtualKernelEngine.bindVirtualOsToQuestNetwork(this)

            val pm = packageManager
            val baseIntent = pm.getLaunchIntentForPackage(pkgName) ?: run {
                val pkgInfo = pm.getPackageInfo(pkgName, android.content.pm.PackageManager.GET_ACTIVITIES)
                val firstAct = pkgInfo.activities?.firstOrNull { it.exported } ?: pkgInfo.activities?.firstOrNull()
                if (firstAct != null) {
                    Intent().apply { component = ComponentName(pkgName, firstAct.name) }
                } else null
            }

            if (baseIntent != null) {
                baseIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                )
                startActivity(baseIntent)
            } else {
                Toast.makeText(this, "No launchable window found for $pkgName", Toast.LENGTH_SHORT).show()
            }
        } catch (t: Throwable) {
            Toast.makeText(this, "Window spawn error: ${t.localizedMessage}", Toast.LENGTH_LONG).show()
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
private fun SpatialFullscreenWindowWithFluidPill(
    plugin: VirtualApkEngine.LoadedPlugin?,
    iconBitmap: ImageBitmap?,
    autoLaunch: Boolean,
    onSpawnAdjacentFullscreenWindow: () -> Unit,
    onOpenInAppPurchaseOverlay: () -> Unit,
    onOpenGmsCoreAuth: () -> Unit,
    onCloseSpatialSession: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pillExpanded by remember { mutableStateOf(true) }
    var activeFluidAction by remember { mutableStateOf("WINDOW") }
    var vmState by remember { mutableStateOf(VirtualKernelEngine.currentNetworkState) }
    var statusBannerText by remember {
        mutableStateOf("VM Network Bridged (${vmState.questLocalIp}) • Play Services Online")
    }

    val infiniteTransition = rememberInfiniteTransition(label = "FluidPillPulse")
    val glowPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowPhase"
    )

    LaunchedEffect(Unit) {
        vmState = VirtualKernelEngine.verifyLiveGooglePlayNetworkBridge(context)
        statusBannerText = "Quest Wi-Fi Bridged (${vmState.questLocalIp}) • Google Play RTT: ${vmState.latencyMs}ms"
        if (autoLaunch && plugin != null) {
            delay(280)
            onSpawnAdjacentFullscreenWindow()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .padding(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // =========================================================================
        // 1. FLOATING iOS 27 FLUID GLASS NAVIGATION PILL (DOCKED NEXT TO WINDOW)
        // =========================================================================
        val pillWidth by animateDpAsState(
            targetValue = if (pillExpanded) 96.dp else 68.dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "PillWidth"
        )

        Box(
            modifier = Modifier
                .width(pillWidth)
                .wrapContentHeight()
                .clip(RoundedCornerShape(999.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF141B2D).copy(alpha = 0.58f),
                            Color(0xFF0A0E18).copy(alpha = 0.68f)
                        )
                    )
                )
                .border(
                    width = 1.5.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.45f + 0.15f * glowPhase),
                            Color(0xFF64D2FF).copy(alpha = 0.25f),
                            Color.White.copy(alpha = 0.08f)
                        )
                    ),
                    shape = RoundedCornerShape(999.dp)
                )
                .padding(vertical = 18.dp, horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                FluidNavButton(
                    icon = if (pillExpanded) Icons.Rounded.LensBlur else Icons.Rounded.MenuOpen,
                    label = if (pillExpanded) "Fluid" else "",
                    isActive = true,
                    accentColor = Color(0xFF64D2FF),
                    onClick = { pillExpanded = !pillExpanded }
                )

                Divider(
                    color = Color.White.copy(alpha = 0.14f),
                    modifier = Modifier
                        .width(38.dp)
                        .clip(CircleShape)
                )

                FluidNavButton(
                    icon = Icons.Rounded.ArrowBackIosNew,
                    label = if (pillExpanded) "Back" else "",
                    isActive = activeFluidAction == "BACK",
                    accentColor = Color.White,
                    onClick = {
                        activeFluidAction = "BACK"
                        scope.launch {
                            VirtualKernelEngine.dispatchVirtualKeyEvent(KeyEvent.KEYCODE_BACK)
                            statusBannerText = "Dispatched Virtual KeyEvent (KEYCODE_BACK)"
                        }
                    }
                )

                FluidNavButton(
                    icon = Icons.Rounded.OpenInNew,
                    label = if (pillExpanded) "Window" else "",
                    isActive = activeFluidAction == "WINDOW",
                    accentColor = Color(0xFF0A84FF),
                    onClick = {
                        activeFluidAction = "WINDOW"
                        statusBannerText = "Spawning Separate Fullscreen Window..."
                        onSpawnAdjacentFullscreenWindow()
                    }
                )

                FluidNavButton(
                    icon = Icons.Rounded.ShoppingBag,
                    label = if (pillExpanded) "Play IAP" else "",
                    isActive = activeFluidAction == "IAP",
                    accentColor = Color(0xFF30D158),
                    onClick = {
                        activeFluidAction = "IAP"
                        statusBannerText = "Opening Google Play Billing Entitlement Sheet..."
                        onOpenInAppPurchaseOverlay()
                    }
                )

                FluidNavButton(
                    icon = Icons.Rounded.AccountCircle,
                    label = if (pillExpanded) "Account" else "",
                    isActive = activeFluidAction == "ACCOUNT",
                    accentColor = Color(0xFFBF5AF2),
                    onClick = {
                        activeFluidAction = "ACCOUNT"
                        statusBannerText = "Opening Google Account OAuth Bridge..."
                        onOpenGmsCoreAuth()
                    }
                )

                Divider(
                    color = Color.White.copy(alpha = 0.14f),
                    modifier = Modifier
                        .width(38.dp)
                        .clip(CircleShape)
                )

                FluidNavButton(
                    icon = Icons.Rounded.PowerSettingsNew,
                    label = if (pillExpanded) "Exit" else "",
                    isActive = false,
                    accentColor = Color(0xFFFF453A),
                    onClick = onCloseSpatialSession
                )
            }
        }

        Spacer(modifier = Modifier.width(20.dp))

        // =========================================================================
        // 2. TRANSLUCENT PASSTHROUGH SPATIAL COMPANION CANVAS (NEXT TO PILL)
        // =========================================================================
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(38.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF101626).copy(alpha = 0.52f),
                            Color(0xFF090D16).copy(alpha = 0.62f)
                        )
                    )
                )
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.38f),
                            Color(0xFF0A84FF).copy(alpha = 0.18f),
                            Color.White.copy(alpha = 0.05f)
                        )
                    ),
                    shape = RoundedCornerShape(38.dp)
                )
                .padding(32.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF0A84FF).copy(alpha = 0.18f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * (0.25f + 0.1f * glowPhase), size.height * 0.2f),
                        radius = size.width * 0.45f
                    )
                )
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.White.copy(alpha = 0.10f))
                            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF30D158))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = statusBannerText,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0xFF0A84FF).copy(alpha = 0.20f))
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = vmState.deviceProfile,
                            color = Color(0xFF64D2FF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color.White.copy(alpha = 0.07f))
                        .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(28.dp))
                        .padding(26.dp)
                ) {
                    if (iconBitmap != null) {
                        Image(
                            bitmap = iconBitmap,
                            contentDescription = plugin?.appLabel,
                            modifier = Modifier
                                .size(82.dp)
                                .clip(RoundedCornerShape(22.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(82.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(Color(0xFF0A84FF).copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Apps,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(42.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(22.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = plugin?.appLabel ?: "Sandboxed Application",
                            color = Color.White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = plugin?.packageName ?: "",
                            color = Color(0xFF64D2FF),
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Quest 3 Wi-Fi Bridge: ${vmState.questLocalIp} (DNS: ${vmState.dnsServers}) • ${plugin?.splitCount ?: 0} Split APKs • ${plugin?.extractedSoFiles?.size ?: 0} arm64 Libraries Mounted",
                            color = Color.White.copy(alpha = 0.68f),
                            fontSize = 13.sp
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Button(
                        onClick = onSpawnAdjacentFullscreenWindow,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .weight(1.3f)
                            .height(56.dp)
                    ) {
                        Icon(Icons.Rounded.OpenInFull, contentDescription = null)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            "Open Separate Full-Screen Window",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = onOpenInAppPurchaseOverlay,
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, Color(0xFF30D158).copy(alpha = 0.55f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                    ) {
                        Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = Color(0xFF30D158))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sync Play Store Purchases", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun FluidNavButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.84f
            isActive -> 1.08f
            else -> 1.0f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "FluidNavButtonScale"
    )

    val bgAlpha by animateFloatAsState(
        targetValue = if (isActive || isPressed) 0.28f else 0.08f,
        animationSpec = tween(220),
        label = "FluidNavButtonAlpha"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(24.dp))
            .background(accentColor.copy(alpha = bgAlpha))
            .border(
                width = 1.dp,
                color = if (isActive) accentColor.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.14f),
                shape = RoundedCornerShape(24.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isActive) accentColor else Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(22.dp)
        )
        AnimatedVisibility(visible = label.isNotBlank()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = label,
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

private fun Drawable.toBitmapSafe(width: Int, height: Int): Bitmap? {
    return try {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        bitmap
    } catch (_: Throwable) {
        null
    }
}
