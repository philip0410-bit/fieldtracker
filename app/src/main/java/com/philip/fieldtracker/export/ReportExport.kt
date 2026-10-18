package com.philip.fieldtracker.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.philip.fieldtracker.R
import com.philip.fieldtracker.data.Exporter
import com.philip.fieldtracker.data.Session
import com.philip.fieldtracker.data.SessionStats
import com.philip.fieldtracker.data.Sport
import com.philip.fieldtracker.i18n.I18n
import com.philip.fieldtracker.i18n.tr
import com.philip.fieldtracker.i18n.trf
import com.philip.fieldtracker.ui.components.fieldRect
import com.philip.fieldtracker.ui.field.drawFieldMarkings
import com.philip.fieldtracker.ui.field.outerRect
import com.philip.fieldtracker.ui.field.templateSize
import com.philip.fieldtracker.ui.screens.thermal
import com.philip.fieldtracker.ui.screens.thermalImage
import com.philip.fieldtracker.ui.theme.Palette
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/** Formats proposés au partage. */
enum class ExportKind(val label: String, val desc: String, val ext: String, val mime: String) {
    SUMMARY("Résumé visuel (image)", "Terrain avec déplacements, carte de chaleur et statistiques sur une image", "png", "image/png"),
    MOVES("Carte des déplacements (image)", "Ta trace sur le terrain, colorée selon la vitesse, en haute résolution", "png", "image/png"),
    HEAT("Carte de chaleur (image)", "Les zones où tu as passé le plus de temps", "png", "image/png"),
    PDF("Rapport PDF", "Résumé + carte des déplacements, prêt à imprimer", "pdf", "application/pdf"),
    XLSX("Données Excel (.xlsx)", "Toutes les positions (X/Y en mètres sur le terrain), vitesses, FC, zones, sprints", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    CSV("Données CSV", "Positions brutes, lisibles par tous les tableurs", "csv", "text/csv"),
    GPX("Trace GPX", "Pour Strava, Garmin, Google Earth…", "gpx", "application/gpx+xml"),
}

object ReportExport {

    // ------------------------------------------------------------------ génération

