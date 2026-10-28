package id.controlengineer.modbus

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import id.controlengineer.modbus.protocol.ByteOrder
import id.controlengineer.modbus.protocol.ConnectionMode
import id.controlengineer.modbus.protocol.ConnectionProfile
import id.controlengineer.modbus.protocol.DataSample
import id.controlengineer.modbus.protocol.DecodedRegisterPair
import id.controlengineer.modbus.protocol.FunctionCode
import id.controlengineer.modbus.protocol.LinearScale
import id.controlengineer.modbus.protocol.ModbusAddress
import id.controlengineer.modbus.protocol.RegisterDecoder
import id.controlengineer.modbus.protocol.ReportMeta
import id.controlengineer.modbus.protocol.ScanHit
import id.controlengineer.modbus.protocol.XlsxWriter
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Kelas pembangun laporan PDF ukuran A4 (595 x 842 pt) memakai android.graphics.pdf.PdfDocument.
 * Mendukung judul, subjudul seksi, blok info key-value, tabel bergaris dengan header abu-abu
 * yang otomatis pindah halaman bila panjang, paragraf/penafian, kolom tanda tangan, dan footer "1/N".
 */
class PdfReport(
    private val footerLeftText: String,
    private val timestampText: String,
) {
    companion object {
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val MARGIN = 36f
        const val CONTENT_WIDTH = PAGE_WIDTH - MARGIN * 2f
        private const val FOOTER_RESERVED = 36f
        private const val BOTTOM_LIMIT = PAGE_HEIGHT - MARGIN - FOOTER_RESERVED
    }

    private val pages = mutableListOf<MutableList<(Canvas) -> Unit>>()
    private var currentOps = mutableListOf<(Canvas) -> Unit>().also { pages.add(it) }
    private var cursorY = MARGIN

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 28, 36)
        textSize = 15f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val sectionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(13, 71, 161)
        textSize = 10.5f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 80, 90)
        textSize = 9f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(25, 30, 35)
        textSize = 9f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    private val tableHeaderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 25, 30)
        textSize = 8.5f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val tableCellPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(25, 30, 35)
        textSize = 8.2f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    private val italicPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(80, 90, 100)
        textSize = 8.2f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)
    }

    private val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(100, 110, 120)
        textSize = 8f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(185, 195, 205)
        style = Paint.Style.STROKE
        strokeWidth = 0.75f
    }

    private val headerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(232, 236, 242)
        style = Paint.Style.FILL
    }

    private val zebraBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(248, 250, 252)
        style = Paint.Style.FILL
    }

    private val infoBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(245, 247, 250)
        style = Paint.Style.FILL
    }

    private fun newPage() {
        currentOps = mutableListOf<(Canvas) -> Unit>().also { pages.add(it) }
        cursorY = MARGIN
    }

    private fun ensureSpace(heightNeeded: Float) {
        if (cursorY + heightNeeded > BOTTOM_LIMIT) {
            newPage()
        }
    }

    private fun buildStaticLayout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(20))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()

    fun title(text: String, subtitle: String? = null) {
        val titleLayout = buildStaticLayout(text, titlePaint, CONTENT_WIDTH.toInt())
        val subLayout = subtitle?.takeIf { it.isNotBlank() }?.let {
            buildStaticLayout(it, italicPaint, CONTENT_WIDTH.toInt())
        }
        val totalH = titleLayout.height + (subLayout?.let { it.height + 3f } ?: 0f) + 10f
        ensureSpace(totalH)
        val yStart = cursorY
        currentOps.add { canvas ->
            canvas.save()
            canvas.translate(MARGIN, yStart)
            titleLayout.draw(canvas)
            if (subLayout != null) {
                canvas.translate(0f, titleLayout.height + 2f)
                subLayout.draw(canvas)
            }
            canvas.restore()
            val lineY = yStart + totalH - 4f
            canvas.drawLine(MARGIN, lineY, MARGIN + CONTENT_WIDTH, lineY, linePaint)
        }
        cursorY += totalH
    }

    fun sectionHeader(text: String) {
        val layout = buildStaticLayout(text, sectionPaint, CONTENT_WIDTH.toInt())
        val h = layout.height + 8f
        ensureSpace(h + 24f)
        val yStart = cursorY + 4f
        currentOps.add { canvas ->
            canvas.save()
            canvas.translate(MARGIN, yStart)
            layout.draw(canvas)
            canvas.restore()
        }
        cursorY += h
    }

    fun keyValueBlock(pairs: List<Pair<String, String>>) {
        val nonEmpty = pairs.filter { it.second.isNotBlank() }
        if (nonEmpty.isEmpty()) return
        val labelWidth = 135
        val valWidth = (CONTENT_WIDTH - labelWidth - 16f).toInt()
        val rowLayouts = nonEmpty.map { (k, v) ->
            val lLayout = buildStaticLayout(k, labelPaint, labelWidth)
            val vLayout = buildStaticLayout(v, bodyPaint, valWidth)
            val rowH = maxOf(lLayout.height, vLayout.height) + 4f
            Triple(lLayout, vLayout, rowH)
        }
        val boxHeight = rowLayouts.sumOf { it.third.toDouble() }.toFloat() + 8f
        ensureSpace(boxHeight)
        val yBox = cursorY
        currentOps.add { canvas ->
            val rect = RectF(MARGIN, yBox, MARGIN + CONTENT_WIDTH, yBox + boxHeight)
            canvas.drawRoundRect(rect, 4f, 4f, infoBgPaint)
            canvas.drawRoundRect(rect, 4f, 4f, linePaint)
            var y = yBox + 4f
            for ((lLayout, vLayout, rowH) in rowLayouts) {
                canvas.save()
                canvas.translate(MARGIN + 6f, y)
                lLayout.draw(canvas)
                canvas.restore()

                canvas.save()
                canvas.translate(MARGIN + 6f + labelWidth, y)
                vLayout.draw(canvas)
                canvas.restore()
                y += rowH
            }
        }
        cursorY += boxHeight + 6f
    }

    fun paragraph(text: String, italic: Boolean = false) {
        if (text.isBlank()) return
        val paint = if (italic) italicPaint else bodyPaint
        val layout = buildStaticLayout(text, paint, CONTENT_WIDTH.toInt())
        val h = layout.height + 6f
        ensureSpace(h)
        val yStart = cursorY
        currentOps.add { canvas ->
            canvas.save()
            canvas.translate(MARGIN, yStart)
            layout.draw(canvas)
            canvas.restore()
        }
        cursorY += h
    }

    fun table(
        headers: List<String>,
        weights: List<Float>,
        rows: List<List<String>>,
    ) {
        if (headers.isEmpty()) return
        val totalWeight = weights.sum().takeIf { it > 0f } ?: headers.size.toFloat()
        val colWidths = weights.map { (it / totalWeight) * CONTENT_WIDTH }
        val cellPadX = 4f
        val cellPadY = 3.5f

        fun measureRow(cells: List<String>, paint: TextPaint): Pair<List<StaticLayout>, Float> {
            val layouts = headers.indices.map { idx ->
                val text = cells.getOrNull(idx) ?: ""
                val w = (colWidths[idx] - cellPadX * 2f).toInt().coerceAtLeast(16)
                buildStaticLayout(text, paint, w)
            }
            val maxH = (layouts.maxOfOrNull { it.height } ?: 10) + cellPadY * 2f
            return layouts to maxH
        }

        val (headerLayouts, headerH) = measureRow(headers, tableHeaderPaint)

        fun drawTableRow(
            layouts: List<StaticLayout>,
            rowH: Float,
            isHeader: Boolean,
            isEvenRow: Boolean,
        ) {
            val yStart = cursorY
            currentOps.add { canvas ->
                val rowRect = RectF(MARGIN, yStart, MARGIN + CONTENT_WIDTH, yStart + rowH)
                if (isHeader) {
                    canvas.drawRect(rowRect, headerBgPaint)
                } else if (isEvenRow) {
                    canvas.drawRect(rowRect, zebraBgPaint)
                }
                canvas.drawRect(rowRect, linePaint)
                var x = MARGIN
                for (i in layouts.indices) {
                    if (i > 0) {
                        canvas.drawLine(x, yStart, x, yStart + rowH, linePaint)
                    }
                    canvas.save()
                    canvas.translate(x + cellPadX, yStart + cellPadY)
                    layouts[i].draw(canvas)
                    canvas.restore()
                    x += colWidths[i]
                }
            }
            cursorY += rowH
        }

        ensureSpace(headerH + 18f)
        drawTableRow(headerLayouts, headerH, isHeader = true, isEvenRow = false)

        rows.forEachIndexed { rIdx, rowCells ->
            val (cellLayouts, rowH) = measureRow(rowCells, tableCellPaint)
            if (cursorY + rowH > BOTTOM_LIMIT) {
                newPage()
                drawTableRow(headerLayouts, headerH, isHeader = true, isEvenRow = false)
            }
            drawTableRow(cellLayouts, rowH, isHeader = false, isEvenRow = rIdx % 2 == 1)
        }
        cursorY += 6f
    }

    fun signatureRow(leftLabel: String, leftName: String, rightLabel: String) {
        val blockH = 44f
        ensureSpace(blockH)
        val yStart = cursorY + 4f
        val colW = (CONTENT_WIDTH - 24f) / 2f
        currentOps.add { canvas ->
            canvas.drawText(leftLabel, MARGIN, yStart + 9f, labelPaint)
            val lineY = yStart + 34f
            canvas.drawLine(MARGIN, lineY, MARGIN + colW, lineY, linePaint)
            if (leftName.isNotBlank()) {
                canvas.drawText(leftName, MARGIN, lineY - 3f, bodyPaint)
            }
            val rightX = MARGIN + colW + 24f
            canvas.drawText(rightLabel, rightX, yStart + 9f, labelPaint)
            canvas.drawLine(rightX, lineY, rightX + colW, lineY, linePaint)
        }
        cursorY += blockH
    }

    fun writeTo(output: OutputStream) {
        val doc = PdfDocument()
        try {
            val totalPages = pages.size.coerceAtLeast(1)
            val bgPaint = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            pages.forEachIndexed { idx, ops ->
                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, idx + 1).create()
                val page = doc.startPage(pageInfo)
                val canvas = page.canvas
                canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), PAGE_HEIGHT.toFloat(), bgPaint)
                for (op in ops) {
                    op(canvas)
                }
                val footerLineY = PAGE_HEIGHT - MARGIN - 14f
                canvas.drawLine(MARGIN, footerLineY, MARGIN + CONTENT_WIDTH, footerLineY, linePaint)
                val footerTextY = PAGE_HEIGHT - MARGIN - 3f
                val leftFooter = "$footerLeftText · $timestampText"
                canvas.drawText(leftFooter, MARGIN, footerTextY, footerPaint)
                val pageNumStr = "${idx + 1}/$totalPages"
                val numW = footerPaint.measureText(pageNumStr)
                canvas.drawText(pageNumStr, MARGIN + CONTENT_WIDTH - numW, footerTextY, footerPaint)
                doc.finishPage(page)
            }
            doc.writeTo(output)
        } finally {
            doc.close()
        }
    }
}

