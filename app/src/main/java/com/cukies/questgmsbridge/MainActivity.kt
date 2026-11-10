package com.cukies.questgmsbridge

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                    secondary = Color(0xFF5E5CE6),
                    background = Color(0xFF07090F),
                    surface = Color(0xFF121624)
                )
            ) {
                iOS27SpatialShell()
            }
        }
    }
}

enum class SpatialTab(val label: String, val icon: ImageVector) {
    LIBRARY("App Space", Icons.Rounded.Apps),
    MINI_OS("Mini Android OS & ADB", Icons.Rounded.DeveloperBoard),
    GMS_STACK("GMS Core & Play Store", Icons.Rounded.CloudDownload)
}

data class InstalledQuestApp(
    val label: String,
    val packageName: String,
    val sourceDir: String,
    val splitCount: Int,
    val iconBitmap: ImageBitmap?
)

@Composable
fun iOS27SpatialShell() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentTab by remember { mutableStateOf(SpatialTab.LIBRARY) }
    var searchQuery by remember { mutableStateOf("") }
    var appsList by remember { mutableStateOf<List<InstalledQuestApp>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }
    var launchingAppPkg by remember { mutableStateOf<String?>(null) }

    var osStatus by remember { mutableStateOf(VirtualOsManager.inspectAndPrepareMiniOs(context)) }
    var adbConnected by remember { mutableStateOf(LocalAdbShellEngine.isConnected) }
    var adbStatusText by remember { mutableStateOf(LocalAdbShellEngine.lastStatusMessage) }
    var ramUsageMb by remember { mutableStateOf(38) }

    fun refreshTelemetry() {
        osStatus = VirtualOsManager.inspectAndPrepareMiniOs(context)
        adbConnected = LocalAdbShellEngine.isConnected
        adbStatusText = LocalAdbShellEngine.lastStatusMessage
        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
        ramUsageMb = (memInfo.totalPss / 1024).coerceAtLeast(28)
    }

    LaunchedEffect(Unit) {
        refreshTelemetry()
        // Auto-attempt Wireless ADB connection on startup just like Quest Games Optimizer
        scope.launch {
            LocalAdbShellEngine.connectLocalAdb(context, 5555)
            refreshTelemetry()
        }
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val loaded = try {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { app ->
                        (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                                app.packageName != context.packageName &&
                                !app.packageName.startsWith("com.oculus") &&
                                !app.packageName.startsWith("com.meta")
                    }
                    .map { app ->
                        val drawable = try { pm.getApplicationIcon(app) } catch (_: Throwable) { null }
                        InstalledQuestApp(
                            label = try { pm.getApplicationLabel(app).toString() } catch (_: Throwable) { app.packageName },
                            packageName = app.packageName,
                            sourceDir = app.sourceDir ?: "",
                            splitCount = app.splitSourceDirs?.size ?: 0,
                            iconBitmap = drawable?.toBitmapSafe(112, 112)?.asImageBitmap()
                        )
                    }
                    .sortedBy { it.label.lowercase() }
            } catch (_: Throwable) {
                emptyList()
            }
            appsList = loaded
            isLoadingApps = false
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "iOS27Mesh")
    val orbShift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(11000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orbShift"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06080E))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF0A84FF).copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(size.width * (0.2f + 0.15f * orbShift), size.height * 0.22f),
                    radius = size.width * 0.45f
                )
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF5E5CE6).copy(alpha = 0.20f), Color.Transparent),
                    center = Offset(size.width * (0.8f - 0.15f * orbShift), size.height * 0.75f),
                    radius = size.width * 0.5f
                )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 20.dp)
        ) {
            iOS27TopStatusPill(
                adbConnected = adbConnected,
                adbStatusText = adbStatusText,
                ramUsageMb = ramUsageMb,
                onConnectAdb = {
                    scope.launch {
                        val ok = LocalAdbShellEngine.connectLocalAdb(context, 5555)
                        refreshTelemetry()
                        Toast.makeText(
                            context,
                            if (ok) "Wireless ADB Connected (uid=2000)!" else LocalAdbShellEngine.lastStatusMessage,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )

            Spacer(modifier = Modifier.height(18.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Crossfade(targetState = currentTab, animationSpec = tween(220), label = "TabSwitch") { tab ->
                    when (tab) {
                        SpatialTab.LIBRARY -> SpatialAppLibraryScreen(
                            apps = appsList.filter {
                                searchQuery.isBlank() ||
                                        it.label.contains(searchQuery, ignoreCase = true) ||
                                        it.packageName.contains(searchQuery, ignoreCase = true)
                            },
                            searchQuery = searchQuery,
                            onSearchChange = { searchQuery = it },
                            isLoading = isLoadingApps,
                            launchingAppPkg = launchingAppPkg,
                            onLaunchWithAdbAndVm = { app ->
                                scope.launch {
                                    launchingAppPkg = app.packageName
                                    if (LocalAdbShellEngine.isConnected || LocalAdbShellEngine.connectLocalAdb(context, 5555)) {
                                        val report = LocalAdbShellEngine.prepareOsAndLaunchGameWithGms(
                                            context = context,
                                            targetGamePkg = app.packageName,
                                            gmsPackages = osStatus.activeGmsPackages
                                        )
                                        refreshTelemetry()
                                        Toast.makeText(context, report, Toast.LENGTH_LONG).show()
                                    } else {
                                        // Fallback if Wireless ADB port 5555 isn't enabled yet: launch via ProxySandboxActivity
                                        val intent = Intent(context, ProxySandboxActivity::class.java).apply {
                                            putExtra("GUEST_PKG", app.packageName)
                                            putExtra("GUEST_APK", app.sourceDir)
                                            putExtra("AUTO_LAUNCH", true)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                    }
                                    launchingAppPkg = null
                                }
                            },
                            onLaunchInsideExternalVm = { app ->
                                val vmPkg = osStatus.externalVmContainerPkg
                                if (vmPkg != null) {
                                    context.packageManager.getLaunchIntentForPackage(vmPkg)?.let {
                                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(it)
                                        return@SpatialAppLibraryScreen
                                    }
                                }
                                Toast.makeText(
                                    context,
                                    "Open the 'GMS Core & Play Store' tab to install the GBox Virtual Android OS kernel.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        )

                        SpatialTab.MINI_OS -> MiniAndroidOsAdbScreen(
                            osStatus = osStatus,
                            adbConnected = adbConnected,
                            adbStatusText = adbStatusText,
                            onConnectAdb = { port ->
                                scope.launch {
                                    LocalAdbShellEngine.connectLocalAdb(context, port)
                                    refreshTelemetry()
                                }
                            }
                        )

                        SpatialTab.GMS_STACK -> RealGmsStackProvisionerScreen(
                            osStatus = osStatus,
                            onRefresh = { refreshTelemetry() }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                iOS27BottomFloatingDock(
                    selectedTab = currentTab,
                    onSelectTab = {
                        refreshTelemetry()
                        currentTab = it
                    }
                )
            }
        }
    }
}

@Composable
fun iOS27GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 28.dp,
    fillAlpha: Float = 0.09f,
    borderAlpha: Float = 0.24f,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = fillAlpha + 0.05f),
                        Color.White.copy(alpha = fillAlpha * 0.65f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = borderAlpha),
                        Color.White.copy(alpha = 0.04f)
                    )
                ),
                shape = shape
            ),
        content = content
    )
}

@Composable
fun iOS27TopStatusPill(
    adbConnected: Boolean,
    adbStatusText: String,
    ramUsageMb: Int,
    onConnectAdb: () -> Unit
) {
    iOS27GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 999.dp,
        fillAlpha = 0.10f
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (adbConnected) Color(0xFF30D158) else Color(0xFFFF9F0A))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "SpatialGMS OS 27",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = adbStatusText,
                    color = if (adbConnected) Color(0xFF30D158) else Color(0xFFFFD60A),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xFF0A84FF).copy(alpha = 0.25f))
                        .clickable { onConnectAdb() }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (adbConnected) "ADB Bridge: ONLINE" else "Connect Wireless ADB",
                        color = Color(0xFF64D2FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "RAM: ${ramUsageMb}MB",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
fun SpatialAppLibraryScreen(
    apps: List<InstalledQuestApp>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    isLoading: Boolean,
    launchingAppPkg: String?,
    onLaunchWithAdbAndVm: (InstalledQuestApp) -> Unit,
    onLaunchInsideExternalVm: (InstalledQuestApp) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        iOS27GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 20.dp,
            fillAlpha = 0.08f
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = "Search",
                    tint = Color.White.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.width(10.dp))
                TextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = {
                        Text(
                            "Search Quest 3 games to launch with Wireless ADB + GMS Stack...",
                            color = Color.White.copy(alpha = 0.45f)
                        )
                    },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF0A84FF))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 240.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(apps, key = { it.packageName }) { app ->
                    iOS27AppSquircleCard(
                        app = app,
                        isLaunching = launchingAppPkg == app.packageName,
                        onLaunchAdbVm = { onLaunchWithAdbAndVm(app) },
                        onLaunchFullVm = { onLaunchInsideExternalVm(app) }
                    )
                }
            }
        }
    }
}

