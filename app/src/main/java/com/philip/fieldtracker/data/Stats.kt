package com.philip.fieldtracker.data

import kotlin.math.max
import kotlin.math.min

data class Profile(
    val age: Int = 30,
    val weightKg: Double = 75.0,
    val male: Boolean = true,
    /** 0 = calculée (220 - âge). */
    val hrMaxOverride: Int = 0,
) {
    val hrMax: Int get() = if (hrMaxOverride > 0) hrMaxOverride else 220 - age
}

data class SpeedBand(val label: String, val fromKmh: Double, val toKmh: Double, val timeMs: Long, val distM: Double)

data class SprintEvent(val t: Long, val maxKmh: Double, val u: Double, val v: Double, val distM: Double)

data class SessionStats(
    val activeMs: Long,
    val distanceM: Double,
    val distanceInFieldM: Double,
    val maxSpeedKmh: Double,
    val avgSpeedKmh: Double,
    val sprints: List<SprintEvent>,
    val hiDistanceM: Double,
    val sprintDistanceM: Double,
    val accelerations: Int,
    /** Part du temps passé dans le terrain (0..1). */
    val presence: Double,
    /** Répartition du temps dans le terrain, grille 3 (longueur) x 3 (largeur), somme = 1. */
    val zones: Array<DoubleArray>,
    val heat: FloatArray,
    val heatHi: FloatArray,
    val bands: List<SpeedBand>,
    /** Distance parcourue par minute active (m/min). */
    val perMinute: List<Double>,
    val hrAvg: Int,
    val hrMax: Int,
    /** Temps passé dans les 5 zones cardio (ms). */
    val hrZonesMs: LongArray,
    val calories: Int,
) {
    companion object {
        const val HX = 32
        const val HY = 20
    }
}

object StatsCalculator {

    fun kcalPerMinute(hr: Int, speedKmh: Double, p: Profile): Double {
        return if (hr > 60) {
            val v = if (p.male) (-55.0969 + 0.6309 * hr + 0.1988 * p.weightKg + 0.2017 * p.age) / 4.184
            else (-20.4022 + 0.4472 * hr - 0.1263 * p.weightKg + 0.074 * p.age) / 4.184
            max(v, 1.0)
        } else {
            val met = if (speedKmh < 1.0) 1.5 else max(2.5, 0.9 * speedKmh + 1.2)
            met * p.weightKg / 60.0
        }
    }

    fun hrZone(hr: Int, p: Profile): Int {
        val pct = hr.toDouble() / p.hrMax
        return when {
            pct < 0.6 -> 0
            pct < 0.7 -> 1
            pct < 0.8 -> 2
            pct < 0.9 -> 3
            else -> 4
        }
    }