enum class ReportFormat { PDF, XLSX }

object ModbusReportExporter {
    const val MIME_PDF = "application/pdf"
    const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    private val fileTimestampFmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT)
    private val displayTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    private val isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun formatTimestamp(millis: Long): String {
        val effective = if (millis > 0L) millis else System.currentTimeMillis()
        return Instant.ofEpochMilli(effective)
            .atZone(ZoneId.systemDefault())
            .format(displayTimeFmt)
    }

    fun formatIso(millis: Long): String =
        Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .format(isoFormatter)

    fun formatConnectionSummary(profile: ConnectionProfile): String =
        if (profile.mode == ConnectionMode.TCP) {
            "Modbus TCP (${profile.host}:${profile.port})"
        } else {
            "Modbus RTU (${profile.baudRate} bps, ${profile.dataBits} data bits, Parity ${profile.parity.name} [${profile.parity.shortLabel}], ${profile.stopBits} stop bit)"
        }

    private fun safeToken(raw: String, fallback: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[^A-Za-z0-9_-]+"), "_")
            .trim('_')
            .take(24)
        return cleaned.ifEmpty { fallback }
    }

    private fun createReportFile(
        context: Context,
        kind: String,
        tagHint: String,
        ext: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): File {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val ts = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).format(fileTimestampFmt)
        val safeTag = safeToken(tagHint, "slave")
        return File(dir, "modbus_${kind}_${safeTag}_$ts.$ext")
    }

    fun shareReportFile(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, file.name).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun buildHeaderPairs(context: Context, meta: ReportMeta): List<Pair<String, String>> = listOf(
        context.getString(R.string.report_label_device) to meta.deviceName.ifBlank { "-" },
        context.getString(R.string.report_label_technician) to meta.technician.ifBlank { "-" },
        context.getString(R.string.report_label_company) to meta.company.ifBlank { "-" },
        context.getString(R.string.report_label_location) to meta.location.ifBlank { "-" },
        context.getString(R.string.report_label_notes) to meta.notes.ifBlank { "-" },
    )

    // ==================== 1. LAPORAN PEMBACAAN REGISTER ====================

    fun exportRegisterPdf(
        context: Context,
        meta: ReportMeta,
        profile: ConnectionProfile,
        unitId: Int,
        fc: FunctionCode,
        startAddress: Int,
        registers: List<Int>,
        bits: List<Boolean>,
        scale: LinearScale? = null,
        byteOrder: ByteOrder = ByteOrder.ABCD,
        readTimestampMillis: Long = System.currentTimeMillis(),
    ): File {
        val tag = meta.deviceName.ifBlank { "id${unitId}_addr$startAddress" }
        val file = createReportFile(context, "register", tag, "pdf", readTimestampMillis)
        val timeStr = formatTimestamp(readTimestampMillis)
        val brandFooter = context.getString(R.string.report_footer_brand, context.getString(R.string.app_name))
        val modiconStart = ModbusAddress.formatModicon(startAddress, fc)
        val startAddrDisplay = String.format(
            Locale.ROOT,
            "%d (0x%04X · Modicon %s)",
            startAddress,
            startAddress and 0xFFFF,
            modiconStart,
        )
        val count = if (registers.isNotEmpty()) registers.size else bits.size
        val scaleSummary = scale?.let {
            "${it.rawMin}..${it.rawMax} -> ${it.engMin}..${it.engMax} ${it.unit}".trim()
        } ?: "-"

        val pdf = PdfReport(footerLeftText = brandFooter, timestampText = timeStr)
        pdf.title(
            text = context.getString(R.string.report_reg_title),
            subtitle = context.getString(R.string.about_disclaimer),
        )
        pdf.sectionHeader(context.getString(R.string.report_section_header))
        pdf.keyValueBlock(buildHeaderPairs(context, meta))

        pdf.sectionHeader(context.getString(R.string.report_section_connection))
        pdf.keyValueBlock(
            listOf(
                context.getString(R.string.report_label_conn_mode) to formatConnectionSummary(profile),
                context.getString(R.string.report_label_unit_id) to unitId.toString(),
                context.getString(R.string.report_label_timeout) to "${profile.timeoutMs} ms",
                context.getString(R.string.report_label_fc) to fc.shortName,
                context.getString(R.string.report_label_start_addr) to startAddrDisplay,
                context.getString(R.string.report_label_quantity) to count.toString(),
                context.getString(R.string.report_label_byte_order) to byteOrder.name,
                context.getString(R.string.report_label_scale) to scaleSummary,
                context.getString(R.string.report_label_read_time) to timeStr,
            ),
        )

        pdf.sectionHeader(context.getString(R.string.report_section_registers))
        if (registers.isNotEmpty()) {
            val decodedRegs = RegisterDecoder.decodeRegisters(startAddress, registers, scale)
            val decodedPairs = RegisterDecoder.decodePairs(startAddress, registers)
            val rows = decodedRegs.mapIndexed { idx, reg ->
                val modicon = ModbusAddress.formatModicon(reg.address, fc)
                val pair = decodedPairs.getOrNull(idx)?.forOrder(byteOrder)
                val decodedParts = mutableListOf<String>()
                if (pair != null) {
                    decodedParts.add("Float32(${byteOrder.name})=${pair.floatText}")
                    decodedParts.add("Int32=${pair.int32}")
                } else {
                    decodedParts.add("UInt16=${reg.uint16}, Int16=${reg.int16}")
                }
                reg.scaledText?.let { decodedParts.add("Scaled=$it") }
                listOf(
                    String.format(Locale.ROOT, "%d (0x%04X)", reg.address, reg.address and 0xFFFF),
                    modicon,
                    reg.hex,
                    "${reg.uint16} / ${reg.int16}",
                    decodedParts.joinToString(" | "),
                )
            }
            pdf.table(
                headers = listOf(
                    context.getString(R.string.report_col_addr),
                    context.getString(R.string.report_col_modicon),
                    context.getString(R.string.report_col_raw_hex),
                    context.getString(R.string.report_col_raw_dec),
                    context.getString(R.string.report_col_decoded),
                ),
                weights = listOf(1.1f, 0.85f, 0.85f, 1.15f, 2.25f),
                rows = rows,
            )
        } else {
            val rows = bits.mapIndexed { idx, bit ->
                val addr = startAddress + idx
                listOf(
                    String.format(Locale.ROOT, "%d (0x%04X)", addr, addr and 0xFFFF),
                    ModbusAddress.formatModicon(addr, fc),
                    if (bit) "ON (1)" else "OFF (0)",
                )
            }
            pdf.table(
                headers = listOf(
                    context.getString(R.string.report_col_addr),
                    context.getString(R.string.report_col_modicon),
                    context.getString(R.string.report_col_status),
                ),
                weights = listOf(1.2f, 1.0f, 1.2f),
                rows = rows,
            )
        }

        pdf.paragraph(context.getString(R.string.about_disclaimer), italic = true)
        pdf.signatureRow(
            leftLabel = context.getString(R.string.report_sign_prepared),
            leftName = meta.technician,
            rightLabel = context.getString(R.string.report_sign_verified),
        )
        file.outputStream().use { pdf.writeTo(it) }
        return file
    }

    fun exportRegisterXlsx(
        context: Context,
        meta: ReportMeta,
        profile: ConnectionProfile,
        unitId: Int,
        fc: FunctionCode,
        startAddress: Int,
        registers: List<Int>,
        bits: List<Boolean>,
        scale: LinearScale? = null,
        byteOrder: ByteOrder = ByteOrder.ABCD,
        readTimestampMillis: Long = System.currentTimeMillis(),
    ): File {
        val tag = meta.deviceName.ifBlank { "id${unitId}_addr$startAddress" }
        val file = createReportFile(context, "register", tag, "xlsx", readTimestampMillis)
        val timeStr = formatTimestamp(readTimestampMillis)
        val modiconStart = ModbusAddress.formatModicon(startAddress, fc)
        val startAddrDisplay = String.format(
            Locale.ROOT,
            "%d (0x%04X · Modicon %s)",
            startAddress,
            startAddress and 0xFFFF,
            modiconStart,
        )
        val count = if (registers.isNotEmpty()) registers.size else bits.size

        val writer = XlsxWriter()
        writer.sheet(
            name = "Register",
            colWidths = listOf(16.0, 14.0, 14.0, 14.0, 14.0, 16.0, 16.0, 16.0),
        ) {
            header(context.getString(R.string.report_reg_title))
            for ((k, v) in buildHeaderPairs(context, meta)) {
                row(k, v)
            }
            row(context.getString(R.string.report_label_conn_mode), formatConnectionSummary(profile))
            row(context.getString(R.string.report_label_unit_id), unitId)
            row(context.getString(R.string.report_label_timeout), "${profile.timeoutMs} ms")
            row(context.getString(R.string.report_label_fc), fc.shortName)
            row(context.getString(R.string.report_label_start_addr), startAddrDisplay)
            row(context.getString(R.string.report_label_quantity), count)
            row(context.getString(R.string.report_label_byte_order), byteOrder.name)
            row(context.getString(R.string.report_label_read_time), timeStr)
            emptyRow()

            if (registers.isNotEmpty()) {
                val decodedRegs = RegisterDecoder.decodeRegisters(startAddress, registers, scale)
                val decodedPairs = RegisterDecoder.decodePairs(startAddress, registers)
                header(
                    context.getString(R.string.report_col_addr),
                    context.getString(R.string.report_col_modicon),
                    context.getString(R.string.report_col_raw_hex),
                    "UInt16",
                    "Int16",
                    "Float32 (${byteOrder.name})",
                    "Int32 (${byteOrder.name})",
                    context.getString(R.string.report_label_scale),
                )
                decodedRegs.forEachIndexed { idx, reg ->
                    val pair = decodedPairs.getOrNull(idx)?.forOrder(byteOrder)
                    row(
                        reg.address,
                        ModbusAddress.formatModicon(reg.address, fc),
                        reg.hex,
                        reg.uint16,
                        reg.int16,
                        pair?.float32,
                        pair?.int32,
                        reg.scaledText ?: reg.scaled,
                    )
                }
            } else {
                header(
                    context.getString(R.string.report_col_addr),
                    context.getString(R.string.report_col_modicon),
                    context.getString(R.string.report_col_status),
                    "Bit (0/1)",
                )
                bits.forEachIndexed { idx, bit ->
                    val addr = startAddress + idx
                    row(
                        addr,
                        ModbusAddress.formatModicon(addr, fc),
                        if (bit) "ON" else "OFF",
                        if (bit) 1 else 0,
                    )
                }
            }
        }
        file.outputStream().use { writer.writeTo(it) }
        return file
    }

    // ==================== 2. LAPORAN PEMINDAI SLAVE ====================

    fun exportScannerPdf(
        context: Context,
        meta: ReportMeta,
        profile: ConnectionProfile,
        startId: Int,
        endId: Int,
        baudSummary: String,
        scannedCount: Int,
        hits: List<ScanHit>,
        scanTimestampMillis: Long = System.currentTimeMillis(),
    ): File {
        val tag = meta.deviceName.ifBlank { "id${startId}_${endId}" }
        val file = createReportFile(context, "scanner", tag, "pdf", scanTimestampMillis)
        val timeStr = formatTimestamp(scanTimestampMillis)
        val brandFooter = context.getString(R.string.report_footer_brand, context.getString(R.string.app_name))
        val effectiveBaud = baudSummary.ifBlank {
            "${profile.baudRate} ${profile.dataBits}${profile.parity.shortLabel}${profile.stopBits}"
        }

        val pdf = PdfReport(footerLeftText = brandFooter, timestampText = timeStr)
        pdf.title(
            text = context.getString(R.string.report_scan_title),
            subtitle = context.getString(R.string.about_disclaimer),
        )
        pdf.sectionHeader(context.getString(R.string.report_section_header))
        pdf.keyValueBlock(buildHeaderPairs(context, meta))

        pdf.sectionHeader(context.getString(R.string.report_section_connection))
        pdf.keyValueBlock(
            listOf(
                context.getString(R.string.report_label_conn_mode) to formatConnectionSummary(profile),
                context.getString(R.string.report_label_id_range) to "$startId .. $endId",
                context.getString(R.string.report_label_baud_tried) to effectiveBaud,
                context.getString(R.string.report_label_scanned_count) to scannedCount.toString(),
                context.getString(R.string.report_label_found_count) to hits.size.toString(),
                context.getString(R.string.report_label_read_time) to timeStr,
            ),
        )

        pdf.sectionHeader(context.getString(R.string.report_section_scan_hits))
        val rows = if (hits.isEmpty()) {
            listOf(listOf("-", "-", "-", context.getString(R.string.scanner_no_hits)))
        } else {
            hits.map { hit ->
                val baudText = if (hit.baudRate != null) {
                    "${hit.baudRate} 8${hit.parity?.shortLabel ?: "N"}1"
                } else {
                    "-"
                }
                val respTimeText = hit.responseTimeMs?.let { "$it ms" } ?: "-"
                listOf(
                    hit.unitId.toString(),
                    baudText,
                    respTimeText,
                    hit.detail,
                )
            }
        }
        pdf.table(
            headers = listOf(
                context.getString(R.string.report_col_slave_id),
                context.getString(R.string.report_col_baud),
                context.getString(R.string.report_col_resp_time),
                context.getString(R.string.report_col_detail),
            ),
            weights = listOf(0.8f, 1.1f, 1.0f, 2.3f),
            rows = rows,
        )

        pdf.paragraph(context.getString(R.string.about_disclaimer), italic = true)
        pdf.signatureRow(
            leftLabel = context.getString(R.string.report_sign_prepared),
            leftName = meta.technician,
            rightLabel = context.getString(R.string.report_sign_verified),
        )
        file.outputStream().use { pdf.writeTo(it) }
        return file
    }

    fun exportScannerXlsx(
        context: Context,
        meta: ReportMeta,
        profile: ConnectionProfile,
        startId: Int,
        endId: Int,
        baudSummary: String,
        scannedCount: Int,
        hits: List<ScanHit>,
        scanTimestampMillis: Long = System.currentTimeMillis(),
    ): File {
        val tag = meta.deviceName.ifBlank { "id${startId}_${endId}" }
        val file = createReportFile(context, "scanner", tag, "xlsx", scanTimestampMillis)
        val timeStr = formatTimestamp(scanTimestampMillis)
        val effectiveBaud = baudSummary.ifBlank {
            "${profile.baudRate} ${profile.dataBits}${profile.parity.shortLabel}${profile.stopBits}"
        }

        val writer = XlsxWriter()
        writer.sheet(
            name = "Scanner",
            colWidths = listOf(22.0, 20.0, 18.0, 32.0),
        ) {
            header(context.getString(R.string.report_scan_title))
            for ((k, v) in buildHeaderPairs(context, meta)) {
                row(k, v)
            }
            row(context.getString(R.string.report_label_conn_mode), formatConnectionSummary(profile))
            row(context.getString(R.string.report_label_id_range), "$startId..$endId")
            row(context.getString(R.string.report_label_baud_tried), effectiveBaud)
            row(context.getString(R.string.report_label_scanned_count), scannedCount)
            row(context.getString(R.string.report_label_found_count), hits.size)
            row(context.getString(R.string.report_label_read_time), timeStr)
            emptyRow()

            header(
                context.getString(R.string.report_col_slave_id),
                context.getString(R.string.report_col_baud),
                context.getString(R.string.report_col_resp_time),
                context.getString(R.string.report_col_detail),
            )
            for (hit in hits) {
                val baudText = if (hit.baudRate != null) {
                    "${hit.baudRate} 8${hit.parity?.shortLabel ?: "N"}1"
                } else {
                    "-"
                }
                row(
                    hit.unitId,
                    baudText,
                    hit.responseTimeMs,
                    hit.detail,
                )
            }
        }
        file.outputStream().use { writer.writeTo(it) }
        return file
    }

    // ==================== 3. LAPORAN LOG TREN (EXCEL & PDF) ====================

    private data class ColumnStat(
        val name: String,
        val min: Double,
        val max: Double,
        val avg: Double,
    )

    private fun computeTrendStats(
        samples: List<DataSample>,
        fc: FunctionCode,
    ): Pair<Int, List<ColumnStat>> {
        val colCount = (samples.maxOfOrNull { it.registers.size } ?: 0).coerceIn(1, 8)
        val baseAddr = samples.firstOrNull()?.startAddress ?: 0
        val stats = mutableListOf<ColumnStat>()
        for (c in 0 until colCount) {
            val addr = baseAddr + c
            val modicon = ModbusAddress.formatModicon(addr, fc)
            val vals = samples.mapNotNull { it.registers.getOrNull(c)?.toDouble() }
            if (vals.isNotEmpty()) {
                stats.add(
                    ColumnStat(
                        name = "Reg[$addr] ($modicon)",
                        min = vals.minOrNull() ?: 0.0,
                        max = vals.maxOrNull() ?: 0.0,
                        avg = vals.average(),
                    ),
                )
            }
        }
        if (colCount >= 2) {
            val floatVals = samples.mapNotNull { s ->
                if (s.registers.size >= 2) {
                    val f = DecodedRegisterPair(s.startAddress, s.registers[0], s.registers[1])
                        .forOrder(ByteOrder.ABCD).float32.toDouble()
                    if (f.isFinite()) f else null
                } else {
                    null
                }
            }
            if (floatVals.isNotEmpty()) {
                stats.add(
                    ColumnStat(
                        name = "Float32 (ABCD)",
                        min = floatVals.minOrNull() ?: 0.0,
                        max = floatVals.maxOrNull() ?: 0.0,
                        avg = floatVals.average(),
                    ),
                )
            }
        }
        return colCount to stats
    }

    fun exportTrendPdf(
        context: Context,
        meta: ReportMeta,
        profile: ConnectionProfile,
        unitId: Int,
        fc: FunctionCode,
        pollIntervalMs: Int,
        samples: List<DataSample>,
    ): File {
        val now = samples.lastOrNull()?.timestampMillis ?: System.currentTimeMillis()
        val tag = meta.deviceName.ifBlank { "trend_id$unitId" }
        val file = createReportFile(context, "trend", tag, "pdf", now)
        val timeStr = formatTimestamp(now)
        val brandFooter = context.getString(R.string.report_footer_brand, context.getString(R.string.app_name))
        val startAddr = samples.firstOrNull()?.startAddress ?: 0
        val modiconStart = ModbusAddress.formatModicon(startAddr, fc)
        val (colCount, stats) = computeTrendStats(samples, fc)

        val pdf = PdfReport(footerLeftText = brandFooter, timestampText = timeStr)
        pdf.title(
            text = context.getString(R.string.report_trend_title),
            subtitle = context.getString(R.string.about_disclaimer),
        )
        pdf.sectionHeader(context.getString(R.string.report_section_header))
        pdf.keyValueBlock(buildHeaderPairs(context, meta))

        pdf.sectionHeader(context.getString(R.string.report_section_connection))
        pdf.keyValueBlock(
            listOf(
                context.getString(R.string.report_label_conn_mode) to formatConnectionSummary(profile),
                context.getString(R.string.report_label_unit_id) to unitId.toString(),
                context.getString(R.string.report_label_fc) to fc.shortName,
                context.getString(R.string.report_label_start_addr) to "$startAddr (Modicon $modiconStart)",
                context.getString(R.string.report_label_interval) to "$pollIntervalMs ms",
                context.getString(R.string.report_label_sample_count) to samples.size.toString(),
            ),
        )

        pdf.sectionHeader(context.getString(R.string.report_section_trend_stats))
        val statRows = stats.map { st ->
            listOf(
                st.name,
                fmtDec(st.min, 3),
                fmtDec(st.max, 3),
                fmtDec(st.avg, 3),
            )
        }
        pdf.table(
            headers = listOf(
                context.getString(R.string.report_col_column),
                context.getString(R.string.report_col_min),
                context.getString(R.string.report_col_max),
                context.getString(R.string.report_col_avg),
            ),
            weights = listOf(1.6f, 1.0f, 1.0f, 1.0f),
            rows = statRows,
        )

        pdf.sectionHeader(context.getString(R.string.report_section_trend_samples))
        val maxSamples = 200
        val shownSamples = samples.takeLast(maxSamples)
        val noteText = if (samples.size > maxSamples) {
            context.getString(R.string.report_trend_truncated, shownSamples.size, samples.size)
        } else {
            context.getString(R.string.report_trend_not_truncated, shownSamples.size)
        }
        pdf.paragraph(noteText, italic = true)

        val sampleHeaders = mutableListOf(context.getString(R.string.report_col_timestamp))
        for (c in 0 until colCount) {
            sampleHeaders.add("Reg[${startAddr + c}]")
        }
        if (colCount >= 2) {
            sampleHeaders.add("Float32(ABCD)")
        }
        val sampleWeights = mutableListOf(1.8f).apply {
            repeat(colCount) { add(0.85f) }
            if (colCount >= 2) add(1.1f)
        }
        val sampleRows = shownSamples.map { s ->
            val row = mutableListOf(formatIso(s.timestampMillis))
            for (c in 0 until colCount) {
                row.add(s.registers.getOrNull(c)?.toString() ?: "-")
            }
            if (colCount >= 2) {
                val fTxt = if (s.registers.size >= 2) {
                    DecodedRegisterPair(s.startAddress, s.registers[0], s.registers[1])
                        .forOrder(ByteOrder.ABCD).floatText
                } else {
                    "-"
                }
                row.add(fTxt)
            }
            row
        }
        pdf.table(headers = sampleHeaders, weights = sampleWeights, rows = sampleRows)

        pdf.paragraph(context.getString(R.string.about_disclaimer), italic = true)
        pdf.signatureRow(
            leftLabel = context.getString(R.string.report_sign_prepared),
            leftName = meta.technician,
            rightLabel = context.getString(R.string.report_sign_verified),
        )
        file.outputStream().use { pdf.writeTo(it) }
        return file
    }

    fun exportTrendXlsx(
        context: Context,
        meta: ReportMeta,
        profile: ConnectionProfile,
        unitId: Int,
        fc: FunctionCode,
        pollIntervalMs: Int,
        samples: List<DataSample>,
    ): File {
        val now = samples.lastOrNull()?.timestampMillis ?: System.currentTimeMillis()
        val tag = meta.deviceName.ifBlank { "trend_id$unitId" }
        val file = createReportFile(context, "trend", tag, "xlsx", now)
        val startAddr = samples.firstOrNull()?.startAddress ?: 0
        val modiconStart = ModbusAddress.formatModicon(startAddr, fc)
        val (colCount, stats) = computeTrendStats(samples, fc)

        val writer = XlsxWriter()
        writer.sheet(
            name = "Log",
            colWidths = listOf(26.0, 14.0, 14.0, 14.0, 14.0, 14.0, 16.0),
        ) {
            val headers = mutableListOf(
                context.getString(R.string.report_col_timestamp),
                context.getString(R.string.report_label_start_addr),
            )
            for (c in 0 until colCount) {
                headers.add("Reg[${startAddr + c}]")
            }
            if (colCount >= 2) {
                headers.add("Float32 (ABCD)")
            }
            header(*headers.toTypedArray())

            for (s in samples) {
                val values = mutableListOf<Any?>(
                    formatIso(s.timestampMillis),
                    s.startAddress,
                )
                for (c in 0 until colCount) {
                    values.add(s.registers.getOrNull(c))
                }
                if (colCount >= 2) {
                    val fVal: Double? = if (s.registers.size >= 2) {
                        val f = DecodedRegisterPair(s.startAddress, s.registers[0], s.registers[1])
                            .forOrder(ByteOrder.ABCD).float32.toDouble()
                        if (f.isFinite()) f else null
                    } else {
                        null
                    }
                    values.add(fVal)
                }
                row(*values.toTypedArray())
            }
        }

        writer.sheet(
            name = "Info",
            colWidths = listOf(24.0, 28.0, 16.0, 16.0),
        ) {
            header(context.getString(R.string.report_trend_title))
            for ((k, v) in buildHeaderPairs(context, meta)) {
                row(k, v)
            }
            row(context.getString(R.string.report_label_conn_mode), formatConnectionSummary(profile))
            row(context.getString(R.string.report_label_unit_id), unitId)
            row(context.getString(R.string.report_label_fc), fc.shortName)
            row(context.getString(R.string.report_label_start_addr), "$startAddr (Modicon $modiconStart)")
            row(context.getString(R.string.report_label_interval), "$pollIntervalMs ms")
            row(context.getString(R.string.report_label_sample_count), samples.size)
            emptyRow()

            header(
                context.getString(R.string.report_col_column),
                context.getString(R.string.report_col_min),
                context.getString(R.string.report_col_max),
                context.getString(R.string.report_col_avg),
            )
            for (st in stats) {
                row(st.name, st.min, st.max, st.avg)
            }
        }

        file.outputStream().use { writer.writeTo(it) }
        return file
    }
}

