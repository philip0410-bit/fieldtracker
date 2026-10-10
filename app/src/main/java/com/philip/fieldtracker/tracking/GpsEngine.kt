package com.philip.fieldtracker.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Fix(
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val speed: Float?,
    val timeMs: Long,
)

/** Accès au GPS du téléphone, partagé entre le balisage et le suivi. */
object GpsEngine {
    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix

    /** (satellites utilisés, satellites visibles) */
    private val _sats = MutableStateFlow(0 to 0)
    val satellites: StateFlow<Pair<Int, Int>> = _sats

    private val users = mutableSetOf<String>()
    private var lm: LocationManager? = null

    private val listener = LocationListener { loc: Location ->
        _fix.value = Fix(
            loc.latitude, loc.longitude,
            if (loc.hasAccuracy()) loc.accuracy else 99f,
            if (loc.hasSpeed()) loc.speed else null,
            System.currentTimeMillis(),
        )
        Tracker.onLocation(loc)
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            _sats.value = used to status.satelliteCount
        }
    }

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isFresh(f: Fix?, maxAgeMs: Long = 4000): Boolean = f != null && System.currentTimeMillis() - f.timeMs < maxAgeMs

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(ctx: Context, tag: String) {
        if (!hasPermission(ctx)) return
        val wasEmpty = users.isEmpty()
        users.add(tag)
        if (!wasEmpty) return
        val m = ctx.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = m
        try {
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            m.registerGnssStatusCallback(gnssCallback, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            users.remove(tag)
        }
    }

    @Synchronized
    fun stop(tag: String) {
        users.remove(tag)
        if (users.isNotEmpty()) return
        lm?.let {
            try {
                it.removeUpdates(listener)
                it.unregisterGnssStatusCallback(gnssCallback)
            } catch (_: Exception) {
            }
        }
        lm = null
    }
}

object Haptics {
    fun vibrate(ctx: Context, pattern: LongArray) {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        try {
            v?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {
        }
    }

    val SHORT = longArrayOf(0, 60)
    val DOUBLE = longArrayOf(0, 80, 90, 80)
    val ALERT = longArrayOf(0, 300, 150, 300, 150, 300)
}
