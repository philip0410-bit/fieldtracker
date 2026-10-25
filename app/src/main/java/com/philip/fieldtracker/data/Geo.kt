package com.philip.fieldtracker.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

object Geo {
    const val R = 6_371_000.0

    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val la1 = Math.toRadians(a.lat)
        val la2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(la1) * cos(la2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Projection locale plane (mètres) autour d'une origine : x = est, y = nord. */
    fun toLocal(origin: LatLon, p: LatLon): DoubleArray {
        val x = Math.toRadians(p.lon - origin.lon) * R * cos(Math.toRadians(origin.lat))
        val y = Math.toRadians(p.lat - origin.lat) * R
        return doubleArrayOf(x, y)
    }

    fun fromLocal(origin: LatLon, x: Double, y: Double): LatLon {
        val lat = origin.lat + Math.toDegrees(y / R)
        val lon = origin.lon + Math.toDegrees(x / (R * cos(Math.toRadians(origin.lat))))
        return LatLon(lat, lon)
    }

    /**
     * Homographie 3x3 (h33 = 1) qui envoie src[i] -> dst[i] pour 4 points.
     * Résolution d'un système 8x8 par élimination de Gauss.
     */
    fun homography(src: List<DoubleArray>, dst: List<DoubleArray>): DoubleArray {
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = src[i][0]; val y = src[i][1]
            val u = dst[i][0]; val v = dst[i][1]
            a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        for (col in 0 until 8) {
            var piv = col
            for (r in col + 1 until 8) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
            val tmp = a[col]; a[col] = a[piv]; a[piv] = tmp
            val d = a[col][col]
            if (abs(d) < 1e-12) continue
            for (c in col until 9) a[col][c] /= d
            for (r in 0 until 8) {
                if (r == col) continue
                val f = a[r][col]
                if (f == 0.0) continue
                for (c in col until 9) a[r][c] -= f * a[col][c]
            }
        }
        return DoubleArray(9) { if (it < 8) a[it][8] else 1.0 }
    }

    /**
     * Transformation affine (moindres carrés) des 4 coins vers le carré unité.
     * Contrairement à une homographie, elle ne peut jamais « projeter à l'infini »
     * des positions quand les coins sont imprécis (GPS de montre).
     */
    fun affineFit(src: List<DoubleArray>, dst: List<DoubleArray>): DoubleArray {
        val m = Array(3) { DoubleArray(3) }
        val bu = DoubleArray(3); val bv = DoubleArray(3)
        for (i in src.indices) {
            val r = doubleArrayOf(src[i][0], src[i][1], 1.0)
            for (a in 0 until 3) {
                for (b in 0 until 3) m[a][b] += r[a] * r[b]
                bu[a] += r[a] * dst[i][0]; bv[a] += r[a] * dst[i][1]
            }
        }
        val pu = solve3(m, bu); val pv = solve3(m, bv)
        return doubleArrayOf(pu[0], pu[1], pu[2], pv[0], pv[1], pv[2], 0.0, 0.0, 1.0)
    }

    private fun solve3(m: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        fun det(a: Array<DoubleArray>) =
            a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
                a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) +
                a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
        val d = det(m)
        if (abs(d) < 1e-12) return doubleArrayOf(0.0, 0.0, 0.0)
        return DoubleArray(3) { k -> det(Array(3) { r -> DoubleArray(3) { c -> if (c == k) b[r] else m[r][c] } }) / d }
    }

    fun applyH(h: DoubleArray, x: Double, y: Double): DoubleArray {
        val w = h[6] * x + h[7] * y + h[8]
        return doubleArrayOf((h[0] * x + h[1] * y + h[2]) / w, (h[3] * x + h[4] * y + h[5]) / w)
    }