@Composable
fun ReportButtonsRow(
    enabled: Boolean,
    onExportPdf: () -> Unit,
    onExportXlsx: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Button(
            onClick = onExportPdf,
            enabled = enabled,
            modifier = Modifier.weight(1f).testTag("btn_report_pdf"),
        ) {
            Text(stringResource(R.string.report_btn_pdf))
        }
        OutlinedButton(
            onClick = onExportXlsx,
            enabled = enabled,
            modifier = Modifier.weight(1f).testTag("btn_report_xlsx"),
        ) {
            Text(stringResource(R.string.report_btn_xlsx))
        }
    }
}

@Composable
fun ReportHeaderDialog(
    initialMeta: ReportMeta,
    onConfirm: (ReportMeta) -> Unit,
    onDismiss: () -> Unit,
) {
    var deviceName by rememberSaveable { mutableStateOf(initialMeta.deviceName) }
    var technician by rememberSaveable { mutableStateOf(initialMeta.technician) }
    var company by rememberSaveable { mutableStateOf(initialMeta.company) }
    var location by rememberSaveable { mutableStateOf(initialMeta.location) }
    var notes by rememberSaveable { mutableStateOf(initialMeta.notes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("report_header_dialog"),
        title = {
            Text(
                stringResource(R.string.report_dialog_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { deviceName = it },
                    label = { Text(stringResource(R.string.report_field_device)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("input_report_device"),
                )
                OutlinedTextField(
                    value = technician,
                    onValueChange = { technician = it },
                    label = { Text(stringResource(R.string.report_field_technician)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("input_report_technician"),
                )
                OutlinedTextField(
                    value = company,
                    onValueChange = { company = it },
                    label = { Text(stringResource(R.string.report_field_company)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("input_report_company"),
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text(stringResource(R.string.report_field_location)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("input_report_location"),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.report_field_notes)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("input_report_notes"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        ReportMeta(
                            technician = technician.trim(),
                            company = company.trim(),
                            location = location.trim(),
                            notes = notes.trim(),
                            deviceName = deviceName.trim(),
                        ),
                    )
                },
                modifier = Modifier.testTag("btn_report_confirm"),
            ) {
                Text(stringResource(R.string.report_export_action))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("btn_report_cancel"),
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
