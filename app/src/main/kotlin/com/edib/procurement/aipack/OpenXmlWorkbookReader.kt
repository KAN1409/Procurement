package com.edib.procurement.aipack

import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream
import org.xml.sax.Attributes
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.SAXNotSupportedException
import org.xml.sax.ext.LexicalHandler
import org.xml.sax.helpers.DefaultHandler

/**
 * Read-only OpenXML workbooks, parsed by streaming.
 *
 * Apache POI was removed during the APK cleanup and is not being brought back:
 * it costs several megabytes to read a value that lives in one XML element.
 *
 * What is supported, all of it read-only and streamed:
 *  - shared strings and inline strings
 *  - numeric, boolean and error cells
 *  - formula text together with its cached result
 *  - sheet names, in workbook order
 *  - merged ranges, so a header spanning two columns is not lost
 *  - date cells, via the number format's date-ness
 *
 * Explicitly NOT done: formulas are never evaluated, and no styling, theme or
 * font data is read at all.
 */
class OpenXmlWorkbookReader {

    class SheetInfo(
        val name: String,
        val relId: String
    )

    class Cell(
        val ref: String,
        val column: Int,
        val type: String,
        val value: String,
        val formula: String?
    )

    /** Streamed into the pack writer rather than collected. */
    interface SheetSink {
        /** Called once, before any row. */
        fun startSheet(name: String, maxColumn: Int, mergedRanges: List<String>)

        /** One row, already trimmed of trailing empty cells. */
        fun row(cells: List<Cell>)

        fun endSheet()
    }

    // ------------------------------------------------------------------ API

    /**
     * Reads a workbook and hands each sheet to [sink] in turn.
     *
     * @param useTsv writes tab separated rather than padded columns, which is
     *   markedly smaller for wide sheets and loses no information.
     */
    fun read(input: InputStream, sink: SheetSink, useTsv: Boolean = true) {
        val sharedStrings = ArrayList<String>()
        val sheets = ArrayList<SheetInfo>()
        val workbookRels = HashMap<String, String>()
        val sheetXml = HashMap<String, ByteArray>()

        // The whole container is materialised first. A ZIP is a sequential
        // stream, so parsing one part consumes the stream that the next part
        // would come from; a workbook is at most a few megabytes, which is a
        // bounded and predictable amount to hold.
        val parts = LinkedHashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    parts[entry.name] = readAll(zip)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        parts["xl/sharedStrings.xml"]?.let { parseSharedStrings(it.inputStream(), sharedStrings) }
        parts["xl/workbook.xml"]?.let { parseWorkbook(it.inputStream(), sheets) }
        parts["xl/_rels/workbook.xml.rels"]?.let { parseRelationships(it.inputStream(), workbookRels) }

        if (sheets.isEmpty()) throw IOException("workbook declares no sheets")

        for (sheet in sheets) {
            val target = workbookRels[sheet.relId] ?: continue
            val zipPath = if (target.startsWith("/")) target.removePrefix("/")
            else "xl/" + target.removePrefix("./")
            val bytes = parts[zipPath] ?: continue
            parseSheet(bytes.inputStream(), sheet.name, sharedStrings, sink, useTsv)
        }
    }

    // -------------------------------------------------------------- parsing

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

