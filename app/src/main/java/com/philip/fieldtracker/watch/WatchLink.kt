package com.philip.fieldtracker.watch

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.philip.fieldtracker.data.AppState
import com.philip.fieldtracker.tracking.Calibrator
import com.philip.fieldtracker.tracking.GpsEngine
import com.philip.fieldtracker.tracking.HeartRateHub
import com.philip.fieldtracker.tracking.Phase
import com.philip.fieldtracker.tracking.Tracker
import com.philip.fieldtracker.tracking.TrackingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Liaison téléphone -> montre Wear OS (Galaxy Watch…). */
object WatchLink {
    const val PATH_STATE = "/ft/state"
    const val PATH_ALERT = "/ft/alert"
    const val PATH_SUMMARY = "/ft/summary"
    const val PATH_CORNER = "/ft/corner"
    const val PATH_HR = "/ft/hr"
    const val PATH_CMD = "/ft/cmd"
    const val PATH_HELLO = "/ft/hello"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Node(val id: String, val name: String)

    private val _nodes = MutableStateFlow<List<Node>>(emptyList())
    val nodes: StateFlow<List<Node>> = _nodes

    @Volatile private var lastRefresh = 0L
    @Volatile private var lastPush = 0L

    fun refresh() {
        scope.launch { refreshNow() }
    }

    private suspend fun refreshNow() {
        if (!isReady()) return
        try {
            val list = Wearable.getNodeClient(AppState.appContext).connectedNodes.await()
            _nodes.value = list.map { Node(it.id, it.displayName) }
            lastRefresh = System.currentTimeMillis()
        } catch (_: Exception) {
            _nodes.value = emptyList()
        }
    }

    private fun isReady(): Boolean = try {
        AppState.appContext; true
    } catch (_: UninitializedPropertyAccessException) {
        false
    }

    fun send(path: String, payload: String) {
        if (!isReady()) return
        scope.launch {
            if (System.currentTimeMillis() - lastRefresh > 30_000) refreshNow()
            val client = Wearable.getMessageClient(AppState.appContext)
            for (n in _nodes.value) {
                try {
                    client.sendMessage(n.id, path, payload.toByteArray()).await()
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Envoie l'état courant à la montre (limité à ~3 envois/s). */
    fun pushState(force: Boolean = false) {
        if (!isReady()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastPush < 300) return
        lastPush = now
        send(PATH_STATE, buildState().toString())
    }

    fun sendSummary(distM: Double, vmax: Double, sprints: Int, activeMs: Long) {
        send(PATH_SUMMARY, JSONObject().apply {
            put("dist", distM); put("vmax", vmax); put("sprints", sprints); put("active", activeMs)
        }.toString())
    }

    private fun buildState(): JSONObject {
        val live = Tracker.live.value
        val sport = AppState.sport.value
        val cal = Calibrator.calibration.value
        val phase = when {
            live.phase == Phase.RUNNING -> "live"
            live.phase == Phase.PAUSED -> "paused"
            sport == null -> "none"
            cal != null -> "ready"
            else -> "calib"
        }
        val fix = GpsEngine.fix.value
        return JSONObject().apply {
            put("phase", phase)
            put("sport", sport?.name ?: "")
            put("corner", Calibrator.currentIndex)
            put("capturing", Calibrator.progress.value != null)
            put("acc", if (GpsEngine.isFresh(fix)) (fix?.accuracy ?: 0f).toDouble() else -1.0)
            put("msg", Calibrator.message.value ?: "")
            put("elapsed", Tracker.elapsedMs())
            put("dist", live.distanceM)
            put("speed", live.speedKmh)
            put("vmax", live.maxSpeedKmh)
            put("inField", live.inField)
            put("zone", live.zoneLabel)
            put("kcal", live.kcal)
            put("sprints", live.sprints)
        }
    }

    /** Commandes reçues de la montre. */
    fun handle(path: String, data: String) {
        val ctx = AppState.appContext
        when (path) {
            PATH_CORNER -> AppState.sport.value?.let { sport ->
                Calibrator.prepare(sport)
                Calibrator.capture(sport)
            }
            PATH_HR -> data.trim().toIntOrNull()?.let { HeartRateHub.push(it, "Montre") }
            PATH_CMD -> when (data) {
                "start" -> {
                    val sport = AppState.sport.value
                    val cal = Calibrator.calibration.value
                    if (sport != null && cal != null && !Tracker.isActive) {
                        Tracker.start(sport, cal)
                        try { TrackingService.start(ctx) } catch (_: Exception) {}
                    }
                }
                "pause" -> Tracker.pause()
                "resume" -> Tracker.resume()
                "mark" -> Tracker.mark("Temps fort (montre)")
                "stop" -> try { TrackingService.stop(ctx) } catch (_: Exception) { Tracker.stop(ctx) }
            }
            PATH_HELLO -> refresh()
        }
        pushState(force = true)
    }
}

/** Reçoit les messages de la montre, même quand l'app est en arrière-plan. */
class PhoneWearService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        AppState.init(this)
        val path = event.path
        val data = String(event.data)
        CoroutineScope(Dispatchers.Main).launch {
            withContext(Dispatchers.Main) { WatchLink.handle(path, data) }
        }
    }
}
