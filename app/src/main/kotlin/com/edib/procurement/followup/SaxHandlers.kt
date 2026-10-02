package com.edib.procurement.followup

import org.xml.sax.Attributes
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.SAXNotSupportedException
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream

/**
 * Pull parser adaptor over SAX events.
 *
 * The workbook is parsed with SAX ([org.xml.sax.XMLReader]) rather than
 * android.util.Xml for two reasons:
 *
 *  - `javax.xml.stream` (StAX) is not part of the Android platform API.
 *  - android.util.Xml is a stub in unit tests, which would make the parser
 *    untestable on the JVM; SAX runs identically on both.
 *
 * Each handler is a NAMED class rather than an anonymous `object : Attributes`.
 * kapt falls back to Kotlin language 1.9 and emits a broken Java stub for an
 * anonymous object-expression implementing a SAX interface, which fails
 * annotation processing with a spurious "duplicate class" error.
 */

/** Reads `<si>` entries into the workbook's shared string table. */
class SharedStringsHandler : DefaultHandler() {
    private val builder = StringBuilder()
    private var inItem = false
    private var inPhonetic = false

    override fun startElement(u: String?, l: String?, q: String?, a: Attributes?) {
        val tag = tagName(l, q)
        when (tag) {
            "si" -> {
                inItem = true
                builder.setLength(0)
            }
            // Phonetic runs are ruby text, not the shared string value.
            "rPh" -> inPhonetic = true
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (inItem && !inPhonetic) builder.appendRange(ch, start, start + length)
    }

    override fun endElement(u: String?, l: String?, q: String?) {
        when (tagName(l, q)) {
            "si" -> {
                inItem = false
                inPhonetic = false
                sink?.add(builder.toString())
                builder.setLength(0)
            }
            "rPh" -> inPhonetic = false
        }
    }

    /** Assigned by the reader before parsing begins. */
    var sink: MutableList<String>? = null
}

/** Reads `<sheet name=... r:id=...>` entries. */
class WorkbookSheetsHandler : DefaultHandler() {
    var sink: MutableList<Pair<String, String>>? = null

    override fun startElement(u: String?, l: String?, q: String?, a: Attributes?) {
        if (tagName(l, q) != "sheet") return
        val name = a?.getValue("name").orEmpty()
        val rid = a?.getValue("$REL_NS:id")
            ?: a?.getValue("r:id")
            ?: ""
        sink?.add(name to rid)
    }
}

/** Reads `<Relationship Id=... Target=...>` entries. */
class RelationshipsHandler : DefaultHandler() {
    var sink: MutableMap<String, String>? = null

