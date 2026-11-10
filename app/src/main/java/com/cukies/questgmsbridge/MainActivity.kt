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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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

        val initialTab = intent.getStringExtra("OPEN_TAB") ?: "LIBRARY"

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF0A84FF),
                    secondary = Color(0xFF5E5CE6),
                    background = Color(0xFF07090F),
                    surface = Color(0xFF121624)
                )
            ) {
                iOS27SpatialShell(initialTab = initialTab)
            }
        }
    }
}

enum class SpatialTab(val label: String, val icon: ImageVector) {
    LIBRARY("App Space", Icons.Rounded.Apps),
    GMS_CORE("GMS Engine", Icons.Rounded.Memory),
    IAP_VAULT("Play Billing & IAP", Icons.Rounded.VerifiedUser)
}

data class InstalledQuestApp(
    val label: String,
    val packageName: String,
    val sourceDir: String,
    val splitCount: Int,
    val iconBitmap: ImageBitmap?
)

@Composable
fun iOS27SpatialShell(initialTab: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentTab by remember {
        mutableStateOf(
            if (initialTab == "IAP_VAULT") SpatialTab.IAP_VAULT else SpatialTab.LIBRARY
        )
    }
    var searchQuery by remember { mutableStateOf("") }
    var appsList by remember { mutableStateOf<List<InstalledQuestApp>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }
    var mountingAppPkg by remember { mutableStateOf<String?>(null) }

    var gmsProvider by remember { mutableStateOf<String?>(null) }
    var stagedGmsExists by remember { mutableStateOf(false) }
    var stagedPhoneskyExists by remember { mutableStateOf(false) }
    var ramUsageMb by remember { mutableStateOf(38) }

    fun refreshTelemetry() {
        gmsProvider = GmsBinderRouter.resolveInstalledGmsPackage(context.packageManager)
        stagedGmsExists = File(context.filesDir, "virtual_gms/GmsCore.apk").exists()
        stagedPhoneskyExists = File(context.filesDir, "virtual_gms/Phonesky.apk").exists()
        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
        ramUsageMb = (memInfo.totalPss / 1024).coerceAtLeast(28)
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
                gmsProvider = gmsProvider,
                ramUsageMb = ramUsageMb,
                onRefresh = { refreshTelemetry() }
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
                            mountingAppPkg = mountingAppPkg,
                            onLaunchInsideVm = { app ->
                                scope.launch {
                                    mountingAppPkg = app.packageName
                                    val loadedPlugin = withContext(Dispatchers.IO) {
                                        VirtualApkEngine.loadPluginApk(context, app.sourceDir, app.packageName)
                                    }
                                    mountingAppPkg = null
                                    if (loadedPlugin != null) {
                                        try {
                                            val intent = Intent(context, ProxySandboxActivity::class.java).apply {
                                                putExtra("GUEST_PKG", loadedPlugin.packageName)
                                                putExtra("GUEST_APK", loadedPlugin.apkPath)
                                                putExtra("GUEST_CLASS", loadedPlugin.mainActivityClass)
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(intent)
                                        } catch (t: Throwable) {
                                            Toast.makeText(context, "Sandbox error: ${t.localizedMessage}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            onLaunchBridgedNativeWindow = { app ->
                                try {
                                    val svcIntent = Intent(context, GmsDaemonService::class.java)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(svcIntent)
                                    } else {
                                        context.startService(svcIntent)
                                    }
                                } catch (_: Throwable) {}

                                // If user clicked ReVanced GmsCore itself in the app grid, use our deep-link launcher!
                                if (app.packageName.contains("gms", ignoreCase = true) ||
                                    app.packageName.contains("microg", ignoreCase = true)
                                ) {
                                    if (GmsBinderRouter.launchGmsCoreSettingsOrLogin(context)) return@SpatialAppLibraryScreen
                                }

                                val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                                if (launchIntent != null) {
                                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(launchIntent)
                                } else {
                                    // Open via ProxySandboxActivity fallback for apps without a standard launcher icon
                                    val intent = Intent(context, ProxySandboxActivity::class.java).apply {
                                        putExtra("GUEST_PKG", app.packageName)
                                        putExtra("GUEST_APK", app.sourceDir)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                }
                            }
                        )

                        SpatialTab.GMS_CORE -> GmsEngineControlCenter(
                            gmsProvider = gmsProvider,
                            stagedGmsExists = stagedGmsExists,
                            stagedPhoneskyExists = stagedPhoneskyExists,
                            ramUsageMb = ramUsageMb,
                            onRefresh = { refreshTelemetry() }
                        )

                        SpatialTab.IAP_VAULT -> PlayBillingVaultScreen(apps = appsList)
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
    gmsProvider: String?,
    ramUsageMb: Int,
    onRefresh: () -> Unit
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
                        .background(Color(0xFF30D158))
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
                    text = "Quest 3 • XR2 Gen 2 (arm64)",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 13.sp
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            if (gmsProvider != null) Color(0xFF30D158).copy(alpha = 0.22f)
                            else Color(0xFF0A84FF).copy(alpha = 0.22f)
                        )
                        .clickable { onRefresh() }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = if (gmsProvider != null) "GMS Linked: $gmsProvider" else "GMS: Internal TinyVM (:gms_daemon)",
                        color = if (gmsProvider != null) Color(0xFF30D158) else Color(0xFF64D2FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable { onRefresh() }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "RAM: ${ramUsageMb}MB",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
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
    mountingAppPkg: String?,
    onLaunchInsideVm: (InstalledQuestApp) -> Unit,
    onLaunchBridgedNativeWindow: (InstalledQuestApp) -> Unit
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
                            "Search sideloaded Quest 3 apps...",
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
        } else if (apps.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No sideloaded 2D/VR user apps found.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 16.sp
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 230.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(apps, key = { it.packageName }) { app ->
                    iOS27AppSquircleCard(
                        app = app,
                        isMounting = mountingAppPkg == app.packageName,
                        onLaunchSandbox = { onLaunchInsideVm(app) },
                        onLaunchNative = { onLaunchBridgedNativeWindow(app) }
                    )
                }
            }
        }
    }
}

@Composable
fun iOS27AppSquircleCard(
    app: InstalledQuestApp,
    isMounting: Boolean,
    onLaunchSandbox: () -> Unit,
    onLaunchNative: () -> Unit
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
            .clickable(interactionSource = interactionSource, indication = null) { onLaunchSandbox() },
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
                    if (app.splitCount > 0) {
                        Text(
                            text = "${app.splitCount} Split APKs + arm64",
                            color = Color(0xFF64D2FF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onLaunchSandbox,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (isMounting) "Opening..." else "TinyVM Sandbox",
                        fontSize = 12.sp,
                        color = Color.White,
                        maxLines = 1
                    )
                }

                OutlinedButton(
                    onClick = onLaunchNative,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Direct 2D",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }
        }
    }
}

@Composable
fun GmsEngineControlCenter(
    gmsProvider: String?,
    stagedGmsExists: Boolean,
    stagedPhoneskyExists: Boolean,
    ramUsageMb: Int,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            iOS27GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 28.dp) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = "Google Play Services & ReVanced GmsCore Hub",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (gmsProvider != null) {
                            "Detected Installed GMS Provider: $gmsProvider. Click 'Sign In to Google Account' below to open its hidden microG / GmsCore Account & OAuth Settings."
                        } else {
                            "No external GmsCore APK detected yet. Click 'Download GmsCore OAuth APK' if you want live Google OAuth sign-in."
                        },
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 14.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Button(
                            onClick = {
                                onRefresh()
                                val launched = GmsBinderRouter.launchGmsCoreSettingsOrLogin(context)
                                if (!launched) {
                                    Toast.makeText(
                                        context,
                                        "No GmsCore / microG APK found on headset. Tap 'Download GmsCore OAuth APK' first.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Rounded.AccountCircle, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sign In to Google Account (Open GmsCore)")
                        }

                        OutlinedButton(
                            onClick = {
                                val url = "https://github.com/ReVanced/GmsCore/releases/latest"
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            },
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, Color(0xFF64D2FF).copy(alpha = 0.5f))
                        ) {
                            Icon(Icons.Rounded.CloudDownload, contentDescription = null, tint = Color(0xFF64D2FF))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Download GmsCore OAuth APK", color = Color(0xFF64D2FF))
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                iOS27GlassSurface(modifier = Modifier.weight(1f), cornerRadius = 24.dp) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("Detected GmsCore / microG Package", color = Color(0xFF64D2FF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = gmsProvider ?: "Internal Fallback (:gms_daemon)",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Automatically scans hidden microG/ReVanced activities (SettingsActivity & LoginActivity).",
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 12.sp
                        )
                    }
                }

                iOS27GlassSurface(modifier = Modifier.weight(1f), cornerRadius = 24.dp) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("Play Billing & LVL Daemon Status", color = Color(0xFF30D158), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "ONLINE (${ramUsageMb}MB RAM)",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Serving IInAppBillingService v3–v7 and Google Play entitlement restoration.",
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlayBillingVaultScreen(apps: List<InstalledQuestApp>) {
    val context = LocalContext.current
    var selectedPkg by remember { mutableStateOf(apps.firstOrNull()?.packageName ?: "com.example.paidapp") }
    var skuInput by remember { mutableStateOf("pro_unlock") }
    var savedEntries by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    fun loadSavedPurchases() {
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
                        val item = arr.getJSONObject(i)
                        list.add(pkg to item.optString("productId"))
                    }
                }
            } catch (_: Throwable) {}
        }
        savedEntries = list
    }

    LaunchedEffect(Unit) { loadSavedPurchases() }

    Column(modifier = Modifier.fillMaxSize()) {
        iOS27GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 28.dp) {
            Column(modifier = Modifier.padding(22.dp)) {
                Text(
                    text = "Google Play In-App Purchase (IAP) Account Entitlement Vault",
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Register the SKUs owned by your Google account so :gms_daemon returns signed PURCHASED receipts whenever a bridged app queries getPurchases().",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = selectedPkg,
                        onValueChange = { selectedPkg = it },
                        label = { Text("App Package Name", color = Color.White.copy(alpha = 0.6f)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF0A84FF),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = skuInput,
                        onValueChange = { skuInput = it },
                        label = { Text("Owned Product SKU ID", color = Color.White.copy(alpha = 0.6f)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF0A84FF),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier.weight(0.8f)
                    )

                    Button(
                        onClick = {
                            if (selectedPkg.isNotBlank() && skuInput.isNotBlank()) {
                                savePurchaseEntitlement(context, selectedPkg.trim(), skuInput.trim())
                                loadSavedPurchases()
                                Toast.makeText(context, "Synced SKU '$skuInput' for $selectedPkg", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30D158)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.height(54.dp)
                    ) {
                        Icon(Icons.Rounded.AddCircle, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sync Entitlement", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(savedEntries) { (pkg, sku) ->
                iOS27GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, fillAlpha = 0.07f) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(pkg, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("Synced Google Play Entitlement SKU: $sku", color = Color(0xFF30D158), fontSize = 12.sp)
                        }
                        IconButton(onClick = {
                            removePurchaseEntitlement(context, pkg, sku)
                            loadSavedPurchases()
                        }) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove", tint = Color(0xFFFF453A))
                        }
                    }
                }
            }
        }
    }
}

private fun savePurchaseEntitlement(context: Context, pkg: String, sku: String) {
    val gmsDir = File(context.filesDir, "virtual_gms").apply { mkdirs() }
    val file = File(gmsDir, "account_purchases.json")
    val root = if (file.exists()) {
        try { JSONObject(file.readText()) } catch (_: Throwable) { JSONObject() }
    } else {
        JSONObject()
    }
    val arr = root.optJSONArray(pkg) ?: JSONArray()
    val entry = JSONObject().apply {
        put("productId", sku)
        put("purchaseTime", System.currentTimeMillis())
        put("orderId", "GPA.3391-QUEST3-${System.currentTimeMillis() % 10000}")
    }
    arr.put(entry)
    root.put(pkg, arr)
    file.writeText(root.toString(2))
}

private fun removePurchaseEntitlement(context: Context, pkg: String, sku: String) {
    val file = File(context.filesDir, "virtual_gms/account_purchases.json")
    if (!file.exists()) return
    try {
        val root = JSONObject(file.readText())
        val arr = root.optJSONArray(pkg) ?: return
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("productId") != sku) {
                newArr.put(obj)
            }
        }
        root.put(pkg, newArr)
        file.writeText(root.toString(2))
    } catch (_: Throwable) {}
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
