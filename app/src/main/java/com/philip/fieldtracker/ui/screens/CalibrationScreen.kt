package com.philip.fieldtracker.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.philip.fieldtracker.data.AppState
import com.philip.fieldtracker.data.Geo
import com.philip.fieldtracker.tracking.Calibrator
import com.philip.fieldtracker.tracking.GpsEngine
import com.philip.fieldtracker.tracking.Tracker
import com.philip.fieldtracker.tracking.TrackingService
import com.philip.fieldtracker.ui.components.BigButton
import com.philip.fieldtracker.ui.components.FieldCanvas
import com.philip.fieldtracker.ui.components.GhostButton
import com.philip.fieldtracker.ui.components.GpsPanel
import com.philip.fieldtracker.ui.components.IconSquare
import com.philip.fieldtracker.ui.components.KeyValue
import com.philip.fieldtracker.ui.components.Label
import com.philip.fieldtracker.ui.components.Panel
import com.philip.fieldtracker.ui.components.Pill
import com.philip.fieldtracker.ui.components.Shape8
import com.philip.fieldtracker.ui.theme.LocalPalette
import com.philip.fieldtracker.ui.theme.Type
import com.philip.fieldtracker.watch.WatchLink
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
fun CalibrationScreen(onGoSetup: () -> Unit, onStarted: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val sport by AppState.sport.collectAsState()
    val corners by Calibrator.corners.collectAsState()
    val progress by Calibrator.progress.collectAsState()
    val message by Calibrator.message.collectAsState()
    val calibration by Calibrator.calibration.collectAsState()
    val fix by GpsEngine.fix.collectAsState()
    val sats by GpsEngine.satellites.collectAsState()
    val nodes by WatchLink.nodes.collectAsState()

    val s = sport
    if (s == null) {
        EmptyState("Aucun sport choisi", "Choisis d'abord ton sport pour afficher le bon terrain.", "Choisir un sport", onGoSetup)
        return
    }

    DisposableEffect(Unit) {
        GpsEngine.start(ctx, "calibration")
        WatchLink.pushState(force = true)
        onDispose { GpsEngine.stop("calibration") }
    }
    LaunchedEffect(s.id) { Calibrator.prepare(s) }

    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000); tick++
            GpsEngine.start(ctx, "calibration") // démarre dès que l'autorisation GPS est accordée
        }
    }
    val fresh = tick >= 0 && GpsEngine.isFresh(fix)
    val acc = fix?.accuracy ?: 99f
    val gpsOk = fresh && acc <= Calibrator.MAX_ACCURACY_M
    val idx = corners.indexOfFirst { it == null }.let { if (it < 0) 4 else it }
    val saved = remember(s.id, calibration) { AppState.lastCalibration(s.id) }

    val blink = rememberInfiniteTransition(label = "blink")
    val pulse by blink.animateFloat(
        0.25f, 1f, infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Reverse), label = "pulse"
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Qualité GPS (avec bouton pour autoriser / activer la localisation)
        GpsPanel("calibration-panel")
        Spacer(Modifier.height(12.dp))

        if (s.indoor) {
            Panel(accent = p.warning.copy(alpha = 0.6f), padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null, tint = p.warning, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Sport en salle : le GPS fonctionne mal sous un toit. En intérieur, importe plutôt les données d'un traqueur équipé d'un système de positionnement intérieur.",
                        style = Type.body, color = p.text
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Étape
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(s.name, p.primary, filled = true, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                Text(if (idx < 4) "Étape ${idx + 1}/4" else "Terminé", style = Type.label, color = p.muted)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (idx < 4) Calibrator.cornerNames[idx] else "Terrain balisé",
                style = Type.h1, color = p.text
            )
            if (fresh && fix != null) {
                Label(String.format("%.6f, %.6f", fix!!.lat, fix!!.lon), color = p.primary, style = Type.caption)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 0 until 4) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(5.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                when {
                                    corners[i] != null -> p.primary
                                    i == idx -> p.primary.copy(alpha = pulse * 0.6f)
                                    else -> p.elevated
                                }
                            )
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Terrain avec les 4 coins
        Box {
            FieldCanvas(s) { r ->
                val pts = listOf(r.topLeft, Offset(r.right, r.top), r.bottomRight, Offset(r.left, r.bottom))
                // côtés déjà balisés
                for (i in 0 until 4) {
                    val j = (i + 1) % 4
                    if (corners[i] != null && corners[j] != null) {
                        drawLine(p.primary, pts[i], pts[j], 5f)
                    }
                }
                pts.forEachIndexed { i, o ->
                    when {
                        corners[i] != null -> {
                            drawCircle(p.primary.copy(alpha = 0.25f), 26f, o)
                            drawCircle(p.primary, 14f, o)
                            drawCircle(p.onPrimary, 5f, o)
                        }
                        i == idx -> {
                            val prog = progress
                            drawCircle(p.primary.copy(alpha = 0.18f * pulse), 30f + 22f * pulse, o)
                            drawCircle(p.primary.copy(alpha = pulse), 15f, o, style = Stroke(5f))
                            if (prog != null) {
                                drawArc(
                                    p.primary, -90f, 360f * prog, false,
                                    topLeft = Offset(o.x - 26f, o.y - 26f),
                                    size = androidx.compose.ui.geometry.Size(52f, 52f),
                                    style = Stroke(6f)
                                )
                            }
                        }
                        else -> drawCircle(
                            p.muted, 13f, o,
                            style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                        )
                    }
                }
            }
            if (idx < 4) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 28.dp)
                        .clip(Shape8)
                        .background(p.elevated.copy(alpha = 0.92f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        "Coin ${idx + 1} : place-toi sur l'angle puis appuie sur OK",
                        style = Type.caption, color = p.text
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Commence par n'importe quel coin, puis fais le tour du terrain dans le sens des aiguilles d'une montre en longeant d'abord la grande ligne de côté.",
            style = Type.caption, color = p.muted
        )

        // Côtés mesurés
        val sides = (0 until 3).mapNotNull { i ->
            val a = corners[i]; val b = corners[i + 1]
            if (a != null && b != null) i to Geo.distance(a, b) else null
        }
        if (sides.isNotEmpty() && calibration == null) {
            Spacer(Modifier.height(10.dp))
            Panel(padding = 12.dp) {
                sides.forEach { (i, d) ->
                    val ref = if (i % 2 == 0) s.lengthM else s.widthM
                    KeyValue("Côté ${i + 1} → ${i + 2}", String.format("%.1f m  (réf. %.0f m)", d, ref))
                }
            }
        }

        message?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = Type.body, color = p.warning)
        }
        Spacer(Modifier.height(14.dp))

        val cal = calibration
        if (cal == null) {
            if (nodes.isNotEmpty()) {
                Panel(padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconSquare(Icons.Filled.Watch, p.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Montre synchronisée", style = Type.bodyBold, color = p.text)
                            Text("Tu peux aussi valider chaque coin depuis ta montre.", style = Type.caption, color = p.muted)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            BigButton(
                text = when {
                    progress != null -> "Mesure… ${((progress ?: 0f) * 100).toInt()} %"
                    !fresh -> "OK — baliser (attente GPS)"
                    !gpsOk -> "OK — baliser (±${acc.toInt()} m)"
                    else -> "OK — baliser ce coin"
                },
                icon = Icons.Filled.AddLocation,
                enabled = progress == null,
                onClick = { Calibrator.capture(s) },
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Annuler le coin", Modifier.weight(1f), Icons.AutoMirrored.Filled.Undo) { Calibrator.undo() }
                GhostButton("Recommencer", Modifier.weight(1f), Icons.Filled.Refresh) { Calibrator.reset() }
            }
            if (saved != null && idx == 0) {
                Spacer(Modifier.height(10.dp))
                GhostButton(
                    "Réutiliser le dernier balisage (${saved.lengthM.toInt()} × ${saved.widthM.toInt()} m)",
                    Modifier.fillMaxWidth(), Icons.Filled.History, tint = p.primary
                ) { Calibrator.useCalibration(saved) }
            }
        } else {
            val gap = cal.diagonalGap
            val dl = abs(cal.lengthM - s.lengthM) / s.lengthM
            Panel(accent = p.primary.copy(alpha = 0.6f)) {
                Label("Terrain balisé", color = p.primary)
                Spacer(Modifier.height(4.dp))
                Text(String.format("%.1f × %.1f m", cal.lengthM, cal.widthM), style = Type.metric, color = p.text)
                Spacer(Modifier.height(6.dp))
                KeyValue("Surface", String.format("%.0f m²", cal.areaM2))
                KeyValue(
                    "Forme",
                    if (gap < 0.04) "Rectangle correct" else String.format("Diagonales : %.0f %% d'écart", gap * 100),
                    if (gap < 0.04) p.primary else p.warning
                )
                if (dl > 0.25) {
                    Text(
                        "Les dimensions s'éloignent du terrain standard : c'est normal pour un terrain amateur, sinon recommence.",
                        style = Type.caption, color = p.muted
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            BigButton("Start", icon = Icons.Filled.PlayArrow, height = 72.dp) {
                Tracker.start(s, cal)
                TrackingService.start(ctx)
                onStarted()
            }
            Spacer(Modifier.height(10.dp))
            GhostButton("Refaire le balisage", Modifier.fillMaxWidth(), Icons.Filled.Refresh) { Calibrator.reset() }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun EmptyState(title: String, text: String, button: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = Type.h1, color = p.text)
        Spacer(Modifier.height(8.dp))
        Text(text, style = Type.body, color = p.muted)
        Spacer(Modifier.height(20.dp))
        BigButton(button, onClick = onClick)
    }
}