@Composable
fun iOS27AppSquircleCard(
    app: InstalledQuestApp,
    isLaunching: Boolean,
    onLaunchAdbVm: () -> Unit,
    onLaunchFullVm: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "SquircleScale"
    )

    iOS27GlassSurface(
        modifier = Modifier
            .scale(scale)
            .fillMaxWidth()
            .clickable(interactionSource = interactionSource, indication = null) { onLaunchAdbVm() },
        cornerRadius = 26.dp,
        fillAlpha = 0.09f
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (app.iconBitmap != null) {
                    Image(
                        bitmap = app.iconBitmap,
                        contentDescription = app.label,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(15.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(Color(0xFF0A84FF).copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Android, contentDescription = null, tint = Color.White)
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.label,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = app.packageName,
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onLaunchAdbVm,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (isLaunching) "Injecting ADB..." else "ADB + GMS Launch",
                        fontSize = 12.sp,
                        color = Color.White,
                        maxLines = 1
                    )
                }

                OutlinedButton(
                    onClick = onLaunchFullVm,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Full VM",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }
        }
    }
}

@Composable
fun MiniAndroidOsAdbScreen(
    osStatus: VirtualOsManager.MiniOsCoreStatus,
    adbConnected: Boolean,
    adbStatusText: String,
    onConnectAdb: (Int) -> Unit
) {
    var portInput by remember { mutableStateOf("5555") }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            iOS27GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 28.dp) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = "Wireless ADB Engine (Quest Games Optimizer Architecture)",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Connects directly to 127.0.0.1 as uid=2000 (shell). Automatically disables Horizon OS phantom-process killers, grants background system permissions to Google Play Services & Play Games, and sets each game's OS installer provenance to com.android.vending.",
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 13.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = portInput,
                            onValueChange = { portInput = it },
                            label = { Text("Local ADB TCP Port (Default 5555)", color = Color.White.copy(alpha = 0.6f)) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF0A84FF),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier.width(260.dp)
                        )

                        Button(
                            onClick = {
                                val port = portInput.toIntOrNull() ?: 5555
                                onConnectAdb(port)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (adbConnected) Color(0xFF30D158) else Color(0xFF0A84FF)
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.height(54.dp)
                        ) {
                            Icon(Icons.Rounded.Terminal, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (adbConnected) "ADB Connected (uid=2000)" else "Connect Local Wireless ADB")
                        }
                    }
                }
            }
        }

        item {
            iOS27GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Mini Android OS Dependency Checklist",
                        color = Color(0xFF64D2FF),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    DependencyCheckRow("Stripped /system/priv-app Rootfs Mounted", osStatus.rootfsReady)
                    DependencyCheckRow("Google Services Framework (GSF)", osStatus.gsfInstalled)
                    DependencyCheckRow("Google Play Services (GmsCore / microG)", osStatus.gmsInstalled)
                    DependencyCheckRow("Google Play Store (com.android.vending)", osStatus.playStoreInstalled)
                    DependencyCheckRow("Google Play Games (Achievements & Cloud Saves)", osStatus.playGamesInstalled)
                    DependencyCheckRow(
                        "Full C++ Virtual Android OS Engine (GBox / VMOS)",
                        osStatus.externalVmContainerPkg != null
                    )
                }
            }
        }
    }
}

