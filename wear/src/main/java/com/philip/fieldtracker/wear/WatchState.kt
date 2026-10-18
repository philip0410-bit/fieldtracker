package com.philip.fieldtracker.wear

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
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
    val lang: String = "fr",
    val us: Int = 0,
    val them: Int = 0,
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
                    lang = o.optString("lang", prev.lang),
                    us = o.optInt("us", 0),
                    them = o.optInt("them", 0),
                )
                // Coin validé -> double vibration au poignet
                if (next.phase == prev.phase && next.corner > prev.corner) vibrate(ctx, longArrayOf(0, 80, 90, 80))
                if (prev.phase == "calib" && next.phase == "ready") vibrate(ctx, longArrayOf(0, 120, 80, 120, 80, 250))
                // Nouvelle séance ou nouveau balisage : on efface le bilan précédent
                if (next.phase != "none" && next.phase != prev.phase) _summary.value = null
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
                // Retour automatique à l'écran normal après 20 s
                scope.launch { kotlinx.coroutines.delay(20_000); _summary.value = null }
            } catch (_: Exception) {
            }
            "/ft/ack" -> {
                WatchRecorder.init(ctx)
                android.os.Handler(android.os.Looper.getMainLooper()).post { WatchRecorder.onAck(data.trim()) }
            }
        }
    }

    fun clearSummary() { _summary.value = null }

    fun parseConfig(m: DataMap) = WatchConfig(
        sportId = m.getString("sportId", ""),
        sportName = m.getString("sportName", ""),
        lengthM = m.getDouble("lengthM", 105.0),
        widthM = m.getDouble("widthM", 68.0),
        marginLM = m.getDouble("marginLM", 0.0),
        marginWM = m.getDouble("marginWM", 0.0),
        sprintKmh = m.getDouble("sprintKmh", 25.2),
        lang = m.getString("lang", "fr"),
        source = m.getString("source", "watch"),
        positionId = m.getString("positionId", ""),
        positionName = m.getString("positionName", ""),
        usLabel = m.getString("us", "Nous"),
        themLabel = m.getString("them", "Eux"),
    )

    /** Lit la dernière config envoyée par le téléphone (même si l'app montre était fermée). */
    fun loadConfig(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            try {
                val items = Wearable.getDataClient(app).dataItems.await()
                for (it in items) {
                    if (it.uri.path == "/ft/config") {
                        val c = parseConfig(DataMapItem.fromDataItem(it).dataMap)
                        kotlinx.coroutines.withContext(Dispatchers.Main) { WatchRecorder.applyConfig(c) }
                    }
                }
                items.release()
            } catch (_: Exception) {
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) { WatchRecorder.syncPending() }
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

    override fun onDataChanged(events: DataEventBuffer) {
        WatchRecorder.init(this)
        val configs = events.filter {
            it.type == com.google.android.gms.wearable.DataEvent.TYPE_CHANGED && it.dataItem.uri.path == "/ft/config"
        }
            .map { WatchState.parseConfig(DataMapItem.fromDataItem(it.dataItem).dataMap) }
        if (configs.isNotEmpty()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                WatchRecorder.applyConfig(configs.last())
                // Téléphone joignable : on renvoie les séances en attente
                WatchRecorder.syncPending()
            }
        }
    }
}

/** Textes de la montre en français, anglais et coréen (langue choisie sur le téléphone). */
object WatchText {
    private val table = mapOf(
        "Ouvre l'app sur le téléphone" to ("Open the app on your phone" to "휴대폰에서 앱을 여세요"),
        "Choisis ton sport sur le téléphone" to ("Choose your sport on the phone" to "휴대폰에서 스포츠를 선택하세요"),
        "COIN %d/4" to ("CORNER %d/4" to "코너 %d/4"),
        "Mesure… reste immobile" to ("Measuring… stay still" to "측정 중… 움직이지 마세요"),
        "GPS faible, réessaie" to ("Weak GPS, try again" to "GPS 약함, 다시 시도"),
        "Terrain balisé" to ("Field marked" to "경기장 설정 완료"),
        "START" to ("START" to "시작"),
        "DANS LE TERRAIN" to ("INSIDE FIELD" to "경기장 안"),
        "HORS TERRAIN" to ("OUT OF BOUNDS" to "경기장 밖"),
        "maintenir ■ pour terminer" to ("hold ■ to finish" to "■ 길게 눌러 종료"),
        "Séance terminée" to ("Session finished" to "운동 종료"),
        "%d sprints" to ("%d sprints" to "스프린트 %d회"),
        "Bilan complet sur le téléphone" to ("Full report on the phone" to "전체 리포트는 휴대폰에서"),
        "GPS montre" to ("Watch GPS" to "워치 GPS"),
        "Recherche GPS…" to ("Searching GPS…" to "GPS 검색 중…"),
        "Va à découvert" to ("Move into the open" to "트인 곳으로 이동하세요"),
        "Autoriser GPS + cardio" to ("Allow GPS + heart rate" to "GPS + 심박 허용"),
        "Cette montre n'a pas de GPS" to ("This watch has no GPS" to "이 워치에는 GPS가 없습니다"),
        "Choisis le sport sur le téléphone (une fois)" to ("Choose the sport on the phone (once)" to "휴대폰에서 스포츠를 한 번 선택하세요"),
        "Mesure…" to ("Measuring…" to "측정 중…"),
        "Réutiliser" to ("Reuse" to "다시 사용"),
        "Refaire" to ("Redo" to "다시"),
        "Annuler" to ("Undo" to "취소"),
        "Nouvelle séance" to ("New session" to "새 운동"),
        "Envoi au téléphone…" to ("Sending to phone…" to "휴대폰으로 전송 중…"),
        "En attente du téléphone" to ("Waiting for phone" to "휴대폰 대기 중"),
        "Envoyé au téléphone ✓" to ("Sent to phone ✓" to "휴대폰으로 전송됨 ✓"),
        "Télécommande du téléphone" to ("Phone remote" to "휴대폰 리모컨"),
    )

    fun t(fr: String, lang: String): String = when (lang) {
        "en" -> table[fr]?.first ?: fr
        "ko" -> table[fr]?.second ?: fr
        else -> fr
    }
}
