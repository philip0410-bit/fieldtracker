package com.philip.fieldtracker.trackers

import com.philip.fieldtracker.i18n.tr
import com.philip.fieldtracker.i18n.trf
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import com.philip.fieldtracker.data.Calibration
import com.philip.fieldtracker.data.Geo
import com.philip.fieldtracker.data.LatLon
import com.philip.fieldtracker.data.Session
import com.philip.fieldtracker.data.Sport
import com.philip.fieldtracker.data.TrackPoint
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Import des exports de traqueurs GPS (STATSports APEX via Sonra/export CSV,
 * MyCoach, Catapult, Polar, Garmin…) : CSV, GPX ou TCX.
 */
object TrackerImport {

    data class RawPoint(val t: Long, val lat: Double, val lon: Double, val speed: Double?, val hr: Int)

    class ImportException(msg: String) : Exception(msg)

    fun fileName(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) return c.getString(i) ?: "fichier"
        }
        return uri.lastPathSegment ?: "fichier"
    }

    fun read(ctx: Context, uri: Uri): Pair<String, List<RawPoint>> {
        val name = fileName(ctx, uri)
        val pts = ctx.contentResolver.openInputStream(uri)?.use { input ->
            val lower = name.lowercase()
            when {
                lower.endsWith(".gpx") || lower.endsWith(".tcx") || lower.endsWith(".xml") -> parseXml(input)
                lower.endsWith(".fit") -> throw ImportException(tr("Format .fit non pris en charge : exporte en CSV, GPX ou TCX."))
                else -> parseCsv(input.bufferedReader().readText())
            }
        } ?: throw ImportException(tr("Impossible d'ouvrir le fichier."))
        if (pts.size < 10) throw ImportException(trf("Aucune position GPS exploitable trouvée dans « %s ».", name))
        return name to pts.sortedBy { it.t }
    }

    // ---------------------------------------------------------------- CSV
    private fun parseCsv(text: String): List<RawPoint> {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return emptyList()
        val sep = listOf(';', ',', '\t').maxByOrNull { c -> lines.first().count { it == c } } ?: ','
        // Cherche la ligne d'en-tête (certains exports ont des lignes d'info avant)
        var headerIdx = -1
        var cols: List<String> = emptyList()
        for (i in 0 until minOf(lines.size, 30)) {
            val c = split(lines[i], sep).map { it.lowercase().trim('"', ' ') }
            if (c.any { it.contains("lat") } && c.any { it.contains("lon") || it.contains("lng") }) {
                headerIdx = i; cols = c; break
            }
        }
        if (headerIdx < 0) throw ImportException(tr("Colonnes latitude / longitude introuvables dans le CSV."))
        fun find(vararg keys: String) = cols.indexOfFirst { h -> keys.any { k -> h.contains(k) } }
        val iLat = find("lat")
        val iLon = cols.indexOfFirst { it.contains("lon") || it.contains("lng") }
        val iTime = find("timestamp", "time", "date", "heure", "temps", "elapsed", "seconds")
        val iSpeed = find("speed", "vitesse", "velocity")
        val iHr = find("heart", "hr", "bpm", "fc", "cardio")
        val speedKmh = iSpeed >= 0 && (cols[iSpeed].contains("km") || cols[iSpeed].contains("kph"))

        val out = ArrayList<RawPoint>()
        var idx = 0L
        val base = System.currentTimeMillis()
        for (i in headerIdx + 1 until lines.size) {
            val c = split(lines[i], sep)
            val lat = c.getOrNull(iLat)?.num() ?: continue
            val lon = c.getOrNull(iLon)?.num() ?: continue
            if (lat == 0.0 && lon == 0.0) continue
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            val t = c.getOrNull(iTime)?.let { parseTime(it) } ?: (base + idx * 1000)
            val spd = if (iSpeed >= 0) c.getOrNull(iSpeed)?.num()?.let { if (speedKmh) it / 3.6 else it } else null
            val hr = if (iHr >= 0) c.getOrNull(iHr)?.num()?.toInt() ?: 0 else 0
            out.add(RawPoint(t, lat, lon, spd, hr))
            idx++
        }
        // Temps relatif (en secondes) -> horodatage absolu
        if (out.isNotEmpty() && out.first().t < 1_000_000_000_000L) {
            val start = base - (out.last().t - out.first().t)
            return out.map { it.copy(t = start + (it.t - out.first().t)) }
        }
        return out
    }

    private fun split(line: String, sep: Char): List<String> {
        val res = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        for (ch in line) {
            when {
                ch == '"' -> quoted = !quoted
                ch == sep && !quoted -> { res.add(sb.toString()); sb.clear() }
                else -> sb.append(ch)
            }
        }
        res.add(sb.toString())
        return res
    }

    private fun String.num(): Double? = trim().trim('"').replace(',', '.').toDoubleOrNull()

    /** Accepte ISO 8601, epoch (s ou ms), "HH:mm:ss(.SSS)" ou un nombre de secondes écoulées. */
    private fun parseTime(raw: String): Long? {
        val s = raw.trim().trim('"')
        if (s.isEmpty()) return null
        s.toDoubleOrNull()?.let { n ->
            return when {
                n > 1e12 -> n.toLong()                 // epoch ms
                n > 1e9 -> (n * 1000).toLong()         // epoch s
                else -> (n * 1000).toLong()            // secondes écoulées (relatif)
            }
        }
        try { return Instant.parse(s).toEpochMilli() } catch (_: Exception) {}
        try { return OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (_: Exception) {}
        try {
            return LocalDateTime.parse(s.replace(' ', 'T')).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Exception) {}
        try {
            val lt = LocalTime.parse(s)
            return LocalDate.now().atTime(lt).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Exception) {}
        return null
    }

    // ---------------------------------------------------------------- GPX / TCX
    private fun parseXml(input: InputStream): List<RawPoint> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val out = ArrayList<RawPoint>()
        var lat: Double? = null
        var lon: Double? = null
        var time: Long? = null
        var hr = 0
        var speed: Double? = null
        var inPoint = false
        var inHrTag = false
        var tag = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name.substringAfter(':').lowercase()
                    when (tag) {
                        "trkpt" -> {
                            inPoint = true; hr = 0; speed = null; time = null
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        }
                        "trackpoint" -> {
                            inPoint = true; hr = 0; speed = null; time = null; lat = null; lon = null
                        }
                        "heartratebpm" -> inHrTag = true
                    }
                }
                XmlPullParser.TEXT -> if (inPoint) {
                    val txt = parser.text.trim()
                    if (txt.isNotEmpty()) when (tag) {
                        "time" -> time = parseTime(txt)
                        "latitudedegrees" -> lat = txt.toDoubleOrNull()
                        "longitudedegrees" -> lon = txt.toDoubleOrNull()
                        "hr", "heartrate" -> hr = txt.toDoubleOrNull()?.toInt() ?: hr
                        "value" -> if (inHrTag) hr = txt.toDoubleOrNull()?.toInt() ?: hr
                        "speed" -> speed = txt.toDoubleOrNull()
                    }
                }
                XmlPullParser.END_TAG -> {
                    val end = parser.name.substringAfter(':').lowercase()
                    if (end == "heartratebpm") inHrTag = false
                    if ((end == "trkpt" || end == "trackpoint") && inPoint) {
                        val la = lat; val lo = lon; val t = time
                        if (la != null && lo != null && t != null) out.add(RawPoint(t, la, lo, speed, hr))
                        inPoint = false
                    }
                    tag = ""
                }
            }
            parser.next()
        }
        return out
    }

    // ---------------------------------------------------------------- Conversion en séance
    /**
     * Construit une séance à partir des points importés. Utilise le balisage fourni
     * ou, à défaut, estime le terrain (plus petit rectangle englobant).
     */
    fun toSession(sport: Sport, name: String, raw: List<RawPoint>, cal: Calibration?): Session {
        val calibration = cal ?: run {
            val corners = Geo.minAreaRect(raw.map { LatLon(it.lat, it.lon) })
                ?: throw ImportException(tr("Impossible d'estimer le terrain."))
            Calibration(corners, estimated = true)
        }
        val pts = ArrayList<TrackPoint>(raw.size)
        var smooth = 0.0
        for (i in raw.indices) {
            val r = raw[i]
            val v = r.speed ?: if (i > 0) {
                val p = raw[i - 1]
                val dt = (r.t - p.t) / 1000.0
                if (dt > 0) Geo.distance(LatLon(p.lat, p.lon), LatLon(r.lat, r.lon)) / dt else 0.0
            } else 0.0
            smooth = if (i == 0) v else if (r.speed != null) v else smooth * 0.45 + v * 0.55
            val uv = calibration.toUV(LatLon(r.lat, r.lon))
            pts.add(TrackPoint(r.t, r.lat, r.lon, uv[0], uv[1], smooth.coerceIn(0.0, 12.5), r.hr, calibration.isInside(uv)))
        }
        val hr = raw.filter { it.hr in 30..240 }.map { it.t to it.hr }
        return Session(
            id = UUID.randomUUID().toString(),
            sportId = sport.id,
            startMs = raw.first().t,
            endMs = raw.last().t,
            activeMs = raw.last().t - raw.first().t,
            calibration = calibration,
            points = pts,
            hrSamples = hr,
            highlights = emptyList(),
            source = "import:$name",
        )
    }
}
