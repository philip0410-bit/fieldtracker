package com.philip.fieldtracker.wear

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/** État reçu du téléphone. */
data class PhoneState(
    val phase: String = "none",
    val sport: String = "",
    val corner: Int = 0,
    val capturing: Boolean = false,
    val accuracy: Double = -1.0,
    val message: String = "",
    val elapsedMs: Long = 0,
    val receivedAt: Long = 0,
    val distanceM: Double = 0.0,
    val speedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val inField: Boolean = true,
    val zone: String = "",
    val kcal: Double = 0.0,
    val sprints: Int = 0,
)

data class Summary(val distM: Double, val vmax: Double, val sprints: Int, val activeMs: Long, val at: Long)

object WatchState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(PhoneState())
    val state: StateFlow<PhoneState> = _state

    private val _summary = MutableStateFlow<Summary?>(null)
    val summary: StateFlow<Summary?> = _summary

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    fun onMessage(ctx: Context, path: String, data: String) {
        when (path) {
            "/ft/state" -> try {
                val o = JSONObject(data)
                val prev = _state.value
                val next = PhoneState(
                    phase = o.optString("phase", "none"),
                    sport = o.optString("sport", ""),
                    corner = o.optInt("corner", 0),
                    capturing = o.optBoolean("capturing", false),
                    accuracy = o.optDouble("acc", -1.0),
                    message = o.optString("msg", ""),
                    elapsedMs = o.optLong("elapsed", 0),
                    receivedAt = System.currentTimeMillis(),
                    distanceM = o.optDouble("dist", 0.0),
                    speedKmh = o.optDouble("speed", 0.0),
                    maxSpeedKmh = o.optDouble("vmax", 0.0),
                    inField = o.optBoolean("inField", true),
                    zone = o.optString("zone", ""),
                    kcal = o.optDouble("kcal", 0.0),
                    sprints = o.optInt("sprints", 0),
                )
                // Coin validé -> double vibration au poignet
                if (next.phase == prev.phase && next.corner > prev.corner) vibrate(ctx, longArrayOf(0, 80, 90, 80))
                if (prev.phase == "calib" && next.phase == "ready") vibrate(ctx, longArrayOf(0, 120, 80, 120, 80, 250))
                if (next.phase == "live" || next.phase == "paused") _summary.value = null
                _state.value = next
                _connected.value = true
            } catch (_: Exception) {
            }
            "/ft/alert" -> vibrate(ctx, longArrayOf(0, 350, 150, 350, 150, 350))
            "/ft/summary" -> try {
                val o = JSONObject(data)
                _summary.value = Summary(
                    o.optDouble("dist", 0.0), o.optDouble("vmax", 0.0), o.optInt("sprints", 0),
                    o.optLong("active", 0), System.currentTimeMillis()
                )
                vibrate(ctx, longArrayOf(0, 200))
            } catch (_: Exception) {
            }
        }
    }

    fun send(ctx: Context, path: String, payload: String = "") {
        val app = ctx.applicationContext
        scope.launch {
            try {
                val nodes = Wearable.getNodeClient(app).connectedNodes.await()
                _connected.value = nodes.isNotEmpty()
                val client = Wearable.getMessageClient(app)
                nodes.forEach { n -> client.sendMessage(n.id, path, payload.toByteArray()).await() }
            } catch (_: Exception) {
                _connected.value = false
            }
        }
    }

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
}

class WatchListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        WatchState.onMessage(this, event.path, String(event.data))
    }
}
