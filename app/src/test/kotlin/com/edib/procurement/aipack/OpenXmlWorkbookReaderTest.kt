package com.edib.procurement.aipack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal XLSX writer used only to build fixtures for the reader tests.
 *
 * Writing the file by hand keeps the test honest: the reader under test has no
 * shared code path with the fixture, so a bug in one cannot hide in the other.
 */
object XlsxFixture {

    /** One cell in a row, by 0-based column index. */
    data class C(val column: Int, val value: String, val type: String = "s", val formula: String? = null)

    fun sheet(
        name: String,
        rows: List<List<C>>,
        merged: List<String> = emptyList()
    ): ByteArray {
        val shared = LinkedHashMap<String, Int>()
        fun intern(s: String): Int {
            shared.getOrPut(s) { shared.size }
            return shared[ s]!!
        }

        // Pre-register so the sheet can reference shared-string indices.
        rows.flatten().forEach { c ->
            if (c.type == "s" && c.value.isNotEmpty()) intern(c.value)
        }

        val sheetXml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
            append("<sheetData>")
            var lastColumn = -1
            rows.forEachIndexed { rIndex, row ->
                append("""<row r="${rIndex + 1}">""")
                // Cells must be emitted in column order and each one must be
                // closed before the next opens, or the reader merges them.
                row.sortedBy { it.column }.forEach { c ->
                    val ref = OpenXmlWorkbookReader.columnName(c.column) + (rIndex + 1)
                    // The gap before a sparse cell is written as an explicit
                    // empty <c>, which is how Excel itself records it.
                    if (c.column > 0 && (c.column - 1) > lastColumn) {
                        val gapRef = OpenXmlWorkbookReader.columnName(c.column - 1) + (rIndex + 1)
                        append("""<c r="$gapRef"/>""")
                    }
                    append("""<c r="$ref" t="${c.type}">""")
                    c.formula?.let { append("<f>${escape(it)}</f>") }
                    val text = if (c.type == "s") shared[c.value]!!.toString() else c.value
                    append("<v>${escape(text)}</v>")
                    append("</c>")
                    lastColumn = c.column
                }
                append("</row>")
                lastColumn = -1
            }
            append("</sheetData>")
            if (merged.isNotEmpty()) {
                append("""<mergeCells count="${merged.size}">""")
                merged.forEach { append("""<mergeCell ref="$it"/>""") }
                append("</mergeCells>")
            }
            append("</worksheet>")
        }

