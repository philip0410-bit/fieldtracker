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

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) updateSensor(WatchState.state.value.phase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        hrSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.BODY_SENSORS)
        }
        WatchState.send(this, "/ft/hello")

        setContent {
            val st by WatchState.state.collectAsState()
            val summary by WatchState.summary.collectAsState()
            val connected by WatchState.connected.collectAsState()
            LaunchedEffect(st.phase) {
                updateSensor(st.phase)
                val live = st.phase == "live" || st.phase == "paused" || st.phase == "calib"
                if (live) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            WatchApp(st, summary, connected, hr.intValue) { path, payload -> WatchState.send(this, path, payload) }
        }
    }

    override fun onResume() {
        super.onResume()
        WatchState.send(this, "/ft/hello")
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
            summary != null && st.phase != "live" && st.phase != "paused" -> SummaryView(summary, st.lang)
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
            T(WatchText.t("GPS faible, réessaie", st.lang), 10.sp, Amber)
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
    var holding by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (st.inField) Lime.copy(alpha = 0.15f) else Orange.copy(alpha = 0.3f))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) { T(if (st.inField) WatchText.t("DANS LE TERRAIN", st.lang) else WatchText.t("HORS TERRAIN", st.lang), 9.sp, if (st.inField) Lime else Orange, mono = true) }
        Spacer(Modifier.height(2.dp))
        T(fmt(elapsed), 30.sp, if (paused) Amber else Lime, mono = true)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                T(if (hr > 0) "$hr" else "—", 20.sp, Orange, mono = true)
                T("BPM", 9.sp, Muted, mono = true)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                T(String.format("%.1f", st.speedKmh), 20.sp, TextHi, mono = true)
                T("KM/H", 9.sp, Muted, mono = true)
            }
        }
        T(
            if (st.distanceM < 1000) String.format("%.0f m", st.distanceM) else String.format("%.2f km", st.distanceM / 1000),
            12.sp, Muted, mono = true
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton("⚑", Surface, TextHi, size = 40) { send("/ft/cmd", "mark") }
            RoundButton(if (paused) "▶" else "II", if (paused) Lime else Orange, if (paused) Bg else TextHi, size = 48) {
                send("/ft/cmd", if (paused) "resume" else "pause")
            }
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (holding) Orange else Surface)
                    .border(1.dp, Orange, CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { holding = true; tryAwaitRelease(); holding = false },
                            onLongPress = { send("/ft/cmd", "stop") },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) { T("■", 14.sp, TextHi) }
        }
        T(WatchText.t("maintenir ■ pour terminer", st.lang), 8.sp, Muted, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
    }
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
