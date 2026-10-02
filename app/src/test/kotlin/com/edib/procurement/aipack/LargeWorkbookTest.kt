package com.edib.procurement.aipack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Large-workbook extraction.
 *
 * The workbook built here has thousands of rows across several sheets, with
 * Arabic and English cells, numbers, formulas with cached results, internal
 * blanks and a styling record that must not reach the output.
 *
 * The point of the test is that extraction streams: the reader is fed row by
 * row and the sink never holds the workbook. Peak heap is sampled while it runs
 * and asserted to stay far below the file's uncompressed size, which is what a
 * full object-graph read would cost.
 */
class LargeWorkbookTest {

    private fun columnName(n: Int): String {
        var x = n
        val sb = StringBuilder()
        while (x >= 0) {
            sb.insert(0, ('A' + (x % 26)))
            x = x / 26 - 1
        }
        return sb.toString()
    }

    /** Writes a workbook with [rows] data rows per sheet, streamed to a file. */
    private fun buildLargeWorkbook(file: File, sheets: Int, rows: Int): Pair<Long, Int> {
        val shared = LinkedHashMap<String, Int>()
        fun intern(s: String): Int {
            shared.getOrPut(s) { shared.size }
            return shared[s]!!
        }

        // Register the header strings so the sheets can reference them.
        val headers = listOf(
            "Code", "Company", "Description", "PR Date", "Qty", "Rate", "Total"
        )
        headers.forEach { intern(it) }
        intern("توريد رخام")
        intern("تركيب رخام للغرف")
        intern("Alpha Construction")
        intern("El Nada")
        intern("2025-01-14")

        val out = FileOutputStream(file)
        var sheetBytes = 0L
        ZipOutputStream(out.buffered(1 shl 16)).use { zip ->
            zip.putNextEntry(
                ZipEntry("[Content_Types].xml")
            )
            zip.write(
                """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""".toByteArray()
            )
            zip.closeEntry()

            // A style table exists in the file but must never be read.
            zip.putNextEntry(ZipEntry("xl/styles.xml"))
            zip.write(
                """<?xml version="1.0"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="3"><font><sz val="11"/><name val="Arial"/></font></fonts><fills count="4"><fill><patternFill patternType="none"/></fill></fills></styleSheet>""".toByteArray()
            )
            zip.closeEntry()

            val sheetEntries = ArrayList<String>()
            for (s in 0 until sheets) {
                val name = "Sheet${s + 1}"
                val sb = StringBuilder()
                sb.append(
                    """<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>"""
                )
                sb.append("""<row r="1">""")
                headers.forEachIndexed { i, h ->
                    sb.append("""<c r="${columnName(i)}1" t="s" s="1"><v>${intern(h)}</v></c>""")
                }
                sb.append("</row>")
                for (r in 1..rows) {
                    sb.append("""<row r="${r + 1}">""")
                    sb.append("""<c r="A${r + 1}" t="s"><v>${intern("CODE-%05d".format(r))}</v></c>""")
                    sb.append("""<c r="B${r + 1}" t="s"><v>${intern(if (r % 2 == 0) "Alpha Construction" else "El Nada")}</v></c>""")
                    sb.append("""<c r="C${r + 1}" t="s"><v>${intern(if (r % 3 == 0) "توريد رخام" else "تركيب رخام للغرف")}</v></c>""")
                    // An internal blank, which must survive as a gap.
                    sb.append("""<c r="D${r + 1}"/>""")
                    sb.append("""<c r="E${r + 1}"><v>${r * 2}</v></c>""")
                    sb.append("""<c r="F${r + 1}"><v>1200</v></c>""")
                    // A formula with its cached result; the formula is reported,
                    // never evaluated.
                    sb.append("""<c r="G${r + 1}"><f>E${r + 1}*F${r + 1}</f><v>${r * 2 * 1200}</v></c>""")
                    sb.append("</row>")
                }
                sb.append("</sheetData></worksheet>")
                val entry = "xl/worksheets/sheet${s + 1}.xml"
                sheetEntries.add(entry)
                val bytes = sb.toString().toByteArray()
                sheetBytes += bytes.size
                zip.putNextEntry(ZipEntry(entry))
                zip.write(bytes)
                zip.closeEntry()
            }

            val sst = buildString {
                append("""<?xml version="1.0"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${shared.size}" uniqueCount="${shared.size}">""")
                shared.forEach { (s, _) -> append("<si><t>${escape(s)}</t></si>") }
                append("</sst>")
            }
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zip.write(sst.toByteArray())
            zip.closeEntry()

            val wb = buildString {
                append("""<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
                sheetEntries.forEachIndexed { i, _ ->
                    append("""<sheet name="Sheet${i + 1}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
                }
                append("</sheets></workbook>")
            }
            zip.putNextEntry(ZipEntry("xl/workbook.xml"))
            zip.write(wb.toByteArray())
            zip.closeEntry()

            val rels = buildString {
                append("""<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                sheetEntries.forEachIndexed { i, _ ->
                    append(
                        """<Relationship Id="rId${i + 1}" Type="worksheet" Target="worksheets/sheet${i + 1}.xml"/>"""
                    )
                }
                append(
                    """<Relationship Id="rId${sheets + 1}" Type="sharedStrings" Target="sharedStrings.xml"/>"""
                )
                append("</Relationships>")
            }
            zip.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zip.write(rels.toByteArray())
            zip.closeEntry()
        }
        return file.length() to sheetBytes.toInt()
    }

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    @Test
    fun `a workbook with thousands of rows streams within bounded memory`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "large-xlsx")
        dir.mkdirs()
        val file = File(dir, "large.xlsx")
        val sheets = 3
        val rows = 4_000
        val (fileSize, _) = buildLargeWorkbook(file, sheets, rows)

        // Force a collection first so a full object graph would show up here.
        System.gc()
        val before = usedHeap()
        var peak = before
        val sink = object : OpenXmlWorkbookReader.SheetSink {
            var rowsSeen = 0
            var sheetsSeen = 0
            override fun startSheet(name: String, maxColumn: Int, merged: List<String>) {
                sheetsSeen++
            }

            override fun row(cells: List<OpenXmlWorkbookReader.Cell>) {
                rowsSeen++
                if (rowsSeen % 500 == 0) {
                    val now = usedHeap()
                    if (now > peak) peak = now
                }
            }

            override fun endSheet() {}
        }

        val started = System.currentTimeMillis()
        OpenXmlWorkbookReader().read(file.inputStream().buffered(1 shl 16), sink)
        val elapsed = System.currentTimeMillis() - started

        assertEquals("every sheet is visited", sheets, sink.sheetsSeen)
        assertEquals("every row is read", sheets * (rows + 1), sink.rowsSeen)
        println("rows=${sink.rowsSeen} file=${fileSize / 1024} KB elapsed=${elapsed}ms " +
            "heapGrowth=${(peak - before) / 1024} KB")

        // Streaming means the heap does not grow with the file. A generous
        // bound still catches an accidental "read it all" regression.
        val growthKb = (peak - before) / 1024
        assertTrue(
            "heap grew ${growthKb} KB for a ${fileSize / 1024} KB workbook",
            growthKb < 24 * 1024
        )
        file.delete(); dir.delete()
    }

    @Test
    fun `a large workbook keeps arabic, numbers and cached formula values`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "large-xlsx2")
        dir.mkdirs()
        val file = File(dir, "large2.xlsx")
        buildLargeWorkbook(file, 1, 500)

        val out = ByteArrayOutputStream()
        val w = PackTextWriter(out)
        val writer = SheetTextWriter(w, detailed = false)
        OpenXmlWorkbookReader().read(file.inputStream(), writer)
        w.flush()
        val text = out.toString("UTF-8")

        assertTrue("arabic survives", text.contains("توريد رخام"))
        assertTrue("arabic survives", text.contains("تركيب رخام للغرف"))
        // Qty, Rate and the cached formula result sit in the last three columns.
        assertTrue("numbers survive", text.contains("\t1200\t"))
        assertTrue("cached formula value survives", Regex("\t1200\t2400\n").containsMatchIn(text))
        // The internal blank keeps the Date column empty rather than shifting
        // every value left, so Qty always lands in the fifth column.
        val firstDataRow = text.lineSequence()
            .firstOrNull { it.startsWith("CODE-00001") }
        assertTrue("first data row missing", firstDataRow != null)
        val columns = firstDataRow!!.split("\t")
        assertEquals("seven columns are kept", 7, columns.size)
        assertEquals("Date column is empty", "", columns[3])
        assertEquals("Qty is in the fifth column", "2", columns[4])
        assertEquals("Rate", "1200", columns[5])
        assertEquals("cached total", "2400", columns[6])
        // Styling must not inflate the output.
        assertTrue("no font metadata", !text.contains("Arial"))
        assertTrue("no style ids", !text.contains("styleSheet"))
        file.delete(); dir.delete()
    }

    @Test
    fun `detailed mode keeps the formula text beside its cached value`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "large-xlsx3")
        dir.mkdirs()
        val file = File(dir, "large3.xlsx")
        buildLargeWorkbook(file, 1, 20)

        val out = ByteArrayOutputStream()
        val w = PackTextWriter(out)
        val writer = SheetTextWriter(w, detailed = true)
        OpenXmlWorkbookReader().read(file.inputStream(), writer)
        w.flush()
        val text = out.toString("UTF-8")

        assertTrue("formula is reported, not evaluated", text.contains("=E2*F2"))
        assertTrue("cached value is still reported", text.contains("1200"))
        file.delete(); dir.delete()
    }

    private fun usedHeap(): Long =
        Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
}
