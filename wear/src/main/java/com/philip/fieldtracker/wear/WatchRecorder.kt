package com.philip.fieldtracker.wear

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.math.max
import kotlin.math.min

/** Sport et réglages envoyés par le téléphone (gardés sur la montre). */
data class WatchConfig(
    val sportId: String = "",
    val sportName: String = "",
    val lengthM: Double = 105.0,
    val widthM: Double = 68.0,
    val marginLM: Double = 0.0,
    val marginWM: Double = 0.0,
    val sprintKmh: Double = 25.2,
    val lang: String = "fr",
    /** "watch" : la montre enregistre seule ; "phone" : télécommande du téléphone. */
    val source: String = "watch",
    val positionId: String = "",
    val positionName: String = "",
    val usLabel: String = "Nous",
    val themLabel: String = "Eux",
)

enum class RecPhase { CALIB, READY, LIVE, PAUSED, DONE }

data class RecState(
    val phase: RecPhase = RecPhase.CALIB,
    val corner: Int = 0,
    val capturing: Float? = null,
    val message: String = "",
    val fieldL: Double = 0.0,
    val fieldW: Double = 0.0,
    val distanceM: Double = 0.0,
    val speedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val sprints: Int = 0,
    val inField: Boolean = true,
    val hr: Int = 0,
    val u: Double = 0.5,
    val v: Double = 0.5,
    /** Envoi de la dernière séance : "", "pending", "sent". */
    val sync: String = "",
    val lastSummary: String = "",
    val scoreUs: Int = 0,
    val scoreThem: Int = 0,
)

data class GpsFix(val t: Long, val lat: Double, val lon: Double, val acc: Float, val speed: Float)

/**
 * Enregistrement autonome sur la montre : GPS de la montre + capteur cardio.
 * Fonctionne sans téléphone ; la séance est envoyée au téléphone dès qu'il est joignable.
 */
