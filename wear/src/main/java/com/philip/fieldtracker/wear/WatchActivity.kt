package com.philip.fieldtracker.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

private val Bg = Color(0xFF000000)
private val Surface = Color(0xFF161B22)
private val Border = Color(0xFF30363D)
private val Lime = Color(0xFF00FF66)
private val Orange = Color(0xFFFF5722)
private val Amber = Color(0xFFF5B83D)
private val TextHi = Color(0xFFF0F6FC)
private val Muted = Color(0xFF8B949E)

private val Chivo = FontFamily(Font(R.font.chivo_900, FontWeight.Black))
private val Mono = FontFamily(Font(R.font.jetbrains_mono_bold, FontWeight.Bold))

@Composable
private fun T(text: String, size: TextUnit, color: Color = TextHi, mono: Boolean = false, modifier: Modifier = Modifier) {
    BasicText(
        text,
        modifier = modifier,
        style = TextStyle(
            color = color, fontSize = size, fontFamily = if (mono) Mono else Chivo,
            fontWeight = if (mono) FontWeight.Bold else FontWeight.Black, textAlign = TextAlign.Center,
        ),
        maxLines = 2,
    )
}

class WatchActivity : ComponentActivity(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var hrSensor: Sensor? = null
    private var listening = false
    private var lastSent = 0L
    private val hr = mutableIntStateOf(0)

    private val permGranted = mutableIntStateOf(0)

    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permGranted.intValue++
        updateSensor(WatchState.state.value.phase)
    }

    fun askPermissions() {
        val l = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.BODY_SENSORS)
        if (android.os.Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        permission.launch(l.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        hrSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        WatchRecorder.init(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED
        ) askPermissions()
        WatchState.send(this, "/ft/hello")
        WatchState.loadConfig(this)

        setContent {
            val st by WatchState.state.collectAsState()
            val summary by WatchState.summary.collectAsState()
            val connected by WatchState.connected.collectAsState()
            val cfg by WatchRecorder.config.collectAsState()
            val rec by WatchRecorder.state.collectAsState()
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) { while (true) { delay(2000); now = System.currentTimeMillis() } }
            val recording = rec.phase == RecPhase.LIVE || rec.phase == RecPhase.PAUSED
            // Télécommande seulement si le téléphone est choisi comme source ET joignable
            val remote = !recording && cfg.source == "phone" && now - st.receivedAt < 10_000
            LaunchedEffect(st.phase, remote, rec.phase) {
                updateSensor(if (remote) st.phase else "none")
                val keep = if (remote) st.phase == "live" || st.phase == "paused" || st.phase == "calib"
                else rec.phase == RecPhase.CALIB || recording
                if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            if (remote) {
                WatchApp(st, summary, connected, hr.intValue) { path, payload -> WatchState.send(this, path, payload) }
            } else {
                StandaloneApp(rec, cfg, permGranted.intValue, ::askPermissions)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WatchState.send(this, "/ft/hello")
        WatchState.loadConfig(this)
    }

    private fun updateSensor(phase: String) {
        val want = (phase == "live" || phase == "paused") &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED
        val sm = sensorManager ?: return
        val s = hrSensor ?: return
        if (want && !listening) {
            listening = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
        } else if (!want && listening) {
            sm.unregisterListener(this)
            listening = false
            hr.intValue = 0
        }
    }

    /** Dernières mesures fiables, pour envoyer une valeur médiane (évite les valeurs aberrantes). */
    private val recent = ArrayDeque<Int>()

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_HEART_RATE) return
        // Capteur pas au contact de la peau ou mesure peu fiable : on ignore
        if (event.accuracy < SensorManager.SENSOR_STATUS_ACCURACY_LOW) return
        val raw = event.values.firstOrNull()?.toInt() ?: return
        if (raw !in 35..220) return
        recent.addLast(raw)
        while (recent.size > 5) recent.removeFirst()
        if (recent.size < 3) return
        val bpm = recent.sorted()[recent.size / 2]
        hr.intValue = bpm
        val now = System.currentTimeMillis()
        if (now - lastSent >= 2000) {
            lastSent = now
            WatchState.send(this, "/ft/hr", bpm.toString())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        super.onDestroy()
    }
}

@Composable
private fun WatchApp(st: PhoneState, summary: Summary?, connected: Boolean, hr: Int, send: (String, String) -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Bg).padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            summary != null && st.phase != "live" && st.phase != "paused" ->
                Box(Modifier.clickable { WatchState.clearSummary() }) { SummaryView(summary, st.lang) }
            !connected && st.receivedAt == 0L -> Message("FieldTracker", WatchText.t("Ouvre l'app sur le téléphone", st.lang))
            st.phase == "none" -> Message("FieldTracker", WatchText.t("Choisis ton sport sur le téléphone", st.lang))
            st.phase == "calib" -> CalibView(st, send)
            st.phase == "ready" -> ReadyView(st, send)
            else -> LiveView(st, hr, send)
        }
    }
}

