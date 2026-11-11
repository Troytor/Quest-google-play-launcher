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
import androidx.compose.ui.draw.alpha
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

        val slotIndex = intent.getIntExtra("VM_SLOT_INDEX", 0)
        val guestPkg = intent.getStringExtra("GUEST_PKG") ?: ""
        val guestLabel = intent.getStringExtra("GUEST_LABEL") ?: guestPkg
        val guestApk = intent.getStringExtra("GUEST_APK") ?: ""
        val iconColorHex = intent.getLongExtra("ICON_COLOR", 0xFF0A84FF)

        try {
            val svcIntent = Intent(this, GmsDaemonService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svcIntent)
            } else {
                startService(svcIntent)
            }
            bindService(svcIntent, daemonConnection, Context.BIND_AUTO_CREATE)
        } catch (_: Throwable) {}

        val plugin = if (guestApk.isNotBlank()) {
            VirtualApkEngine.loadPluginApk(this, guestApk, guestPkg)
        } else null

        val iconBitmap = try {
            packageManager.getApplicationIcon(guestPkg).toBitmapSafe(128, 128)?.asImageBitmap()
        } catch (_: Throwable) {
            null
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                VmMultiWindowStage(
                    slotIndex = slotIndex,
                    packageName = guestPkg,
                    appLabel = plugin?.appLabel ?: guestLabel,
                    iconBitmap = iconBitmap,
                    iconColor = Color(iconColorHex),
                    onLaunchNativeSurface = { launchAdjacentRealSurface(guestPkg) },
                    onMinimizeToFluidDock = {
                        VirtualKernelEngine.minimizeWindowToFluidDock(guestPkg)
                        finish()
                    },
                    onTerminateSlot = {
                        VirtualKernelEngine.closeVmWindowSlot(guestPkg)
                        finish()
                    }
                )
            }
        }
    }

    private fun launchAdjacentRealSurface(pkgName: String) {
        try {
            VirtualKernelEngine.bindVirtualOsToQuestNetwork(this)
            val pm = packageManager
            val launchIntent = pm.getLaunchIntentForPackage(pkgName)
            if (launchIntent != null) {
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                )
                startActivity(launchIntent)
            }
        } catch (t: Throwable) {
            Toast.makeText(this, "Surface handoff: ${t.localizedMessage}", Toast.LENGTH_SHORT).show()
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
private fun VmMultiWindowStage(
    slotIndex: Int,
    packageName: String,
    appLabel: String,
    iconBitmap: ImageBitmap?,
    iconColor: Color,
    onLaunchNativeSurface: () -> Unit,
    onMinimizeToFluidDock: () -> Unit,
    onTerminateSlot: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var pillExpanded by remember { mutableStateOf(true) }
    var isMinimizing by remember { mutableStateOf(false) }

    // Fluid Minimize-to-Dock Spring Animation
    val windowScale by animateFloatAsState(
        targetValue = if (isMinimizing) 0.22f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "WindowMinimizeScale"
    )
    val windowAlpha by animateFloatAsState(
        targetValue = if (isMinimizing) 0.0f else 1.0f,
        animationSpec = tween(260),
        label = "WindowMinimizeAlpha"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "FluidWindowGlow")
    val shimmer by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer"
    )

    LaunchedEffect(Unit) {
        delay(240)
        onLaunchNativeSurface()
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .scale(windowScale)
            .alpha(windowAlpha)
            .background(Color.Transparent)
            .padding(22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // =========================================================================
        // 1. FLOATING iOS 27 FLUID GLASS NAVIGATION PILL NEXT TO THE APP WINDOW
        // =========================================================================
        val pillWidth by animateDpAsState(
            targetValue = if (pillExpanded) 96.dp else 66.dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "FluidPillWidth"
        )

        Box(
            modifier = Modifier
                .width(pillWidth)
                .wrapContentHeight()
                .clip(RoundedCornerShape(999.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF161F33).copy(alpha = 0.56f),
                            Color(0xFF0B101B).copy(alpha = 0.68f)
                        )
                    )
                )
                .border(
                    width = 1.5.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.48f + 0.15f * shimmer),
                            Color(0xFF64D2FF).copy(alpha = 0.28f),
                            Color.White.copy(alpha = 0.10f)
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
                FluidPillButton(
                    icon = if (pillExpanded) Icons.Rounded.AutoAwesomeMosaic else Icons.Rounded.ChevronRight,
                    label = if (pillExpanded) "Slot ${slotIndex + 1}/3" else "",
                    tint = Color(0xFF64D2FF),
                    onClick = { pillExpanded = !pillExpanded }
                )

                Divider(color = Color.White.copy(alpha = 0.15f), modifier = Modifier.width(36.dp))

                // Back Button
                FluidPillButton(
                    icon = Icons.Rounded.ArrowBackIosNew,
                    label = if (pillExpanded) "Back" else "",
                    tint = Color.White,
                    onClick = {
                        scope.launch {
                            VirtualKernelEngine.dispatchVirtualKeyEvent(KeyEvent.KEYCODE_BACK)
                        }
                    }
                )

                // Minimize to Fluid Dock Button
                FluidPillButton(
                    icon = Icons.Rounded.Minimize,
                    label = if (pillExpanded) "Minimize" else "",
                    tint = Color(0xFFFFD60A),
                    onClick = {
                        scope.launch {
                            isMinimizing = true
                            delay(240)
                            onMinimizeToFluidDock()
                        }
                    }
                )

                // Pop-Out Fullscreen Native Surface
                FluidPillButton(
                    icon = Icons.Rounded.OpenInFull,
                    label = if (pillExpanded) "Fullscreen" else "",
                    tint = Color(0xFF30D158),
                    onClick = onLaunchNativeSurface
                )

                Divider(color = Color.White.copy(alpha = 0.15f), modifier = Modifier.width(36.dp))

                // Close Window & Free Slot
                FluidPillButton(
                    icon = Icons.Rounded.Close,
                    label = if (pillExpanded) "Close" else "",
                    tint = Color(0xFFFF453A),
                    onClick = {
                        scope.launch {
                            isMinimizing = true
                            delay(200)
                            onTerminateSlot()
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.width(18.dp))

        // =========================================================================
        // 2. TRANSLUCENT FULL-SCREEN VM APPLICATION VIEWPORT
        // =========================================================================
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(38.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF121828).copy(alpha = 0.50f),
                            Color(0xFF090D16).copy(alpha = 0.62f)
                        )
                    )
                )
                .border(
                    width = 1.4.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.42f),
                            Color(0xFF64D2FF).copy(alpha = 0.20f),
                            Color.White.copy(alpha = 0.06f)
                        )
                    ),
                    shape = RoundedCornerShape(38.dp)
                )
                .padding(32.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Translucent Window Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF30D158))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "$appLabel • VM Window Slot ${slotIndex + 1} of 3",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "Google Play Services & Quest Wi-Fi Active (${VirtualKernelEngine.questWifiIp.value})",
                        color = Color(0xFF64D2FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Center App Stage Card
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(32.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(32.dp))
                        .padding(horizontal = 48.dp, vertical = 36.dp)
                ) {
                    if (iconBitmap != null) {
                        Image(
                            bitmap = iconBitmap,
                            contentDescription = appLabel,
                            modifier = Modifier
                                .size(88.dp)
                                .clip(RoundedCornerShape(24.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(88.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(iconColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Apps,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(46.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Text(
                        text = appLabel,
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = packageName,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Button(
                            onClick = onLaunchNativeSurface,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.height(50.dp)
                        ) {
                            Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Focus Fullscreen Window", fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isMinimizing = true
                                    delay(240)
                                    onMinimizeToFluidDock()
                                }
                            },
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                            modifier = Modifier.height(50.dp)
                        ) {
                            Icon(Icons.Rounded.Minimize, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Minimize to Fluid Dock", color = Color.White)
                        }
                    }
                }

                Text(
                    text = "Use the Floating Glass Pill on the left to go Back, Minimize to the VM Dock, or Close this slot.",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun FluidPillButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.82f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "FluidBtnScale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(22.dp))
            .background(tint.copy(alpha = if (isPressed) 0.28f else 0.10f))
            .border(1.dp, tint.copy(alpha = if (isPressed) 0.6f else 0.22f), RoundedCornerShape(22.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        AnimatedVisibility(visible = label.isNotBlank()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = label,
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
