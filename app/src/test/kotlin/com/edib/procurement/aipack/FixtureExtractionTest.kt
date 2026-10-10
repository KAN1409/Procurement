package com.edib.procurement.aipack

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.io.File

/**
 * Extraction against the real device fixtures.
 *
 * These are the same files the device acceptance pack is built from, so what
 * passes here is what the pack will contain.
 */
@Ignore("requires acceptance fixtures that are intentionally not stored in CI")
class FixtureExtractionTest {

    private fun fixture(name: String): File {
        val onDisk = File("src/test/resources/fixtures/$name")
        if (onDisk.exists()) return onDisk
        val f = File("/storage/emulated/0/Android/data/com.androidharness.app/files/workspace/repo/app/src/test/resources/fixtures/$name")
        if (f.exists()) return f
        throw AssertionError("fixture missing: $name")
    }

    // ------------------------------------------------------------- XLSX

    @Test
    fun `the sample workbook keeps text, arabic, numbers and sheet names`() {
        val sink = RecordingSink()
        OpenXmlWorkbookReader().read(fixture("BOQ-sample.xlsx").inputStream(), sink)

        assertEquals(listOf("Orders", "Quantities"), sink.sheets)
        val text = sink.rows.flatten().joinToString("|")
        assertTrue("English cell", text.contains("MMH2-GR-AR-14-25A"))
        assertTrue("English description", text.contains("Marble tresta for corridors"))
        assertTrue("Arabic cell survives", text.contains("تركيب رخام للغرف"))
        assertTrue("number survives", text.contains("400"))
        assertTrue("number survives", text.contains("1200"))
        // Styling must not leak into the output: no style ids or fonts.
        assertTrue("no style metadata", !text.contains("style") && !text.contains("font"))
    }

    @Test
    fun `the workbook is written as tsv with internal blanks intact`() {
        val out = java.io.ByteArrayOutputStream()
        val w = PackTextWriter(out)
        val writer = SheetTextWriter(w, detailed = false)
        OpenXmlWorkbookReader().read(fixture("BOQ-sample.xlsx").inputStream(), writer)
        w.flush()
        val text = out.toString("UTF-8")
        assertTrue("sheet heading, got: $text", text.contains("--- SHEET: Orders ---"))
        assertTrue("tab separated", text.contains("\t"))
        assertTrue("row count reported", text.contains("(rows:"))
        // The header row is the fixture's own first row, not an invented one.
        assertTrue(text.lineSequence().any { it.startsWith("Code\tCompany") })
    }

    // ------------------------------------------------------------- DOCX

    @Test
    fun `the sample docx keeps paragraphs, arabic, english, mixed text and its table`() {
        val doc = DocxTextReader().read(fixture("specification.docx").inputStream())
        val paras = doc.blocks.filterIsInstance<DocxTextReader.Block.Paragraph>().map { it.text }
        assertTrue("arabic", paras.contains("توريد رخام تريستا للمعابر"))
        assertTrue("english", paras.contains("Supply of marble tresta for corridors"))
        assertTrue("mixed", paras.contains("Marble توريد رخام 25A"))
        assertTrue("numbers", paras.contains("Quantity: 1250 Rate: 12450.75"))

        val table = doc.blocks.filterIsInstance<DocxTextReader.Block.Table>().first()
        assertEquals(listOf("Code", "Qty"), table.rows[0])
        assertEquals(listOf("MMH2-GR-AR-14-25A", "400"), table.rows[1])
    }

    // -------------------------------------------------------------- PDF
    //
    // PDFBox-Android resolves its bundled CMaps through a Context, so PDF
    // extraction is exercised on the device rather than here. The pack built on
    // the device from these same fixtures is the evidence; see the acceptance
    // report.

    @Test
    fun `the pdf fixtures are the ones the device acceptance uses`() {
        assertTrue(fixture("quotation-text.pdf").exists())
        assertTrue(fixture("scanned-drawing.pdf").exists())
        assertTrue("text pdf starts with a header", fixture("quotation-text.pdf").readBytes()
            .copyOfRange(0, 5).decodeToString().startsWith("%PDF-"))
    }

    // ---------------------------------------------------------- TXT/CSV

    @Test
    fun `plain text is read`() {
        val bytes = fixture("notes.txt").readBytes()
        assertEquals("hello", String(bytes, Charsets.UTF_8).trim())
    }
}