    fun compute(s: Session, p: Profile): SessionStats {
        val sport = s.sport
        val pts = s.points
        val hi = sport.hiKmh
        val mu = sport.marginLM / sport.lengthM
        val mv = sport.marginWM / sport.widthM
        val sp = sport.sprintKmh
        val bandsDef = listOf(
            Triple("Arrêt / marche", 0.0, 7.0),
            Triple("Footing", 7.0, hi * 0.72),
            Triple("Course", hi * 0.72, hi),
            Triple("Haute intensité", hi, sp),
            Triple("Sprint", sp, 99.0),
        )
        val bandTime = LongArray(5)
        val bandDist = DoubleArray(5)
        val heat = FloatArray(SessionStats.HX * SessionStats.HY)
        val heatHi = FloatArray(SessionStats.HX * SessionStats.HY)
        val zones = Array(3) { DoubleArray(3) }
        val perMinute = ArrayList<Double>()
        val sprints = ArrayList<SprintEvent>()
        val hrZones = LongArray(5)

        var active = 0L
        var inFieldMs = 0L
        var dist = 0.0
        var distIn = 0.0
        var vmax = 0.0
        var hiDist = 0.0
        var sprintDist = 0.0
        var acc = 0
        var accelState = false
        var inSprint = false
        var sprintStart = 0L
        var sprintMax = 0.0
        var sprintU = 0.0
        var sprintV = 0.0
        var sprintD = 0.0
        var kcal = 0.0
        var hrSum = 0.0
        var hrMs = 0L
        var hrPeak = 0

        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val dt = b.t - a.t
            if (dt <= 0 || dt > 5000) continue
            active += dt
            val d = if (a.speed < 0.4 && b.speed < 0.4) 0.0 else Geo.distance(LatLon(a.lat, a.lon), LatLon(b.lat, b.lon))
            // garde-fou contre les sauts GPS
            val segD = if (d / (dt / 1000.0) > 12.5) a.speed * dt / 1000.0 else d
            dist += segD
            val kmh = a.speed * 3.6
            // vitesse max : 2 mesures de suite au-dessus (évite les pics isolés)
            vmax = max(vmax, min(min(kmh, b.speed * 3.6), 45.0))

            val band = bandsDef.indexOfFirst { kmh >= it.second && kmh < it.third }.let { if (it < 0) 4 else it }
            bandTime[band] += dt
            bandDist[band] += segD
            if (kmh >= hi) hiDist += segD
            if (kmh >= sp) sprintDist += segD

            val minute = (active / 60000L).toInt()
            while (perMinute.size <= minute) perMinute.add(0.0)
            perMinute[minute] = perMinute[minute] + segD

            if (a.inField) {
                inFieldMs += dt
                distIn += segD
                // Zones dans le sens d'attaque de mon équipe (Défense → Attaque)
                val au = s.attackUV(a.t, a.u, a.v)
                val zi = (au[0].coerceIn(0.0, 0.999) * 3).toInt()
                val zj = (au[1].coerceIn(0.0, 0.999) * 3).toInt()
                zones[zi][zj] += dt.toDouble()
            }
            // Carte de chaleur : terrain + zone de jeu autour (tennis, volley…)
            val ue = (a.u + mu) / (1 + 2 * mu)
            val ve = (a.v + mv) / (1 + 2 * mv)
            if (ue in 0.0..0.999 && ve in 0.0..0.999) {
                val hx = (ue * SessionStats.HX).toInt()
                val hy = (ve * SessionStats.HY).toInt()
                heat[hy * SessionStats.HX + hx] += dt.toFloat()
                if (kmh >= hi) heatHi[hy * SessionStats.HX + hx] += dt.toFloat()
            }

            // Sprints (hystérésis 10 %)
            if (!inSprint && kmh >= sp) {
                inSprint = true; sprintStart = a.t; sprintMax = kmh; sprintU = a.u; sprintV = a.v; sprintD = 0.0
            }
            if (inSprint) {
                sprintD += segD
                if (kmh > sprintMax) { sprintMax = kmh; sprintU = a.u; sprintV = a.v }
                if (kmh < sp * 0.9) {
                    inSprint = false
                    if (b.t - sprintStart >= 1000) sprints.add(SprintEvent(sprintStart, sprintMax, sprintU, sprintV, sprintD))
                }
            }

            // Accélérations > 2 m/s²
            val accel = (b.speed - a.speed) / (dt / 1000.0)
            if (!accelState && accel >= 2.0) { acc++; accelState = true }
            if (accelState && accel < 0.5) accelState = false

            if (a.hr > 0) {
                hrSum += a.hr.toDouble() * dt; hrMs += dt; hrPeak = max(hrPeak, a.hr)
                hrZones[hrZone(a.hr, p)] += dt
            }
            kcal += kcalPerMinute(a.hr, kmh, p) * dt / 60000.0
        }
        if (inSprint && sprintD > 0) sprints.add(SprintEvent(sprintStart, sprintMax, sprintU, sprintV, sprintD))

        val zoneTotal = zones.sumOf { it.sum() }
        if (zoneTotal > 0) for (r in zones) for (c in r.indices) r[c] /= zoneTotal

        val moving = bandTime.drop(1).sum()
        val avg = if (moving > 0) (dist - bandDist[0]) / (moving / 1000.0) * 3.6 else 0.0

        return SessionStats(
            activeMs = if (active > 0) active else s.activeMs,
            distanceM = dist,
            distanceInFieldM = distIn,
            maxSpeedKmh = vmax,
            avgSpeedKmh = avg,
            sprints = sprints,
            hiDistanceM = hiDist,
            sprintDistanceM = sprintDist,
            accelerations = acc,
            presence = if (active > 0) inFieldMs.toDouble() / active else 0.0,
            zones = zones,
            heat = blur(heat),
            heatHi = blur(heatHi),
            bands = bandsDef.mapIndexed { i, b -> SpeedBand(b.first, b.second, b.third, bandTime[i], bandDist[i]) },
            perMinute = perMinute,
            hrAvg = if (hrMs > 0) (hrSum / hrMs).toInt() else 0,
            hrMax = hrPeak,
            hrZonesMs = hrZones,
            calories = kcal.toInt(),
        )
    }

    /** Flou 3x3 appliqué deux fois puis normalisation 0..1. */
    private fun blur(src: FloatArray): FloatArray {
        val w = SessionStats.HX
        val h = SessionStats.HY
        var cur = src
        repeat(2) {
            val out = FloatArray(cur.size)
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0f
                var n = 0f
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx
                    val yy = y + dy
                    if (xx in 0 until w && yy in 0 until h) {
                        val k = if (dx == 0 && dy == 0) 4f else if (dx == 0 || dy == 0) 2f else 1f
                        s += cur[yy * w + xx] * k; n += k
                    }
                }
                out[y * w + x] = s / n
            }
            cur = out
        }
        val m = cur.maxOrNull() ?: 0f
        if (m > 0f) for (i in cur.indices) cur[i] /= m
        return cur
    }
}
