package com.philip.fieldtracker.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TrackPoint(
    val t: Long,
    val lat: Double,
    val lon: Double,
    val u: Double,
    val v: Double,
    /** Vitesse lissée (m/s). */
    val speed: Double,
    /** Fréquence cardiaque (bpm), 0 si absente. */
    val hr: Int,
    val inField: Boolean,
)

data class Highlight(val t: Long, val label: String)

data class Session(
    val id: String,
    val sportId: String,
    val startMs: Long,
    val endMs: Long,
    val activeMs: Long,
    val calibration: Calibration,
    val points: List<TrackPoint>,
    val hrSamples: List<Pair<Long, Int>>,
    val highlights: List<Highlight>,
    /** "gps", "import:<fichier>"… */
    val source: String,
    val title: String = "",
) {
    val sport: Sport get() = Sports.byId(sportId) ?: Sports.all.last()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("sportId", sportId); put("start", startMs); put("end", endMs)
        put("active", activeMs); put("source", source); put("title", title)
        put("calibration", calibration.toJson())
        put("points", JSONArray().apply {
            points.forEach {
                put(JSONArray().put(it.t).put(it.lat).put(it.lon).put(round(it.u)).put(round(it.v))
                    .put(round(it.speed)).put(it.hr).put(if (it.inField) 1 else 0))
            }
        })
        put("hr", JSONArray().apply { hrSamples.forEach { put(JSONArray().put(it.first).put(it.second)) } })
        put("highlights", JSONArray().apply { highlights.forEach { put(JSONArray().put(it.t).put(it.label)) } })
    }

    companion object {
        private fun round(d: Double) = Math.round(d * 10000.0) / 10000.0

        fun fromJson(o: JSONObject): Session? {
            val cal = Calibration.fromJson(o.optJSONObject("calibration")) ?: return null
            val pts = o.optJSONArray("points") ?: JSONArray()
            val points = ArrayList<TrackPoint>(pts.length())
            for (i in 0 until pts.length()) {
                val a = pts.getJSONArray(i)
                points.add(
                    TrackPoint(
                        a.getLong(0), a.getDouble(1), a.getDouble(2), a.getDouble(3), a.getDouble(4),
                        a.getDouble(5), a.getInt(6), a.getInt(7) == 1
                    )
                )
            }
            val hrA = o.optJSONArray("hr") ?: JSONArray()
            val hr = (0 until hrA.length()).map { val a = hrA.getJSONArray(it); a.getLong(0) to a.getInt(1) }
            val hlA = o.optJSONArray("highlights") ?: JSONArray()
            val hl = (0 until hlA.length()).map { val a = hlA.getJSONArray(it); Highlight(a.getLong(0), a.getString(1)) }
            return Session(
                id = o.getString("id"),
                sportId = o.getString("sportId"),
                startMs = o.getLong("start"),
                endMs = o.getLong("end"),
                activeMs = o.optLong("active", o.getLong("end") - o.getLong("start")),
                calibration = cal,
                points = points,
                hrSamples = hr,
                highlights = hl,
                source = o.optString("source", "gps"),
                title = o.optString("title", ""),
            )
        }
    }
}

/** Résumé léger pour l'historique. */
data class SessionSummary(
    val id: String,
    val sportId: String,
    val startMs: Long,
    val activeMs: Long,
    val distanceM: Double,
    val maxSpeedKmh: Double,
    val source: String,
)

object SessionStore {
    private fun dir(ctx: Context) = File(ctx.filesDir, "sessions").apply { mkdirs() }
    private fun indexFile(ctx: Context) = File(dir(ctx), "index.json")

    @Synchronized
    fun save(ctx: Context, s: Session, stats: SessionStats) {
        File(dir(ctx), "${s.id}.json").writeText(s.toJson().toString())
        val list = list(ctx).filter { it.id != s.id }.toMutableList()
        list.add(0, SessionSummary(s.id, s.sportId, s.startMs, s.activeMs, stats.distanceM, stats.maxSpeedKmh, s.source))
        writeIndex(ctx, list)
    }

    @Synchronized
    fun load(ctx: Context, id: String): Session? = try {
        val f = File(dir(ctx), "$id.json")
        if (f.exists()) Session.fromJson(JSONObject(f.readText())) else null
    } catch (e: Exception) {
        null
    }

    @Synchronized
    fun delete(ctx: Context, id: String) {
        File(dir(ctx), "$id.json").delete()
        writeIndex(ctx, list(ctx).filter { it.id != id })
    }

    @Synchronized
    fun list(ctx: Context): List<SessionSummary> = try {
        val f = indexFile(ctx)
        if (!f.exists()) emptyList() else {
            val a = JSONArray(f.readText())
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                SessionSummary(
                    o.getString("id"), o.getString("sportId"), o.getLong("start"), o.getLong("active"),
                    o.getDouble("dist"), o.getDouble("vmax"), o.optString("source", "gps")
                )
            }.sortedByDescending { it.startMs }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun writeIndex(ctx: Context, list: List<SessionSummary>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().apply {
                put("id", it.id); put("sportId", it.sportId); put("start", it.startMs); put("active", it.activeMs)
                put("dist", it.distanceM); put("vmax", it.maxSpeedKmh); put("source", it.source)
            })
        }
        indexFile(ctx).writeText(a.toString())
    }
}
