package com.philip.fieldtracker.data

import kotlin.math.abs
import kotlin.math.min

/** Position GPS brute (montre, traqueur, fichier). */
data class RawFix(val t: Long, val lat: Double, val lon: Double, val acc: Double, val speed: Double?)

/**
 * Lissage d'une trace complète, après coup : chaque point est moyenné avec ses
 * voisins (avant ET après), les sauts impossibles sont rejetés, la vitesse
 * combine le Doppler du GPS et le déplacement mesuré.
 */
object TrackSmoother {

    /** Un joueur ne peut pas être à plus de [FAR_M] m du terrain : au-delà, c'est une erreur GPS. */
    private const val FAR_M = 30.0

    fun nearField(cal: Calibration, sport: Sport?, lat: Double, lon: Double): Boolean {
        val uv = cal.toUV(LatLon(lat, lon))
        return cal.isInside(uv, (sport?.marginLM ?: 0.0) + FAR_M, (sport?.marginWM ?: 0.0) + FAR_M)
    }

    /** Recalcule (u, v) et « dans le terrain » de points déjà enregistrés, sans les points aberrants. */
    fun reproject(points: List<TrackPoint>, cal: Calibration, sport: Sport?): List<TrackPoint> {
        val mL = (sport?.marginLM ?: 0.0) + 1.5
        val mW = (sport?.marginWM ?: 0.0) + 1.5
        val out = ArrayList<TrackPoint>(points.size)
        for (p in points) {
            if (!nearField(cal, sport, p.lat, p.lon)) continue
            val prev = out.lastOrNull()
            if (prev != null) {
                val dt = ((p.t - prev.t) / 1000.0).coerceAtLeast(0.2)
                if (Geo.distance(LatLon(prev.lat, prev.lon), LatLon(p.lat, p.lon)) / dt > 12.0 && p.t - prev.t < 5000) continue
            }
            val uv = cal.toUV(LatLon(p.lat, p.lon))
            out.add(p.copy(u = uv[0], v = uv[1], inField = cal.isInside(uv, mL, mW)))
        }
        return out
    }

    fun smooth(
        input: List<RawFix>,
        cal: Calibration,
        sport: Sport,
        hr: List<Pair<Long, Int>>,
    ): List<TrackPoint> {
        val raw = input.filter { it.acc <= 30.0 && nearField(cal, sport, it.lat, it.lon) }.sortedBy { it.t }
        // 1. Rejet des sauts impossibles (> 12 m/s), sauf s'ils se confirment
        val clean = ArrayList<RawFix>(raw.size)
        var rejected = 0
        for (f in raw) {
            val last = clean.lastOrNull()
            if (last != null) {
                val dt = ((f.t - last.t) / 1000.0).coerceAtLeast(0.2)
                val d = Geo.distance(LatLon(last.lat, last.lon), LatLon(f.lat, f.lon))
                if (d > f.acc + 5 && d / dt > 12.0 && rejected < 3) { rejected++; continue }
            }
            rejected = 0
            clean.add(f)
        }
        if (clean.size < 2) return emptyList()

        // 2. Moyenne pondérée centrée (fenêtre ±2 points, poids = précision)
        val sm = ArrayList<RawFix>(clean.size)
        for (i in clean.indices) {
            var w = 0.0; var la = 0.0; var lo = 0.0
            for (k in -2..2) {
                val j = i + k
                if (j !in clean.indices || abs(clean[j].t - clean[i].t) > 6000) continue
                val kw = when (abs(k)) { 0 -> 1.0; 1 -> 0.6; else -> 0.3 }
                val ww = kw / (clean[j].acc.coerceAtLeast(1.0) * clean[j].acc.coerceAtLeast(1.0))
                la += clean[j].lat * ww; lo += clean[j].lon * ww; w += ww
            }
            sm.add(clean[i].copy(lat = la / w, lon = lo / w))
        }

        // 3. Vitesse lissée + points terrain
        val out = ArrayList<TrackPoint>(sm.size)
        var speed = 0.0
        var hi = 0
        val mL = sport.marginLM + 1.5
        val mW = sport.marginWM + 1.5
        for (i in sm.indices) {
            val c = sm[i]
            if (i > 0) {
                val p = sm[i - 1]
                val dt = ((c.t - p.t) / 1000.0).coerceIn(0.2, 10.0)
                val d = Geo.distance(LatLon(p.lat, p.lon), LatLon(c.lat, c.lon))
                val dop = (maxOf(0, i - 1)..min(sm.size - 1, i + 1)).mapNotNull { clean[it].speed?.takeIf { s -> s >= 0 } }.sorted()
                val dv = if (dop.isNotEmpty()) dop[dop.size / 2] else null
                // Le Doppler du GPS est la mesure de vitesse la plus fiable : poids 70 %
                val raw = (if (dv != null) 0.7 * dv + 0.3 * (d / dt) else d / dt).coerceIn(0.0, 12.0)
                speed = if (c.t - p.t > 10_000) raw else speed * 0.25 + raw * 0.75
                if (speed < 0.5) speed = 0.0
            }
            while (hi < hr.size - 1 && abs(hr[hi + 1].first - c.t) <= abs(hr[hi].first - c.t)) hi++
            val bpm = if (hr.isNotEmpty() && abs(hr[hi].first - c.t) <= 8000) hr[hi].second else 0
            val uv = cal.toUV(LatLon(c.lat, c.lon))
            out.add(TrackPoint(c.t, c.lat, c.lon, uv[0], uv[1], speed, bpm, cal.isInside(uv, mL, mW)))
        }
        return out
    }
}