@Composable
private fun Message(title: String, text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        T(title, 16.sp, Lime)
        Spacer(Modifier.height(8.dp))
        T(text, 13.sp, Muted)
    }
}

@Composable
private fun RoundButton(label: String, color: Color, textColor: Color, size: Int = 64, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { T(label, if (label.length > 3) 12.sp else 18.sp, textColor) }
}

@Composable
private fun CalibView(st: PhoneState, send: (String, String) -> Unit) {
    val anim = rememberInfiniteTransition(label = "c")
    val pulse by anim.animateFloat(0.2f, 1f, infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse), label = "p")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        T(st.sport, 11.sp, Muted)
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.width(110.dp).height(64.dp)) {
            val pad = 8f
            val l = pad; val t = pad; val r = size.width - pad; val b = size.height - pad
            drawRect(Border, Offset(l, t), androidx.compose.ui.geometry.Size(r - l, b - t), style = Stroke(2f))
            drawLine(Border, Offset((l + r) / 2, t), Offset((l + r) / 2, b), 2f)
            val pts = listOf(Offset(l, t), Offset(r, t), Offset(r, b), Offset(l, b))
            pts.forEachIndexed { i, o ->
                when {
                    i < st.corner -> drawCircle(Lime, 7f, o)
                    i == st.corner -> drawCircle(Lime.copy(alpha = pulse), 9f, o, style = Stroke(3f))
                    else -> drawCircle(Muted, 5f, o, style = Stroke(2f))
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        T(String.format(WatchText.t("COIN %d/4", st.lang), (st.corner + 1).coerceAtMost(4)), 14.sp, TextHi, mono = true)
        val accText = if (st.accuracy < 0) "GPS…" else "±${st.accuracy.toInt()} m"
        T(accText, 11.sp, if (st.accuracy in 0.0..20.0) Lime else Amber, mono = true)
        Spacer(Modifier.height(6.dp))
        if (st.capturing) {
            T(WatchText.t("Mesure… reste immobile", st.lang), 12.sp, Lime)
        } else {
            RoundButton("OK", Lime, Bg) { send("/ft/corner", "") }
        }
        if (st.message.isNotEmpty() && !st.capturing) {
            Spacer(Modifier.height(4.dp))
            T(WatchText.t(st.message, st.lang), 10.sp, Amber)
        }
    }
}

@Composable
private fun ReadyView(st: PhoneState, send: (String, String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        T(st.sport, 12.sp, Muted)
        T(WatchText.t("Terrain balisé", st.lang), 15.sp, Lime)
        Spacer(Modifier.height(10.dp))
        RoundButton(WatchText.t("START", st.lang), Lime, Bg, size = 80) { send("/ft/cmd", "start") }
    }
}

@Composable
private fun LiveView(st: PhoneState, hr: Int, send: (String, String) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(500); now = System.currentTimeMillis() } }
    val paused = st.phase == "paused"
    val elapsed = if (paused) st.elapsedMs else st.elapsedMs + (now - st.receivedAt).coerceIn(0, 5000)
    LivePanel(
        st.lang, st.inField, elapsed, paused, hr, st.speedKmh, st.distanceM,
        st.us, st.them,
        onGoal = { team -> send("/ft/cmd", if (team == 0) "goalA" else "goalB") },
        onUndo = { team -> send("/ft/cmd", if (team == 0) "undoA" else "undoB") },
        onMark = { send("/ft/cmd", "mark") },
        onPause = { send("/ft/cmd", if (paused) "resume" else "pause") },
        onStop = { send("/ft/cmd", "stop") },
    )
}

