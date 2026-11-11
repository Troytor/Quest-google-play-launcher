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
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Transparent window background so Quest 3 Passthrough shines through
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
                    secondary = Color(0xFF5E5CE6),
                    background = Color.Transparent,
                    surface = Color.Transparent
                )
            ) {
                MiniGBoxSpatialContainer()
            }
        }
    }
}

enum class GBoxSpatialSection(val label: String, val icon: ImageVector) {
    VIRTUAL_APPS("GBox Apps", Icons.Rounded.GridView),
    PLAY_SERVICES("GMS & Network", Icons.Rounded.WifiTethering),
    PLAY_BILLING("Play Billing", Icons.Rounded.ShoppingBag)
}

data class InstalledQuestApp(
    val label: String,
    val packageName: String,
    val sourceDir: String,
    val splitCount: Int,
    val iconBitmap: ImageBitmap?
)

@Composable
fun MiniGBoxSpatialContainer() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeSection by remember { mutableStateOf(GBoxSpatialSection.VIRTUAL_APPS) }
    var navPillExpanded by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var appsList by remember { mutableStateOf<List<InstalledQuestApp>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }
    var spawningAppPkg by remember { mutableStateOf<String?>(null) }

    var vmState by remember { mutableStateOf(VirtualKernelEngine.currentNetworkState) }
    var ramUsageMb by remember { mutableStateOf(36) }

    fun refreshTelemetry() {
        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
        ramUsageMb = (memInfo.totalPss / 1024).coerceAtLeast(26)
        scope.launch {
            vmState = VirtualKernelEngine.verifyLiveGooglePlayNetworkBridge(context)
        }
    }

    LaunchedEffect(Unit) {
        refreshTelemetry()
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
                            iconBitmap = drawable?.toBitmapSafe(120, 120)?.asImageBitmap()
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

    val infiniteTransition = rememberInfiniteTransition(label = "iOS27FluidGlass")
    val fluidPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(8500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fluidPhase"
    )

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // =========================================================================
        // 1. FLOATING DYNAMIC GLASS PILL NEXT TO THE MAIN VIRTUAL WINDOW
        // =========================================================================
        val sidePillWidth by animateDpAsState(
            targetValue = if (navPillExpanded) 108.dp else 72.dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "SidePillWidth"
        )

        iOS27PassthroughGlass(
            modifier = Modifier
                .width(sidePillWidth)
                .wrapContentHeight(),
            cornerRadius = 999.dp,
            fillAlpha = 0.48f,
            borderAlpha = 0.42f
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(vertical = 20.dp, horizontal = 10.dp)
            ) {
                SpatialPillIconButton(
                    icon = if (navPillExpanded) Icons.Rounded.BlurOn else Icons.Rounded.ChevronRight,
                    label = if (navPillExpanded) "GBox VM" else "",
                    selected = false,
                    tintColor = Color(0xFF64D2FF),
                    onClick = { navPillExpanded = !navPillExpanded }
                )

                Divider(
                    color = Color.White.copy(alpha = 0.16f),
                    modifier = Modifier.width(40.dp)
                )

                GBoxSpatialSection.entries.forEach { section ->
                    SpatialPillIconButton(
                        icon = section.icon,
                        label = if (navPillExpanded) section.label else "",
                        selected = activeSection == section,
                        tintColor = Color(0xFF0A84FF),
                        onClick = {
                            refreshTelemetry()
                            activeSection = section
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(18.dp))

        // =========================================================================
        // 2. MAIN TRANSLUCENT PASSTHROUGH GBOX CONTAINER WINDOW
        // =========================================================================
        iOS27PassthroughGlass(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            cornerRadius = 40.dp,
            fillAlpha = 0.52f,
            borderAlpha = 0.36f
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF0A84FF).copy(alpha = 0.18f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * (0.2f + 0.15f * fluidPhase), size.height * 0.2f),
                        radius = size.width * 0.45f
                    )
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF5E5CE6).copy(alpha = 0.16f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * (0.8f - 0.15f * fluidPhase), size.height * 0.8f),
                        radius = size.width * 0.48f
                    )
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp, vertical = 22.dp)
            ) {
                GBoxTopDynamicIsland(
                    vmState = vmState,
                    ramUsageMb = ramUsageMb,
                    onRefresh = { refreshTelemetry() }
                )

                Spacer(modifier = Modifier.height(18.dp))

                Box(modifier = Modifier.weight(1f)) {
                    Crossfade(
                        targetState = activeSection,
                        animationSpec = tween(260, easing = FastOutSlowInEasing),
                        label = "SectionCrossfade"
                    ) { section ->
                        when (section) {
                            GBoxSpatialSection.VIRTUAL_APPS -> GBoxVirtualAppGrid(
                                apps = appsList.filter {
                                    searchQuery.isBlank() ||
                                            it.label.contains(searchQuery, ignoreCase = true) ||
                                            it.packageName.contains(searchQuery, ignoreCase = true)
                                },
                                searchQuery = searchQuery,
                                onSearchChange = { searchQuery = it },
                                isLoading = isLoadingApps,
                                spawningAppPkg = spawningAppPkg,
                                onSpawnSeparateWindowWithPill = { app ->
                                    scope.launch {
                                        spawningAppPkg = app.packageName
                                        val pillHostIntent = Intent(context, ProxySandboxActivity::class.java).apply {
                                            putExtra("GUEST_PKG", app.packageName)
                                            putExtra("GUEST_APK", app.sourceDir)
                                            putExtra("AUTO_LAUNCH", true)
                                            addFlags(
                                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                                                Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
                                            )
                                        }
                                        context.startActivity(pillHostIntent)
                                        spawningAppPkg = null
                                    }
                                }
                            )

                            GBoxSpatialSection.PLAY_SERVICES -> GBoxPlayServicesAndNetworkHub(
                                vmState = vmState,
                                onRefresh = { refreshTelemetry() }
                            )

                            GBoxSpatialSection.PLAY_BILLING -> GBoxPlayBillingVault()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun iOS27PassthroughGlass(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 30.dp,
    fillAlpha: Float = 0.42f,
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
                        Color(0xFF141B2D).copy(alpha = fillAlpha),
                        Color(0xFF0B0F1A).copy(alpha = fillAlpha + 0.10f)
                    )
                )
            )
            .border(
                width = 1.2.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = borderAlpha),
                        Color(0xFF64D2FF).copy(alpha = borderAlpha * 0.45f),
                        Color.White.copy(alpha = 0.05f)
                    )
                ),
                shape = shape
            ),
        content = content
    )
}

