package com.philip.fieldtracker.tracking

import com.philip.fieldtracker.i18n.tr
import com.philip.fieldtracker.i18n.trf
import com.philip.fieldtracker.data.AppState
import com.philip.fieldtracker.data.Calibration
import com.philip.fieldtracker.data.LatLon
import com.philip.fieldtracker.data.Sport
import com.philip.fieldtracker.watch.WatchLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Balisage des 4 coins du terrain. */
object Calibrator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    const val CAPTURE_MS = 4000L
    const val MAX_ACCURACY_M = 20f

    val cornerNames = listOf("Coin haut gauche", "Coin haut droit", "Coin bas droit", "Coin bas gauche")

    private val _corners = MutableStateFlow<List<LatLon?>>(listOf(null, null, null, null))
    val corners: StateFlow<List<LatLon?>> = _corners

    /** Progression de la mesure en cours (0..1), null si aucune. */
    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Balisage terminé et validé pour ce sport. */
    private val _calibration = MutableStateFlow<Calibration?>(null)
    val calibration: StateFlow<Calibration?> = _calibration

    private var sportId: String? = null
    private var job: Job? = null

    val currentIndex: Int get() = _corners.value.indexOfFirst { it == null }.let { if (it < 0) 4 else it }

    /** À appeler quand le sport change : remet le balisage à zéro si c'est un autre sport. */
    fun prepare(sport: Sport) {
        if (sportId != sport.id) {
            sportId = sport.id
            reset()
        }
    }

    fun reset() {
        job?.cancel()
        _corners.value = listOf(null, null, null, null)
        _progress.value = null
        _calibration.value = null
        _message.value = null
        WatchLink.pushState()
    }

    fun undo() {
        job?.cancel()
        _progress.value = null
        val idx = currentIndex - 1
        if (idx < 0) return
        _corners.value = _corners.value.toMutableList().also { it[idx] = null }
        _calibration.value = null
        _message.value = null
        WatchLink.pushState()
    }

    fun useCalibration(c: Calibration) {
        job?.cancel()
        _progress.value = null
        _corners.value = c.corners
        _calibration.value = c
        _message.value = tr("Balisage précédent réutilisé")
        WatchLink.pushState()
    }

    /** Mesure le coin courant : moyenne des positions GPS pendant [CAPTURE_MS]. */
    fun capture(sport: Sport) {
        if (_progress.value != null || currentIndex >= 4) return
        val ctx = AppState.appContext
        GpsEngine.start(ctx, "calibration")
        job = scope.launch {
            val samples = ArrayList<Fix>()
            val start = System.currentTimeMillis()
            // Précision acceptée : 20 m idéalement, sinon on prend ce qui est disponible (max 150 m)
            val limit = maxOf(MAX_ACCURACY_M, (GpsEngine.fix.value?.accuracy ?: MAX_ACCURACY_M) * 1.5f).coerceAtMost(150f)
            _message.value = null
            _progress.value = 0f
            WatchLink.pushState()
            while (System.currentTimeMillis() - start < CAPTURE_MS) {
                delay(150)
                _progress.value = ((System.currentTimeMillis() - start).toFloat() / CAPTURE_MS).coerceAtMost(1f)
                val f = GpsEngine.fix.value
                if (f != null && GpsEngine.isFresh(f, 3000) && f.accuracy <= limit &&
                    samples.none { it.timeMs == f.timeMs }
                ) samples.add(f)
            }
            _progress.value = null
            // Localisation réseau : peu de mises à jour, on garde la dernière position récente
            if (samples.isEmpty()) GpsEngine.fix.value?.takeIf { GpsEngine.isFresh(it, 15000) && it.accuracy <= 150f }?.let { samples.add(it) }
            if (samples.isEmpty()) {
                _message.value = tr("Aucune position reçue — active la localisation et mets-toi à découvert, puis réessaie.")
                Haptics.vibrate(ctx, Haptics.ALERT)
                WatchLink.send(WatchLink.PATH_ALERT, "gps")
                WatchLink.pushState()
                return@launch
            }
            val best = samples.minOf { it.accuracy }
            if (best > MAX_ACCURACY_M) _message.value = trf("Coin enregistré avec une précision de ±%d m : le terrain sera approximatif.", best.toInt())
            var wSum = 0.0; var lat = 0.0; var lon = 0.0
            for (s in samples) {
                val w = 1.0 / (s.accuracy * s.accuracy).coerceAtLeast(1f)
                lat += s.lat * w; lon += s.lon * w; wSum += w
            }
            val p = LatLon(lat / wSum, lon / wSum)
            val idx = currentIndex
            _corners.value = _corners.value.toMutableList().also { it[idx] = p }
            Haptics.vibrate(ctx, Haptics.DOUBLE)
            if (currentIndex >= 4) {
                val cal = Calibration.normalized(_corners.value.filterNotNull(), sport.lengthM > sport.widthM)
                _calibration.value = cal
                AppState.saveCalibration(sport.id, cal)
                _message.value = null
            }
            WatchLink.pushState()
        }
    }
}

/** Fréquence cardiaque reçue de la montre ou d'un capteur Bluetooth. */
object HeartRateHub {
    private val _bpm = MutableStateFlow(0)
    val bpm: StateFlow<Int> = _bpm

    private val _source = MutableStateFlow("")
    val source: StateFlow<String> = _source

    @Volatile
    var lastUpdateMs = 0L
        private set

    fun push(bpm: Int, source: String) {
        if (bpm !in 30..240) return
        _bpm.value = bpm
        _source.value = source
        lastUpdateMs = System.currentTimeMillis()
        Tracker.onHeartRate(bpm)
    }

    /** FC actuelle si reçue dans les 6 dernières secondes, sinon 0. */
    fun current(): Int = if (System.currentTimeMillis() - lastUpdateMs < 6000) _bpm.value else 0
}
