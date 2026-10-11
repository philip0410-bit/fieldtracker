package com.philip.fieldtracker

import com.philip.fieldtracker.i18n.tr
import com.philip.fieldtracker.i18n.trf
import android.Manifest
import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.philip.fieldtracker.data.AppState
import com.philip.fieldtracker.tracking.GpsEngine
import com.philip.fieldtracker.tracking.HeartRateHub
import com.philip.fieldtracker.tracking.Phase
import com.philip.fieldtracker.tracking.Tracker
import com.philip.fieldtracker.ui.components.Pill
import com.philip.fieldtracker.ui.components.Shape8
import com.philip.fieldtracker.ui.screens.AnalyticsScreen
import com.philip.fieldtracker.ui.screens.CalibrationScreen
import com.philip.fieldtracker.ui.screens.LiveScreen
import com.philip.fieldtracker.ui.screens.SetupScreen
import com.philip.fieldtracker.ui.theme.FieldTheme
import com.philip.fieldtracker.ui.theme.LocalPalette
import com.philip.fieldtracker.ui.theme.Palettes
import com.philip.fieldtracker.ui.theme.Type
import com.philip.fieldtracker.watch.WatchLink
import kotlinx.coroutines.delay

class FieldApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppState.init(this)
    }
}

enum class Tab(val label: String, val icon: ImageVector) {
    SETUP("Sports", Icons.Filled.Tune),
    CALIB("Balisage", Icons.Filled.GpsFixed),
    LIVE("Live", Icons.Filled.Timer),
    STATS("Analyse", Icons.Filled.Insights),
}

class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (!GpsEngine.hasPermission(this)) permLauncher.launch(perms.toTypedArray())
        WatchLink.refresh()

        setContent {
            val themeId by AppState.theme.collectAsState()
            val palette = Palettes.resolve(themeId)
            LaunchedEffect(palette) {
                val c = palette.bg.toArgb()
                enableEdgeToEdge(
                    statusBarStyle = if (palette.dark) SystemBarStyle.dark(c) else SystemBarStyle.light(c, c),
                    navigationBarStyle = if (palette.dark) SystemBarStyle.dark(c) else SystemBarStyle.light(c, c),
                )
            }
            FieldTheme(themeId) { AppRoot() }
        }
    }

    override fun onResume() {
        super.onResume()
        WatchLink.refresh()
        WatchLink.pushState(force = true)
    }
}

@Composable
fun AppRoot() {
    val p = LocalPalette.current
    var tab by rememberSaveable { mutableStateOf(if (Tracker.isActive) Tab.LIVE else Tab.SETUP) }
    val live by Tracker.live.collectAsState()

    // Fin de séance -> onglet Analyse
    var lastPhase by remember { mutableStateOf(live.phase) }
    LaunchedEffect(live.phase) {
        if (lastPhase != Phase.IDLE && live.phase == Phase.IDLE) tab = Tab.STATS
        if (lastPhase == Phase.IDLE && live.phase != Phase.IDLE) tab = Tab.LIVE
        lastPhase = live.phase
    }

    Scaffold(
        containerColor = p.bg,
        topBar = { TopBar(tab, onGpsClick = { tab = Tab.CALIB }) },
        bottomBar = {
            NavigationBar(containerColor = p.surface, tonalElevation = 0.dp) {
                Tab.entries.forEach { tb ->
                    NavigationBarItem(
                        selected = tab == tb,
                        onClick = { tab = tb },
                        icon = { Icon(tb.icon, null) },
                        label = { Text(tr(tb.label).uppercase(), style = Type.caption) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = p.primary,
                            selectedTextColor = p.primary,
                            indicatorColor = p.primary.copy(alpha = 0.12f),
                            unselectedIconColor = p.muted,
                            unselectedTextColor = p.muted,
                        ),
                    )
                }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner).background(p.bg)) {
            when (tab) {
                Tab.SETUP -> SetupScreen(onNext = { tab = Tab.CALIB }, onOpenStats = { tab = Tab.STATS })
                Tab.CALIB -> CalibrationScreen(onGoSetup = { tab = Tab.SETUP }, onStarted = { tab = Tab.LIVE })
                Tab.LIVE -> LiveScreen(onGoCalib = { tab = Tab.CALIB })
                Tab.STATS -> AnalyticsScreen(onGoSetup = { tab = Tab.SETUP })
            }
        }
    }
}

@Composable
private fun TopBar(tab: Tab, onGpsClick: () -> Unit) {
    val p = LocalPalette.current
    val fix by GpsEngine.fix.collectAsState()
    val nodes by WatchLink.nodes.collectAsState()
    val hr by HeartRateHub.bpm.collectAsState()
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }
    val fresh = tick >= 0 && GpsEngine.isFresh(fix, 5000)
    val hrFresh = tick >= 0 && HeartRateHub.current() > 0

    Row(
        Modifier
            .fillMaxWidth()
            .background(p.bg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(36.dp).border(1.dp, p.border, Shape8).padding(7.dp)) {
            val w = size.width; val h = size.height
            drawRect(p.primary, style = Stroke(2f))
            drawLine(p.primary, Offset(w / 2, 0f), Offset(w / 2, h), 1.5f)
            listOf(Offset(0f, 0f), Offset(w, 0f), Offset(w, h), Offset(0f, h)).forEach { drawCircle(p.primary, 3f, it) }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("FIELDTRACKER", style = Type.bodyBold.copy(fontSize = 16.sp), color = p.text, maxLines = 1)
            Text(tr(tab.label), style = Type.caption, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(
                if (fresh) trf("GPS ±%d m", fix?.accuracy?.toInt() ?: 0) else tr("GPS — activer"),
                if (fresh) (if ((fix?.accuracy ?: 99f) <= 8f) p.primary else p.warning) else p.secondary,
                modifier = Modifier.clickable(onClick = onGpsClick),
            )
            if (hrFresh) Pill("♥ $hr", p.secondary)
            else Pill(if (nodes.isNotEmpty()) tr("Montre") else tr("Montre —"), if (nodes.isNotEmpty()) p.primary else p.muted)
        }
    }
}
