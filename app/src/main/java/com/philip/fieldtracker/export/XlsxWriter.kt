package com.philip.fieldtracker.export

import java.io.File
import java.io.Writer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Petit générateur de fichiers Excel (.xlsx) sans bibliothèque externe.
 * Chaque feuille = liste de lignes ; une cellule est un nombre, un texte ou null.
 * La première ligne de chaque feuille est mise en gras (en-têtes).
 */
class XlsxWriter {

    class Sheet(val name: String, val rows: List<List<Any?>>, val colWidths: List<Double> = emptyList())

    private val sheets = ArrayList<Sheet>()

    fun sheet(name: String, rows: List<List<Any?>>, colWidths: List<Double> = emptyList()) {
        // Nom de feuille Excel : 31 caractères max, sans [ ] : * ? / \
        val clean = name.replace(Regex("[\\[\\]:*?/\\\\]"), " ").take(31).ifBlank { "Sheet${sheets.size + 1}" }
        sheets.add(Sheet(clean, rows, colWidths))
    }

    fun write(file: File) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun entry(path: String, body: (Writer) -> Unit) {
                zip.putNextEntry(ZipEntry(path))
                val w = zip.writer(Charsets.UTF_8)
                body(w)
                w.flush()
                zip.closeEntry()
            }
            entry("[Content_Types].xml") { w ->
                w.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                w.write("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
                w.write("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
                w.write("""<Default Extension="xml" ContentType="application/xml"/>""")
                w.write("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
                w.write("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
                sheets.indices.forEach {
                    w.write("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
                }
                w.write("</Types>")
            }
            entry("_rels/.rels") { w ->
                w.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                w.write("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                w.write("""<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""")
                w.write("</Relationships>")
            }
            entry("xl/workbook.xml") { w ->
                w.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                w.write("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
                sheets.forEachIndexed { i, s ->
                    w.write("""<sheet name="${esc(s.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
                }
                w.write("</sheets></workbook>")
            }
            entry("xl/_rels/workbook.xml.rels") { w ->
                w.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                w.write("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                sheets.indices.forEach {
                    w.write("""<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""")
                }
                w.write("""<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
                w.write("</Relationships>")
            }
            entry("xl/styles.xml") { w ->
                w.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                w.write("""<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
                w.write("""<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>""")
                w.write("""<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>""")
                w.write("""<fill><patternFill patternType="solid"><fgColor rgb="FFD9F7E5"/><bgColor indexed="64"/></patternFill></fill></fills>""")
                w.write("""<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""")
                w.write("""<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""")
                w.write("""<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""")
                w.write("""<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/></cellXfs>""")
                w.write("""<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""")
                w.write("</styleSheet>")
            }
            sheets.forEachIndexed { i, s ->
                entry("xl/worksheets/sheet${i + 1}.xml") { w -> writeSheet(w, s) }
            }
        }
    }

    private fun writeSheet(w: Writer, s: Sheet) {
        w.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        w.write("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        // En-tête figée
        w.write("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        if (s.colWidths.isNotEmpty()) {
            w.write("<cols>")
            s.colWidths.forEachIndexed { c, wd -> w.write("""<col min="${c + 1}" max="${c + 1}" width="$wd" customWidth="1"/>""") }
            w.write("</cols>")
        }
        w.write("<sheetData>")
        s.rows.forEachIndexed { r, row ->
            w.write("""<row r="${r + 1}">""")
            row.forEachIndexed { c, v ->
                if (v == null) return@forEachIndexed
                val ref = col(c) + (r + 1)
                val style = if (r == 0) " s=\"1\"" else ""
                when (v) {
                    is Number -> {
                        val d = v.toDouble()
                        if (d.isFinite()) w.write("""<c r="$ref"$style><v>${num(d)}</v></c>""")
                    }
                    is Boolean -> w.write("""<c r="$ref" t="b"$style><v>${if (v) 1 else 0}</v></c>""")
                    else -> w.write("""<c r="$ref" t="inlineStr"$style><is><t xml:space="preserve">${esc(v.toString())}</t></is></c>""")
                }
            }
            w.write("</row>")
        }
        w.write("</sheetData></worksheet>")
    }

    private fun num(d: Double): String =
        if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString()
        else String.format(java.util.Locale.US, "%.6f", d).trimEnd('0').trimEnd('.')

    private fun col(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) {
            val m = (n - 1) % 26
            sb.insert(0, ('A' + m))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun esc(s: String): String = buildString {
        for (ch in s) when {
            ch == '&' -> append("&amp;")
            ch == '<' -> append("&lt;")
            ch == '>' -> append("&gt;")
            ch == '"' -> append("&quot;")
            ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r' -> {}
            else -> append(ch)
        }
    }
}