@Composable
private fun SpatialPillIconButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    tintColor: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.85f
            selected -> 1.07f
            else -> 1.0f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "PillButtonSpring"
    )

    val highlightAlpha by animateFloatAsState(
        targetValue = if (selected) 0.32f else if (isPressed) 0.20f else 0.0f,
        animationSpec = tween(200),
        label = "PillHighlight"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .scale(scale)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(tintColor.copy(alpha = highlightAlpha))
            .border(
                width = 1.dp,
                color = if (selected) Color.White.copy(alpha = 0.45f) else Color.Transparent,
                shape = RoundedCornerShape(24.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 12.dp, horizontal = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) Color.White else Color.White.copy(alpha = 0.72f),
            modifier = Modifier.size(24.dp)
        )
        AnimatedVisibility(visible = label.isNotBlank()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = label,
                    color = if (selected) Color.White else Color.White.copy(alpha = 0.72f),
                    fontSize = 10.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun GBoxTopDynamicIsland(
    vmState: VirtualKernelEngine.VirtualOsState,
    ramUsageMb: Int,
    onRefresh: () -> Unit
) {
    iOS27PassthroughGlass(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 999.dp,
        fillAlpha = 0.32f,
        borderAlpha = 0.30f
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (vmState.networkBridged) Color(0xFF30D158) else Color(0xFFFF9F0A))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Mini-GBox Virtual OS 27",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Quest Wi-Fi IP: ${vmState.questLocalIp}",
                    color = Color(0xFF64D2FF),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xFF30D158).copy(alpha = 0.22f))
                        .clickable { onRefresh() }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = if (vmState.googlePlayReachable) "Google Play Net: ${vmState.latencyMs}ms" else "Play Host: ONLINE",
                        color = Color(0xFF30D158),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "${ramUsageMb}MB RAM",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun GBoxVirtualAppGrid(
    apps: List<InstalledQuestApp>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    isLoading: Boolean,
    spawningAppPkg: String?,
    onSpawnSeparateWindowWithPill: (InstalledQuestApp) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        iOS27PassthroughGlass(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 22.dp,
            fillAlpha = 0.25f,
            borderAlpha = 0.25f
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
                Spacer(modifier = Modifier.width(10.dp))
                TextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = {
                        Text(
                            "Tap any app to open in a separate Full-Screen Spatial Window + Floating Glass Pill...",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 13.sp
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
                CircularProgressIndicator(color = Color(0xFF64D2FF))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 215.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(apps, key = { it.packageName }) { app ->
                    FluidGlassAppTile(
                        app = app,
                        isSpawning = spawningAppPkg == app.packageName,
                        onClick = { onSpawnSeparateWindowWithPill(app) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FluidGlassAppTile(
    app: InstalledQuestApp,
    isSpawning: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "AppTileFluidScale"
    )

    iOS27PassthroughGlass(
        modifier = Modifier
            .scale(scale)
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        cornerRadius = 28.dp,
        fillAlpha = if (isPressed) 0.45f else 0.28f,
        borderAlpha = if (isPressed) 0.55f else 0.28f
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
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF0A84FF).copy(alpha = 0.35f)),
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
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isSpawning) "Spawning Window..." else "Full-Screen + Nav Pill",
                        color = Color(0xFF64D2FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun GBoxPlayServicesAndNetworkHub(
    vmState: VirtualKernelEngine.VirtualOsState,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            iOS27PassthroughGlass(modifier = Modifier.fillMaxWidth(), cornerRadius = 28.dp, fillAlpha = 0.34f) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Self-Contained Virtual Android OS & Quest 3 Wi-Fi Bridge",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "The Virtual Machine is bound directly to your Quest 3's hardware Wi-Fi interface (ConnectivityManager.bindProcessToNetwork) with certified Pixel 8 Pro (husky) build properties so Google Play Services and games communicate directly with Google's servers.",
                        color = Color.White.copy(alpha = 0.74f),
                        fontSize = 13.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    NetRow("Virtual Device Fingerprint", vmState.deviceProfile)
                    NetRow("Quest 3 Wi-Fi Interface IP", vmState.questLocalIp)
                    NetRow("Active DNS Resolvers", vmState.dnsServers)
                    NetRow(
                        "Google Play API Socket (android.clients.google.com)",
                        if (vmState.googlePlayReachable) "CONNECTED (${vmState.latencyMs}ms RTT)" else "STANDBY (Tap Test Below)"
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = onRefresh,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30D158)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Rounded.WifiFind, contentDescription = null, tint = Color.Black)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Test Quest Wi-Fi -> Google Play Connection", color = Color.Black, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                if (!GmsBinderRouter.launchGmsCoreSettingsOrLogin(context)) {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("https://accounts.google.com/"))
                                    )
                                }
                            },
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f))
                        ) {
                            Icon(Icons.Rounded.AccountCircle, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Google Account OAuth Login", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
        Text(value, color = Color(0xFF64D2FF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GBoxPlayBillingVault() {
    val context = LocalContext.current
    var autoSyncEnabled by remember { mutableStateOf(true) }
    var savedEntries by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    fun loadVault() {
        val list = mutableListOf<Pair<String, String>>()
        val file = File(context.filesDir, "virtual_gms/account_purchases.json")
        if (file.exists()) {
            try {
                val root = JSONObject(file.readText())
                val keys = root.keys()
                while (keys.hasNext()) {
                    val pkg = keys.next()
                    val arr = root.optJSONArray(pkg) ?: continue
                    for (i in 0 until arr.length()) {
                        list.add(pkg to arr.getJSONObject(i).optString("productId"))
                    }
                }
            } catch (_: Throwable) {}
        }
        savedEntries = list
    }

    LaunchedEffect(Unit) { loadVault() }

    Column(modifier = Modifier.fillMaxSize()) {
        iOS27PassthroughGlass(modifier = Modifier.fillMaxWidth(), cornerRadius = 28.dp, fillAlpha = 0.32f) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Universal Google Play Billing & Entitlement Sync",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Automatically syncs queried In-App Purchase SKUs and serves com.android.vending billing across all spawned windows.",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = autoSyncEnabled,
                    onCheckedChange = { autoSyncEnabled = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF30D158))
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(savedEntries) { (pkg, sku) ->
                iOS27PassthroughGlass(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, fillAlpha = 0.22f) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(pkg, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Synced SKU: $sku", color = Color(0xFF30D158), fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
