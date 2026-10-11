package com.philip.fieldtracker.ui.screens

import com.philip.fieldtracker.i18n.tr
import com.philip.fieldtracker.i18n.trf
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.philip.fieldtracker.data.AppState
import com.philip.fieldtracker.data.Exporter
import com.philip.fieldtracker.data.Session
import com.philip.fieldtracker.data.SessionStats
import com.philip.fieldtracker.data.SessionStore
import com.philip.fieldtracker.data.SessionSummary
import com.philip.fieldtracker.data.Sports
import com.philip.fieldtracker.data.StatsCalculator
import com.philip.fieldtracker.health.HealthLink
import com.philip.fieldtracker.ui.components.Bar
import com.philip.fieldtracker.ui.components.Chip
import com.philip.fieldtracker.ui.components.FieldCanvas
import com.philip.fieldtracker.ui.components.GhostButton
import com.philip.fieldtracker.ui.components.IconSquare
import com.philip.fieldtracker.ui.components.KeyValue
import com.philip.fieldtracker.ui.components.Label
import com.philip.fieldtracker.ui.components.MetricCard
import com.philip.fieldtracker.ui.components.Panel
import com.philip.fieldtracker.ui.components.Pill
import com.philip.fieldtracker.ui.components.SectionTitle
import com.philip.fieldtracker.ui.components.Shape8
import com.philip.fieldtracker.ui.components.formatDuration
import com.philip.fieldtracker.ui.components.formatKm
import com.philip.fieldtracker.ui.components.unitKm
import com.philip.fieldtracker.ui.theme.LocalPalette
import com.philip.fieldtracker.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun AnalyticsScreen(onGoSetup: () -> Unit) {
    val ctx = LocalContext.current
    val selectedId by AppState.selectedSessionId.collectAsState()
    val version by AppState.historyVersion.collectAsState()
    val profile by AppState.profile.collectAsState()
    var showHistory by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf<Pair<Session, SessionStats>?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(selectedId, version, profile) {
        loading = true
        loaded = withContext(Dispatchers.Default) {
            val id = selectedId ?: SessionStore.list(ctx).firstOrNull()?.id
            id?.let { SessionStore.load(ctx, it) }?.let { it to StatsCalculator.compute(it, profile) }
        }
        loading = false
    }

    if (showHistory) {
        HistoryList(onBack = { showHistory = false }) { id ->
            AppState.selectSession(id)
            showHistory = false
        }
        return
    }

    val data = loaded
    if (data == null) {
        if (!loading) {
            EmptyState(
                tr("Aucune séance"),
                tr("Termine une séance ou importe les données d'un traqueur pour voir ton bilan."),
                tr("Choisir un sport"), onGoSetup
            )
        }
        return
    }
    SessionReport(data.first, data.second, onHistory = { showHistory = true })
}