    override fun startElement(u: String?, l: String?, q: String?, a: Attributes?) {
        if (tagName(l, q) != "Relationship") return
        val id = a?.getValue("Id") ?: return
        val target = a?.getValue("Target") ?: return
        sink?.put(id, target)
    }
}

/**
 * Reads `<row>` / `<c>` / `<v>` from a worksheet, resolving `t="s"` cells
 * through the shared string table, and collects the sheet's hyperlinks.
 *
 * Hyperlinks are NOT nested inside `<row>`: the workbook emits one
 * `<hyperlinks>` block after `</sheetData>`, containing every hyperlink for the
 * sheet. Both are collected here and the reader joins them afterwards.
 */
class SheetHandler(
    private val sharedStrings: List<String>,
    private val sheetRels: Map<String, String>
) : DefaultHandler() {

    /** Rows as parsed, with no hyperlink data attached. */
    val rows: MutableList<XlsxStreamReader.SheetRow> = ArrayList()

    /** Excel row number -> (column index -> full historical path). */
    val hyperlinksByRow: MutableMap<Int, MutableMap<Int, String>> = LinkedHashMap()

    private val cells = LinkedHashMap<Int, String>()
    private val value = StringBuilder()

    private var rowNumber = 0
    private var curCol = -1
    private var curType = "n"
    private var inValue = false
    private var inInline = false

    override fun startElement(u: String?, l: String?, q: String?, a: Attributes?) {
        when (tagName(l, q)) {
            "row" -> {
                rowNumber = a?.getValue("r")?.toIntOrNull() ?: (rowNumber + 1)
                cells.clear()
            }

            "c" -> {
                curCol = XlsxStreamReader.columnIndex(a?.getValue("r")) ?: (curCol + 1)
                curType = a?.getValue("t") ?: "n"
                value.setLength(0)
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

            "hyperlink" -> {
                val ref = a?.getValue("ref") ?: return
                // A ref may span a range ("H45:H46"); the first cell is enough.
                val cellRef = ref.substringBefore(':')
                val idx = XlsxStreamReader.columnIndex(cellRef) ?: return
                val rowNo = cellRef.dropWhile { it.isLetter() }.toIntOrNull() ?: return
                // Two sources, in priority order:
                //  1. the relationship target, the authoritative stored path;
                //  2. the `display` attribute, which this workbook also fills
                //     with the full historical Windows path.
                val rid = a?.getValue("$REL_NS:id") ?: a?.getValue("r:id")
                val target = rid?.let { sheetRels[it] }
                val chosen = target?.takeIf { it.isNotBlank() } ?: a?.getValue("display")
                if (!chosen.isNullOrBlank()) {
                    val rowMap = hyperlinksByRow.getOrPut(rowNo) { LinkedHashMap() }
                    rowMap[idx] = XlsxStreamReader.decodeTarget(chosen)
                }
            }

            // A formula's cached value arrives in <v>; the formula text itself
            // is not what the workbook displays.
            "f" -> inValue = false
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (inValue) value.appendRange(ch, start, start + length)
    }

    override fun endElement(u: String?, l: String?, q: String?) {
        when (tagName(l, q)) {
            "v", "t" -> inValue = false

            "is" -> {
                inInline = false
                inValue = false
            }

            "c" -> {
                val text = when (curType) {
                    "s" -> value.toString().trim().toIntOrNull()
                        ?.let { sharedStrings.getOrNull(it) }
                    else -> value.toString()
                }
                if (curCol >= 0 && !text.isNullOrEmpty()) cells[curCol] = text
                value.setLength(0)
                inValue = false
                inInline = false
            }

            "row" -> if (cells.isNotEmpty()) {
                rows.add(
                    XlsxStreamReader.SheetRow(
                        rowNumber,
                        LinkedHashMap(cells),
                        emptyMap()
                    )
                )
            }
        }
    }
}

/** The officeDocument relationships namespace, used to resolve `r:id`. */
const val REL_NS =
    "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

/**
 * Element name for the current event.
 *
 * The parser runs with `isNamespaceAware = false`, so SAX reports the tag in
 * `qName` and leaves `localName` null. Falling back keeps the handlers working
 * if namespace awareness is ever switched on.
 */
internal fun tagName(localName: String?, qName: String?): String =
    when {
        !localName.isNullOrEmpty() -> localName
        !qName.isNullOrEmpty() -> qName.substringAfter(':')
        else -> ""
    }

/**
 * Wraps a stream so a SAX parser closing it does not close the surrounding
 * [java.util.zip.ZipInputStream].
 *
 * Without this, the parser's automatic close on the current entry terminates
 * the zip stream, and the caller's walk to the next part fails with
 * "Stream closed".
 */
internal class NonClosingInputStream(stream: InputStream) :
    java.io.FilterInputStream(stream) {
    override fun close() {
        // Intentionally not delegating: the zip stream owns its own lifecycle.
    }
}

/** Creates a SAX parser with external entities and DTDs disabled. */
internal fun newSaxReader(): javax.xml.parsers.SAXParser {
    val factory = javax.xml.parsers.SAXParserFactory.newInstance()
    factory.isNamespaceAware = false
    factory.isValidating = false
    return try {
        val parser = factory.newSAXParser()
        val reader = parser.xmlReader
        reader.setEntityResolver { _, _ ->
            org.xml.sax.InputSource(java.io.StringReader(""))
        }
        trySetFeature(reader, "http://apache.org/xml/features/disallow-doctype-decl", true)
        trySetFeature(reader, "http://xml.org/sax/features/external-general-entities", false)
        trySetFeature(reader, "http://xml.org/sax/features/external-parameter-entities", false)
        parser
    } catch (e: Exception) {
        throw IllegalStateException("no usable XML parser available", e)
    }
}

private fun trySetFeature(
    reader: org.xml.sax.XMLReader,
    name: String,
    value: Boolean
) {
    try {
        reader.setFeature(name, value)
    } catch (_: SAXNotRecognizedException) {
        // Feature unsupported by this parser; the default is already safe.
    } catch (_: SAXNotSupportedException) {
        // As above.
    }
}
