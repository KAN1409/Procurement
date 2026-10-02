package com.edib.procurement.aipack

import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.xml.sax.Attributes
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.SAXNotSupportedException
import org.xml.sax.helpers.DefaultHandler

/**
 * Read-only DOCX text extraction.
 *
 * A DOCX is an OpenXML ZIP whose `word/document.xml` holds the body. Only that
 * part is read: fonts, themes, styles, numbering definitions, headers, footers
 * and relationships are never opened, which is both faster and much smaller in
 * the output than anything that tried to preserve the document's look.
 *
 * Document order is preserved, because that is the order the reader of the pack
 * needs. Tables come out as tab separated rows, which keeps their cell
 * boundaries without spending characters on a Markdown grid.
 */
class DocxTextReader {

    /** One logical block, in document order. */
    sealed interface Block {
        data class Paragraph(val text: String, val heading: Boolean) : Block
        data class Table(val rows: List<List<String>>) : Block
    }

    class Document(val blocks: List<Block>) {
        val isEmpty: Boolean get() = blocks.all { block ->
            when (block) {
                is Block.Paragraph -> block.text.isBlank()
                is Block.Table -> block.rows.all { r -> r.all { c -> c.isBlank() } }
            }
        }
    }

    fun read(input: InputStream): Document {
        // A ZIP is sequential: parsing one part consumes the stream the next
        // part would come from, so the container is materialised first. Only
        // the two parts that carry text are kept, which bounds the memory to
        // the document's own XML.
        var documentXml: ByteArray? = null
        var stylesXml: ByteArray? = null

        ZipInputStream(input).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                when (entry!!.name) {
                    "word/document.xml" -> documentXml = readAll(zip)
                    // Read only to learn which style ids are headings; the
                    // definitions themselves are not carried into the output.
                    "word/styles.xml" -> stylesXml = readAll(zip)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val body = documentXml ?: throw IOException("DOCX has no word/document.xml")
        val headingStyles = stylesXml?.let { headingStyleIds(it) } ?: emptySet()

        val handler = BodyHandler(headingStyles)
        run(body.inputStream(), handler)
        return Document(handler.blocks())
    }

    private fun readAll(zip: ZipInputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream(64 * 1024)
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = zip.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Style ids whose name starts with "heading", e.g. "Heading1". */
    private fun headingStyleIds(styles: ByteArray): Set<String> {
        val out = HashSet<String>()
        val handler = object : DefaultHandler() {
            private var currentId: String? = null
            private var inName = false

            override fun startElement(u: String?, l: String?, q: String?, a: Attributes?) {
                when (l) {
                    "style" -> currentId = a?.getValue("styleId") ?: a?.getValue("w:styleId")
                    "name" -> inName = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inName && currentId != null) {
                    val name = String(ch, start, length)
                    if (name.contains("heading", ignoreCase = true)) out.add(currentId!!)
                }
            }

            override fun endElement(u: String?, l: String?, q: String?) {
                if (l == "name") inName = false
                if (l == "style") {
                    inName = false
                    currentId = null
                }
            }
        }
        run(styles.inputStream(), handler)
        return out
    }

    private class BodyHandler(private val headingStyles: Set<String>) : DefaultHandler() {

        private val out = ArrayList<Block>()
        private val text = StringBuilder()

        // Paragraph state
        private var inParagraph = false
        private var paragraphStyle: String? = null
        private var inHeadingStyle = false

        // Table state
        private var inTable = false
        private var inRow = false
        private var inCell = false
        private val cellText = StringBuilder()
        private var row = ArrayList<String>()
        private val table = ArrayList<List<String>>()

        fun blocks(): List<Block> {
            flushParagraph()
            flushTable()
            return out
        }

        private fun flushParagraph() {
            if (!inParagraph) return
            val value = text.toString().trim()
            if (value.isNotEmpty()) out.add(Block.Paragraph(value, inHeadingStyle))
            text.setLength(0)
            inParagraph = false
            inHeadingStyle = false
            paragraphStyle = null
        }

        private fun flushCell() {
            if (!inCell) return
            row.add(cellText.toString().trim())
            cellText.setLength(0)
            inCell = false
        }

        private fun flushRow() {
            if (!inRow) return
            // A row whose last cell never closed still has to be kept.
            flushCell()
            table.add(ArrayList(row))
            row.clear()
            inRow = false
        }

        private fun flushTable() {
            if (!inTable) return
            flushRow()
            if (table.any { r -> r.any { c -> c.isNotBlank() } }) {
                out.add(Block.Table(ArrayList(table)))
            }
            table.clear()
            inTable = false
        }

        override fun startElement(u: String?, l: String?, q: String?, a: Attributes?) {
            when (l) {
                "p" -> {
                    // A cell contains a paragraph, so an opening <w:p> inside a
                    // table must not be treated as a body paragraph: that would
                    // close the table half way through its first row.
                    if (inTable) {
                        inCell = true
                        cellText.setLength(0)
                    } else {
                        flushParagraph()
                        inParagraph = true
                        val style = a?.getValue("val")
                        paragraphStyle = style
                        inHeadingStyle = style != null && headingStyles.contains(style)
                    }
                }

                "tbl" -> {
                    flushParagraph()
                    flushTable()
                    inTable = true
                }

                "tr" -> {
                    flushCell()
                    flushRow()
                    inRow = true
                }

                "tc" -> {
                    flushCell()
                    inCell = true
                    cellText.setLength(0)
                }
                // Line and page breaks are structure, not words.
                "br" -> if (inParagraph) text.append('\n')
                "tab" -> if (inParagraph) text.append('\t')

                "t" -> Unit
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inCell) cellText.appendRange(ch, start, start + length)
            else if (inParagraph) text.appendRange(ch, start, start + length)
        }

        override fun endElement(u: String?, l: String?, q: String?) {
            when (l) {
                "p" -> if (inTable) flushCell() else flushParagraph()
                "tbl" -> flushTable()
                "tr" -> flushRow()
                "tc" -> flushCell()
            }
        }
    }

    private fun newReader(): javax.xml.parsers.SAXParser {
        val factory = javax.xml.parsers.SAXParserFactory.newInstance()
        // WordprocessingML tags are namespaced, so the local name is what the
        // handlers match on.
        factory.isNamespaceAware = true
        factory.isValidating = false
        val parser = factory.newSAXParser()
        val reader = parser.xmlReader
        reader.setEntityResolver { _, _ ->
            org.xml.sax.InputSource(java.io.StringReader(""))
        }
        setFeature(reader, "http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature(reader, "http://xml.org/sax/features/external-general-entities", false)
        setFeature(reader, "http://xml.org/sax/features/external-parameter-entities", false)
        return parser
    }

    private fun setFeature(reader: org.xml.sax.XMLReader, name: String, value: Boolean) {
        try {
            reader.setFeature(name, value)
        } catch (_: SAXNotRecognizedException) {
        } catch (_: SAXNotSupportedException) {
        }
    }

    private fun run(stream: InputStream, handler: DefaultHandler) {
        try {
            newReader().parse(stream, handler)
        } catch (_: Exception) {
            // Keep what was read so far.
        }
    }
}