@Composable
private fun SessionReport(s: Session, st: SessionStats, onHistory: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sport = s.sport
    var heatMode by remember(s.id) { mutableStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val hcAvailable = remember { HealthLink.isAvailable(ctx) }

    fun sendToHealth() {
        busy = true
        scope.launch {
            val msg = try {
                HealthLink.writeSession(ctx, s, st); tr("Séance envoyée à Samsung Health (Health Connect)")
            } catch (e: Exception) {
                trf("Envoi impossible : %s", e.message)
            }
            busy = false
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.containsAll(HealthLink.permissions)) sendToHealth()
        else Toast.makeText(ctx, tr("Autorisations Health Connect refusées"), Toast.LENGTH_SHORT).show()
    }

    fun importHr() {
        busy = true
        scope.launch {
            val msg = try {
                if (!HealthLink.hasAllPermissions(ctx)) {
                    busy = false
                    hcLauncher.launch(HealthLink.permissions)
                    return@launch
                }
                val hr = HealthLink.readHeartRate(ctx, s.startMs, s.endMs)
                if (hr.isEmpty()) tr("Aucune fréquence cardiaque trouvée dans Samsung Health pour cette séance")
                else {
                    withContext(Dispatchers.Default) {
                        var j = 0
                        val pts = s.points.map { pt ->
                            while (j < hr.size - 1 && abs(hr[j + 1].first - pt.t) <= abs(hr[j].first - pt.t)) j++
                            if (abs(hr[j].first - pt.t) <= 15_000) pt.copy(hr = hr[j].second) else pt
                        }
                        val ns = s.copy(points = pts)
                        SessionStore.save(ctx, ns, StatsCalculator.compute(ns, AppState.profile.value))
                    }
                    AppState.historyChanged()
                    trf("%d mesures de FC ajoutées", hr.size)
                }
            } catch (e: Exception) {
                trf("Lecture impossible : %s", e.message)
            }
            busy = false
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // En-tête
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(
                if (s.source.startsWith("import:")) "Import · ${s.source.removePrefix("import:")}" else tr("Séance GPS"),
                p.primary, modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.weight(1f))
            Text(formatDuration(st.activeMs), style = Type.label, color = p.muted)
        }
        Spacer(Modifier.height(8.dp))
        Text(tr(sport.name), style = Type.hero, color = p.text)
        Text(
            SimpleDateFormat("EEEE d MMMM yyyy · HH:mm", com.philip.fieldtracker.i18n.I18n.locale).format(Date(s.startMs))
                .replaceFirstChar { it.uppercase() },
            style = Type.body, color = p.muted
        )
        Label(
            trf("Terrain %s%.0f × %.0f m", if (s.calibration.estimated) tr("estimé ") else "", s.calibration.lengthM, s.calibration.widthM),
            color = p.muted, style = Type.caption
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(tr("Historique"), icon = Icons.Filled.History, onClick = onHistory)
            GhostButton(tr("Exporter"), icon = Icons.Filled.Share) {
                try {
                    Exporter.share(ctx, listOf(Exporter.csv(ctx, s, st), Exporter.gpx(ctx, s)))
                } catch (e: Exception) {
                    Toast.makeText(ctx, trf("Export impossible : %s", e.message), Toast.LENGTH_LONG).show()
                }
            }
            if (hcAvailable) GhostButton(if (busy) "…" else tr("Samsung Health"), icon = Icons.Filled.FavoriteBorder, tint = p.primary) {
                if (busy) return@GhostButton
                scope.launch {
                    if (HealthLink.hasAllPermissions(ctx)) sendToHealth() else hcLauncher.launch(HealthLink.permissions)
                }
            }
            GhostButton(tr("Supprimer"), icon = Icons.Filled.Delete, tint = p.secondary) { confirmDelete = true }
        }
        Spacer(Modifier.height(16.dp))

        // Heatmap
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Carte de chaleur"), style = Type.h2, color = p.text, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(tr("Plein"), heatMode == 0) { heatMode = 0 }
                Chip(tr("Haute int."), heatMode == 1) { heatMode = 1 }
                Chip(tr("Sprints"), heatMode == 2) { heatMode = 2 }
                Chip(tr("Trace"), heatMode == 3) { heatMode = 3 }
            }
            Spacer(Modifier.height(10.dp))
            val heatImg = remember(s.id, st) { thermalImage(st.heat) }
            val heatHiImg = remember(s.id, st) { thermalImage(st.heatHi) }
            FieldCanvas(sport) { r ->
                val cw = r.width / SessionStats.HX
                val ch = r.height / SessionStats.HY
                when (heatMode) {
                    0, 1 -> {
                        val img = if (heatMode == 0) heatImg else heatHiImg
                        drawImage(
                            img,
                            dstOffset = IntOffset(r.left.toInt(), r.top.toInt()),
                            dstSize = IntSize(r.width.toInt(), r.height.toInt()),
                            filterQuality = FilterQuality.High,
                        )
                    }
                    2 -> st.sprints.forEach { sp ->
                        val o = Offset(r.left + sp.u.toFloat().coerceIn(0f, 1f) * r.width, r.top + sp.v.toFloat().coerceIn(0f, 1f) * r.height)
                        drawCircle(p.secondary.copy(alpha = 0.3f), 22f, o)
                        drawCircle(p.secondary, 9f, o)
                    }
                    else -> {
                        val path = Path()
                        var first = true
                        var prevT = 0L
                        s.points.forEach { pt ->
                            val o = Offset(r.left + pt.u.toFloat().coerceIn(-0.05f, 1.05f) * r.width, r.top + pt.v.toFloat().coerceIn(-0.05f, 1.05f) * r.height)
                            if (first || pt.t - prevT > 5000) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                            first = false; prevT = pt.t
                        }
                        drawPath(path, p.primary.copy(alpha = 0.7f), style = Stroke(2.5f))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(tr("Faible"), style = Type.caption)
                Spacer(Modifier.width(8.dp))
                Canvas(Modifier.weight(1f).height(6.dp)) {
                    val n = 30
                    for (i in 0 until n) {
                        drawRect(
                            thermal(i / (n - 1f)),
                            topLeft = Offset(size.width * i / n, 0f), size = Size(size.width / n + 1, size.height)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Label(tr("Point chaud"), style = Type.caption)
            }
        }
        Spacer(Modifier.height(16.dp))

        // Présence
        SectionTitle(tr("Présence sur le terrain"), trf("%.0f %% dans les limites", st.presence * 100))
        val lenZones = st.zones.map { it.sum() }
        val widZones = (0 until 3).map { j -> st.zones.sumOf { it[j] } }
        val colors = listOf(p.primary, p.tertiary, p.secondary)
        Panel {
            Row(Modifier.fillMaxWidth().height(10.dp).clip(Shape8)) {
                lenZones.forEachIndexed { i, f ->
                    if (f > 0.001) Box(Modifier.weight(f.toFloat()).height(10.dp).background(colors[i]))
                }
                if (lenZones.sum() < 0.001) Box(Modifier.weight(1f).height(10.dp).background(p.elevated))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sport.zoneLabelsLength().forEachIndexed { i, label ->
                    Column(
                        Modifier.weight(1f).clip(Shape8).background(p.elevated).padding(10.dp)
                    ) {
                        Label(label, color = colors[i], style = Type.caption)
                        Text("${(lenZones[i] * 100).toInt()}%", style = Type.metric, color = p.text)
                        Text(formatDuration((lenZones[i] * st.activeMs * st.presence).toLong()), style = Type.caption, color = p.muted)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Label(tr("Couloirs"), style = Type.caption)
            Spacer(Modifier.height(4.dp))
            sport.zoneLabelsWidth().forEachIndexed { j, label ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(label, style = Type.body, color = p.text, modifier = Modifier.width(70.dp))
                    Bar(widZones[j].toFloat(), p.primary, Modifier.weight(1f))
                    Text("${(widZones[j] * 100).toInt()}%", style = Type.label, color = p.muted, modifier = Modifier.width(48.dp).padding(start = 8.dp))
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // Métriques clés
        SectionTitle(tr("Métriques clés & intensité"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard(
                tr("Vitesse max"), String.format("%.1f", st.maxSpeedKmh), "km/h", Modifier.weight(1f),
                icon = Icons.Filled.Speed, color = p.primary,
                footer = trf("Moyenne en mouvement %.1f km/h", st.avgSpeedKmh)
            )
            MetricCard(
                tr("Distance"), formatKm(st.distanceM), unitKm(st.distanceM), Modifier.weight(1f),
                icon = Icons.Filled.Straighten, color = p.text,
                footer = trf("Dans le terrain : %s %s", formatKm(st.distanceInFieldM), unitKm(st.distanceInFieldM))
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard(
                tr("Sprints"), "${st.sprints.size}", "", Modifier.weight(1f),
                icon = Icons.Filled.Bolt, color = p.secondary,
                footer = String.format("> %.0f km/h · %.0f m", sport.sprintKmh, st.sprintDistanceM)
            )
            MetricCard(
                tr("Haute intensité"), formatKm(st.hiDistanceM), unitKm(st.hiDistanceM), Modifier.weight(1f),
                icon = Icons.Filled.Timer, color = p.warning,
                footer = trf("> %.0f km/h · %d accélérations", sport.hiKmh, st.accelerations)
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard(
                tr("Fréquence cardiaque"), if (st.hrAvg > 0) "${st.hrAvg}" else "—", tr("bpm moy."), Modifier.weight(1f),
                icon = Icons.Filled.Favorite, color = if (st.hrAvg > 0) p.secondary else p.muted,
                footer = if (st.hrMax > 0) "Pic : ${st.hrMax} bpm" else tr("Pas de FC enregistrée")
            )
            MetricCard(
                tr("Calories"), "${st.calories}", "kcal", Modifier.weight(1f),
                icon = Icons.Filled.LocalFireDepartment, color = p.text,
                footer = if (st.hrAvg > 0) tr("Estimées avec la FC") else tr("Estimées avec la vitesse")
            )
        }
        if (st.hrAvg == 0 && hcAvailable) {
            Spacer(Modifier.height(10.dp))
            GhostButton(
                if (busy) tr("Lecture…") else tr("Récupérer la FC depuis Samsung Health"),
                Modifier.fillMaxWidth(), Icons.Filled.FavoriteBorder, tint = p.secondary
            ) { if (!busy) importHr() }
        }
        Spacer(Modifier.height(16.dp))

        // Zones de vitesse
        Panel {
            Text(tr("Zones de vitesse"), style = Type.h2, color = p.text)
            Spacer(Modifier.height(8.dp))
            val maxT = st.bands.maxOf { it.timeMs }.coerceAtLeast(1)
            val bandColors = listOf(p.muted, p.tertiary, p.primary, p.warning, p.secondary)
            st.bands.forEachIndexed { i, b ->
                Column(Modifier.padding(vertical = 5.dp)) {
                    Row {
                        Text(tr(b.label), style = Type.body, color = p.text, modifier = Modifier.weight(1f))
                        Text("${formatDuration(b.timeMs)} · ${formatKm(b.distM)} ${unitKm(b.distM)}", style = Type.caption, color = p.muted)
                    }
                    Spacer(Modifier.height(4.dp))
                    Bar(b.timeMs.toFloat() / maxT, bandColors[i])
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // Courbe d'intensité
        if (st.perMinute.size >= 2) {
            Panel {
                Text(tr("Courbe d'intensité"), style = Type.h2, color = p.text)
                Label(tr("Distance parcourue par minute (m/min)"), style = Type.caption)
                Spacer(Modifier.height(10.dp))
                IntensityChart(st.perMinute)
            }
            Spacer(Modifier.height(16.dp))
        }

        // Zones cardio
        if (st.hrAvg > 0) {
            Panel {
                Text(tr("Zones cardio"), style = Type.h2, color = p.text)
                Label(trf("FC max de référence : %d bpm", AppState.profile.value.hrMax), style = Type.caption)
                Spacer(Modifier.height(8.dp))
                val names = listOf(tr("Z1 Récup."), tr("Z2 Endurance"), tr("Z3 Tempo"), tr("Z4 Seuil"), tr("Z5 Max"))
                val zc = listOf(p.muted, p.tertiary, p.primary, p.warning, p.secondary)
                val tot = st.hrZonesMs.sum().coerceAtLeast(1)
                names.forEachIndexed { i, n ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Text(n, style = Type.body, color = p.text, modifier = Modifier.width(110.dp))
                        Bar(st.hrZonesMs[i].toFloat() / tot, zc[i], Modifier.weight(1f))
                        Text(formatDuration(st.hrZonesMs[i]), style = Type.caption, color = p.muted, modifier = Modifier.width(56.dp).padding(start = 8.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Temps forts & sprints
        if (s.highlights.isNotEmpty() || st.sprints.isNotEmpty()) {
            Panel {
                Text(tr("Temps forts"), style = Type.h2, color = p.text)
                Spacer(Modifier.height(6.dp))
                val fmt = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
                val items = s.highlights.map { it.t to tr(it.label) } +
                    st.sprints.sortedByDescending { it.maxKmh }.take(5).map { it.t to trf("Sprint à %.1f km/h (%.0f m)", it.maxKmh, it.distM) }
                items.sortedBy { it.first }.forEach { (t, label) ->
                    KeyValue(label, fmt.format(Date(t)))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = p.elevated,
            title = { Text(tr("Supprimer cette séance ?"), style = Type.h2, color = p.text) },
            text = { Text(tr("Cette action est définitive."), style = Type.body, color = p.muted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    SessionStore.delete(ctx, s.id)
                    AppState.selectSession(null)
                    AppState.historyChanged()
                }) { Text(tr("SUPPRIMER"), style = Type.label, color = p.secondary) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("ANNULER"), style = Type.label, color = p.text) } },
        )
    }
}

/** Dégradé type caméra thermique : vert -> jaune -> orange -> rouge. */
private val thermalStops = listOf(
    0.00f to Color(0xFF16A34A),
    0.35f to Color(0xFF84CC16),
    0.55f to Color(0xFFFACC15),
    0.75f to Color(0xFFF97316),
    1.00f to Color(0xFFDC2626),
)

private fun thermal(v: Float): Color {
    val x = v.coerceIn(0f, 1f)
    for (i in 1 until thermalStops.size) {
        val (p1, c1) = thermalStops[i - 1]
        val (p2, c2) = thermalStops[i]
        if (x <= p2) return lerp(c1, c2, (x - p1) / (p2 - p1))
    }
    return thermalStops.last().second
}

/**
 * Carte de chaleur lisse : la grille de présence est agrandie par interpolation
 * bilinéaire puis colorée du vert (peu de temps) au rouge (zone la plus occupée).
 */
private fun thermalImage(grid: FloatArray): ImageBitmap {
    val gw = SessionStats.HX
    val gh = SessionStats.HY
    val scale = 6
    val w = gw * scale
    val h = gh * scale
    val pixels = IntArray(w * h)
    for (py in 0 until h) {
        val gy = ((py + 0.5f) / scale - 0.5f).coerceIn(0f, gh - 1f)
        val y0 = gy.toInt().coerceAtMost(gh - 2).coerceAtLeast(0)
        val fy = (gy - y0).coerceIn(0f, 1f)
        for (px in 0 until w) {
            val gx = ((px + 0.5f) / scale - 0.5f).coerceIn(0f, gw - 1f)
            val x0 = gx.toInt().coerceAtMost(gw - 2).coerceAtLeast(0)
            val fx = (gx - x0).coerceIn(0f, 1f)
            val a = grid[y0 * gw + x0]
            val b = grid[y0 * gw + x0 + 1]
            val c = grid[(y0 + 1) * gw + x0]
            val d = grid[(y0 + 1) * gw + x0 + 1]
            val v = (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
            pixels[py * w + px] = if (v < 0.02f) 0 else {
                val col = thermal(v)
                val alpha = (0.35f + 0.45f * v).coerceAtMost(0.8f)
                col.copy(alpha = alpha).toArgb()
            }
        }
    }
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    return bmp.asImageBitmap()
}

@Composable
private fun IntensityChart(values: List<Double>) {
    val p = LocalPalette.current
    val max = (values.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val w = size.width
        val h = size.height
        for (k in 1..3) {
            val y = h * k / 4f
            drawLine(p.border, Offset(0f, y), Offset(w, y), 1f)
        }
        val n = values.size
        fun pt(i: Int) = Offset(w * i / (n - 1).coerceAtLeast(1), h - (values[i] / max * h * 0.92).toFloat())
        val line = Path().apply { for (i in 0 until n) { val o = pt(i); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
        val fill = Path().apply {
            addPath(line)
            lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(fill, p.primary.copy(alpha = 0.12f))
        drawPath(line, p.primary, style = Stroke(3f))
        val iMax = values.indices.maxByOrNull { values[it] } ?: 0
        drawCircle(p.secondary, 6f, pt(iMax))
    }
    Row(Modifier.fillMaxWidth()) {
        Text("0'", style = Type.caption, color = p.muted, modifier = Modifier.weight(1f))
        Text("${values.size / 2}'", style = Type.caption, color = p.muted, modifier = Modifier.weight(1f))
        Text("${values.size}'", style = Type.caption, color = p.muted)
    }
}

@Composable
private fun HistoryList(onBack: () -> Unit, onSelect: (String) -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val version by AppState.historyVersion.collectAsState()
    var items by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    LaunchedEffect(version) { items = withContext(Dispatchers.IO) { SessionStore.list(ctx) } }
    val fmt = SimpleDateFormat("d MMM yyyy · HH:mm", com.philip.fieldtracker.i18n.I18n.locale)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconSquare(Icons.AutoMirrored.Filled.ArrowBack, p.text, onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text(tr("Historique"), style = Type.h1, color = p.text)
        }
        Spacer(Modifier.height(12.dp))
        if (items.isEmpty()) Text(tr("Aucune séance enregistrée."), style = Type.body, color = p.muted)
        items.forEach { it ->
            val sport = Sports.byId(it.sportId)
            Panel(modifier = Modifier.clickable { onSelect(it.id) }, padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sport?.name?.let { tr(it) } ?: it.sportId, style = Type.bodyBold, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Label(fmt.format(Date(it.startMs)) + if (it.source.startsWith("import")) tr(" · import") else "", style = Type.caption)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${formatKm(it.distanceM)} ${unitKm(it.distanceM)}", style = Type.label, color = p.primary)
                        Text(formatDuration(it.activeMs) + String.format(" · %.1f km/h", it.maxSpeedKmh), style = Type.caption, color = p.muted)
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.Delete, null, tint = p.muted, modifier = Modifier.size(22.dp).clickable {
                        SessionStore.delete(ctx, it.id)
                        if (AppState.selectedSessionId.value == it.id) AppState.selectSession(null)
                        AppState.historyChanged()
                    })
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
