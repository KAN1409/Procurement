package com.edib.procurement.aipack

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dates are converted only where the WORKBOOK says they are dates.
 *
 * The real procurement workbook turned out to store its date columns as plain
 * numbers with the "General" format. Converting those would invent a format the
 * file never declared, so the correct behaviour there is to leave them alone -
 * which is why this test pins the negative cases as firmly as the positive one.
 */
class ExcelDateConversionTest {

    private fun workbook(styles: String, sheetCells: String): ByteArray {
        val files = mapOf(
            "[Content_Types].xml" to "<?xml version=\"1.0\"?><Types/>",
            "xl/workbook.xml" to
                "<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<sheets><sheet name=\"S\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to
                "<?xml version=\"1.0\"?><Relationships " +
                "xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" " +
                "Target=\"worksheets/sheet1.xml\"/></Relationships>",
            "xl/styles.xml" to styles,
            "xl/worksheets/sheet1.xml" to
                "<?xml version=\"1.0\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                "<sheetData><row r=\"1\">$sheetCells</row></sheetData></worksheet>"
        )
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            files.forEach { (n, c) ->
                z.putNextEntry(java.util.zip.ZipEntry(n))
                z.write(c.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun read(cells: String, styles: String = DEFAULT_STYLES): List<OpenXmlWorkbookReader.Cell> {
        val rows = ArrayList<OpenXmlWorkbookReader.Cell>()
        val sink = object : OpenXmlWorkbookReader.SheetSink {
            override fun startSheet(name: String, maxColumn: Int, merged: List<String>) {}
            override fun row(cells: List<OpenXmlWorkbookReader.Cell>) {
                rows.addAll(cells)
            }

            override fun endSheet() {}
        }
        OpenXmlWorkbookReader().read(
            ByteArrayInputStream(workbook(styles, cells)), sink
        )
        return rows
    }

    private companion object {
        // style 0 = General, 1 = built-in date (14), 2 = currency-ish custom.
        const val DEFAULT_STYLES =
            "<?xml version=\"1.0\"?><styleSheet " +
                "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"#,##0.00\"/></numFmts>" +
                "<cellXfs count=\"3\"><xf numFmtId=\"0\"/><xf numFmtId=\"14\"/>" +
                "<xf numFmtId=\"164\"/></cellXfs></styleSheet>"
    }

    @Test
    fun `a date formatted cell is rendered as ISO plus its serial`() {
        val rows = read("<c r=\"C1\" t=\"n\" s=\"1\"><v>43741</v></c>")
        assertEquals(1, rows.size)
        val v = rows[0].value
        assertTrue("expected an ISO date, got: $v", v.startsWith("2019-10-03"))
        assertTrue("the serial must remain for provenance: $v", v.contains("43741"))
    }

    @Test
    fun `a General formatted number is left exactly as it is`() {
        // This is the real workbook's case: 43741 stored with format General.
        val rows = read("<c r=\"A1\" t=\"n\" s=\"0\"><v>43741</v></c>")
        assertEquals("43741", rows[0].value)
    }

    @Test
    fun `a quantity is not turned into a date`() {
        val rows = read("<c r=\"A1\" t=\"n\" s=\"0\"><v>150</v></c>")
        assertEquals("150", rows[0].value)
    }

    @Test
    fun `a rate is not turned into a date`() {
        val rows = read("<c r=\"A1\" t=\"n\" s=\"0\"><v>2500.75</v></c>")
        assertEquals("2500.75", rows[0].value)
    }

    @Test
    fun `a currency formatted amount is not turned into a date`() {
        val rows = read("<c r=\"A1\" t=\"n\" s=\"2\"><v>43741.5</v></c>")
        assertEquals("43741.5", rows[0].value)
    }

    @Test
    fun `a date and a plain number in the same row are treated differently`() {
        val rows = read(
            "<c r=\"A1\" t=\"n\" s=\"0\"><v>150</v></c>" +
                "<c r=\"B1\" t=\"n\" s=\"1\"><v>43741</v></c>"
        )
        assertEquals(2, rows.size)
        assertEquals("150", rows[0].value)
        assertTrue(rows[1].value.startsWith("2019-10-03"))
    }

    @Test
    fun `a custom long date format is recognised`() {
        val styles =
            "<?xml version=\"1.0\"?><styleSheet " +
                "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"mmmm d, yyyy\"/></numFmts>" +
                "<cellXfs count=\"2\"><xf numFmtId=\"0\"/><xf numFmtId=\"164\"/></cellXfs></styleSheet>"
        val rows = read("<c r=\"A1\" t=\"n\" s=\"1\"><v>43741</v></c>", styles)
        assertTrue(rows[0].value.startsWith("2019-10-03"))
    }

    @Test
    fun `a workbook without styles still reads every row`() {
        val styles =
            "<?xml version=\"1.0\"?><styleSheet " +
                "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"></styleSheet>"
        val rows = read("<c r=\"A1\" t=\"n\" s=\"0\"><v>43741</v></c>", styles)
        assertEquals("no styles must not lose data", 1, rows.size)
        assertEquals("43741", rows[0].value)
    }

    @Test
    fun `a shared string is unaffected by date handling`() {
        val rows = read("<c r=\"A1\" t=\"inlineStr\"><is><t>abc</t></is></c>")
        assertEquals(1, rows.size)
    }
}
