package com.edib.procurement.aipack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Minimal DOCX writer used only to build fixtures. */
object DocxFixture {

    fun build(paragraphs: List<String>, table: List<List<String>>? = null): ByteArray {
        val body = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
            paragraphs.forEach { p ->
                append("""<w:p><w:r><w:t xml:space="preserve">${esc(p)}</w:t></w:r></w:p>""")
            }
            table?.let { rows ->
                append("<w:tbl>")
                rows.forEach { r ->
                    append("<w:tr>")
                    r.forEach { c -> append("<w:tc><w:p><w:r><w:t>${esc(c)}</w:t></w:r></w:p></w:tc>") }
                    append("</w:tr>")
                }
                append("</w:tbl>")
            }
            append("</w:body></w:document>")
        }

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write("""<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml")); zip.write(body.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/styles.xml"))
            zip.write("""<?xml version="1.0"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:style w:styleId="Heading1"><w:name w:val="heading 1"/></w:style></w:styles>""".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

class DocxTextReaderTest {

    private val reader = DocxTextReader()

    @Test
    fun `paragraph order is preserved`() {
        val bytes = DocxFixture.build(
            listOf("First paragraph", "Second paragraph", "Third paragraph")
        )
        val doc = reader.read(bytes.inputStream())
        val texts = doc.blocks.filterIsInstance<DocxTextReader.Block.Paragraph>().map { it.text }
        assertEquals(listOf("First paragraph", "Second paragraph", "Third paragraph"), texts)
    }

    @Test
    fun `arabic english and mixed paragraphs are preserved exactly`() {
        val arabic = "توريد رخام تريستا للمعابر"
        val english = "Supply of marble tresta for corridors"
        val mixed = "Marble توريد رخام 25A corridor"
        val doc = reader.read(
            DocxFixture.build(listOf(arabic, english, mixed)).inputStream()
        )
        val texts = doc.blocks.filterIsInstance<DocxTextReader.Block.Paragraph>().map { it.text }
        assertEquals(listOf(arabic, english, mixed), texts)
    }

    @Test
    fun `numbers are preserved`() {
        val doc = reader.read(
            DocxFixture.build(listOf("Quantity: 1250", "Rate: 12450.75")).inputStream()
        )
        val texts = doc.blocks.filterIsInstance<DocxTextReader.Block.Paragraph>().map { it.text }
        assertEquals(listOf("Quantity: 1250", "Rate: 12450.75"), texts)
    }

    @Test
    fun `tables keep their cells and rows`() {
        val table = listOf(
            listOf("Code", "Company", "Qty"),
            listOf("MMH2-GR-AR-14-25A", "Alpha", "400")
        )
        val doc = reader.read(DocxFixture.build(listOf("Header"), table).inputStream())
        val t = doc.blocks.filterIsInstance<DocxTextReader.Block.Table>().first()
        assertEquals(2, t.rows.size)
        assertEquals(listOf("Code", "Company", "Qty"), t.rows[0])
        assertEquals(listOf("MMH2-GR-AR-14-25A", "Alpha", "400"), t.rows[1])
    }
}