    fun build(ctx: Context, kinds: Set<ExportKind>, s: Session, st: SessionStats, p: Palette): List<File> {
        val base = Exporter.baseName(s)
        val dir = Exporter.dir(ctx)
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000) it.delete() }
        val out = ArrayList<File>()
        val fonts = Fonts(ctx)
        for (k in ExportKind.entries) {
            if (k !in kinds) continue
            val f = when (k) {
                ExportKind.SUMMARY -> png(File(dir, "${base}_resume.png"), summary(s, st, p, fonts))
                ExportKind.MOVES -> png(File(dir, "${base}_deplacements.png"), fieldMap(s, st, p, fonts, heat = false))
                ExportKind.HEAT -> png(File(dir, "${base}_chaleur.png"), fieldMap(s, st, p, fonts, heat = true))
                ExportKind.PDF -> pdf(File(dir, "$base.pdf"), listOf(summary(s, st, p, fonts), fieldMap(s, st, p, fonts, heat = false)))
                ExportKind.XLSX -> File(dir, "$base.xlsx").also { xlsx(it, s, st) }
                ExportKind.CSV -> Exporter.csv(ctx, s, st)
                ExportKind.GPX -> Exporter.gpx(ctx, s)
            }
            out.add(f)
        }
        return out
    }

    fun mime(f: File): String = ExportKind.entries.firstOrNull { f.name.endsWith("." + it.ext) }?.mime ?: "*/*"

    fun share(ctx: Context, files: List<File>) {
        if (files.isEmpty()) return
        val uris = files.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it) }
        val types = files.map { mime(it) }.distinct()
        val type = if (types.size == 1) types[0] else if (types.all { it.startsWith("image/") }) "image/*" else "*/*"
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris)) }
        }.apply {
            this.type = type
            clipData = android.content.ClipData.newRawUri("", uris[0]).also { c -> uris.drop(1).forEach { c.addItem(android.content.ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, tr("Partager la séance")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Copie dans Téléchargements/FieldTracker. Renvoie le nombre de fichiers enregistrés. */
    @RequiresApi(29)
    fun saveToDownloads(ctx: Context, files: List<File>): Int {
        var n = 0
        for (f in files) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, mime(f))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FieldTracker")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: continue
            ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
            n++
        }
        return n
    }

    // ------------------------------------------------------------------ Excel

    private fun r1(d: Double) = Math.round(d * 10) / 10.0
    private fun r2(d: Double) = Math.round(d * 100) / 100.0

    fun xlsx(file: File, s: Session, st: SessionStats) {
        val sport = s.sport
        val L = s.calibration.lengthM
        val W = s.calibration.widthM
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", I18n.locale)
        val tf = SimpleDateFormat("HH:mm:ss", I18n.locale)
        val x = XlsxWriter()

        // Résumé
        val sum = ArrayList<List<Any?>>()
        sum.add(listOf(tr("Indicateur"), tr("Valeur"), tr("Unité")))
        sum.add(listOf(tr("Sport"), tr(sport.name), null))
        val m = com.philip.fieldtracker.data.MatchAnalysis.compute(s, st)
        s.position?.let { sum.add(listOf(tr("Ton poste"), tr(it.name), null)) }
        if (s.goals.isNotEmpty()) sum.add(listOf(tr("Score"), "${m.scoreUs} – ${m.scoreThem}", null))
        m.position?.let {
            sum.add(listOf(tr("Dans la zone du poste"), r1(it.inZone * 100), "%"))
            sum.add(listOf(tr("Position moyenne"), r1(it.avgU * 100), "%"))
        }
        sum.add(listOf(tr("Date"), df.format(Date(s.startMs)), null))
        sum.add(listOf(tr("Durée active"), r1(st.activeMs / 60000.0), "min"))
        sum.add(listOf(tr("Terrain (longueur)"), r1(L), "m"))
        sum.add(listOf(tr("Terrain (largeur)"), r1(W), "m"))
        sum.add(listOf(tr("Distance"), Math.round(st.distanceM), "m"))
        sum.add(listOf(tr("Distance dans le terrain"), Math.round(st.distanceInFieldM), "m"))
        sum.add(listOf(tr("Vitesse max"), r1(st.maxSpeedKmh), "km/h"))
        sum.add(listOf(tr("Vitesse moyenne en mouvement"), r1(st.avgSpeedKmh), "km/h"))
        sum.add(listOf(tr("Sprints"), st.sprints.size, null))
        sum.add(listOf(tr("Distance en sprint"), Math.round(st.sprintDistanceM), "m"))
        sum.add(listOf(tr("Distance haute intensité"), Math.round(st.hiDistanceM), "m"))
        sum.add(listOf(tr("Accélérations"), st.accelerations, null))
        sum.add(listOf(tr("Présence dans le terrain"), r1(st.presence * 100), "%"))
        sum.add(listOf(tr("FC moyenne"), if (st.hrAvg > 0) st.hrAvg else null, "bpm"))
        sum.add(listOf(tr("FC max"), if (st.hrMax > 0) st.hrMax else null, "bpm"))
        sum.add(listOf(tr("Calories"), st.calories, "kcal"))
        sum.add(listOf(tr("Source"), if (s.source.startsWith("import:")) s.source.removePrefix("import:") else "GPS", null))
        x.sheet(tr("Résumé"), sum, listOf(34.0, 22.0, 10.0))

        // Déplacements (le plus important) : position en mètres sur le terrain
        val pts = ArrayList<List<Any?>>(s.points.size + 1)
        pts.add(listOf(
            tr("Temps (s)"), tr("Heure"), tr("X le long du terrain (m)"), tr("Y en largeur (m)"),
            tr("Vitesse (km/h)"), tr("FC (bpm)"), tr("Dans le terrain"), tr("Zone"), "Latitude", "Longitude",
        ))
        val t0 = s.points.firstOrNull()?.t ?: s.startMs
        for (p in s.points) {
            val zone = if (p.inField) {
                val zi = (p.u.coerceIn(0.0, 0.999) * 3).toInt()
                val zj = (p.v.coerceIn(0.0, 0.999) * 3).toInt()
                "${sport.zoneLabelsLength()[zi]} · ${sport.zoneLabelsWidth()[zj]}"
            } else tr("Hors terrain")
            pts.add(listOf(
                r1((p.t - t0) / 1000.0), tf.format(Date(p.t)), r2(p.u * L), r2(p.v * W),
                r1(p.speed * 3.6), if (p.hr > 0) p.hr else null, if (p.inField) tr("Oui") else tr("Non"), zone,
                p.lat, p.lon,
            ))
        }
        x.sheet(tr("Déplacements"), pts, listOf(10.0, 10.0, 22.0, 18.0, 14.0, 10.0, 14.0, 22.0, 13.0, 13.0))

        // Par minute
        val pm = ArrayList<List<Any?>>()
        pm.add(listOf(tr("Minute"), tr("Distance (m)"), tr("Vitesse moyenne (km/h)")))
        st.perMinute.forEachIndexed { i, d -> pm.add(listOf(i + 1, Math.round(d), r1(d / 60.0 * 3.6))) }
        x.sheet(tr("Par minute"), pm, listOf(10.0, 14.0, 22.0))

        // Zones de présence 3 x 3
        val z = ArrayList<List<Any?>>()
        z.add(listOf(tr("Zone")) + sport.zoneLabelsWidth().map { "$it (%)" })
        sport.zoneLabelsLength().forEachIndexed { i, l -> z.add(listOf<Any?>(l) + (0 until 3).map { j -> r1(st.zones[i][j] * 100) }) }
        x.sheet(tr("Zones"), z, listOf(16.0, 14.0, 14.0, 14.0))

        // Zones de vitesse
        val b = ArrayList<List<Any?>>()
        b.add(listOf(tr("Zone de vitesse"), tr("De (km/h)"), tr("À (km/h)"), tr("Temps (min)"), tr("Distance (m)")))
        st.bands.forEach { b.add(listOf(tr(it.label), r1(it.fromKmh), if (it.toKmh >= 99) null else r1(it.toKmh), r1(it.timeMs / 60000.0), Math.round(it.distM))) }
        x.sheet(tr("Zones de vitesse"), b, listOf(20.0, 10.0, 10.0, 12.0, 12.0))

        // Sprints
        val sp = ArrayList<List<Any?>>()
        sp.add(listOf(tr("Heure"), tr("Vitesse max (km/h)"), tr("Distance (m)"), tr("X le long du terrain (m)"), tr("Y en largeur (m)")))
        st.sprints.forEach { sp.add(listOf(tf.format(Date(it.t)), r1(it.maxKmh), Math.round(it.distM), r1(it.u * L), r1(it.v * W))) }
        x.sheet(tr("Sprints"), sp, listOf(10.0, 18.0, 12.0, 22.0, 18.0))

        // Score et états du match
        if (s.goals.isNotEmpty()) {
            val sc = ArrayList<List<Any?>>()
            sc.add(listOf(tr("Minute"), tr("Équipe"), tr("Score")))
            m.goals.forEach { g -> sc.add(listOf(g.minute, if (g.team == 0) tr("Nous") else tr("Eux"), "${g.us} – ${g.them}")) }
            sc.add(listOf(null)); sc.add(listOf(tr("Selon le score"), tr("Temps (min)"), "m/min", tr("Pos. moy.") + " (%)", tr("Tiers off.") + " (%)"))
            m.states.forEach { x ->
                sc.add(listOf(
                    when (x.state) { 1 -> tr("Je mène"); 0 -> tr("Égalité"); else -> tr("Je suis mené") },
                    r1(x.timeMs / 60000.0), Math.round(x.mPerMin), r1(x.avgU * 100), r1(x.offThird * 100),
                ))
            }
            x.sheet(tr("Score"), sc, listOf(18.0, 14.0, 12.0, 16.0, 14.0))
        }

        // Zones cardio
        if (st.hrAvg > 0) {
            val names = listOf("Z1 Récup.", "Z2 Endurance", "Z3 Tempo", "Z4 Seuil", "Z5 Max")
            val h = ArrayList<List<Any?>>()
            h.add(listOf(tr("Zones cardio"), tr("Temps (min)")))
            names.forEachIndexed { i, n -> h.add(listOf(tr(n), r1(st.hrZonesMs[i] / 60000.0))) }
            x.sheet(tr("Zones cardio"), h, listOf(18.0, 12.0))
        }
        x.write(file)
    }

    // ------------------------------------------------------------------ images

    private class Fonts(ctx: Context) {
        val black: Typeface = ResourcesCompat.getFont(ctx, R.font.chivo_900) ?: Typeface.DEFAULT_BOLD
        val bold: Typeface = ResourcesCompat.getFont(ctx, R.font.chivo_700) ?: Typeface.DEFAULT_BOLD
        val regular: Typeface = ResourcesCompat.getFont(ctx, R.font.chivo_400) ?: Typeface.DEFAULT
        val mono: Typeface = ResourcesCompat.getFont(ctx, R.font.jetbrains_mono_bold) ?: Typeface.MONOSPACE
    }

    private fun render(w: Int, h: Int, block: DrawScope.() -> Unit): Bitmap {
        val img = ImageBitmap(w, h)
        val canvas = androidx.compose.ui.graphics.Canvas(img)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(w.toFloat(), h.toFloat()), block)
        return img.asAndroidBitmap()
    }

    private fun DrawScope.text(t: String, x: Float, y: Float, size: Float, color: Color, tf: Typeface, align: Paint.Align = Paint.Align.LEFT) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb(); textSize = size; typeface = tf; textAlign = align
        }
        drawContext.canvas.nativeCanvas.drawText(t, x, y, paint)
    }

    /** Hauteur d'un terrain de largeur w (zone autour comprise). */
    private fun fieldHeight(sport: Sport, w: Float): Float {
        val (tl, tw) = templateSize(sport.layout, sport.lengthM, sport.widthM)
        val mL = sport.marginLM * tl / sport.lengthM
        val mW = sport.marginWM * tw / sport.widthM
        val ratio = ((tl + 2 * mL) / (tw + 2 * mW)).toFloat().coerceIn(0.8f, 2.4f)
        return w / ratio
    }

    /** Dessine le terrain et ses couches dans [area]. */
    private fun DrawScope.field(
        s: Session, st: SessionStats, p: Palette, area: Rect,
        heat: Boolean, trace: Boolean, sprints: Boolean, stroke: Float,
    ) {
        val sport = s.sport
        drawRoundRect(p.fieldBg, area.topLeft, area.size, CornerRadius(18f, 18f))
        val ins = area.width * 0.03f
        val fr = fieldRect(sport, area.width, area.height, ins).translate(area.topLeft)
        if (sport.marginLM > 0 || sport.marginWM > 0) {
            val o = outerRect(fr, sport)
            drawRect(p.fieldLine.copy(alpha = 0.08f), o.topLeft, o.size)
            drawRect(p.fieldLine.copy(alpha = 0.5f), o.topLeft, o.size, style = Stroke(stroke * 0.6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(stroke * 4, stroke * 3))))
        }
        if (heat) {
            val o = outerRect(fr, sport)
            drawImage(
                thermalImage(st.heat),
                dstOffset = IntOffset(o.left.toInt(), o.top.toInt()),
                dstSize = IntSize(o.width.toInt(), o.height.toInt()),
                filterQuality = FilterQuality.High,
            )
        }
        drawFieldMarkings(sport.layout, fr, sport.lengthM, sport.widthM, if (heat || trace) p.fieldLine.copy(alpha = 0.9f) else p.fieldLine, stroke)
        fun pt(u: Double, v: Double) = Offset(
            fr.left + u.toFloat().coerceIn(-1f, 2f) * fr.width,
            fr.top + v.toFloat().coerceIn(-1f, 2f) * fr.height,
        )
        if (trace && s.points.size >= 2) {
            val pts = s.points
            for (i in 1 until pts.size) {
                val a = pts[i - 1]; val b = pts[i]
                if (b.t - a.t > 5000) continue
                val c = thermal((a.speed * 3.6 / sport.sprintKmh).toFloat().coerceIn(0f, 1f))
                drawLine(c, pt(a.u, a.v), pt(b.u, b.v), stroke * 1.4f, cap = StrokeCap.Round)
            }
            val a = pts.first(); val z = pts.last()
            drawCircle(Color.White, stroke * 5f, pt(a.u, a.v))
            drawCircle(Color(0xFF16A34A), stroke * 3.6f, pt(a.u, a.v))
            drawCircle(Color.White, stroke * 5f, pt(z.u, z.v))
            drawCircle(Color(0xFFDC2626), stroke * 3.6f, pt(z.u, z.v))
        }
        if (sprints) st.sprints.forEach { spr ->
            val o = pt(spr.u, spr.v)
            drawCircle(p.secondary.copy(alpha = 0.3f), stroke * 6f, o)
            drawCircle(p.secondary, stroke * 2.6f, o)
        }
    }

    private fun DrawScope.gradientLegend(x: Float, y: Float, w: Float, h: Float, left: String, right: String, p: Palette, f: Fonts, size: Float) {
        val n = 60
        for (i in 0 until n) drawRect(thermal(i / (n - 1f)), Offset(x + w * i / n, y), Size(w / n + 1, h))
        text(left, x, y + h + size * 1.3f, size, p.muted, f.regular)
        text(right, x + w, y + h + size * 1.3f, size, p.muted, f.regular, Paint.Align.RIGHT)
    }

    private fun header(s: Session): Pair<String, String> {
        val title = tr(s.sport.name)
        val date = SimpleDateFormat("EEEE d MMMM yyyy · HH:mm", I18n.locale).format(Date(s.startMs)).replaceFirstChar { it.uppercase() }
        return title to date
    }

    private fun fmtDur(ms: Long): String {
        val sec = ms / 1000; val h = sec / 3600; val m = (sec % 3600) / 60; val ss = sec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, ss) else String.format("%02d:%02d", m, ss)
    }

    private fun fmtKm(m: Double) = if (m < 1000) String.format("%.0f m", m) else String.format(I18n.locale, "%.2f km", m / 1000)

    /** Image récapitulative : en-tête, déplacements, chaleur, statistiques. */
    private fun summary(s: Session, st: SessionStats, p: Palette, f: Fonts): Bitmap {
        val W = 1080f
        val pad = 48f
        val inner = W - 2 * pad
        val fh = fieldHeight(s.sport, inner)
        val h = (pad + 170 + 56 + fh + 90 + 56 + fh + 40 + 4 * 150 + 40 + 190 + 60 + 5 * 58 + 80).toInt()
        return render(W.toInt(), h) {
            drawRect(p.bg, Offset.Zero, size)
            var y = pad
            val (title, date) = header(s)
            text("FIELDTRACKER", pad, y + 26, 26f, p.primary, f.mono)
            text(title, pad, y + 92, 60f, p.text, f.black)
            text(date, pad, y + 136, 30f, p.muted, f.regular)
            val extra = listOfNotNull(
                s.position?.let { tr(it.name) },
                if (s.goals.isNotEmpty()) tr("Score") + " " + s.goals.count { it.team == 0 } + "–" + s.goals.count { it.team == 1 } else null,
            ).joinToString(" · ")
            text(
                trf("Durée %s · Terrain %.0f × %.0f m", fmtDur(st.activeMs), s.calibration.lengthM, s.calibration.widthM) +
                    if (extra.isNotEmpty()) " · $extra" else "",
                pad, y + 170, 26f, p.muted, f.mono
            )
            y += 170 + 56

            text(tr("Déplacements"), pad, y - 14, 34f, p.text, f.bold)
            field(s, st, p, Rect(Offset(pad, y), Size(inner, fh)), heat = false, trace = true, sprints = false, stroke = 3.2f)
            y += fh + 22
            gradientLegend(pad, y, inner * 0.6f, 12f, tr("Lent"), tr("Rapide"), p, f, 22f)
            text("● " + tr("Départ") + "   ● " + tr("Arrivée"), W - pad, y + 18, 22f, p.muted, f.regular, Paint.Align.RIGHT)
            y += 90 - 22 + 56

            text(tr("Carte de chaleur"), pad, y - 14, 34f, p.text, f.bold)
            field(s, st, p, Rect(Offset(pad, y), Size(inner, fh)), heat = true, trace = false, sprints = false, stroke = 3.2f)
            y += fh + 40

            // Statistiques (grille 2 x 4)
            val stats = listOf(
                tr("Distance") to fmtKm(st.distanceM),
                tr("Vitesse max") to String.format(I18n.locale, "%.1f km/h", st.maxSpeedKmh),
                tr("Sprints") to "${st.sprints.size}",
                tr("Haute intensité") to fmtKm(st.hiDistanceM),
                tr("Présence sur le terrain") to String.format("%.0f %%", st.presence * 100),
                tr("Vitesse moyenne en mouvement") to String.format(I18n.locale, "%.1f km/h", st.avgSpeedKmh),
                tr("Fréquence cardiaque") to (if (st.hrAvg > 0) "${st.hrAvg} / ${st.hrMax} bpm" else "—"),
                tr("Calories") to "${st.calories} kcal",
            )
            val cw = (inner - 20) / 2
            stats.forEachIndexed { i, (k, v) ->
                val cx = pad + (i % 2) * (cw + 20)
                val cy = y + (i / 2) * 150
                drawRoundRect(p.surface, Offset(cx, cy), Size(cw, 130f), CornerRadius(16f, 16f))
                drawRoundRect(p.border, Offset(cx, cy), Size(cw, 130f), CornerRadius(16f, 16f), style = Stroke(2f))
                text(k.uppercase(I18n.locale), cx + 24, cy + 42, 22f, p.muted, f.mono)
                text(v, cx + 24, cy + 104, 50f, if (i == 1 || i == 2) p.primary else p.text, f.mono)
            }
            y += 4 * 150 + 40

            // Présence par zone (longueur)
            text(tr("Présence par zone"), pad, y + 30, 34f, p.text, f.bold)
            val len = st.zones.map { it.sum() }
            val cols = listOf(p.primary, p.tertiary, p.secondary)
            var x0 = pad
            val barY = y + 56
            val total = len.sum().coerceAtLeast(1e-9)
            len.forEachIndexed { i, v ->
                val w = (inner * (v / total)).toFloat()
                if (w > 0) drawRect(cols[i], Offset(x0, barY), Size(w, 26f))
                x0 += w
            }
            s.sport.zoneLabelsLength().forEachIndexed { i, l ->
                text("$l  ${(len[i] / total * 100).toInt()}%", pad + i * inner / 3, barY + 76, 26f, cols[i], f.mono)
            }
            y += 190 + 60

            // Zones de vitesse
            text(tr("Zones de vitesse"), pad, y - 20, 34f, p.text, f.bold)
            val maxT = st.bands.maxOf { it.timeMs }.coerceAtLeast(1)
            val bc = listOf(p.muted, p.tertiary, p.primary, p.warning, p.secondary)
            st.bands.forEachIndexed { i, b ->
                val by = y + 10 + i * 58
                text(tr(b.label), pad, by + 22, 26f, p.text, f.regular)
                text(fmtDur(b.timeMs), W - pad, by + 22, 24f, p.muted, f.mono, Paint.Align.RIGHT)
                drawRoundRect(p.elevated, Offset(pad, by + 34), Size(inner, 12f), CornerRadius(6f, 6f))
                drawRoundRect(bc[i], Offset(pad, by + 34), Size(inner * b.timeMs / maxT, 12f), CornerRadius(6f, 6f))
            }
        }
    }

    /** Carte du terrain en haute résolution (déplacements ou chaleur). */
    private fun fieldMap(s: Session, st: SessionStats, p: Palette, f: Fonts, heat: Boolean): Bitmap {
        val W = 2000f
        val pad = 70f
        val inner = W - 2 * pad
        val fh = fieldHeight(s.sport, inner)
        val h = (pad + 190 + fh + 150).toInt()
        return render(W.toInt(), h) {
            drawRect(p.bg, Offset.Zero, size)
            val (title, date) = header(s)
            text(title + " — " + tr(if (heat) "Carte de chaleur" else "Déplacements"), pad, pad + 64, 64f, p.text, f.black)
            text(date + " · " + fmtDur(st.activeMs) + " · " + fmtKm(st.distanceM), pad, pad + 120, 38f, p.muted, f.regular)
            val top = pad + 170
            field(s, st, p, Rect(Offset(pad, top), Size(inner, fh)), heat = heat, trace = !heat, sprints = !heat, stroke = 5f)
            val ly = top + fh + 44
            if (heat) {
                gradientLegend(pad, ly, inner * 0.5f, 20f, tr("Faible"), tr("Point chaud"), p, f, 34f)
            } else {
                gradientLegend(pad, ly, inner * 0.45f, 20f, tr("Lent"), tr("Rapide"), p, f, 34f)
                text("● " + tr("Départ") + "   ● " + tr("Arrivée") + "   ● " + tr("Sprints"), W - pad, ly + 26, 34f, p.muted, f.regular, Paint.Align.RIGHT)
            }
        }
    }

    // ------------------------------------------------------------------ fichiers

    private fun png(file: File, b: Bitmap): File {
        file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    private fun pdf(file: File, pages: List<Bitmap>): File {
        val doc = PdfDocument()
        pages.forEachIndexed { i, b ->
            // A4 portrait (points) ; l'image est mise à l'échelle pour tenir dans la page
            val pw = 595; val ph = 842
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
            val margin = 24f
            val scale = minOf((pw - 2 * margin) / b.width, (ph - 2 * margin) / b.height)
            val dw = b.width * scale; val dh = b.height * scale
            val left = (pw - dw) / 2f; val top = margin
            page.canvas.drawBitmap(b, null, android.graphics.RectF(left, top, left + dw, top + dh), Paint(Paint.FILTER_BITMAP_FLAG))
            doc.finishPage(page)
        }
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }
}
