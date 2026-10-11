package com.philip.fieldtracker.tracking

import com.philip.fieldtracker.i18n.tr
import com.philip.fieldtracker.i18n.trf
import android.content.Context
import android.location.Location
import com.philip.fieldtracker.data.AppState
import com.philip.fieldtracker.data.Calibration
import com.philip.fieldtracker.data.Geo
import com.philip.fieldtracker.data.Highlight
import com.philip.fieldtracker.data.LatLon
import com.philip.fieldtracker.data.Session
import com.philip.fieldtracker.data.SessionStore
import com.philip.fieldtracker.data.Sport
import com.philip.fieldtracker.data.StatsCalculator
import com.philip.fieldtracker.data.TrackPoint
import com.philip.fieldtracker.watch.WatchLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import kotlin.math.max

enum class Phase { IDLE, RUNNING, PAUSED }

data class LiveState(
    val phase: Phase = Phase.IDLE,
    val sport: Sport? = null,
    val startMs: Long = 0,
    val distanceM: Double = 0.0,
    val speedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val inField: Boolean = true,
    val hasFix: Boolean = false,
    val zoneLabel: String = "",
    val kcal: Double = 0.0,
    val sprints: Int = 0,
    val u: Double = 0.5,
    val v: Double = 0.5,
    /** Trace récente (u, v) pour l'affichage. */
    val trail: List<Pair<Float, Float>> = emptyList(),
    val lastHighlight: Highlight? = null,
    val accuracy: Float = 0f,
)

/** Enregistrement d'une séance en direct. */
object Tracker {
    private val _live = MutableStateFlow(LiveState())
    val live: StateFlow<LiveState> = _live

    private var calibration: Calibration? = null
    private val points = ArrayList<TrackPoint>()
    private val hrSamples = ArrayList<Pair<Long, Int>>()
    private val highlights = ArrayList<Highlight>()
    private val trail = ArrayDeque<Pair<Float, Float>>()

    private var startMs = 0L
    private var pausedTotal = 0L
    private var pauseStart = 0L
    private var smoothSpeed = 0.0
    private var lastLoc: Location? = null
    private var outSince = 0L
    private var outAlerted = false
    private var inSprint = false

    val isActive: Boolean get() = _live.value.phase != Phase.IDLE

    fun elapsedMs(now: Long = System.currentTimeMillis()): Long {
        val s = _live.value
        return when (s.phase) {
            Phase.IDLE -> 0L
            Phase.RUNNING -> now - startMs - pausedTotal
            Phase.PAUSED -> pauseStart - startMs - pausedTotal
        }
    }

    @Synchronized
    fun start(sport: Sport, cal: Calibration) {
        if (isActive) return
        calibration = cal
        points.clear(); hrSamples.clear(); highlights.clear(); trail.clear()
        startMs = System.currentTimeMillis()
        pausedTotal = 0; pauseStart = 0; smoothSpeed = 0.0; lastLoc = null
        outSince = 0; outAlerted = false; inSprint = false
        _live.value = LiveState(phase = Phase.RUNNING, sport = sport, startMs = startMs)
        WatchLink.pushState()
    }

    @Synchronized
    fun pause() {
        if (_live.value.phase != Phase.RUNNING) return
        pauseStart = System.currentTimeMillis()
        lastLoc = null
        _live.value = _live.value.copy(phase = Phase.PAUSED, speedKmh = 0.0)
        WatchLink.pushState()
    }

    @Synchronized
    fun resume() {
        if (_live.value.phase != Phase.PAUSED) return
        pausedTotal += System.currentTimeMillis() - pauseStart
        _live.value = _live.value.copy(phase = Phase.RUNNING)
        WatchLink.pushState()
    }

    @Synchronized
    fun mark(label: String = tr("Temps fort")) {
        if (!isActive) return
        val h = Highlight(System.currentTimeMillis(), label)
        highlights.add(h)
        _live.value = _live.value.copy(lastHighlight = h)
        WatchLink.pushState()
    }

    @Synchronized
    fun onHeartRate(bpm: Int) {
        if (_live.value.phase == Phase.RUNNING) hrSamples.add(System.currentTimeMillis() to bpm)
    }

