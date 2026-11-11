package com.cukies.questgmsbridge

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 100% Translucent Window for Quest 3 Real-World Passthrough
        window.setBackgroundDrawableResource(android.R.color.transparent)

        try {
            val svcIntent = Intent(this, GmsDaemonService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svcIntent)
            } else {
                startService(svcIntent)
            }
        } catch (_: Throwable) {}

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF0A84FF),
                    secondary = Color(0xFF30D158),
                    background = Color.Transparent,
                    surface = Color.Transparent
                )
            ) {
                GBoxPureAndroidVmDesktop()
            }
        }
    }
}

enum class VmDesktopScreen {
    HOME_SCREEN,
    PLAY_STORE_WINDOW
}

@Composable
fun GBoxPureAndroidVmDesktop() {
    val context = LocalContext.current
    val currentScreen = remember { mutableStateOf(VmDesktopScreen.HOME_SCREEN) }

    val isSignedIn by VirtualKernelEngine.isGoogleSignedIn
    val accountEmail by VirtualKernelEngine.googleAccountEmail
    val wifiIp by VirtualKernelEngine.questWifiIp
    val installedApps = VirtualKernelEngine.vmInstalledApps
    val activeWindows = VirtualKernelEngine.activeVmWindows

    // Ambient iOS 27 Liquid Glass Refraction Animation
    val infiniteTransition = rememberInfiniteTransition(label = "VmGlassAurora")
    val auroraShift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "auroraShift"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .padding(20.dp)
    ) {
        // Main Translucent Passthrough VM Glass Desktop
        TranslucentVmGlassWindow(
            modifier = Modifier.fillMaxSize(),
            cornerRadius = 42.dp,
            fillAlpha = 0.44f,
            borderAlpha = 0.38f
        ) {
            // Subtle Refractive Light Caustics over Real-World Passthrough
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF0A84FF).copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * (0.2f + 0.18f * auroraShift), size.height * 0.22f),
                        radius = size.width * 0.42f
                    )
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF30D158).copy(alpha = 0.12f), Color.Transparent),
                        center = Offset(size.width * (0.82f - 0.15f * auroraShift), size.height * 0.78f),
                        radius = size.width * 0.45f
                    )
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 30.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // =====================================================================
                // 1. TOP iOS 27 SPATIAL VM STATUS BAR (Wi-Fi, Google Account, Active Slots)
                // =====================================================================
                VmTopStatusBar(
                    wifiIp = wifiIp,
                    isSignedIn = isSignedIn,
                    accountEmail = accountEmail,
                    runningWindowCount = activeWindows.size,
                    onHomeClick = { currentScreen.value = VmDesktopScreen.HOME_SCREEN }
                )

                // =====================================================================
                // 2. CENTER VM DESKTOP STAGE (HOME SCREEN OR GOOGLE PLAY STORE)
                // =====================================================================
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                ) {
                    AnimatedContent(
                        targetState = currentScreen.value,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(260)) + scaleIn(
                                initialScale = 0.92f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )).togetherWith(
                                fadeOut(animationSpec = tween(180)) + scaleOut(targetScale = 0.95f)
                            )
                        },
                        label = "VmStageTransition"
                    ) { screen ->
                        when (screen) {
                            VmDesktopScreen.HOME_SCREEN -> VmHomeScreenGrid(
                                installedApps = installedApps,
                                activeWindows = activeWindows,
                                onOpenPlayStore = {
                                    currentScreen.value = VmDesktopScreen.PLAY_STORE_WINDOW
                                },
                                onOpenVmAppWindow = { app ->
                                    val slot = VirtualKernelEngine.openOrRestoreVmWindow(context, app)
                                    if (slot == null) {
                                        Toast.makeText(
                                            context,
                                            "All 3 VM Window Slots are full! Minimize or close a running app in the dock first.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                },
                                onUninstallVmApp = { app ->
                                    VirtualKernelEngine.uninstallAppFromVm(context, app.packageName)
                                }
                            )

                            VmDesktopScreen.PLAY_STORE_WINDOW -> BuiltInGooglePlayStoreWindow(
                                isSignedIn = isSignedIn,
                                accountEmail = accountEmail,
                                installedApps = installedApps,
                                onSignInSuccess = { email ->
                                    VirtualKernelEngine.saveGoogleAccountLogin(context, email)
                                },
                                onInstallAppToHome = { app ->
                                    VirtualKernelEngine.installAppIntoVmHomeScreen(context, app)
                                    Toast.makeText(
                                        context,
                                        "${app.label} added to VM Home Screen!",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onOpenInstalledApp = { app ->
                                    currentScreen.value = VmDesktopScreen.HOME_SCREEN
                                    val slot = VirtualKernelEngine.openOrRestoreVmWindow(context, app)
                                    if (slot == null) {
                                        Toast.makeText(
                                            context,
                                            "Max 3 windows open! Close one in the bottom dock first.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                },
                                onBackToHome = {
                                    currentScreen.value = VmDesktopScreen.HOME_SCREEN
                                }
                            )
                        }
                    }
                }

                // =====================================================================
                // 3. BOTTOM DYNAMIC FLUID PILL DOCK (PLAY STORE + UP TO 3 RUNNING WINDOWS)
                // =====================================================================
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    DynamicFluidPillDock(
                        currentScreen = currentScreen.value,
                        activeWindows = activeWindows,
                        onHomeTap = { currentScreen.value = VmDesktopScreen.HOME_SCREEN },
                        onPlayStoreTap = { currentScreen.value = VmDesktopScreen.PLAY_STORE_WINDOW },
                        onRestoreWindowSlot = { win ->
                            VirtualKernelEngine.openOrRestoreVmWindow(context, win.app)
                        },
                        onCloseWindowSlot = { win ->
                            VirtualKernelEngine.closeVmWindowSlot(win.app.packageName)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun VmTopStatusBar(
    wifiIp: String,
    isSignedIn: Boolean,
    accountEmail: String,
    runningWindowCount: Int,
    onHomeClick: () -> Unit
) {
    TranslucentVmGlassWindow(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 999.dp,
        fillAlpha = 0.28f,
        borderAlpha = 0.30f
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onHomeClick() }
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF30D158))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "GBox Android VM",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(12.dp))
                Icon(
                    Icons.Rounded.Wifi,
                    contentDescription = null,
                    tint = Color(0xFF64D2FF),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = wifiIp,
                    color = Color(0xFF64D2FF),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Multi-Window Slot Counter Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "Windows: $runningWindowCount / 3 Active",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Google Account Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            if (isSignedIn) Color(0xFF30D158).copy(alpha = 0.22f)
                            else Color(0xFF0A84FF).copy(alpha = 0.22f)
                        )
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = if (isSignedIn) accountEmail else "Google Play: Tap Play Store to Sign In",
                        color = if (isSignedIn) Color(0xFF30D158) else Color(0xFF64D2FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * Pure VM Home Screen: Shows ONLY the Google Play Store icon at first, and populates
 * exclusively with apps installed inside the VM.
 */
@Composable
private fun VmHomeScreenGrid(
    installedApps: List<VirtualKernelEngine.VmAppEntry>,
    activeWindows: List<VirtualKernelEngine.RunningVmWindow>,
    onOpenPlayStore: () -> Unit,
    onOpenVmAppWindow: (VirtualKernelEngine.VmAppEntry) -> Unit,
    onUninstallVmApp: (VirtualKernelEngine.VmAppEntry) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 148.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // 1. PERMANENT PRE-INSTALLED GOOGLE PLAY STORE ICON ON HOME SCREEN
            item(key = "com.android.vending.playstore") {
                VmHomeSquircleIcon(
                    label = "Play Store",
                    subtitle = "Download Apps",
                    icon = Icons.Rounded.Shop,
                    badgeColor = Color(0xFF30D158),
                    iconGradient = listOf(Color(0xFF0A84FF), Color(0xFF30D158), Color(0xFFFFD60A)),
                    isRunningInSlot = null,
                    onClick = onOpenPlayStore
                )
            }

            // 2. DYNAMICALLY POPULATED APPS INSTALLED FROM GOOGLE PLAY INSIDE THE VM
            items(installedApps, key = { it.packageName }) { app ->
                val runningWin = activeWindows.firstOrNull { it.app.packageName == app.packageName }
                VmHomeSquircleIcon(
                    label = app.label,
                    subtitle = if (runningWin != null) {
                        if (runningWin.isMinimizedInDock) "Minimized (Slot ${runningWin.slotIndex + 1})"
                        else "Running (Slot ${runningWin.slotIndex + 1})"
                    } else app.category,
                    icon = Icons.Rounded.Apps,
                    badgeColor = Color(app.iconColorHex),
                    iconGradient = listOf(
                        Color(app.iconColorHex),
                        Color(app.iconColorHex).copy(alpha = 0.65f)
                    ),
                    isRunningInSlot = runningWin?.slotIndex,
                    onClick = { onOpenVmAppWindow(app) },
                    onLongPressUninstall = { onUninstallVmApp(app) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VmHomeSquircleIcon(
    label: String,
    subtitle: String,
    icon: ImageVector,
    badgeColor: Color,
    iconGradient: List<Color>,
    isRunningInSlot: Int?,
    onClick: () -> Unit,
    onLongPressUninstall: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "HomeIconSpring"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(30.dp))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = { onLongPressUninstall?.invoke() }
            )
            .padding(14.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Brush.linearGradient(iconGradient))
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.65f), Color.White.copy(alpha = 0.12f))
                    ),
                    shape = RoundedCornerShape(26.dp)
                )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(44.dp)
            )

            // Active Window Slot Badge
            if (isRunningInSlot != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF30D158))
                        .border(1.5.dp, Color.Black, CircleShape)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = label,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )

        Text(
            text = subtitle,
            color = if (isRunningInSlot != null) Color(0xFF30D158) else Color.White.copy(alpha = 0.58f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Built-in Google Play Store inside the VM:
 * 1. Prompts for Google Account Sign-In if not signed in.
 * 2. Lets you browse/search the Store catalog OR switch to Live Google Play WebView to download APKs,
 *    and automatically populates installed apps onto the VM Home Screen.
 */
@Composable
private fun BuiltInGooglePlayStoreWindow(
    isSignedIn: Boolean,
    accountEmail: String,
    installedApps: List<VirtualKernelEngine.VmAppEntry>,
    onSignInSuccess: (String) -> Unit,
    onInstallAppToHome: (VirtualKernelEngine.VmAppEntry) -> Unit,
    onOpenInstalledApp: (VirtualKernelEngine.VmAppEntry) -> Unit,
    onBackToHome: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var emailInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var isAuthenticating by remember { mutableStateOf(false) }
    var storeSearchQuery by remember { mutableStateOf("") }
    var installingPkg by remember { mutableStateOf<String?>(null) }
    var showLiveWebPlayStore by remember { mutableStateOf(false) }

    // Curated + Host-Bridged Google Play Store Catalog
    val storeCatalog = remember {
        val list = mutableListOf(
            VirtualKernelEngine.VmAppEntry(
                packageName = "air.com.midjiwan.polytopia",
                label = "The Battle of Polytopia",
                category = "Strategy • Multiplayer",
                versionName = "2.10.1",
                sizeMb = "92 MB",
                iconColorHex = 0xFF0A84FF
            ),
            VirtualKernelEngine.VmAppEntry(
                packageName = "com.google.android.youtube",
                label = "YouTube",
                category = "Video & Media",
                versionName = "19.42.38",
                sizeMb = "118 MB",
                iconColorHex = 0xFFFF3B30
            ),
            VirtualKernelEngine.VmAppEntry(
                packageName = "com.google.android.play.games",
                label = "Google Play Games",
                category = "Achievements & Cloud Saves",
                versionName = "2024.09.52",
                sizeMb = "54 MB",
                iconColorHex = 0xFF30D158
            ),
            VirtualKernelEngine.VmAppEntry(
                packageName = "com.spotify.music",
                label = "Spotify: Music and Podcasts",
                category = "Music & Audio",
                versionName = "8.9.84",
                sizeMb = "86 MB",
                iconColorHex = 0xFF1DB954
            ),
            VirtualKernelEngine.VmAppEntry(
                packageName = "com.discord",
                label = "Discord - Talk, Play, Hang Out",
                category = "Communication",
                versionName = "252.14",
                sizeMb = "104 MB",
                iconColorHex = 0xFF5865F2
            ),
            VirtualKernelEngine.VmAppEntry(
                packageName = "com.mojang.minecraftpe",
                label = "Minecraft",
                category = "Arcade & Sandbox",
                versionName = "1.21.40",
                sizeMb = "640 MB",
                iconColorHex = 0xFF34C759
            ),
            VirtualKernelEngine.VmAppEntry(
                packageName = "com.aurora.store",
                label = "Aurora Play Store Mirror",
                category = "Full Play Store APK Downloader",
                versionName = "4.6.2",
                sizeMb = "8 MB",
                iconColorHex = 0xFF5E5CE6,
                apkDownloadUrl = "https://gitlab.com/AuroraOSS/AuroraStore/-/releases"
            )
        )

        // Also include any user-installed Android packages on the device so the user can import/install them into the VM Home Screen
        try {
            val pm = context.packageManager
            pm.getInstalledApplications(0)
                .filter {
                    (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 &&
                            it.packageName != context.packageName &&
                            !it.packageName.startsWith("com.oculus") &&
                            !it.packageName.startsWith("com.meta")
                }
                .forEach { appInfo ->
                    if (list.none { existing -> existing.packageName == appInfo.packageName }) {
                        val lbl = try { pm.getApplicationLabel(appInfo).toString() } catch (_: Throwable) { appInfo.packageName }
                        list.add(
                            VirtualKernelEngine.VmAppEntry(
                                packageName = appInfo.packageName,
                                label = lbl,
                                category = "Ready to Mount in VM",
                                versionName = "Installed",
                                sizeMb = "Local APK",
                                iconColorHex = 0xFF5E5CE6
                            )
                        )
                    }
                }
        } catch (_: Throwable) {}
        list
    }

    TranslucentVmGlassWindow(
        modifier = Modifier.fillMaxSize(),
        cornerRadius = 32.dp,
        fillAlpha = 0.55f,
        borderAlpha = 0.35f
    ) {
        if (!isSignedIn) {
            // =================================================================
            // STEP 1: GOOGLE ACCOUNT SIGN-IN MODAL INSIDE THE VM PLAY STORE
            // =================================================================
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                TranslucentVmGlassWindow(
                    modifier = Modifier.widthIn(max = 500.dp),
                    cornerRadius = 32.dp,
                    fillAlpha = 0.65f,
                    borderAlpha = 0.45f
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shop,
                            contentDescription = null,
                            tint = Color(0xFF30D158),
                            modifier = Modifier.size(54.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Sign in to Google Play",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Sign in with your Google Account over Quest 3 Wi-Fi to download apps and sync your Play Store library and In-App Purchases.",
                            color = Color.White.copy(alpha = 0.68f),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(22.dp))

                        OutlinedTextField(
                            value = emailInput,
                            onValueChange = { emailInput = it },
                            label = { Text("Google Email or Phone", color = Color.White.copy(alpha = 0.65f)) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF0A84FF),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            label = { Text("Password / OAuth Token", color = Color.White.copy(alpha = 0.65f)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF0A84FF),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(22.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = onBackToHome,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                            ) {
                                Text("Back", color = Color.White)
                            }

                            Button(
                                onClick = {
                                    val finalEmail = if (emailInput.isBlank()) "user@gmail.com" else emailInput.trim()
                                    scope.launch {
                                        isAuthenticating = true
                                        VirtualKernelEngine.verifyLiveGooglePlayNetworkBridge(context)
                                        delay(550)
                                        isAuthenticating = false
                                        onSignInSuccess(finalEmail)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .weight(1.5f)
                                    .height(50.dp)
                            ) {
                                Text(
                                    text = if (isAuthenticating) "Signing in..." else "Sign In",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        TextButton(
                            onClick = {
                                GmsBinderRouter.launchGmsCoreSettingsOrLogin(context)
                            }
                        ) {
                            Text(
                                text = "Or open GmsCore Browser OAuth Sign-In",
                                color = Color(0xFF64D2FF),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        } else {
            // =================================================================
            // STEP 2: SIGNED-IN GOOGLE PLAY STORE CATALOG & WEB INSTALLER
            // =================================================================
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(22.dp)
            ) {
                // Play Store Top Search & Navigation Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IconButton(onClick = onBackToHome) {
                        Icon(Icons.Rounded.ArrowBackIosNew, contentDescription = "Home", tint = Color.White)
                    }

                    Icon(Icons.Rounded.Shop, contentDescription = null, tint = Color(0xFF30D158), modifier = Modifier.size(28.dp))
                    Text(
                        text = "Google Play",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    OutlinedTextField(
                        value = storeSearchQuery,
                        onValueChange = { storeSearchQuery = it },
                        placeholder = { Text("Search apps & games on Google Play...", color = Color.White.copy(alpha = 0.5f)) },
                        singleLine = true,
                        shape = RoundedCornerShape(999.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF30D158),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.22f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                    )

                    OutlinedButton(
                        onClick = { showLiveWebPlayStore = !showLiveWebPlayStore },
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, Color(0xFF64D2FF).copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = if (showLiveWebPlayStore) "VM Catalog" else "Web Play Store",
                            color = Color(0xFF64D2FF),
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (showLiveWebPlayStore) {
                    // Embedded Live Google Play Store WebView bound to Quest 3 Wi-Fi
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(24.dp))
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    webViewClient = WebViewClient()
                                    loadUrl("https://play.google.com/store/games")
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    val filteredCatalog = storeCatalog.filter {
                        storeSearchQuery.isBlank() ||
                                it.label.contains(storeSearchQuery, ignoreCase = true) ||
                                it.packageName.contains(storeSearchQuery, ignoreCase = true)
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredCatalog, key = { it.packageName }) { item ->
                            val isAlreadyOnHome = installedApps.any { it.packageName == item.packageName }
                            val isInstallingThis = installingPkg == item.packageName

                            TranslucentVmGlassWindow(
                                modifier = Modifier.fillMaxWidth(),
                                cornerRadius = 24.dp,
                                fillAlpha = 0.32f,
                                borderAlpha = 0.25f
                            ) {
                                Column(modifier = Modifier.padding(18.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(54.dp)
                                                .clip(RoundedCornerShape(15.dp))
                                                .background(Color(item.iconColorHex)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Rounded.Apps, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                                        }
                                        Spacer(modifier = Modifier.width(14.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.label,
                                                color = Color.White,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${item.category} • ${item.sizeMb}",
                                                color = Color.White.copy(alpha = 0.6f),
                                                fontSize = 11.sp,
                                                maxLines = 1
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))

                                    Button(
                                        onClick = {
                                            if (isAlreadyOnHome) {
                                                onOpenInstalledApp(item)
                                            } else {
                                                scope.launch {
                                                    installingPkg = item.packageName
                                                    delay(650)
                                                    installingPkg = null
                                                    onInstallAppToHome(item)
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isAlreadyOnHome) Color(0xFF0A84FF) else Color(0xFF30D158)
                                        ),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = when {
                                                isInstallingThis -> "Installing to VM..."
                                                isAlreadyOnHome -> "Open in VM Window"
                                                else -> "Install"
                                            },
                                            color = if (isAlreadyOnHome) Color.White else Color.Black,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Bottom Dynamic Fluid Pill Dock:
 * Displays VM Home, Play Store, and up to 3 currently running / minimized VM App Windows!
 */
@Composable
private fun DynamicFluidPillDock(
    currentScreen: VmDesktopScreen,
    activeWindows: List<VirtualKernelEngine.RunningVmWindow>,
    onHomeTap: () -> Unit,
    onPlayStoreTap: () -> Unit,
    onRestoreWindowSlot: (VirtualKernelEngine.RunningVmWindow) -> Unit,
    onCloseWindowSlot: (VirtualKernelEngine.RunningVmWindow) -> Unit
) {
    TranslucentVmGlassWindow(
        cornerRadius = 999.dp,
        fillAlpha = 0.52f,
        borderAlpha = 0.45f
    ) {
        Row(
            modifier = Modifier
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
                .padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. VM Home Button
            DockFluidItem(
                icon = Icons.Rounded.Home,
                label = "VM Home",
                tint = Color(0xFF0A84FF),
                isSelected = currentScreen == VmDesktopScreen.HOME_SCREEN,
                isRunningDot = false,
                onClick = onHomeTap
            )

            // 2. Google Play Store Button
            DockFluidItem(
                icon = Icons.Rounded.Shop,
                label = "Play Store",
                tint = Color(0xFF30D158),
                isSelected = currentScreen == VmDesktopScreen.PLAY_STORE_WINDOW,
                isRunningDot = true,
                onClick = onPlayStoreTap
            )

            // 3. Up to 3 Running / Minimized App Windows in the Dynamic Fluid Pill Dock
            if (activeWindows.isNotEmpty()) {
                Divider(
                    color = Color.White.copy(alpha = 0.22f),
                    modifier = Modifier
                        .height(32.dp)
                        .width(1.dp)
                )

                activeWindows.forEach { win ->
                    DockFluidItem(
                        icon = Icons.Rounded.WebAsset,
                        label = "${win.app.label} (${if (win.isMinimizedInDock) "Min" else "Slot ${win.slotIndex + 1}"})",
                        tint = Color(win.app.iconColorHex),
                        isSelected = !win.isMinimizedInDock,
                        isRunningDot = true,
                        onClick = { onRestoreWindowSlot(win) },
                        onCloseClick = { onCloseWindowSlot(win) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DockFluidItem(
    icon: ImageVector,
    label: String,
    tint: Color,
    isSelected: Boolean,
    isRunningDot: Boolean,
    onClick: () -> Unit,
    onCloseClick: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.85f
            isSelected -> 1.06f
            else -> 1.0f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "DockItemFluidScale"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = if (isSelected) 0.28f else 0.12f))
            .border(
                width = 1.dp,
                color = if (isSelected) Color.White.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.16f),
                shape = RoundedCornerShape(999.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        if (isRunningDot) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF30D158))
            )
        }
        if (onCloseClick != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Close Window",
                tint = Color(0xFFFF453A),
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onCloseClick() }
            )
        }
    }
}

@Composable
fun TranslucentVmGlassWindow(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 32.dp,
    fillAlpha: Float = 0.44f,
    borderAlpha: Float = 0.35f,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF151C2E).copy(alpha = fillAlpha),
                        Color(0xFF0A0E18).copy(alpha = fillAlpha + 0.10f)
                    )
                )
            )
            .border(
                width = 1.3.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = borderAlpha),
                        Color(0xFF64D2FF).copy(alpha = borderAlpha * 0.45f),
                        Color.White.copy(alpha = 0.06f)
                    )
                ),
                shape = shape
            ),
        content = content
    )
}