    /**
     * Rectangle orienté d'aire minimale contenant tous les points (terrain estimé
     * quand on importe un fichier sans balisage). Renvoie 4 coins dans l'ordre.
     */
    fun minAreaRect(points: List<LatLon>): List<LatLon>? {
        if (points.size < 4) return null
        val origin = points.first()
        val pts = points.map { toLocal(origin, it) }
        var best: DoubleArray? = null
        var bestArea = Double.MAX_VALUE
        var bestAngle = 0.0
        var a = 0.0
        while (a < Math.PI / 2) {
            val c = cos(a); val s = sin(a)
            var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
            var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
            for (p in pts) {
                val rx = p[0] * c + p[1] * s
                val ry = -p[0] * s + p[1] * c
                if (rx < minX) minX = rx; if (rx > maxX) maxX = rx
                if (ry < minY) minY = ry; if (ry > maxY) maxY = ry
            }
            val area = (maxX - minX) * (maxY - minY)
            if (area < bestArea) {
                bestArea = area; best = doubleArrayOf(minX, maxX, minY, maxY); bestAngle = a
            }
            a += Math.toRadians(1.0)
        }
        val b = best ?: return null
        val c = cos(bestAngle); val s = sin(bestAngle)
        fun back(rx: Double, ry: Double): LatLon = fromLocal(origin, rx * c - ry * s, rx * s + ry * c)
        // Agrandit de 2 m pour garder les points en bordure dans le terrain
        val m = 2.0
        val minX = b[0] - m; val maxX = b[1] + m; val minY = b[2] - m; val maxY = b[3] + m
        val corners = if (maxX - minX >= maxY - minY) {
            listOf(back(minX, maxY), back(maxX, maxY), back(maxX, minY), back(minX, minY))
        } else {
            listOf(back(maxX, maxY), back(maxX, minY), back(minX, minY), back(minX, maxY))
        }
        return corners
    }
}

/**
 * Terrain balisé : 4 coins GPS dans l'ordre C1 (haut-gauche), C2 (haut-droit),
 * C3 (bas-droit), C4 (bas-gauche) tels qu'affichés à l'écran.
 * Convertit une position GPS en coordonnées terrain (u, v) dans [0..1].
 */
class Calibration(val corners: List<LatLon>, val estimated: Boolean = false) {
    val origin: LatLon = corners[0]
    private val local = corners.map { Geo.toLocal(origin, it) }
    private val h: DoubleArray = Geo.affineFit(
        local,
        listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 0.0), doubleArrayOf(1.0, 1.0), doubleArrayOf(0.0, 1.0))
    )

    val lengthM: Double = (Geo.distance(corners[0], corners[1]) + Geo.distance(corners[3], corners[2])) / 2
    val widthM: Double = (Geo.distance(corners[1], corners[2]) + Geo.distance(corners[0], corners[3])) / 2
    val areaM2: Double get() = lengthM * widthM

    /** Écart relatif entre les deux diagonales (0 = rectangle parfait). */
    val diagonalGap: Double
        get() {
            val d1 = Geo.distance(corners[0], corners[2])
            val d2 = Geo.distance(corners[1], corners[3])
            return abs(d1 - d2) / maxOf(d1, d2)
        }

    fun toUV(p: LatLon): DoubleArray {
        val l = Geo.toLocal(origin, p)
        return Geo.applyH(h, l[0], l[1])
    }

    /** Dans le terrain, avec une tolérance en mètres. */
    fun isInside(uv: DoubleArray, toleranceM: Double = 1.5, toleranceWM: Double = toleranceM): Boolean {
        val tu = toleranceM / lengthM.coerceAtLeast(1.0)
        val tv = toleranceWM / widthM.coerceAtLeast(1.0)
        return uv[0] >= -tu && uv[0] <= 1 + tu && uv[1] >= -tv && uv[1] <= 1 + tv
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("estimated", estimated)
        put("corners", JSONArray().apply {
            corners.forEach { put(JSONArray().put(it.lat).put(it.lon)) }
        })
    }

    companion object {
        fun fromJson(o: JSONObject?): Calibration? {
            if (o == null) return null
            val arr = o.optJSONArray("corners") ?: return null
            if (arr.length() != 4) return null
            val list = (0 until 4).map { val p = arr.getJSONArray(it); LatLon(p.getDouble(0), p.getDouble(1)) }
            return Calibration(list, o.optBoolean("estimated", false))
        }

        /**
         * Construit la calibration en remettant les coins dans un ordre cohérent :
         * le premier côté (C1->C2) doit être la longueur du terrain.
         */
        fun normalized(raw: List<LatLon>, sportIsLonger: Boolean): Calibration {
            val c = Calibration(raw)
            return if (sportIsLonger && c.lengthM < c.widthM) {
                Calibration(listOf(raw[1], raw[2], raw[3], raw[0]))
            } else c
        }
    }
}
