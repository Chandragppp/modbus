package id.controlengineer.modbus.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.xml.sax.InputSource

class XlsxWriterTest {

    @Test
    fun generatesValidZipEntriesAndWellFormedXml() {
        val sheets = listOf(
            XlsxSheet(
                name = "Register",
                colWidths = listOf(12.0, 16.0, 14.0),
                rows = listOf(
                    listOf(
                        XlsxCell.Text("Perangkat & <Slave>", bold = true),
                        XlsxCell.Text("PM-01 \"Main\" 'A'"),
                    ),
                    listOf(
                        XlsxCell.Text("40001", bold = false),
                        XlsxCell.Num(123.456),
                        XlsxCell.Blank(),
                    ),
                ),
            ),
            XlsxSheet(
                name = "Info",
                rows = listOf(
                    listOf(XlsxCell.Text("Mode", bold = true), XlsxCell.Text("Modbus TCP")),
                ),
            ),
        )

        val baos = ByteArrayOutputStream()
        XlsxWriter.write(baos, sheets)
        val bytes = baos.toByteArray()
        assertTrue(bytes.isNotEmpty())

        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val content = zis.readBytes().toString(Charsets.UTF_8)
                entries[entry.name] = content
                zis.closeEntry()
            }
        }

        assertTrue(entries.containsKey("[Content_Types].xml"))
        assertTrue(entries.containsKey("_rels/.rels"))
        assertTrue(entries.containsKey("xl/workbook.xml"))
        assertTrue(entries.containsKey("xl/_rels/workbook.xml.rels"))
        assertTrue(entries.containsKey("xl/styles.xml"))
        assertTrue(entries.containsKey("xl/worksheets/sheet1.xml"))
        assertTrue(entries.containsKey("xl/worksheets/sheet2.xml"))

        // Parse setiap entri XML dengan DocumentBuilderFactory untuk memastikan XML well-formed
        val dbf = DocumentBuilderFactory.newInstance()
        for ((name, xml) in entries) {
            val doc = dbf.newDocumentBuilder().parse(InputSource(xml.reader()))
            assertTrue(doc.documentElement != null, "Root element missing in $name")
        }

        val sheet1Xml = entries.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet1Xml.contains("Perangkat &amp; &lt;Slave&gt;"))
        assertTrue(sheet1Xml.contains("PM-01 &quot;Main&quot; &apos;A&apos;"))
        assertTrue(sheet1Xml.contains("<v>123.456</v>"))

        val wbXml = entries.getValue("xl/workbook.xml")
        assertTrue(wbXml.contains("""name="Register""""))
        assertTrue(wbXml.contains("""name="Info""""))
    }

    @Test
    fun builderDslWritesHeadersNumericCellsAndBlankCells() {
        val baos = ByteArrayOutputStream()
        XlsxWriter()
            .sheet("Log", colWidths = listOf(22.0, 12.0, 14.0, 14.0)) {
                row("Perangkat", "Power meter PM-01")
                emptyRow()
                header("Timestamp", "Alamat", "Reg0", "Float32")
                row("2026-01-01T00:00:00Z", 0, 17142, 123.456)
                row("2026-01-01T00:00:01Z", 0, 17142, null)
            }
            .sheet("Info") {
                header("Parameter", "Nilai")
                row("Status", "OK")
            }
            .writeTo(baos)

        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(baos.toByteArray())).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                entries[entry.name] = zis.readBytes().toString(Charsets.UTF_8)
                zis.closeEntry()
            }
        }

        val dbf = DocumentBuilderFactory.newInstance()
        for ((_, xml) in entries) {
            val doc = dbf.newDocumentBuilder().parse(InputSource(xml.reader()))
            assertTrue(doc.documentElement != null)
        }

        val sheet1 = entries.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet1.contains("""<cols><col min="1" max="1" width="22.00" customWidth="1"/>"""))
        assertTrue(sheet1.contains("""<c r="D4"><v>123.456</v></c>"""))
        assertTrue(!sheet1.contains("""r="D5""""))
    }

    @Test
    fun columnNamingCoversSingleAndDoubleLetters() {
        assertEquals("A", XlsxWriter.colName(0))
        assertEquals("Z", XlsxWriter.colName(25))
        assertEquals("AA", XlsxWriter.colName(26))
        assertEquals("AZ", XlsxWriter.colName(51))
        assertEquals("BA", XlsxWriter.colName(52))
    }

    @Test
    fun reportMetaEncodeDecodeRoundTrip() {
        val meta = ReportMeta(
            technician = "Budi\tSantoso",
            company = "PT Otomasi\nNusantara",
            location = "Panel Utama #2",
            notes = "Uji beban normal",
            deviceName = "Power meter PM-01",
        )
        val encoded = meta.encode()
        assertEquals(meta, ReportMeta.decode(encoded))
        assertEquals(ReportMeta(), ReportMeta.decode(""))
    }
}
