package com.philip.fieldtracker.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.philip.fieldtracker.data.AppState
import com.philip.fieldtracker.tracking.Calibrator
import com.philip.fieldtracker.tracking.HeartRateHub
import com.philip.fieldtracker.tracking.Phase
import com.philip.fieldtracker.tracking.Tracker
import com.philip.fieldtracker.tracking.TrackingService
import com.philip.fieldtracker.ui.components.Bar
import com.philip.fieldtracker.ui.components.BigButton
import com.philip.fieldtracker.ui.components.FieldCanvas
import com.philip.fieldtracker.ui.components.Label
import com.philip.fieldtracker.ui.components.MetricCard
import com.philip.fieldtracker.ui.components.Panel
import com.philip.fieldtracker.ui.components.Pill
import com.philip.fieldtracker.ui.components.Shape12
import com.philip.fieldtracker.ui.components.Shape8
import com.philip.fieldtracker.ui.components.formatDuration
import com.philip.fieldtracker.ui.components.formatKm
import com.philip.fieldtracker.ui.field.pathOf
import com.philip.fieldtracker.ui.components.unitKm
import com.philip.fieldtracker.ui.theme.LocalPalette
import com.philip.fieldtracker.ui.theme.Type
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LiveScreen(onGoCalib: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val live by Tracker.live.collectAsState()
    val sport by AppState.sport.collectAsState()
    val calibration by Calibrator.calibration.collectAsState()
    val hr by HeartRateHub.bpm.collectAsState()
    val hrSource by HeartRateHub.source.collectAsState()
    val keepOn by AppState.keepScreenOn.collectAsState()

    if (live.phase == Phase.IDLE) {
        val s = sport
        val cal = calibration
        if (s == null || cal == null) {
            EmptyState(
                "Terrain non balisé",
                "Avant de commencer, balise les 4 coins du terrain.",
                "Aller au balisage", onGoCalib
            )
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(s.name, style = Type.h1, color = p.text)
                Label(String.format("Terrain balisé : %.0f × %.0f m", cal.lengthM, cal.widthM), color = p.primary)
                Spacer(Modifier.height(14.dp))
                FieldCanvas(s, lineColor = p.primary.copy(alpha = 0.7f))
                Spacer(Modifier.height(20.dp))
                BigButton("Start", icon = Icons.Filled.PlayArrow, height = 76.dp) {
                    Tracker.start(s, cal)
                    TrackingService.start(ctx)
                }
            }
        }
        return
    }

    val s = live.sport ?: return
    val view = LocalView.current
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(500); now = System.currentTimeMillis() } }
    var locked by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    val paused = live.phase == Phase.PAUSED
    val hrNow = if (now > 0 && HeartRateHub.current() > 0) hr else 0

    val anim = rememberInfiniteTransition(label = "live")
    val pulse by anim.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "p")

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(if (paused) "En pause" else "En cours", if (paused) p.warning else p.primary, filled = true)
                    Spacer(Modifier.weight(1f))
                    Label("Temps de jeu", style = Type.caption)
                    Spacer(Modifier.width(8.dp))
                    Text(formatDuration(Tracker.elapsedMs(now)), style = Type.metricXl, color = if (paused) p.warning else p.primary)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard(
                        "FC live", if (hrNow > 0) "$hrNow" else "—", "bpm", Modifier.weight(1f),
                        icon = Icons.Filled.Favorite, color = if (hrNow > 0) p.secondary else p.muted,
                        footer = if (hrNow > 0) hrSource else "Montre ou capteur non reçu",
                    )
                    val band = when {
                        live.speedKmh >= s.sprintKmh -> "SPRINT"
                        live.speedKmh >= s.hiKmh -> "HAUTE INT."
                        live.speedKmh >= s.hiKmh * 0.72 -> "COURSE"
                        live.speedKmh >= 7 -> "FOOTING"
                        else -> "MARCHE"
                    }
                    MetricCard(
                        "Allure", String.format("%.1f", live.speedKmh), "km/h", Modifier.weight(1f),
                        icon = Icons.Filled.Speed, color = if (live.speedKmh >= s.hiKmh) p.secondary else p.primary,
                        footer = band,
                        footerColor = if (live.speedKmh >= s.hiKmh) p.secondary else p.primary,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Bar((live.speedKmh / s.sprintKmh).toFloat(), if (live.speedKmh >= s.hiKmh) p.secondary else p.primary)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("Distance", formatKm(live.distanceM), unitKm(live.distanceM), Modifier.weight(1f), icon = Icons.Filled.Straighten, color = p.tertiary)
                    MetricCard("Calories", "${live.kcal.toInt()}", "kcal", Modifier.weight(1f), icon = Icons.Filled.LocalFireDepartment, color = p.text)
                }
            }
            Spacer(Modifier.height(12.dp))

            // Statut terrain
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(Shape12)
                    .background(if (live.inField) p.surface else p.secondary.copy(alpha = 0.18f))
                    .border(1.dp, if (live.inField) p.border else p.secondary, Shape12)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label(
                    if (!live.hasFix) "Signal GPS faible" else if (live.inField) "Dans les limites du terrain" else "Hors terrain",
                    color = if (!live.hasFix) p.warning else if (live.inField) p.primary else p.secondary,
                    modifier = Modifier.weight(1f)
                )
                if (live.inField && live.zoneLabel.isNotEmpty()) Pill(live.zoneLabel, p.primary)
            }
            Spacer(Modifier.height(12.dp))

            // Terrain + trace
            Box {
                FieldCanvas(s) { r ->
                    fun pt(u: Float, v: Float) = Offset(r.left + u.coerceIn(-0.05f, 1.05f) * r.width, r.top + v.coerceIn(-0.05f, 1.05f) * r.height)
                    val trail = live.trail
                    if (trail.size >= 2) {
                        drawPath(
                            pathOf(trail.map { pt(it.first, it.second) }),
                            p.primary.copy(alpha = 0.85f),
                            style = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                    }
                    if (live.hasFix) {
                        val o = pt(live.u.toFloat(), live.v.toFloat())
                        val c = if (live.inField) p.primary else p.secondary
                        drawCircle(c.copy(alpha = 0.35f * (1 - pulse)), 14f + 30f * pulse, o)
                        drawCircle(c, 12f, o)
                        drawCircle(p.bg, 5f, o)
                    }
                }
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(Shape8)
                        .background(p.elevated.copy(alpha = 0.92f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(String.format("Vitesse max : %.1f km/h · Sprints : %d", live.maxSpeedKmh, live.sprints), style = Type.caption, color = p.text)
                }
            }
            Spacer(Modifier.height(12.dp))

            live.lastHighlight?.let { h ->
                Panel(padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(Shape8).background(p.secondary), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Flag, null, tint = Color.White)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Label("Dernier temps fort", style = Type.caption)
                            Text(h.label, style = Type.bodyBold, color = p.text)
                        }
                        Text(SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date(h.t)), style = Type.label, color = p.muted)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ControlButton("Temps fort", Icons.Filled.Bolt, p.surface, p.text, Modifier.weight(1f)) { Tracker.mark() }
                ControlButton(
                    if (paused) "Reprendre" else "Pause",
                    if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    if (paused) p.primary else p.secondary,
                    if (paused) p.onPrimary else Color.White,
                    Modifier.weight(1.2f)
                ) { if (paused) Tracker.resume() else Tracker.pause() }
                ControlButton("Verrouiller", Icons.Filled.Lock, p.surface, p.text, Modifier.weight(1f)) { locked = true }
            }
            Spacer(Modifier.height(12.dp))
            BigButton("Terminer la séance", icon = Icons.Filled.Stop, color = p.elevated, textColor = p.secondary, height = 56.dp) {
                confirmStop = true
            }
            Spacer(Modifier.height(24.dp))
        }

        if (locked) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .pointerInput(Unit) { detectTapGestures(onLongPress = { locked = false }) },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Lock, null, tint = Color.White, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Écran verrouillé", style = Type.h2, color = Color.White)
                    Text("Appui long pour déverrouiller", style = Type.label, color = Color.White.copy(alpha = 0.8f))
                }
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            containerColor = p.elevated,
            title = { Text("Terminer la séance ?", style = Type.h2, color = p.text) },
            text = { Text("Les statistiques seront calculées et enregistrées.", style = Type.body, color = p.muted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    TrackingService.stop(ctx)
                }) { Text("TERMINER", style = Type.label, color = p.secondary) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) { Text("CONTINUER", style = Type.label, color = p.text) }
            },
        )
    }
}

@Composable
private fun ControlButton(text: String, icon: ImageVector, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(
        modifier
            .height(92.dp)
            .clip(Shape12)
            .background(bg)
            .border(1.dp, p.border, Shape12)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(6.dp))
        Text(text.uppercase(), style = Type.label, color = fg, textAlign = TextAlign.Center)
    }
}
