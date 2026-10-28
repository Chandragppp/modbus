package id.controlengineer.modbus.protocol

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Sel tunggal pada lembar kerja XLSX. */
sealed class XlsxCell {
    abstract val bold: Boolean

    data class Text(val value: String, override val bold: Boolean = false) : XlsxCell()
    data class Num(val value: Double, override val bold: Boolean = false) : XlsxCell()
    data class Blank(override val bold: Boolean = false) : XlsxCell()
}

/** Definisi satu sheet XLSX beserta daftar baris dan lebar kolom opsional. */
data class XlsxSheet(
    val name: String,
    val rows: List<List<XlsxCell>>,
    val colWidths: List<Double> = emptyList(),
)

/**
 * Penulis file Excel (.xlsx) minimal tanpa pustaka pihak ketiga.
 * Menghasilkan paket OpenXML yang kompatibel dengan Excel, LibreOffice Calc, dan Google Sheets.
 */
class XlsxWriter {
    private val sheets = mutableListOf<XlsxSheet>()

    class SheetBuilder(
        private val name: String,
        private val colWidths: MutableList<Double> = mutableListOf(),
    ) {
        private val rows = mutableListOf<List<XlsxCell>>()

        fun widths(vararg widths: Double) {
            colWidths.clear()
            colWidths.addAll(widths.toList())
        }

        fun header(vararg titles: String) {
            rows.add(titles.map { XlsxCell.Text(it, bold = true) })
        }

        fun row(vararg values: Any?) {
            rows.add(
                values.map { v ->
                    when (v) {
                        null -> XlsxCell.Blank()
                        is XlsxCell -> v
                        is Number -> {
                            val d = v.toDouble()
                            if (d.isFinite()) XlsxCell.Num(d) else XlsxCell.Text("-")
                        }
                        else -> XlsxCell.Text(v.toString())
                    }
                },
            )
        }

        fun emptyRow() {
            rows.add(emptyList())
        }

        internal fun build(): XlsxSheet = XlsxSheet(
            name = name,
            rows = rows.toList(),
            colWidths = colWidths.toList(),
        )
    }

    fun sheet(
        name: String,
        colWidths: List<Double> = emptyList(),
        block: SheetBuilder.() -> Unit,
    ): XlsxWriter {
        val builder = SheetBuilder(name, colWidths.toMutableList())
        builder.block()
        sheets.add(builder.build())
        return this
    }

    fun writeTo(output: OutputStream) {
        write(output, sheets)
    }

