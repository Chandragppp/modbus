package id.controlengineer.modbus

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.controlengineer.modbus.protocol.ByteOrder
import id.controlengineer.modbus.protocol.ReportMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Alur UI utama di emulator. Tiap tes menyimpan screenshot ke filesDir/shots (diambil skrip CI lewat run-as).
 */
@RunWith(AndroidJUnit4::class)
class UiFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        launchMode(pro = "off", tab = 0, demo = 1)
    }

    private fun str(id: Int) = rule.activity.getString(id)

    private fun click(tag: String) = rule.onNodeWithTag(tag).performScrollTo().performClick()

    private fun type(tag: String, text: String) {
        rule.onNodeWithTag(tag).performScrollTo().performTextClearance()
        rule.onNodeWithTag(tag).performTextInput(text)
    }

    private fun launchMode(
        pro: String = "off",
        tab: Int = 0,
        subtab: Int = 0,
        demo: Int = 1,
        trial: String? = null,
        welcome: Boolean = false,
    ) {
        rule.runOnUiThread {
            val intent = Intent(rule.activity, MainActivity::class.java)
                .putExtra("pro", pro)
                .putExtra("tab", tab)
                .putExtra("subtab", subtab)
                .putExtra("demo", demo)
                .putExtra("welcome", welcome)
            if (trial != null) {
                intent.putExtra("trial", trial)
            }
            rule.activity.applyIntent(intent)
        }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val target = if (rule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()) {
            rule.onNode(isDialog())
        } else {
            rule.onRoot()
        }
        val bmp = try {
            target.captureToImage().asAndroidBitmap()
        } catch (e: AssertionError) {
            android.util.Log.w("UiFlowTest", "screenshot $name gagal: ${e.message}")
            return
        }
        val dir = File(rule.activity.filesDir, "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // 1. Mode demo=1: baca 2 register menampilkan Float32 123.456 yang benar dalam 4 urutan byte gratis.
    @Test
    fun demoReadTwoRegistersDisplaysCorrectFloat32() {
        launchMode(pro = "off", tab = 1, demo = 1)
        type("input_rw_address", "40001")
        rule.onNodeWithTag("rw_address_preview").assertTextContains("0x0000", substring = true)
        click("btn_read_once")
        rule.waitForIdle()

        rule.onNodeWithTag("reg_row_0").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("reg_row_1").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("pair_0_order_ABCD", useUnmergedTree = true)
            .performScrollTo()
            .assertTextContains("Float32=123.456", substring = true)
        rule.onNodeWithTag("pair_0_order_CDAB", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        shot("rw-demo-float32")
    }

    // 2. Monitor lalu lintas menampilkan frame TX dan RX dalam hex dengan lencana CRC OK.
    @Test
    fun monitorDisplaysTxAndRxFrames() {
        launchMode(pro = "off", tab = 1, demo = 1)
        click("btn_read_once")
        rule.waitForIdle()

        rule.onNodeWithTag("nav_monitor").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("frame_item_0").assertIsDisplayed()
        rule.onNodeWithTag("frame_hex_0", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("frame_badge_0", useUnmergedTree = true).assertTextEquals("CRC OK")
        shot("monitor-frames")
    }

    // 3. Tab Pro terkunci saat pro=off dan tombol Unlock Pro membuka paywall.
    @Test
    fun proTabLockedWhenProOffAndOpensPaywall() {
        launchMode(pro = "off", tab = 3, demo = 1)
        rule.onNodeWithTag("locked_screen").assertIsDisplayed()
        rule.onNodeWithTag("locked_title").assertIsDisplayed()
        rule.onNodeWithTag("btn_scan_start").assertDoesNotExist()
        shot("pro-off-locked")

        click("btn_unlock_pro")
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("paywall_sheet").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("paywall_sheet").assertIsDisplayed()
        rule.onNodeWithTag("btn_watch_ad").assertIsDisplayed()
        rule.onNodeWithTag("btn_buy_pro").assertIsDisplayed()
        shot("pro-off-paywall")
    }

    // 4. Pemindai (Scanner) pada mode demo=1 & pro=on menemukan slave tiruan (ID 1 dan ID 17).
    @Test
    fun demoScannerFindsSimulatedSlaves() {
        launchMode(pro = "on", tab = 3, subtab = 0, demo = 1)
        type("input_scan_start", "1")
        type("input_scan_end", "20")
        click("btn_scan_start")

        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("scan_hit_1").fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithTag("scan_hit_17").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("scan_hit_1").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("scan_hit_17").performScrollTo().assertIsDisplayed()
        shot("scanner-demo-hits")
    }

    // 5. Tulis register memunculkan dialog konfirmasi sebelum dieksekusi, dan Tentang memuat penafian independen.
    @Test
    fun writeShowsConfirmationDialogAndAboutShowsDisclaimer() {
        launchMode(pro = "off", tab = 1, demo = 1)
        type("input_write_values", "500")
        click("btn_write_submit")
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("write_confirm_dialog").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("write_confirm_dialog").assertIsDisplayed()
        shot("write-confirm-dialog")
        rule.onNodeWithTag("btn_write_confirm").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("nav_about").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("about_disclaimer_text").assertTextEquals(str(R.string.about_disclaimer))
        shot("about-disclaimer")
    }

    // 6. Mode demo=1 & pro=on: baca 10 holding register -> buat laporan PDF & XLSX langsung, render halaman 1 ke shot("report-pdf-page1").
    @Test
    fun reportPdfAndXlsxGeneratedFromTenHoldingRegisters() {
        launchMode(pro = "on", tab = 1, demo = 1)
        type("input_rw_address", "40001")
        type("input_rw_quantity", "10")
        click("btn_read_once")

        val session = rule.activity.sessionController
        rule.waitUntil(5_000) {
            session.lastRegisters.value.size == 10
        }
        assertEquals(10, session.lastRegisters.value.size)

        val meta = ReportMeta(
            technician = "Andi Pratama",
            company = "PT Kontrol Industri",
            location = "Panel Utama MDP-01",
            notes = "Pemeriksaan rutin parameter daya & VFD",
            deviceName = "Power meter PM-01",
        )
        val pdfFile = ModbusReportExporter.exportRegisterPdf(
            context = rule.activity,
            meta = meta,
            profile = session.profile.value,
            unitId = session.lastUnitId.value,
            fc = session.lastFunctionCode.value,
            startAddress = session.lastStartAddress.value,
            registers = session.lastRegisters.value,
            bits = session.lastBits.value,
            scale = null,
            byteOrder = ByteOrder.ABCD,
            readTimestampMillis = session.lastReadTimestampMillis.value,
        )
        assertTrue(pdfFile.exists() && pdfFile.length() > 0L)

        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                assertTrue(renderer.pageCount >= 1)
                renderer.openPage(0).use { page ->
                    val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                    Canvas(bmp).drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val dir = File(rule.activity.filesDir, "shots").apply { mkdirs() }
                    File(dir, "report-pdf-page1.png").outputStream().use {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
        }

        val xlsxFile = ModbusReportExporter.exportRegisterXlsx(
            context = rule.activity,
            meta = meta,
            profile = session.profile.value,
            unitId = session.lastUnitId.value,
            fc = session.lastFunctionCode.value,
            startAddress = session.lastStartAddress.value,
            registers = session.lastRegisters.value,
            bits = session.lastBits.value,
            scale = null,
            byteOrder = ByteOrder.ABCD,
            readTimestampMillis = session.lastReadTimestampMillis.value,
        )
        assertTrue(xlsxFile.exists() && xlsxFile.length() > 0L)
        val zipEntries = mutableSetOf<String>()
        ZipInputStream(xlsxFile.inputStream()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                zipEntries.add(entry.name)
                zis.closeEntry()
            }
        }
        assertTrue(zipEntries.contains("xl/workbook.xml"))
        assertTrue(zipEntries.contains("xl/worksheets/sheet1.xml"))
    }

    // 7. Saat pro=off, tombol laporan (PDF maupun Excel) membuka paywall.
    @Test
    fun reportButtonsOpenPaywallWhenProOff() {
        launchMode(pro = "off", tab = 1, demo = 1)
        click("btn_report_pdf")
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("paywall_sheet").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("paywall_sheet").assertIsDisplayed()
        click("paywall_close")
        rule.waitForIdle()

        click("btn_report_xlsx")
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("paywall_sheet").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("paywall_sheet").assertIsDisplayed()
    }
}