@Composable
private fun DependencyCheckRow(label: String, active: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
        Text(
            text = if (active) "INSTALLED & READY" else "NOT INSTALLED (See GMS Tab)",
            color = if (active) Color(0xFF30D158) else Color(0xFFFF9F0A),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun RealGmsStackProvisionerScreen(
    osStatus: VirtualOsManager.MiniOsCoreStatus,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            iOS27GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 28.dp) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = "1. Full Lightweight Virtual Android OS (GBox ARM64 Engine)",
                        color = Color(0xFF30D158),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Want a true, prebuilt stripped-down Android OS with a real C++ Virtual Kernel (libblackbox/GBox) that runs the unmodified official Google Play Store, Google Play Services, and Google Play Games in a sandbox? Install GBox ARM64 below and launch it right from our app!",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.gboxlab.com/")))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30D158)),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.CloudDownload, contentDescription = null, tint = Color.Black)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Download Prebuilt GBox Virtual OS APK", color = Color.Black, fontWeight = FontWeight.Bold)
                        }

                        if (osStatus.externalVmContainerPkg != null) {
                            OutlinedButton(
                                onClick = {
                                    context.packageManager.getLaunchIntentForPackage(osStatus.externalVmContainerPkg)?.let {
                                        context.startActivity(it)
                                    }
                                },
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("Open Installed Virtual OS", color = Color.White)
                            }
                        }
                    }
                }
            }
        }

        item {
            iOS27GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 28.dp) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = "2. Direct Quest 3 GMS Dependencies (Paired with Wireless ADB)",
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Install the official open-source microG Services (com.google.android.gms) + microG Companion Play Store (com.android.vending) below, then use our Wireless ADB Bridge to grant them system background privileges on your Quest 3.",
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                val launched = GmsBinderRouter.launchGmsCoreSettingsOrLogin(context)
                                if (!launched) {
                                    Toast.makeText(context, "Install microG GmsCore first.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.AccountCircle, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Open GmsCore Account Login")
                        }

                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/microg/GmsCore/releases"))
                                )
                            },
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0xFF64D2FF).copy(alpha = 0.5f))
                        ) {
                            Text("Download microG GmsCore + Play Store Companion", color = Color(0xFF64D2FF))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun iOS27BottomFloatingDock(
    selectedTab: SpatialTab,
    onSelectTab: (SpatialTab) -> Unit
) {
    iOS27GlassSurface(
        cornerRadius = 999.dp,
        fillAlpha = 0.14f,
        borderAlpha = 0.32f
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpatialTab.entries.forEach { tab ->
                val isSelected = tab == selectedTab
                val bgAlpha by animateFloatAsState(if (isSelected) 0.28f else 0f, label = "DockAlpha")

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xFF0A84FF).copy(alpha = bgAlpha))
                        .clickable { onSelectTab(tab) }
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = if (isSelected) Color(0xFF64D2FF) else Color.White.copy(alpha = 0.65f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = tab.label,
                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.65f),
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
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