    companion object {
        fun write(output: OutputStream, sheets: List<XlsxSheet>) {
            require(sheets.isNotEmpty()) { "At least one sheet is required" }
            ZipOutputStream(output).use { zip ->
                putEntry(zip, "[Content_Types].xml", buildContentTypes(sheets.size))
                putEntry(zip, "_rels/.rels", buildRootRels())
                putEntry(zip, "xl/workbook.xml", buildWorkbook(sheets))
                putEntry(zip, "xl/_rels/workbook.xml.rels", buildWorkbookRels(sheets.size))
                putEntry(zip, "xl/styles.xml", buildStyles())
                sheets.forEachIndexed { index, sheet ->
                    putEntry(zip, "xl/worksheets/sheet${index + 1}.xml", buildWorksheet(sheet))
                }
            }
        }

        private fun putEntry(zip: ZipOutputStream, name: String, xml: String) {
            val entry = ZipEntry(name)
            zip.putNextEntry(entry)
            zip.write(xml.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }

        fun escapeXml(s: String): String {
            val sb = StringBuilder(s.length)
            for (ch in s) {
                when (ch) {
                    '&' -> sb.append("&amp;")
                    '<' -> sb.append("&lt;")
                    '>' -> sb.append("&gt;")
                    '"' -> sb.append("&quot;")
                    '\'' -> sb.append("&apos;")
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }

        fun colName(colIndexZeroBased: Int): String {
            var n = colIndexZeroBased + 1
            val sb = StringBuilder()
            while (n > 0) {
                val rem = (n - 1) % 26
                sb.append(('A'.code + rem).toChar())
                n = (n - 1) / 26
            }
            return sb.reverse().toString()
        }

        private fun buildContentTypes(sheetCount: Int): String = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
            append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
            append("""<Default Extension="xml" ContentType="application/xml"/>""")
            append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
            append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
            for (i in 1..sheetCount) {
                append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
            }
            append("""</Types>""")
        }

        private fun buildRootRels(): String =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
                """</Relationships>"""

        private fun buildWorkbook(sheets: List<XlsxSheet>): String = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
            append("""<sheets>""")
            sheets.forEachIndexed { idx, sheet ->
                val sheetId = idx + 1
                val safeName = escapeXml(sheet.name.take(31))
                append("""<sheet name="$safeName" sheetId="$sheetId" r:id="rId$sheetId"/>""")
            }
            append("""</sheets>""")
            append("""</workbook>""")
        }

        private fun buildWorkbookRels(sheetCount: Int): String = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            for (i in 1..sheetCount) {
                append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
            }
            val styleId = sheetCount + 1
            append("""<Relationship Id="rId$styleId" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
            append("""</Relationships>""")
        }

        private fun buildStyles(): String =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
                """<fonts count="2">""" +
                """<font><sz val="11"/><name val="Calibri"/></font>""" +
                """<font><b/><sz val="11"/><name val="Calibri"/></font>""" +
                """</fonts>""" +
                """<fills count="2">""" +
                """<fill><patternFill patternType="none"/></fill>""" +
                """<fill><patternFill patternType="gray125"/></fill>""" +
                """</fills>""" +
                """<borders count="1">""" +
                """<border><left/><right/><top/><bottom/><diagonal/></border>""" +
                """</borders>""" +
                """<cellStyleXfs count="1">""" +
                """<xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>""" +
                """</cellStyleXfs>""" +
                """<cellXfs count="2">""" +
                """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
                """<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>""" +
                """</cellXfs>""" +
                """</styleSheet>"""

        private fun buildWorksheet(sheet: XlsxSheet): String = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
            if (sheet.colWidths.isNotEmpty()) {
                append("""<cols>""")
                sheet.colWidths.forEachIndexed { idx, w ->
                    val colNum = idx + 1
                    val widthStr = String.format(Locale.ROOT, "%.2f", w)
                    append("""<col min="$colNum" max="$colNum" width="$widthStr" customWidth="1"/>""")
                }
                append("""</cols>""")
            }
            append("""<sheetData>""")
            sheet.rows.forEachIndexed { rIdx, row ->
                val rowNum = rIdx + 1
                append("""<row r="$rowNum">""")
                row.forEachIndexed { cIdx, cell ->
                    val ref = "${colName(cIdx)}$rowNum"
                    val styleAttr = if (cell.bold) """ s="1"""" else ""
                    when (cell) {
                        is XlsxCell.Text -> {
                            val escaped = escapeXml(cell.value)
                            append("""<c r="$ref"$styleAttr t="inlineStr"><is><t>$escaped</t></is></c>""")
                        }
                        is XlsxCell.Num -> {
                            if (cell.value.isFinite()) {
                                val numStr = String.format(Locale.ROOT, "%.6f", cell.value)
                                    .trimEnd('0')
                                    .trimEnd('.')
                                    .ifEmpty { "0" }
                                append("""<c r="$ref"$styleAttr><v>$numStr</v></c>""")
                            } else {
                                append("""<c r="$ref"$styleAttr t="inlineStr"><is><t>-</t></is></c>""")
                            }
                        }
                        is XlsxCell.Blank -> {
                            // Sel kosong tidak perlu ditulis
                        }
                    }
                }
                append("""</row>""")
            }
            append("""</sheetData>""")
            append("""</worksheet>""")
        }
    }
}
