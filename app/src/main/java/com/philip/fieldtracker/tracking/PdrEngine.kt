package com.philip.fieldtracker.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Handler
import android.os.Looper
import com.philip.fieldtracker.data.Calibration
import com.philip.fieldtracker.data.Geo
import com.philip.fieldtracker.data.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.cos
import kotlin.math.sin

/**
 * Mode sans GPS (expérimental) : estimation de la position à l'estime.
 * Chaque pas détecté avance la position dans la direction de la boussole.
 * L'erreur grandit avec le temps : on la remet à zéro en "recalant" sur une
 * balise (un coin, le centre…) en touchant le terrain à l'endroit où l'on est.
 */
object PdrEngine : SensorEventListener {

    private var sm: SensorManager? = null
    private var cal: Calibration? = null
    private var origin = doubleArrayOf(0.0, 0.0)
    private var axisU = doubleArrayOf(1.0, 0.0)
    private var axisV = doubleArrayOf(0.0, 1.0)

    private var x = 0.0
    private var y = 0.0
    private val stepTimes = ArrayDeque<Long>()
    private var lastStepMs = 0L
    private var lastEmitMs = 0L
    private var distSinceAnchor = 0.0

    /** Cap de la boussole (degrés, 0 = nord, sens horaire). */
    private val _heading = MutableStateFlow(0f)
    val heading: StateFlow<Float> = _heading

    private val _steps = MutableStateFlow(0)
    val steps: StateFlow<Int> = _steps

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            // Immobile : on envoie quand même une position (vitesse 0) pour compter le temps
            if (now - lastStepMs > 1500 && now - lastEmitMs >= 1000) emit(0.0)
            handler.postDelayed(this, 1000)
        }
    }

    fun isAvailable(ctx: Context): Boolean {
        val m = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return m.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null &&
            m.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null
    }

    /** Écoute uniquement la boussole (pour le balisage sans GPS). */
    fun startCompass(ctx: Context) {
        val m = ctx.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm = m
        m.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { m.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stopCompass() {
        if (!running) sm?.unregisterListener(this)
    }

    /** Démarre l'estimation de position, en partant du point (u, v) du terrain. */
    fun start(ctx: Context, c: Calibration, startU: Double = 0.0, startV: Double = 0.0) {
        cal = c
        val loc = c.corners.map { Geo.toLocal(c.origin, it) }
        origin = loc[0]
        axisU = doubleArrayOf(loc[1][0] - loc[0][0], loc[1][1] - loc[0][1])
        axisV = doubleArrayOf(loc[3][0] - loc[0][0], loc[3][1] - loc[0][1])
        anchor(startU, startV)
        _steps.value = 0
        val m = ctx.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm = m
        m.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let { m.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        m.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { m.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        running = true
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(ticker)
        sm?.unregisterListener(this)
        stepTimes.clear()
    }

    /** Recalage sur une balise : "je suis ici" (coordonnées terrain 0..1). */
    fun anchor(u: Double, v: Double) {
        x = origin[0] + u * axisU[0] + v * axisV[0]
        y = origin[1] + u * axisU[1] + v * axisV[1]
        distSinceAnchor = 0.0
        if (running) emit(0.0)
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val r = FloatArray(9)
                val o = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(r, e.values)
                SensorManager.getOrientation(r, o)
                var deg = Math.toDegrees(o[0].toDouble()).toFloat()
                if (deg < 0) deg += 360f
                // Lissage circulaire
                val prev = _heading.value
                var diff = deg - prev
                if (diff > 180) diff -= 360
                if (diff < -180) diff += 360
                _heading.value = ((prev + diff * 0.25f) + 360f) % 360f
            }
            Sensor.TYPE_STEP_DETECTOR -> if (running) onStep()
        }
    }

    private fun onStep() {
        val now = System.currentTimeMillis()
        stepTimes.addLast(now)
        while (stepTimes.size > 6) stepTimes.removeFirst()
        // Cadence (pas/min) -> longueur de foulée : marche ~0,7 m, sprint ~1,6 m
        val cadence = if (stepTimes.size >= 2)
            60000.0 * (stepTimes.size - 1) / (stepTimes.last() - stepTimes.first()).coerceAtLeast(1)
        else 100.0
        val stride = when {
            cadence < 110 -> 0.70
            cadence < 150 -> 0.70 + (cadence - 110) / 40 * 0.40
            cadence < 190 -> 1.10 + (cadence - 150) / 40 * 0.50
            else -> 1.60
        }
        val h = Math.toRadians(_heading.value.toDouble())
        x += stride * sin(h)
        y += stride * cos(h)
        distSinceAnchor += stride
        _steps.value = _steps.value + 1
        val dt = if (lastStepMs > 0) ((now - lastStepMs) / 1000.0).coerceIn(0.2, 2.0) else 0.6
        lastStepMs = now
        emit(stride / dt)
    }

    private fun emit(speed: Double) {
        val c = cal ?: return
        val now = System.currentTimeMillis()
        lastEmitMs = now
        val p: LatLon = Geo.fromLocal(c.origin, x, y)
        val loc = Location("pdr").apply {
            latitude = p.lat
            longitude = p.lon
            time = now
            this.speed = speed.toFloat().coerceIn(0f, 12f)
            // Précision indicative qui se dégrade depuis le dernier recalage
            accuracy = (3.0 + distSinceAnchor * 0.05).coerceAtMost(20.0).toFloat()
        }
        Tracker.onLocation(loc)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Terrain sans GPS : construit à partir des dimensions et de la direction
     * de la boussole, depuis le coin 1 en regardant vers le coin 2 (grande ligne).
     * Le terrain est à droite (tour dans le sens des aiguilles d'une montre).
     */
    fun buildCalibration(lengthM: Double, widthM: Double, headingDeg: Float): Calibration {
        val o = LatLon(0.0, 0.0)
        val h = Math.toRadians(headingDeg.toDouble())
        val dir = doubleArrayOf(sin(h), cos(h))
        val right = doubleArrayOf(cos(h), -sin(h))
        fun pt(a: Double, b: Double) = Geo.fromLocal(o, a, b)
        val c1 = pt(0.0, 0.0)
        val c2 = pt(dir[0] * lengthM, dir[1] * lengthM)
        val c3 = pt(dir[0] * lengthM + right[0] * widthM, dir[1] * lengthM + right[1] * widthM)
        val c4 = pt(right[0] * widthM, right[1] * widthM)
        return Calibration(listOf(c1, c2, c3, c4), estimated = true)
    }
}