    @Synchronized
    fun onLocation(loc: Location) {
        val s = _live.value
        val cal = calibration
        if (s.phase != Phase.RUNNING || cal == null) return
        val acc = if (loc.hasAccuracy()) loc.accuracy else 99f
        if (acc > 25f) {
            _live.value = s.copy(hasFix = false, accuracy = acc)
            return
        }
        val now = System.currentTimeMillis()
        val prev = lastLoc
        val dtS = if (prev != null) ((loc.time - prev.time) / 1000.0).coerceIn(0.2, 10.0) else 1.0
        val raw = when {
            loc.hasSpeed() -> loc.speed.toDouble()
            prev != null -> Geo.distance(LatLon(prev.latitude, prev.longitude), LatLon(loc.latitude, loc.longitude)) / dtS
            else -> 0.0
        }.coerceIn(0.0, 12.5)
        smoothSpeed = if (prev == null) raw else smoothSpeed * 0.45 + raw * 0.55

        var add = 0.0
        if (prev != null && smoothSpeed >= 0.4) {
            val d = Geo.distance(LatLon(prev.latitude, prev.longitude), LatLon(loc.latitude, loc.longitude))
            add = if (d / dtS > 12.5) smoothSpeed * dtS else d
        }
        lastLoc = loc

        val uv = cal.toUV(LatLon(loc.latitude, loc.longitude))
        val inside = cal.isInside(uv)
        val hr = HeartRateHub.current()
        points.add(TrackPoint(now, loc.latitude, loc.longitude, uv[0], uv[1], smoothSpeed, hr, inside))

        trail.addLast(uv[0].toFloat() to uv[1].toFloat())
        while (trail.size > 240) trail.removeFirst()

        val kmh = smoothSpeed * 3.6
        val sport = s.sport
        var sprints = s.sprints
        if (sport != null) {
            if (!inSprint && kmh >= sport.sprintKmh) { inSprint = true; sprints++ }
            if (inSprint && kmh < sport.sprintKmh * 0.9) inSprint = false
        }
        val profile = AppState.profile.value
        val kcal = s.kcal + StatsCalculator.kcalPerMinute(hr, kmh, profile) * dtS / 60.0

        val zone = if (sport != null && inside) {
            val zi = (uv[0].coerceIn(0.0, 0.999) * 3).toInt()
            val zj = (uv[1].coerceIn(0.0, 0.999) * 3).toInt()
            "${sport.zoneLabelsLength()[zi]} · ${sport.zoneLabelsWidth()[zj]}"
        } else tr("Hors terrain")

        // Alerte sortie de terrain (après 3 s dehors)
        if (!inside) {
            if (outSince == 0L) outSince = now
            if (!outAlerted && now - outSince >= 3000 && AppState.outAlert.value) {
                outAlerted = true
                Haptics.vibrate(AppState.appContext, Haptics.ALERT)
                WatchLink.send(WatchLink.PATH_ALERT, "out")
            }
        } else {
            outSince = 0L; outAlerted = false
        }

        _live.value = s.copy(
            distanceM = s.distanceM + add,
            speedKmh = kmh,
            maxSpeedKmh = max(s.maxSpeedKmh, kmh),
            inField = inside,
            hasFix = true,
            zoneLabel = zone,
            kcal = kcal,
            sprints = sprints,
            u = uv[0], v = uv[1],
            trail = trail.toList(),
            accuracy = acc,
        )
    }

    /** Termine la séance, calcule les stats et l'enregistre. Renvoie l'id. */
    @Synchronized
    fun stop(ctx: Context): String? {
        val s = _live.value
        if (s.phase == Phase.IDLE) return null
        if (s.phase == Phase.PAUSED) resume()
        val end = System.currentTimeMillis()
        val active = elapsedMs(end)
        val cal = calibration
        val sport = s.sport
        _live.value = LiveState()
        WatchLink.pushState()
        if (cal == null || sport == null || points.size < 2) return null
        val session = Session(
            id = UUID.randomUUID().toString(),
            sportId = sport.id,
            startMs = startMs,
            endMs = end,
            activeMs = active,
            calibration = cal,
            points = points.toList(),
            hrSamples = hrSamples.toList(),
            highlights = highlights.toList(),
            source = "gps",
        )
        val stats = StatsCalculator.compute(session, AppState.profile.value)
        SessionStore.save(ctx, session, stats)
        AppState.selectSession(session.id)
        AppState.historyChanged()
        WatchLink.sendSummary(stats.distanceM, stats.maxSpeedKmh, stats.sprints.size, active)
        return session.id
    }
}