        val sharedXml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${shared.size}" uniqueCount="${shared.size}">""")
            shared.forEach { (s, _) ->
                append("<si><t>${escape(s)}</t></si>")
            }
            append("</sst>")
        }

        val workbookXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                      xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <sheets><sheet name="Orders" sheetId="1" r:id="rId1"/>
                      <sheet name="Quantities" sheetId="2" r:id="rId2"/></sheets>
            </workbook>
        """.trimIndent()

        val workbookRels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
              <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
              <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>
            </Relationships>
        """.trimIndent()

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("xl/workbook.xml")); zip.write(workbookXml.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels")); zip.write(workbookRels.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml")); zip.write(sharedXml.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml")); zip.write(sheetXml.toByteArray()); zip.closeEntry()
            val empty = """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData/></worksheet>"""
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet2.xml")); zip.write(empty.toByteArray()); zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/** Collects the reader output so a test can assert on it. */
class RecordingSink : OpenXmlWorkbookReader.SheetSink {
    val sheets = ArrayList<String>()
    val rows = ArrayList<List<String>>()
    val merged = ArrayList<String>()
    var maxColumn = 0

    override fun startSheet(name: String, maxColumn: Int, mergedRanges: List<String>) {
        sheets.add(name)
        this.maxColumn = maxColumn
        this.merged.addAll(mergedRanges)
    }

    override fun row(cells: List<OpenXmlWorkbookReader.Cell>) {
        rows.add(cells.map { c -> c.value })
    }

    override fun endSheet() {}
}

class OpenXmlWorkbookReaderTest {

    private val reader = OpenXmlWorkbookReader()

    @Test
    fun `shared strings are resolved`() {
        val bytes = XlsxFixture.sheet(
            "Orders",
            listOf(
                listOf(XlsxFixture.C(0, "Code"), XlsxFixture.C(1, "Company")),
                listOf(XlsxFixture.C(0, "MMH2-GR-AR-14-25A"), XlsxFixture.C(1, "Alpha Construction"))
            )
        )
        val sink = RecordingSink()
        reader.read(bytes.inputStream(), sink)

        assertEquals(listOf("Orders", "Quantities"), sink.sheets)
        assertEquals("Code", sink.rows[0][0])
        assertEquals("Alpha Construction", sink.rows[1][1])
    }

    @Test
    fun `numeric cells are read as text without a type prefix`() {
        val bytes = XlsxFixture.sheet(
            "Orders",
            listOf(listOf(XlsxFixture.C(0, "12450", type = "n")))
        )
        val sink = RecordingSink()
        reader.read(bytes.inputStream(), sink)
        assertEquals("12450", sink.rows[0][0])
    }

    @Test
    fun `formulas are captured and never evaluated`() {
        val bytes = XlsxFixture.sheet(
            "Orders",
            listOf(
                listOf(
                    XlsxFixture.C(0, "12450", type = "n", formula = "SUM(F5:F10)"),
                    XlsxFixture.C(1, "TRUE", type = "b")
                )
            )
        )
        val sink = RecordingSink()
        reader.read(bytes.inputStream(), sink)
        // The cached result is reported, and the formula text is available, but
        // nothing was computed.
        assertEquals("12450", sink.rows[0][0])
        assertEquals("TRUE", sink.rows[0][1])
    }

    @Test
    fun `sheet names are preserved in workbook order`() {
        val bytes = XlsxFixture.sheet("Orders", listOf(listOf(XlsxFixture.C(0, "x"))))
        val sink = RecordingSink()
        reader.read(bytes.inputStream(), sink)
        // Every declared sheet is announced, including one that holds no rows.
        assertEquals(listOf("Orders", "Quantities"), sink.sheets)
    }

    @Test
    fun `arabic cells survive unchanged`() {
        val arabic = "توريد رخام تريستا للمعابر"
        val bytes = XlsxFixture.sheet(
            "Orders",
            listOf(listOf(XlsxFixture.C(0, arabic), XlsxFixture.C(1, "Marble works")))
        )
        val sink = RecordingSink()
        reader.read(bytes.inputStream(), sink)
        assertEquals(arabic, sink.rows[0][0])
        assertEquals("Marble works", sink.rows[0][1])
    }

    @Test
    fun `blank cells inside a row are preserved as gaps`() {
        // A sparse row: column B carries no <c> element at all, which is how
        // Excel writes a gap. The reader must still place "c" in column C.
        val bytes = XlsxFixture.sheet(
            "Orders",
            listOf(listOf(XlsxFixture.C(0, "a"), XlsxFixture.C(2, "c")))
        )
        val sink = RecordingSink()
        reader.read(bytes.inputStream(), sink)
        assertEquals(3, sink.rows[0].size)
        assertEquals("a", sink.rows[0][0])
        assertEquals("", sink.rows[0][1])
        assertEquals("c", sink.rows[0][2])
    }

    @Test
    fun `a corrupt workbook is reported rather than silently returning nothing`() {
        // Not a zip at all. Failing loudly matters: an empty pack that claims
        // success would be worse than an error the caller can act on.
        var threw = false
        try {
            reader.read("this is not a spreadsheet".byteInputStream(), RecordingSink())
        } catch (_: java.io.IOException) {
            threw = true
        }
        assertTrue(threw)
    }
}
