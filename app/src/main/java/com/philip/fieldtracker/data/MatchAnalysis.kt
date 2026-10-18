package com.philip.fieldtracker.data

/** Statistiques selon l'état du score (je mène / égalité / je suis mené). */
data class StateStats(
    val state: Int, // 1 = mène, 0 = égalité, -1 = mené
    val timeMs: Long,
    val distM: Double,
    val hiDistM: Double,
    /** Position moyenne vers l'attaque (0 = ma ligne de but, 1 = ligne adverse). */
    val avgU: Double,
    /** Part du temps dans le tiers offensif. */
    val offThird: Double,
    /** Part du temps dans le tiers défensif. */
    val defThird: Double,
) {
    val mPerMin: Double get() = if (timeMs > 0) distM / (timeMs / 60000.0) else 0.0
    val hiPerMin: Double get() = if (timeMs > 0) hiDistM / (timeMs / 60000.0) else 0.0
}

data class GoalEvent(val minute: Int, val team: Int, val us: Int, val them: Int)

data class PositionStats(
    val position: PlayPosition,
    /** Part du temps (dans le terrain) passée dans la zone habituelle du poste. */
    val inZone: Double,
    val avgU: Double,
    val avgV: Double,
    val mPerMin: Double,
    /** Part du temps à gauche / dans l'axe / à droite. */
    val left: Double, val axis: Double, val right: Double,
)

data class MatchStats(
    val scoreUs: Int,
    val scoreThem: Int,
    val goals: List<GoalEvent>,
    val states: List<StateStats>,
    val position: PositionStats?,
    /** Position moyenne sur tout le match (sens d'attaque). */
    val avgU: Double,
    val avgV: Double,
)

object MatchAnalysis {

    fun compute(s: Session, st: SessionStats): MatchStats {
        val sport = s.sport
        val goals = s.goals.sortedBy { it.t }
        val pts = s.points
        val hi = sport.hiKmh

        // Temps actif écoulé à chaque instant (pour la minute des buts)
        fun activeAt(t: Long): Long {
            var acc = 0L
            for (i in 1 until pts.size) {
                if (pts[i].t > t) break
                val dt = pts[i].t - pts[i - 1].t
                if (dt in 1..5000) acc += dt
            }
            return acc
        }
        var us = 0; var them = 0
        val events = goals.map { g ->
            if (g.team == 0) us++ else them++
            GoalEvent((activeAt(g.t) / 60000L).toInt() + 1, g.team, us, them)
        }

        val time = LongArray(3); val dist = DoubleArray(3); val hiD = DoubleArray(3)
        val uSum = DoubleArray(3); val off = LongArray(3); val def = LongArray(3)
        var inZone = 0L; var inFieldMs = 0L
        var su = 0.0; var sv = 0.0
        var left = 0L; var axis = 0L; var right = 0L
        val pos = s.position
        var gi = 0; var a0 = 0; var b0 = 0

        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val dt = b.t - a.t
            if (dt <= 0 || dt > 5000) continue
            while (gi < goals.size && goals[gi].t <= a.t) {
                if (goals[gi].team == 0) a0++ else b0++
                gi++
            }
            val state = Integer.signum(a0 - b0) // 1, 0, -1
            val k = state + 1
            val d = if (a.speed < 0.4) 0.0 else Geo.distance(LatLon(a.lat, a.lon), LatLon(b.lat, b.lon)).coerceAtMost(12.0 * dt / 1000.0)
            time[k] += dt; dist[k] += d
            if (a.speed * 3.6 >= hi) hiD[k] += d
            if (!a.inField) continue
            val au = s.attackUV(a.t, a.u, a.v)
            val u = au[0].coerceIn(0.0, 1.0); val v = au[1].coerceIn(0.0, 1.0)
            inFieldMs += dt
            uSum[k] += u * dt
            if (u >= 2.0 / 3) off[k] += dt
            if (u < 1.0 / 3) def[k] += dt
            su += u * dt; sv += v * dt
            when {
                v < 1.0 / 3 -> left += dt
                v < 2.0 / 3 -> axis += dt
                else -> right += dt
            }
            if (pos != null && u in pos.uMin..pos.uMax && v in pos.vMin..pos.vMax) inZone += dt
        }

        // Temps dans le terrain par état (pour les moyennes)
        val inFieldByState = LongArray(3)
        run {
            var g = 0; var x = 0; var y = 0
            for (i in 1 until pts.size) {
                val a = pts[i - 1]; val dt = pts[i].t - a.t
                if (dt <= 0 || dt > 5000 || !a.inField) continue
                while (g < goals.size && goals[g].t <= a.t) { if (goals[g].team == 0) x++ else y++; g++ }
                inFieldByState[Integer.signum(x - y) + 1] += dt
            }
        }
        val states = listOf(1, 0, -1).mapNotNull { state ->
            val k = state + 1
            if (time[k] <= 0) null else {
                val f = inFieldByState[k].coerceAtLeast(1)
                StateStats(state, time[k], dist[k], hiD[k], uSum[k] / f, off[k].toDouble() / f, def[k].toDouble() / f)
            }
        }
        val total = inFieldMs.coerceAtLeast(1)
        val activeMin = (st.activeMs / 60000.0).coerceAtLeast(0.1)
        return MatchStats(
            scoreUs = us, scoreThem = them, goals = events, states = states,
            position = pos?.let {
                PositionStats(
                    it, inZone.toDouble() / total, su / total, sv / total, st.distanceM / activeMin,
                    left.toDouble() / total, axis.toDouble() / total, right.toDouble() / total,
                )
            },
            avgU = su / total, avgV = sv / total,
        )
    }
}