@Composable
private fun LivePanel(
    lang: String, inField: Boolean, elapsed: Long, paused: Boolean, hr: Int, speedKmh: Double, distanceM: Double,
    scoreUs: Int, scoreThem: Int, onGoal: (Int) -> Unit, onUndo: (Int) -> Unit,
    onMark: () -> Unit, onPause: () -> Unit, onStop: () -> Unit,
) {
    var holding by remember { mutableStateOf(false) }
    val stopNow by androidx.compose.runtime.rememberUpdatedState(onStop)

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        // Score : un bouton par équipe (appui = +1, appui long = annuler)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScoreButton(Lime, Bg, { onGoal(0) }, { onUndo(0) })
            T("$scoreUs – $scoreThem", 18.sp, TextHi, mono = true)
            ScoreButton(Orange, TextHi, { onGoal(1) }, { onUndo(1) })
        }
        T(
            if (inField) WatchText.t("DANS LE TERRAIN", lang) else WatchText.t("HORS TERRAIN", lang),
            8.sp, if (inField) Lime else Orange, mono = true,
        )
        T(fmt(elapsed), 26.sp, if (paused) Amber else if (inField) Lime else Orange, mono = true)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                T(if (hr > 0) "$hr" else "—", 20.sp, Orange, mono = true)
                T("BPM", 9.sp, Muted, mono = true)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                T(String.format("%.1f", speedKmh), 20.sp, TextHi, mono = true)
                T("KM/H", 9.sp, Muted, mono = true)
            }
        }
        T(
            if (distanceM < 1000) String.format("%.0f m", distanceM) else String.format("%.2f km", distanceM / 1000),
            10.sp, Muted, mono = true
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton("⚑", Surface, TextHi, size = 40) { onMark() }
            RoundButton(if (paused) "▶" else "II", if (paused) Lime else Orange, if (paused) Bg else TextHi, size = 48) { onPause() }
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (holding) Orange else Surface)
                    .border(1.dp, Orange, CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { holding = true; tryAwaitRelease(); holding = false },
                            onLongPress = { stopNow() },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) { T("■", 14.sp, TextHi) }
        }
        T(WatchText.t("maintenir ■ pour terminer", lang), 8.sp, Muted, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
    }
}

@Composable
private fun ScoreButton(bg: Color, fg: Color, onTap: () -> Unit, onLong: () -> Unit) {
    val tap by androidx.compose.runtime.rememberUpdatedState(onTap)
    val long by androidx.compose.runtime.rememberUpdatedState(onLong)
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(bg)
            .pointerInput(Unit) { detectTapGestures(onTap = { tap() }, onLongPress = { long() }) },
        contentAlignment = Alignment.Center,
    ) { T("+1", 12.sp, fg, mono = true) }
}