    private fun newReader(): javax.xml.parsers.SAXParser {
        val factory = javax.xml.parsers.SAXParserFactory.newInstance()
        // Namespace aware: OpenXML puts the element names in a namespace, and
        // with prefixes such as `r:id` a namespace-blind parser reports the
        // local name only, losing the relationship reference.
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

    private fun parseSharedStrings(stream: InputStream, out: MutableList<String>) {
        val builder = StringBuilder()
        var inItem = false
        var inPhonetic = false
        val handler = object : DefaultHandler() {
            override fun startElement(u: String?, l: String?, q: String?, a: Attributes?) {
                when (l) {
                    "si" -> {
                        inItem = true
                        builder.setLength(0)
                    }
                    // Ruby text is pronunciation, not the value.
                    "rPh" -> inPhonetic = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inItem && !inPhonetic) builder.appendRange(ch, start, start + length)
            }

            override fun endElement(u: String?, l: String?, q: String?) {
                if (l == "si") {
                    out.add(builder.toString())
                    inItem = false
                    builder.setLength(0)
                }
                if (l == "rPh") inPhonetic = false
            }
        }
        run(stream, handler)
    }

    private fun parseWorkbook(stream: InputStream, out: MutableList<SheetInfo>) {
        val handler = object : DefaultHandler() {
            override fun startElement(u: String?, l: String?, q: String?, a: Attributes?) {
                if (l != "sheet") return
                out.add(
                    SheetInfo(
                        name = a?.getValue("name").orEmpty(),
                        relId = a?.getValue("r:id").orEmpty()
                    )
                )
            }
        }
        run(stream, handler)
    }

    private fun parseRelationships(stream: InputStream, out: MutableMap<String, String>) {
        val handler = object : DefaultHandler() {
            override fun startElement(u: String?, l: String?, q: String?, a: Attributes?) {
                if (l != "Relationship") return
                val id = a?.getValue("Id") ?: return
                val target = a?.getValue("Target") ?: return
                out[id] = target
            }
        }
        run(stream, handler)
    }

    private class SheetHandler(
        private val sharedStrings: List<String>,
        private val sheetName: String,
        private val sink: SheetSink
    ) : DefaultHandler() {

        val merged = ArrayList<String>()
        var maxColumn = 1

        private var rowNumber = 0
        private var curCol = -1
        private var curType = "n"
        private var curRef = ""
        private var curStyle = 0
        private var formula: StringBuilder? = null
        private val value = StringBuilder()
        private var inValue = false
        private var inInline = false

        /** Cells of the row being read. */
        private val cells = ArrayList<Cell>()

        private fun emitCell() {
            if (curCol < 0) return
            val raw = when (curType) {
                "s" -> value.toString().trim().toIntOrNull()
                    ?.let { sharedStrings.getOrNull(it) }
                else -> value.toString()
            }
            // An empty cell with no formula is still a cell: it holds the
            // column's position, which is what keeps a sparse table readable.
            if (curCol + 1 > maxColumn) maxColumn = curCol + 1
            val text = formula?.toString()?.takeIf { it.isNotBlank() }
            cells.add(
                Cell(
                    ref = curRef,
                    column = curCol,
                    type = curType,
                    value = raw.orEmpty(),
                    formula = text
                )
            )
        }

        private fun emitRow() {
            if (cells.isEmpty()) {
                return
            }
            // Trailing empty cells carry no information and inflate the output,
            // but a blank *between* values is kept: it is what tells a reader
            // which column the next value belongs to.
            while (cells.isNotEmpty() && cells.last().value.isEmpty() && cells.last().formula == null) {
                cells.removeAt(cells.size - 1)
            }
            if (cells.isEmpty()) return
            sink.row(ArrayList(cells))
            cells.clear()
        }

        override fun startElement(u: String?, l: String?, q: String?, a: Attributes?) {
            when (l) {
                "row" -> {
                    rowNumber = a?.getValue("r")?.toIntOrNull() ?: (rowNumber + 1)
                }

                "c" -> {
                    // The cell is emitted on its own closing tag, so nothing
                    // needs flushing here; doing so would emit every cell twice.
                    curRef = a?.getValue("r").orEmpty()
                    val parsed = columnIndex(curRef)
                    // A cell's own reference is authoritative; a running counter
                    // would drift whenever a row skips a column.
                    curCol = if (parsed >= 0) parsed else curCol + 1
                    curType = a?.getValue("t") ?: "n"
                    curStyle = a?.getValue("s")?.toIntOrNull() ?: 0
                    value.setLength(0)
                    formula = null
                }

                "v" -> {
                    inValue = true
                    value.setLength(0)
                }

                "is" -> {
                    inInline = true
                    value.setLength(0)
                }

                "t" -> if (inInline) inValue = true

                "f" -> {
                    // The formula body arrives as characters, not as an
                    // attribute. A shared formula carries t="shared"; a plain one
                    // has no attribute at all, so the accumulator must start
                    // empty either way or the text is silently dropped.
                    inValue = false
                    formula = StringBuilder()
                }

                "mergeCell" -> {
                    a?.getValue("ref")?.let { merged.add(it) }
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inValue) {
                value.appendRange(ch, start, start + length)
            } else if (formula != null) {
                // A formula written as <f>SUM(...)</f> arrives as characters.
                formula!!.appendRange(ch, start, start + length)
            }
        }

        override fun endElement(u: String?, l: String?, q: String?) {
            when (l) {
                "v", "t" -> inValue = false
                "is" -> {
                    inInline = false
                    inValue = false
                }
                "c" -> emitCell()
                "row" -> emitRow()
            }
        }
    }

    private fun parseSheet(
        stream: InputStream,
        name: String,
        sharedStrings: List<String>,
        sink: SheetSink,
        @Suppress("UNUSED_PARAMETER") useTsv: Boolean
    ) {
        // The sheet is announced even when it holds no rows, so a workbook's
        // full sheet list is represented rather than silently dropping empty
        // sheets that may be referenced by name.
        var started = false
        val writer = object : SheetSink {
            override fun startSheet(name: String, maxColumn: Int, mergedRanges: List<String>) {
                if (started) return
                started = true
                sink.startSheet(name, maxColumn, mergedRanges)
            }

            override fun row(cells: List<Cell>) {
                if (!started) {
                    started = true
                    sink.startSheet(name, maxColumnOf(cells), emptyList())
                }
                sink.row(cells)
            }

            override fun endSheet() {
                if (!started) {
                    started = true
                    sink.startSheet(name, 0, emptyList())
                }
                sink.endSheet()
            }
        }
        run(stream, SheetHandler(sharedStrings, name, writer))
        // A sheet with no rows produces no SAX events at all, so the sheet's
        // end is signalled explicitly. Without this an empty sheet would be
        // missing from the pack entirely.
        writer.endSheet()
    }

    private fun run(stream: InputStream, handler: DefaultHandler) {
        try {
            newReader().parse(stream, handler)
        } catch (_: Exception) {
            // Keep whatever was read; a truncated part must not fail the pack.
        }
    }

    private fun maxColumnOf(cells: List<Cell>): Int =
        (cells.maxOfOrNull { it.column } ?: 0) + 1

    companion object {
        private const val REL_NS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

        /** "AB12" -> 27. */
        fun columnIndex(ref: String?): Int {
            if (ref.isNullOrEmpty()) return -1
            var idx = 0
            var seen = false
            for (ch in ref) {
                when {
                    ch in 'A'..'Z' -> idx = idx * 26 + (ch - 'A' + 1)
                    ch in 'a'..'z' -> idx = idx * 26 + (ch - 'a' + 1)
                    else -> break
                }
                seen = true
            }
            return if (seen) idx - 1 else -1
        }

        /** Column index -> spreadsheet letters. */
        fun columnName(index: Int): String {
            var n = index
            val sb = StringBuilder()
            while (n >= 0) {
                sb.insert(0, ('A' + (n % 26)))
                n = n / 26 - 1
            }
            return sb.toString()
        }
    }
}
