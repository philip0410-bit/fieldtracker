package com.philip.fieldtracker.wear

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LL(val lat: Double, val lon: Double)

/** Calculs de terrain sur la montre (mêmes formules que le téléphone). */
object WatchGeo {
    private const val R = 6_371_000.0

    fun distance(a: LL, b: LL): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * atan2(sqrt(h), sqrt(1 - h))
    }

    fun toLocal(o: LL, p: LL): DoubleArray = doubleArrayOf(
        Math.toRadians(p.lon - o.lon) * R * cos(Math.toRadians(o.lat)),
        Math.toRadians(p.lat - o.lat) * R,
    )

    private fun homography(src: List<DoubleArray>, dst: List<DoubleArray>): DoubleArray {
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = src[i][0]; val y = src[i][1]; val u = dst[i][0]; val v = dst[i][1]
            a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        for (col in 0 until 8) {
            var piv = col
            for (r in col + 1 until 8) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
            val t = a[col]; a[col] = a[piv]; a[piv] = t
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

    /** Terrain balisé : convertit une position GPS en coordonnées terrain (u, v) dans [0..1]. */
    class Field(val corners: List<LL>, sportLonger: Boolean, val marginLM: Double, val marginWM: Double) {
        val c: List<LL>
        val lengthM: Double
        val widthM: Double
        private val origin: LL
        private val h: DoubleArray

        init {
            val l0 = (distance(corners[0], corners[1]) + distance(corners[3], corners[2])) / 2
            val w0 = (distance(corners[1], corners[2]) + distance(corners[0], corners[3])) / 2
            // Le premier côté doit être la longueur du terrain
            c = if (sportLonger && l0 < w0) listOf(corners[1], corners[2], corners[3], corners[0]) else corners
            lengthM = (distance(c[0], c[1]) + distance(c[3], c[2])) / 2
            widthM = (distance(c[1], c[2]) + distance(c[0], c[3])) / 2
            origin = c[0]
            h = affineFit(
                c.map { toLocal(origin, it) },
                listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 0.0), doubleArrayOf(1.0, 1.0), doubleArrayOf(0.0, 1.0)),
            )
        }

        fun uv(p: LL): DoubleArray {
            val l = toLocal(origin, p)
            val w = h[6] * l[0] + h[7] * l[1] + h[8]
            return doubleArrayOf((h[0] * l[0] + h[1] * l[1] + h[2]) / w, (h[3] * l[0] + h[4] * l[1] + h[5]) / w)
        }

        fun near(uv: DoubleArray, extraM: Double): Boolean {
            val tu = (marginLM + extraM) / lengthM.coerceAtLeast(1.0)
            val tv = (marginWM + extraM) / widthM.coerceAtLeast(1.0)
            return uv[0] >= -tu && uv[0] <= 1 + tu && uv[1] >= -tv && uv[1] <= 1 + tv
        }

        fun inside(uv: DoubleArray): Boolean {
            val tu = (marginLM + 1.5) / lengthM.coerceAtLeast(1.0)
            val tv = (marginWM + 1.5) / widthM.coerceAtLeast(1.0)
            return uv[0] >= -tu && uv[0] <= 1 + tu && uv[1] >= -tv && uv[1] <= 1 + tv
        }
    }
}