@Composable
private fun SummaryView(s: Summary, lang: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        T(WatchText.t("Séance terminée", lang), 13.sp, Lime)
        Spacer(Modifier.height(4.dp))
        T(fmt(s.activeMs), 22.sp, TextHi, mono = true)
        T(if (s.distM < 1000) String.format("%.0f m", s.distM) else String.format("%.2f km", s.distM / 1000), 16.sp, TextHi, mono = true)
        T(String.format("Vmax %.1f km/h", s.vmax), 12.sp, Muted, mono = true)
        T(String.format(WatchText.t("%d sprints", lang), s.sprints), 12.sp, Orange, mono = true)
        Spacer(Modifier.height(4.dp))
        T(WatchText.t("Bilan complet sur le téléphone", lang), 9.sp, Muted)
    }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
}

// ======================================================================
// Mode autonome : la montre balise et enregistre avec son propre GPS
// ======================================================================

@Composable
private fun StandaloneApp(rec: RecState, cfg: WatchConfig, permTick: Int, askPerms: () -> Unit) {
    val fix by WatchRecorder.fix.collectAsState()
    val needsGps = rec.phase == RecPhase.CALIB || rec.phase == RecPhase.READY
    DisposableEffect(needsGps, permTick) {
        if (needsGps) WatchRecorder.gpsStart("ui")
        onDispose { WatchRecorder.gpsStop("ui") }
    }
    val hasPerm = permTick >= 0 && WatchRecorder.hasLocation()
    Box(
        Modifier.fillMaxSize().background(Bg).padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            !WatchRecorder.hasGpsHardware() -> Message("FieldTracker", WatchRecorder.t("Cette montre n'a pas de GPS"))
            cfg.sportId.isEmpty() -> Message("FieldTracker", WatchRecorder.t("Choisis le sport sur le téléphone (une fois)"))
            !hasPerm -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                T("FieldTracker", 15.sp, Lime)
                Spacer(Modifier.height(8.dp))
                RoundButton(WatchRecorder.t("Autoriser GPS + cardio"), Lime, Bg, size = 96) { askPerms() }
            }
            rec.phase == RecPhase.CALIB -> SoloCalib(rec, cfg, fix)
            rec.phase == RecPhase.READY -> SoloReady(rec, cfg, fix)
            rec.phase == RecPhase.DONE -> SoloDone(rec)
            else -> {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) { while (true) { delay(500); now = System.currentTimeMillis() } }
                LivePanel(
                    cfg.lang, rec.inField, if (now > 0) WatchRecorder.elapsed(now) else 0L, rec.phase == RecPhase.PAUSED,
                    rec.hr, rec.speedKmh, rec.distanceM,
                    rec.scoreUs, rec.scoreThem,
                    onGoal = { WatchRecorder.goal(it) },
                    onUndo = { WatchRecorder.undoGoal(it) },
                    onMark = { WatchRecorder.mark() },
                    onPause = { if (rec.phase == RecPhase.PAUSED) WatchRecorder.resume() else WatchRecorder.pause() },
                    onStop = { WatchRecorder.stop() },
                )
            }
        }
    }
}

@Composable
private fun GpsLine(fix: GpsFix?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val fresh = fix != null && now - fix.t < 4000
    val acc = fix?.acc ?: 99f
    T(
        if (fresh) WatchRecorder.t("GPS montre") + " ±${acc.toInt()} m" else WatchRecorder.t("Recherche GPS…"),
        10.sp, if (fresh && acc <= 10f) Lime else Amber, mono = true,
    )
}

@Composable
private fun MiniField(corner: Int, pulse: Float) {
    Canvas(Modifier.width(104.dp).height(58.dp)) {
        val pad = 8f
        val l = pad; val t = pad; val r = size.width - pad; val b = size.height - pad
        drawRect(Border, Offset(l, t), androidx.compose.ui.geometry.Size(r - l, b - t), style = Stroke(2f))
        drawLine(Border, Offset((l + r) / 2, t), Offset((l + r) / 2, b), 2f)
        val pts = listOf(Offset(l, t), Offset(r, t), Offset(r, b), Offset(l, b))
        for (i in 0 until 4) if (i < corner && (i + 1) % 4 < corner) drawLine(Lime, pts[i], pts[(i + 1) % 4], 4f)
        pts.forEachIndexed { i, o ->
            when {
                i < corner -> drawCircle(Lime, 7f, o)
                i == corner -> drawCircle(Lime.copy(alpha = pulse), 9f, o, style = Stroke(3f))
                else -> drawCircle(Muted, 5f, o, style = Stroke(2f))
            }
        }
    }
}

