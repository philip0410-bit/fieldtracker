package com.philip.fieldtracker.watch

import com.philip.fieldtracker.i18n.tr
import com.philip.fieldtracker.i18n.trf
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.philip.fieldtracker.data.Calibration
import com.philip.fieldtracker.data.LatLon
import com.philip.fieldtracker.data.RawFix
import com.philip.fieldtracker.data.Session
import com.philip.fieldtracker.data.SessionStore
import com.philip.fieldtracker.data.Sports
import com.philip.fieldtracker.data.StatsCalculator
import com.philip.fieldtracker.data.Highlight
import com.philip.fieldtracker.data.TrackSmoother
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

    /**
     * Envoie à la montre le sport choisi et les réglages (synchronisé même si la
     * montre est loin : elle le reçoit dès qu'elle se reconnecte).
     */
    fun pushConfig() {
        if (!isReady()) return
        val sport = AppState.sport.value ?: return
        scope.launch {
            try {
                val req = PutDataMapRequest.create("/ft/config").apply {
                    dataMap.putString("sportId", sport.id)
                    dataMap.putString("sportName", tr(sport.name))
                    dataMap.putDouble("lengthM", sport.lengthM)
                    dataMap.putDouble("widthM", sport.widthM)
                    dataMap.putDouble("marginLM", sport.marginLM)
                    dataMap.putDouble("marginWM", sport.marginWM)
                    dataMap.putDouble("sprintKmh", sport.sprintKmh)
                    val pos = com.philip.fieldtracker.data.Positions.find(sport.id, AppState.position.value)
                    dataMap.putString("positionId", pos?.id ?: "")
                    dataMap.putString("positionName", pos?.let { tr(it.name) } ?: "")
                    dataMap.putString("us", tr("Nous"))
                    dataMap.putString("them", tr("Eux"))
                    dataMap.putString("lang", com.philip.fieldtracker.i18n.I18n.lang)
                    dataMap.putString("source", if (AppState.positionMode.value == "watch") "watch" else "phone")
                    dataMap.putLong("ts", System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()
                Wearable.getDataClient(AppState.appContext).putDataItem(req).await()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Séance enregistrée par la montre (GPS + cardio de la montre) :
     * lissage, statistiques et enregistrement dans l'historique.
     * Renvoie l'id de la séance ou null.
     */
    fun importWatchSession(json: JSONObject): String? {
        val sport = Sports.byId(json.optString("sportId")) ?: AppState.sport.value ?: return null
        val ca = json.getJSONArray("corners")
        if (ca.length() != 4) return null
        val corners = (0 until 4).map { val p = ca.getJSONArray(it); LatLon(p.getDouble(0), p.getDouble(1)) }
        val cal = Calibration.normalized(corners, sport.lengthM > sport.widthM)
        val fa = json.getJSONArray("fixes")
        val raw = (0 until fa.length()).map {
            val a = fa.getJSONArray(it)
            val sp = a.optDouble(4, -1.0)
            RawFix(a.getLong(0), a.getDouble(1), a.getDouble(2), a.getDouble(3), if (sp >= 0) sp else null)
        }
        val ha = json.optJSONArray("hr") ?: org.json.JSONArray()
        val hr = (0 until ha.length()).map { val a = ha.getJSONArray(it); a.getLong(0) to a.getInt(1) }
        val pts = TrackSmoother.smooth(raw, cal, sport, hr)
        if (pts.size < 2) return null
        val ma = json.optJSONArray("marks") ?: org.json.JSONArray()
        val id = json.optString("id").ifEmpty { java.util.UUID.randomUUID().toString() }
        val session = Session(
            id = id,
            sportId = sport.id,
            startMs = json.optLong("start", pts.first().t),
            endMs = json.optLong("end", pts.last().t),
            activeMs = json.optLong("active", pts.last().t - pts.first().t),
            calibration = cal,
            points = pts,
            hrSamples = hr,
            highlights = (0 until ma.length()).map { Highlight(ma.getLong(it), tr("Temps fort (montre)")) },
            source = "watch",
            positionId = json.optString("position", ""),
            goals = (json.optJSONArray("goals") ?: org.json.JSONArray()).let { g ->
                (0 until g.length()).map { val a = g.getJSONArray(it); com.philip.fieldtracker.data.Goal(a.getLong(0), a.getInt(1)) }
            },
        )
        val ctx = AppState.appContext
        SessionStore.save(ctx, session, StatsCalculator.compute(session, AppState.profile.value))
        AppState.saveCalibration(sport.id, cal)
        return id
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
            put("sport", sport?.name?.let { tr(it) } ?: "")
            put("lang", com.philip.fieldtracker.i18n.I18n.lang)
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
            put("us", live.scoreUs); put("them", live.scoreThem)
        }
    }

    /** Commandes reçues de la montre. */
    fun handle(path: String, data: String) {
        val ctx = AppState.appContext
        when (path) {
            PATH_CORNER -> AppState.sport.value?.let { sport ->
                Calibrator.prepare(sport)
                if (AppState.positionMode.value == "pdr") {
                    // Sans GPS : position estimée par les pas depuis le coin 1
                    if (Calibrator.currentIndex == 0) com.philip.fieldtracker.tracking.PdrEngine.startSurvey(ctx)
                    Calibrator.setCornerManual(sport, com.philip.fieldtracker.tracking.PdrEngine.surveyPoint())
                    com.philip.fieldtracker.tracking.PdrEngine.markSurvey()
                } else {
                    Calibrator.capture(sport)
                }
            }
            PATH_HR -> data.trim().toIntOrNull()?.let { HeartRateHub.push(it, tr("Montre")) }
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
                "mark" -> Tracker.mark(tr("Temps fort (montre)"))
                "goalA" -> Tracker.goal(0)
                "goalB" -> Tracker.goal(1)
                "undoA" -> Tracker.undoGoal(0)
                "undoB" -> Tracker.undoGoal(1)
                "stop" -> try { TrackingService.stop(ctx) } catch (_: Exception) { Tracker.stop(ctx) }
            }
            PATH_HELLO -> { refresh(); pushConfig() }
        }
        pushState(force = true)
    }
}

/** Reçoit les messages de la montre, même quand l'app est en arrière-plan. */
class PhoneWearService : WearableListenerService() {

    /** Réception d'une séance enregistrée par la montre (appelé sur un fil secondaire). */
    override fun onDataChanged(events: DataEventBuffer) {
        AppState.init(this)
        val items = events.filter {
            it.type == com.google.android.gms.wearable.DataEvent.TYPE_CHANGED &&
                it.dataItem.uri.path?.startsWith("/ft/session/") == true
        }
            .map { it.dataItem.freeze() }
        for (item in items) {
            try {
                val asset: Asset = DataMapItem.fromDataItem(item).dataMap.getAsset("data") ?: continue
                val fd = Tasks.await(Wearable.getDataClient(this).getFdForAsset(asset))
                val bytes = fd.inputStream.use { java.util.zip.GZIPInputStream(it).readBytes() }
                val json = JSONObject(String(bytes))
                val id = json.optString("id")
                val already = SessionStore.list(this).any { it.id == id }
                val saved = if (already) id else WatchLink.importWatchSession(json)
                if (saved != null) {
                    // Accusé de réception : la montre peut effacer sa copie
                    Tasks.await(Wearable.getMessageClient(this).sendMessage(item.uri.host ?: "", "/ft/ack", id.toByteArray()))
                    Tasks.await(Wearable.getDataClient(this).deleteDataItems(item.uri))
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        AppState.selectSession(saved)
                        AppState.historyChanged()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        AppState.init(this)
        val path = event.path
        val data = String(event.data)
        CoroutineScope(Dispatchers.Main).launch {
            withContext(Dispatchers.Main) { WatchLink.handle(path, data) }
        }
    }
}
