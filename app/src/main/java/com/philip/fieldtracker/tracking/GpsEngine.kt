package com.philip.fieldtracker.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Fix(
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val speed: Float?,
    val timeMs: Long,
    /** "gps" (satellites) ou "network" (Wi-Fi / antennes, approximatif). */
    val provider: String = "gps",
)

/** État de la localisation, pour guider l'utilisateur. */
enum class GpsStatus { NO_PERMISSION, LOCATION_OFF, SEARCHING, APPROXIMATE, OK }

/**
 * Accès à la localisation du téléphone, partagé entre le balisage et le suivi.
 * Utilise les satellites GPS (fonctionne sans internet) et, en secours, la
 * localisation réseau (Wi-Fi / antennes) quand les satellites ne répondent pas.
 */
object GpsEngine {
    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix

    /** (satellites utilisés, satellites visibles) */
    private val _sats = MutableStateFlow(0 to 0)
    val satellites: StateFlow<Pair<Int, Int>> = _sats

    private val users = mutableSetOf<String>()
    private var lm: LocationManager? = null
    private var registered = false
    private var gpsRegistered = false
    private var lastGpsFixMs = 0L

    private val listener = LocationListener { loc: Location -> onLocation(loc) }

    private fun onLocation(loc: Location) {
        val isGps = loc.provider == LocationManager.GPS_PROVIDER
        val now = System.currentTimeMillis()
        if (isGps) lastGpsFixMs = now
        // Le réseau ne sert que de secours quand le GPS ne donne rien depuis 5 s
        if (!isGps && now - lastGpsFixMs < 5000) return
        _fix.value = Fix(
            loc.latitude, loc.longitude,
            if (loc.hasAccuracy()) loc.accuracy else 99f,
            if (loc.hasSpeed()) loc.speed else null,
            now,
            if (isGps) "gps" else "network",
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

    fun hasPermission(ctx: Context) = hasPrecise(ctx) ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasPrecise(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isLocationOn(ctx: Context): Boolean {
        val m = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= 28) m.isLocationEnabled
        else m.isProviderEnabled(LocationManager.GPS_PROVIDER) || m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun status(ctx: Context): GpsStatus = when {
        !hasPermission(ctx) -> GpsStatus.NO_PERMISSION
        !isLocationOn(ctx) -> GpsStatus.LOCATION_OFF
        !isFresh(_fix.value) -> GpsStatus.SEARCHING
        !hasPrecise(ctx) || _fix.value?.provider != "gps" -> GpsStatus.APPROXIMATE
        else -> GpsStatus.OK
    }

    fun isFresh(f: Fix?, maxAgeMs: Long = 4000): Boolean = f != null && System.currentTimeMillis() - f.timeMs < maxAgeMs

    @Synchronized
    fun start(ctx: Context, tag: String) {
        users.add(tag)
        ensure(ctx)
    }

    /** (Re)branche l'écoute si besoin — à appeler après une autorisation ou l'activation de la localisation. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun ensure(ctx: Context) {
        if (users.isEmpty() || !hasPermission(ctx)) return
        if (registered && (gpsRegistered || !hasPrecise(ctx))) return
        val m = ctx.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // Autorisation précise accordée après coup : on ré-abonne avec le GPS
        if (registered) try { m.removeUpdates(listener) } catch (_: Exception) {}
        gpsRegistered = false
        lm = m
        var any = false
        try {
            if (hasPrecise(ctx) && m.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
                m.registerGnssStatusCallback(gnssCallback, Handler(Looper.getMainLooper()))
                any = true
                gpsRegistered = true
            }
        } catch (_: Exception) {
        }
        try {
            if (m.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, listener, Looper.getMainLooper())
                any = true
            }
        } catch (_: Exception) {
        }
        registered = any
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
        registered = false
        gpsRegistered = false
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