@SuppressLint("MissingPermission")
object WatchRecorder : SensorEventListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences

    private val _config = MutableStateFlow(WatchConfig())
    val config: StateFlow<WatchConfig> = _config

    private val _state = MutableStateFlow(RecState())
    val state: StateFlow<RecState> = _state

    private val _fix = MutableStateFlow<GpsFix?>(null)
    val fix: StateFlow<GpsFix?> = _fix

    // ---- balisage
    private val corners = arrayOfNulls<LL>(4)
    private var field: WatchGeo.Field? = null
    private var captureJob: Job? = null

    // ---- enregistrement
    private var sessionId = ""
    private var startMs = 0L
    private var pausedTotal = 0L
    private var pauseStart = 0L
    private val fixes = ArrayList<GpsFix>()
    private val hrSamples = ArrayList<Pair<Long, Int>>()
    private val marks = ArrayList<Long>()
    private val goals = ArrayList<Pair<Long, Int>>()
    private var lastLive: GpsFix? = null
    private var smooth = 0.0
    private var inSprint = false
    private val hrWindow = ArrayDeque<Int>()

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("ftwatch", Context.MODE_PRIVATE)
        _config.value = WatchConfig(
            sportId = prefs.getString("sportId", "") ?: "",
            sportName = prefs.getString("sportName", "") ?: "",
            lengthM = prefs.getFloat("lengthM", 105f).toDouble(),
            widthM = prefs.getFloat("widthM", 68f).toDouble(),
            marginLM = prefs.getFloat("marginLM", 0f).toDouble(),
            marginWM = prefs.getFloat("marginWM", 0f).toDouble(),
            sprintKmh = prefs.getFloat("sprintKmh", 25.2f).toDouble(),
            lang = prefs.getString("lang", "fr") ?: "fr",
            source = prefs.getString("source", "watch") ?: "watch",
            positionId = prefs.getString("positionId", "") ?: "",
            positionName = prefs.getString("positionName", "") ?: "",
            usLabel = prefs.getString("usLabel", "Nous") ?: "Nous",
            themLabel = prefs.getString("themLabel", "Eux") ?: "Eux",
        )
        _state.value = _state.value.copy(sync = if (pendingDir().listFiles()?.isNotEmpty() == true) "pending" else "")
    }

    fun t(fr: String) = WatchText.t(fr, _config.value.lang)

    // ------------------------------------------------------------------ config

    fun applyConfig(c: WatchConfig) {
        val changedSport = c.sportId != _config.value.sportId
        _config.value = c
        prefs.edit()
            .putString("sportId", c.sportId).putString("sportName", c.sportName)
            .putFloat("lengthM", c.lengthM.toFloat()).putFloat("widthM", c.widthM.toFloat())
            .putFloat("marginLM", c.marginLM.toFloat()).putFloat("marginWM", c.marginWM.toFloat())
            .putFloat("sprintKmh", c.sprintKmh.toFloat()).putString("lang", c.lang).putString("source", c.source)
            .putString("positionId", c.positionId).putString("positionName", c.positionName)
            .putString("usLabel", c.usLabel).putString("themLabel", c.themLabel)
            .apply()
        val ph = _state.value.phase
        if (changedSport && (ph == RecPhase.CALIB || ph == RecPhase.READY)) resetCalibration()
    }

    // ------------------------------------------------------------------ GPS de la montre

    private val users = mutableSetOf<String>()
    private var lm: LocationManager? = null

    private val listener = LocationListener { loc: Location ->
        val f = GpsFix(
            // Heure de la mesure ramenée sur l'horloge de la montre (même base que le cardio et les buts)
            System.currentTimeMillis() - ((android.os.SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L).coerceIn(0L, 30_000L),
            loc.latitude, loc.longitude,
            if (loc.hasAccuracy()) loc.accuracy else 99f, if (loc.hasSpeed()) loc.speed else -1f,
        )
        _fix.value = f.copy(t = System.currentTimeMillis())
        onFix(f)
    }

    fun hasLocation(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasGpsHardware(): Boolean =
        app.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)

    @Synchronized
    fun gpsStart(tag: String) {
        users.add(tag)
        if (lm != null || !hasLocation()) return
        val m = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            // GPS_PROVIDER = puce GPS de la montre (pas celle du téléphone)
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            lm = m
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun gpsStop(tag: String) {
        users.remove(tag)
        if (users.isNotEmpty()) return
        try { lm?.removeUpdates(listener) } catch (_: Exception) {}
        lm = null
    }

    fun fresh(f: GpsFix?, ms: Long = 4000) = f != null && System.currentTimeMillis() - f.t < ms

    // ------------------------------------------------------------------ balisage

    fun resetCalibration() {
        captureJob?.cancel()
        for (i in 0 until 4) corners[i] = null
        field = null
        _state.value = _state.value.copy(phase = RecPhase.CALIB, corner = 0, capturing = null, message = "")
    }

    fun undoCorner() {
        captureJob?.cancel()
        val idx = corners.indexOfFirst { it == null }.let { if (it < 0) 4 else it } - 1
        if (idx < 0) return
        corners[idx] = null
        field = null
        _state.value = _state.value.copy(phase = RecPhase.CALIB, corner = idx, capturing = null, message = "")
    }

    fun savedCalibration(): List<LL>? {
        val s = prefs.getString("cal_" + _config.value.sportId, null) ?: return null
        return try {
            val a = JSONArray(s)
            (0 until 4).map { val p = a.getJSONArray(it); LL(p.getDouble(0), p.getDouble(1)) }
        } catch (_: Exception) {
            null
        }
    }

    fun reuseCalibration() {
        val c = savedCalibration() ?: return
        c.forEachIndexed { i, p -> corners[i] = p }
        finishCalibration(save = false)
    }

    /** Mesure le coin courant : moyenne des positions GPS de la montre pendant 4 s. */
    fun captureCorner() {
        val idx = corners.indexOfFirst { it == null }
        if (idx < 0 || _state.value.capturing != null) return
        gpsStart("calib")
        captureJob = scope.launch {
            val samples = ArrayList<GpsFix>()
            val start = System.currentTimeMillis()
            // Coins précis indispensables : seules les mesures à ±20 m (±30 m en dernier recours) sont gardées
            val limit = 20f
            _state.value = _state.value.copy(capturing = 0f, message = "")
            while (System.currentTimeMillis() - start < 6000) {
                delay(150)
                _state.value = _state.value.copy(capturing = ((System.currentTimeMillis() - start) / 6000f).coerceAtMost(1f))
                val f = _fix.value
                if (f != null && fresh(f, 2500) && f.acc <= limit && samples.none { it.t == f.t }) samples.add(f)
            }
            if (samples.isEmpty()) _fix.value?.takeIf { fresh(it, 3000) && it.acc <= 30f }?.let { samples.add(it) }
            if (samples.isEmpty()) {
                _state.value = _state.value.copy(capturing = null, message = t("GPS faible, réessaie"))
                vibrate(longArrayOf(0, 350, 150, 350))
                return@launch
            }
            var w = 0.0; var la = 0.0; var lo = 0.0
            for (s in samples) {
                val k = 1.0 / (s.acc * s.acc).coerceAtLeast(1f)
                la += s.lat * k; lo += s.lon * k; w += k
            }
            corners[idx] = LL(la / w, lo / w)
            vibrate(longArrayOf(0, 80, 90, 80))
            val next = idx + 1
            if (next >= 4) finishCalibration(save = true)
            else _state.value = _state.value.copy(corner = next, capturing = null)
        }
    }

    private fun finishCalibration(save: Boolean) {
        val c = _config.value
        val list = corners.filterNotNull()
        if (list.size < 4) return
        val f = WatchGeo.Field(list, c.lengthM > c.widthM, c.marginLM, c.marginWM)
        val d1 = WatchGeo.distance(list[0], list[2]); val d2 = WatchGeo.distance(list[1], list[3])
        if (abs(d1 - d2) / max(d1, d2) > 0.15) {
            // Diagonales trop différentes : un coin est faux → on recommence
            resetCalibration()
            _state.value = _state.value.copy(message = t("Coins imprécis : recommence le balisage"))
            vibrate(longArrayOf(0, 350, 150, 350))
            return
        }
        field = f
        if (save) {
            prefs.edit().putString("cal_" + c.sportId, JSONArray().apply {
                f.c.forEach { put(JSONArray().put(it.lat).put(it.lon)) }
            }.toString()).apply()
        }
        vibrate(longArrayOf(0, 120, 80, 120, 80, 250))
        _state.value = _state.value.copy(
            phase = RecPhase.READY, corner = 4, capturing = null, message = "",
            fieldL = f.lengthM, fieldW = f.widthM,
        )
    }

    // ------------------------------------------------------------------ séance

    fun elapsed(now: Long = System.currentTimeMillis()): Long = when (_state.value.phase) {
        RecPhase.LIVE -> now - startMs - pausedTotal
        RecPhase.PAUSED -> pauseStart - startMs - pausedTotal
        else -> 0L
    }

    fun start() {
        if (field == null || _state.value.phase != RecPhase.READY) return
        sessionId = UUID.randomUUID().toString()
        startMs = System.currentTimeMillis()
        pausedTotal = 0; pauseStart = 0
        fixes.clear(); hrSamples.clear(); marks.clear(); goals.clear()
        lastLive = null; smooth = 0.0; inSprint = false
        _state.value = _state.value.copy(
            phase = RecPhase.LIVE, distanceM = 0.0, speedKmh = 0.0, maxSpeedKmh = 0.0, sprints = 0, hr = 0,
            scoreUs = 0, scoreThem = 0,
        )
        WatchTrackingService.start(app)
        autosave()
    }

    fun pause() {
        if (_state.value.phase != RecPhase.LIVE) return
        pauseStart = System.currentTimeMillis()
        lastLive = null
        _state.value = _state.value.copy(phase = RecPhase.PAUSED, speedKmh = 0.0)
    }

    fun resume() {
        if (_state.value.phase != RecPhase.PAUSED) return
        pausedTotal += System.currentTimeMillis() - pauseStart
        _state.value = _state.value.copy(phase = RecPhase.LIVE)
    }

    fun mark() {
        if (_state.value.phase == RecPhase.LIVE) { marks.add(System.currentTimeMillis()); vibrate(longArrayOf(0, 60)) }
    }

    /** But : team 0 = mon équipe, 1 = adversaire. */
    fun goal(team: Int) {
        val ph = _state.value.phase
        if (ph != RecPhase.LIVE && ph != RecPhase.PAUSED) return
        goals.add(System.currentTimeMillis() to team)
        updateScore()
        vibrate(longArrayOf(0, 70))
    }

    fun undoGoal(team: Int) {
        val i = goals.indexOfLast { it.second == team }
        if (i < 0) return
        goals.removeAt(i)
        updateScore()
        vibrate(longArrayOf(0, 70, 80, 70))
    }

    private fun updateScore() {
        _state.value = _state.value.copy(scoreUs = goals.count { it.second == 0 }, scoreThem = goals.count { it.second == 1 })
    }

    fun stop() {
        val ph = _state.value.phase
        if (ph != RecPhase.LIVE && ph != RecPhase.PAUSED) return
        if (ph == RecPhase.PAUSED) resume()
        val end = System.currentTimeMillis()
        val active = elapsed(end)
        val st = _state.value
        val f = field
        WatchTrackingService.stop(app)
        val score = if (goals.isNotEmpty()) "  ${st.scoreUs}–${st.scoreThem}" else ""
        val summary = String.format(
            "%s · %s · Vmax %.1f km/h$score", fmtDur(active),
            if (st.distanceM < 1000) String.format("%.0f m", st.distanceM) else String.format("%.2f km", st.distanceM / 1000),
            st.maxSpeedKmh,
        )
        if (f != null && fixes.size >= 2) {
            val json = toJson(f, end, active)
            File(pendingDir(), "$sessionId.json").writeText(json.toString())
            currentFile().delete()
            _state.value = st.copy(phase = RecPhase.DONE, sync = "pending", lastSummary = summary, speedKmh = 0.0)
            syncPending()
        } else {
            currentFile().delete()
            _state.value = st.copy(phase = RecPhase.DONE, lastSummary = summary, speedKmh = 0.0)
        }
        vibrate(longArrayOf(0, 200))
    }

    /** Retour à l'écran de départ (même terrain : prêt pour une nouvelle séance). */
    fun newSession() {
        _state.value = _state.value.copy(
            phase = if (field != null) RecPhase.READY else RecPhase.CALIB,
            distanceM = 0.0, speedKmh = 0.0, maxSpeedKmh = 0.0, sprints = 0, scoreUs = 0, scoreThem = 0,
        )
    }

    private fun onFix(f: GpsFix) {
        if (_state.value.phase != RecPhase.LIVE) return
        if (f.acc > 30f) return
        val fl = field ?: return
        // À plus de 30 m du terrain : erreur GPS, ignorée
        if (!fl.near(fl.uv(LL(f.lat, f.lon)), 30.0)) return
        fixes.add(f)
        val prev = lastLive
        val dt = if (prev != null) ((f.t - prev.t) / 1000.0).coerceIn(0.2, 10.0) else 1.0
        val d = if (prev != null) WatchGeo.distance(LL(prev.lat, prev.lon), LL(f.lat, f.lon)) else 0.0
        // Saut impossible : on l'ignore pour l'affichage (le téléphone refait le calcul complet)
        if (prev != null && d > f.acc + 5 && d / dt > 12.0) return
        val raw = (if (f.speed >= 0) f.speed.toDouble() else d / dt).coerceIn(0.0, 12.0)
        smooth = if (prev == null) raw else smooth * 0.4 + raw * 0.6
        if (smooth < 0.5) smooth = 0.0
        lastLive = f
        val kmh = smooth * 3.6
        val st = _state.value
        var sprints = st.sprints
        val sp = _config.value.sprintKmh
        if (!inSprint && kmh >= sp) { inSprint = true; sprints++ }
        if (inSprint && kmh < sp * 0.9) inSprint = false
        val uv = fl.uv(LL(f.lat, f.lon))
        val inside = fl.inside(uv)
        if (st.inField && !inside) vibrate(longArrayOf(0, 300, 150, 300))
        _state.value = st.copy(
            distanceM = st.distanceM + if (smooth >= 0.5) min(d, 12.0 * dt) else 0.0,
            speedKmh = kmh, maxSpeedKmh = max(st.maxSpeedKmh, kmh), sprints = sprints,
            inField = inside, u = uv[0], v = uv[1],
        )
    }

    // ------------------------------------------------------------------ cardio

    private var hrRegistered = false

    fun hrStart() {
        if (hrRegistered) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) return
        val sm = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(Sensor.TYPE_HEART_RATE) ?: return
        hrRegistered = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun hrStop() {
        if (!hrRegistered) return
        (app.getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        hrRegistered = false
        hrWindow.clear()
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_HEART_RATE) return
        if (e.accuracy < SensorManager.SENSOR_STATUS_ACCURACY_LOW) return
        val raw = e.values.firstOrNull()?.toInt() ?: return
        if (raw !in 35..220) return
        hrWindow.addLast(raw)
        while (hrWindow.size > 5) hrWindow.removeFirst()
        if (hrWindow.size < 3) return
        val bpm = hrWindow.sorted()[hrWindow.size / 2]
        _state.value = _state.value.copy(hr = bpm)
        if (_state.value.phase == RecPhase.LIVE) {
            val now = System.currentTimeMillis()
            if (hrSamples.isEmpty() || now - hrSamples.last().first >= 2000) hrSamples.add(now to bpm)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ------------------------------------------------------------------ sauvegarde / envoi

    private fun pendingDir() = File(app.filesDir, "pending").apply { mkdirs() }
    private fun currentFile() = File(app.filesDir, "current.json")

    private fun toJson(f: WatchGeo.Field, end: Long, active: Long) = JSONObject().apply {
        val c = _config.value
        put("id", sessionId); put("sportId", c.sportId); put("start", startMs); put("end", end); put("active", active)
        put("corners", JSONArray().apply { f.c.forEach { put(JSONArray().put(it.lat).put(it.lon)) } })
        put("fixes", JSONArray().apply {
            fixes.forEach { put(JSONArray().put(it.t).put(it.lat).put(it.lon).put(it.acc.toDouble()).put(it.speed.toDouble())) }
        })
        put("hr", JSONArray().apply { hrSamples.forEach { put(JSONArray().put(it.first).put(it.second)) } })
        put("marks", JSONArray().apply { marks.forEach { put(it) } })
        put("position", c.positionId)
        put("goals", JSONArray().apply { goals.forEach { put(JSONArray().put(it.first).put(it.second)) } })
    }

    /** Sauvegarde de sécurité toutes les 30 s pendant la séance. */
    private fun autosave() {
        scope.launch {
            while (_state.value.phase == RecPhase.LIVE || _state.value.phase == RecPhase.PAUSED) {
                delay(30_000)
                val f = field ?: break
                try { currentFile().writeText(toJson(f, System.currentTimeMillis(), elapsed()).toString()) } catch (_: Exception) {}
            }
        }
    }

    /** Envoie au téléphone toutes les séances pas encore confirmées. */
    fun syncPending() {
        if (!::app.isInitialized) return
        val files = pendingDir().listFiles()?.filter { it.name.endsWith(".json") } ?: return
        if (files.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            for (file in files) {
                try {
                    val bytes = ByteArrayOutputStream().also { bo -> GZIPOutputStream(bo).use { it.write(file.readBytes()) } }.toByteArray()
                    val id = file.name.removeSuffix(".json")
                    val req = PutDataMapRequest.create("/ft/session/$id").apply {
                        dataMap.putAsset("data", Asset.createFromBytes(bytes))
                        dataMap.putLong("ts", System.currentTimeMillis())
                    }.asPutDataRequest().setUrgent()
                    Wearable.getDataClient(app).putDataItem(req).await()
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Le téléphone a bien reçu la séance [id]. */
    fun onAck(id: String) {
        File(pendingDir(), "$id.json").delete()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Wearable.getDataClient(app).deleteDataItems(
                    android.net.Uri.Builder().scheme("wear").path("/ft/session/$id").build()
                ).await()
            } catch (_: Exception) {}
        }
        val left = pendingDir().listFiles()?.isNotEmpty() == true
        _state.value = _state.value.copy(sync = if (left) "pending" else "sent")
        if (!left) vibrate(longArrayOf(0, 60, 80, 60))
    }

    fun vibrate(p: LongArray) = WatchState.vibrate(app, p)

    fun fmtDur(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
    }
}
