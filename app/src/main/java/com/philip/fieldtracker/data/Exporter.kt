package com.philip.fieldtracker.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant

object Exporter {

    fun dir(ctx: Context) = File(ctx.cacheDir, "exports").apply { mkdirs() }

    fun baseName(s: Session): String {
        val d = java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.FRANCE).format(java.util.Date(s.startMs))
        return "FieldTracker_${s.sportId}_$d"
    }

    fun csv(ctx: Context, s: Session, st: SessionStats): File {
        val f = File(dir(ctx), baseName(s) + ".csv")
        f.bufferedWriter().use { w ->
            w.write("# Sport;${s.sport.name}\n")
            w.write("# Terrain (m);${"%.1f".format(s.calibration.lengthM)} x ${"%.1f".format(s.calibration.widthM)}\n")
            w.write("# Distance (m);${"%.0f".format(st.distanceM)}\n")
            w.write("# Vitesse max (km/h);${"%.1f".format(st.maxSpeedKmh)}\n")
            w.write("# Sprints;${st.sprints.size}\n")
            w.write("# Presence terrain (%);${"%.0f".format(st.presence * 100)}\n")
            w.write("time;latitude;longitude;u;v;speed_kmh;heart_rate;in_field\n")
            s.points.forEach {
                w.write(
                    "${Instant.ofEpochMilli(it.t)};${it.lat};${it.lon};${"%.4f".format(java.util.Locale.US, it.u)};" +
                        "${"%.4f".format(java.util.Locale.US, it.v)};${"%.2f".format(java.util.Locale.US, it.speed * 3.6)};${it.hr};${if (it.inField) 1 else 0}\n"
                )
            }
        }
        return f
    }

    fun gpx(ctx: Context, s: Session): File {
        val f = File(dir(ctx), baseName(s) + ".gpx")
        f.bufferedWriter().use { w ->
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            w.write("<gpx version=\"1.1\" creator=\"FieldTracker\" xmlns=\"http://www.topografix.com/GPX/1/1\" ")
            w.write("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n")
            w.write("<trk><name>${s.sport.name}</name><trkseg>\n")
            s.points.forEach {
                w.write("<trkpt lat=\"${it.lat}\" lon=\"${it.lon}\"><time>${Instant.ofEpochMilli(it.t)}</time>")
                if (it.hr > 0) w.write("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>${it.hr}</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>")
                w.write("</trkpt>\n")
            }
            w.write("</trkseg></trk></gpx>\n")
        }
        return f
    }

    fun share(ctx: Context, files: List<File>) {
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it) })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "Exporter la séance").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