@Composable
private fun SmallButton(label: String, color: Color = Surface, text: Color = TextHi, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(color).border(1.dp, Border, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) { T(label, 10.sp, text) }
}

@Composable
private fun SoloCalib(rec: RecState, cfg: WatchConfig, fix: GpsFix?) {
    val anim = rememberInfiniteTransition(label = "c")
    val pulse by anim.animateFloat(0.2f, 1f, infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse), label = "p")
    val saved = remember(cfg.sportId, rec.corner) { WatchRecorder.savedCalibration() }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        T(cfg.sportName, 11.sp, Muted)
        MiniField(rec.corner, pulse)
        T(String.format(WatchRecorder.t("COIN %d/4"), (rec.corner + 1).coerceAtMost(4)), 13.sp, TextHi, mono = true)
        GpsLine(fix)
        Spacer(Modifier.height(4.dp))
        val prog = rec.capturing
        if (prog != null) {
            T(WatchRecorder.t("Mesure… reste immobile") + " ${(prog * 100).toInt()}%", 11.sp, Lime)
        } else {
            RoundButton("OK", Lime, Bg, size = 56) { WatchRecorder.captureCorner() }
        }
        if (rec.message.isNotEmpty() && prog == null) T(rec.message, 9.sp, Amber)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (rec.corner > 0) SmallButton(WatchRecorder.t("Annuler")) { WatchRecorder.undoCorner() }
            else if (saved != null) SmallButton(WatchRecorder.t("Réutiliser"), text = Lime) { WatchRecorder.reuseCalibration() }
        }
    }
}

@Composable
private fun SoloReady(rec: RecState, cfg: WatchConfig, fix: GpsFix?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        T(cfg.sportName, 11.sp, Muted)
        T(WatchRecorder.t("Terrain balisé"), 14.sp, Lime)
        T(String.format("%.0f × %.0f m", rec.fieldL, rec.fieldW), 11.sp, TextHi, mono = true)
        if (cfg.positionName.isNotEmpty()) T(cfg.positionName, 10.sp, Muted)
        GpsLine(fix)
        Spacer(Modifier.height(6.dp))
        RoundButton(WatchRecorder.t("START"), Lime, Bg, size = 72) { WatchRecorder.start() }
        Spacer(Modifier.height(6.dp))
        SmallButton(WatchRecorder.t("Refaire")) { WatchRecorder.resetCalibration() }
    }
}

@Composable
private fun SoloDone(rec: RecState) {
    // Retour automatique à l'écran de départ après 30 s
    LaunchedEffect(Unit) { delay(30_000); WatchRecorder.newSession() }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        T(WatchRecorder.t("Séance terminée"), 13.sp, Lime)
        Spacer(Modifier.height(4.dp))
        T(rec.lastSummary, 11.sp, TextHi, mono = true)
        T(String.format(WatchRecorder.t("%d sprints"), rec.sprints), 11.sp, Orange, mono = true)
        Spacer(Modifier.height(4.dp))
        T(
            when (rec.sync) {
                "sent" -> WatchRecorder.t("Envoyé au téléphone ✓")
                "pending" -> WatchRecorder.t("En attente du téléphone")
                else -> ""
            }, 10.sp, if (rec.sync == "sent") Lime else Amber,
        )
        Spacer(Modifier.height(6.dp))
        RoundButton(WatchRecorder.t("Nouvelle séance"), Lime, Bg, size = 72) { WatchRecorder.newSession() }
    }
}
