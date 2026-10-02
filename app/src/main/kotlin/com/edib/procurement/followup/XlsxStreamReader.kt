package com.edib.procurement.followup

import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Minimal streaming XLSX reader for the Follow-up workbook.
 *
 * Apache POI would need the whole workbook in memory (this one is ~6.5 MB of
 * sheet XML plus a 600 KB styles part and ~20k shared strings) and would only
 * expose hyperlinks by building a full `XSSFWorkbook`. Both are avoidable.
 *
 * The reason this class exists: the workbook's PR/Order columns store
 * *truncated display text* (about 45 characters), while the complete historical
 * Windows path lives in the sheet's relationship part. Reading the relationship
 * targets is what makes historical filename matching possible later.
 *
 * Memory behaviour: shared strings are retained (they are the data), styles and
 * every other part are discarded, and each sheet part is consumed as a stream
 * that emits row by row, so no sheet-sized structure is ever built.
 */
class XlsxStreamReader {

    data class SheetRow(
        val rowNumber: Int,
        /** 0-based column index -> displayed cell text. */
        val cells: Map<Int, String>,
        /** 0-based column index -> full hyperlink target (never truncated). */
        val hyperlinks: Map<Int, String>
    ) {
        fun text(column: Int): String? = cells[column]?.trim()?.takeIf { it.isNotEmpty() }

        /** Display text, but the full path when the cell carries a hyperlink. */
        fun pathText(column: Int): String? =
            (hyperlinks[column] ?: cells[column])?.trim()?.takeIf { it.isNotEmpty() }
    }

    data class SheetInfo(val name: String, val zipPath: String)

    class WorkbookFormatException(message: String) : Exception(message)

    /** Sheets in workbook order plus the shared string table they index into. */
    class WorkbookStructure(
        val sheets: List<SheetInfo>,
        val sharedStrings: List<String>
    )

    // ------------------------------------------------------------ structure

    fun readWorkbookStructure(input: InputStream): WorkbookStructure {
        val sharedStrings = ArrayList<String>()
        val sheetTags = ArrayList<Pair<String, String>>()   // name -> relationship id
        val workbookRels = HashMap<String, String>()       // relationship id -> part path

        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when (entry.name) {
                    "xl/sharedStrings.xml" ->
                        parseSharedStrings(readEntry(zip).inputStream(), sharedStrings)
                    "xl/workbook.xml" ->
                        parseWorkbookSheets(readEntry(zip).inputStream(), sheetTags)
                    "xl/_rels/workbook.xml.rels" ->
                        parseRelationships(readEntry(zip).inputStream(), workbookRels)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        if (sheetTags.isEmpty()) throw WorkbookFormatException("workbook contains no sheets")

        val sheets = sheetTags.mapNotNull { (name, rid) ->
            val target = workbookRels[rid] ?: return@mapNotNull null
            val zipPath = if (target.startsWith("/")) target.removePrefix("/")
            else "xl/" + target.removePrefix("./")
            SheetInfo(name, zipPath)
        }
        return WorkbookStructure(sheets, sharedStrings)
    }

    // ----------------------------------------------------------------- rows

/**
 * Streams one sheet, invoking [onRow] for every row in order.
 *
 * [onRow] runs on the calling (IO) thread and must not block.
 *
 * The zip is walked twice: once to read the tiny relationship part (needed to
 * resolve `r:id` -> full historical path), and once to stream the sheet itself.
 * Each entry is materialised into a byte array first, because a streaming SAX
 * parse reads ahead and would otherwise consume the following zip entries.
 */
fun forEachRow(
        input: InputStream,
        structure: WorkbookStructure,
        sheet: SheetInfo,
        onRow: (SheetRow) -> Unit
    ) {
        val relsPath = relsPathFor(sheet.zipPath)
        val sheetRels = HashMap<String, String>()
        var sheetBytes: ByteArray? = null

        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when (entry.name) {
                    relsPath -> {
                        val bytes = readEntry(zip)
                        parseRelationships(bytes.inputStream(), sheetRels)
                    }
                    sheet.zipPath -> {
                        sheetBytes = readEntry(zip)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val data = sheetBytes ?: run {
            android.util.Log.w(
                "ProcurementImport",
                "sheet part not found in workbook: ${sheet.zipPath}"
            )
            return
        }
        parseSheet(data.inputStream(), structure.sharedStrings, sheetRels, onRow)
    }

    /** Materialises one zip entry so a SAX parse cannot read into the next. */
    private fun readEntry(zip: ZipInputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream(zip.available().coerceAtLeast(64 * 1024))
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = zip.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun relsPathFor(partPath: String): String {
        val idx = partPath.lastIndexOf('/')
        val dir = if (idx >= 0) partPath.substring(0, idx) else ""
        val file = if (idx >= 0) partPath.substring(idx + 1) else partPath
        return if (dir.isEmpty()) "xl/_rels/$file.rels" else "$dir/_rels/$file.rels"
    }

    // -------------------------------------------------------------- parsers

    private fun parseSharedStrings(stream: InputStream, out: MutableList<String>) {
        val handler = SharedStringsHandler().apply { sink = out }
        runSax(stream, handler)
    }

    private fun parseWorkbookSheets(
        stream: InputStream,
        out: MutableList<Pair<String, String>>
    ) {
        val handler = WorkbookSheetsHandler().apply { sink = out }
        runSax(stream, handler)
    }

    private fun parseRelationships(stream: InputStream, out: MutableMap<String, String>) {
        val handler = RelationshipsHandler().apply { sink = out }
        runSax(stream, handler)
    }

    private fun parseSheet(
        stream: InputStream,
        sharedStrings: List<String>,
        sheetRels: Map<String, String>,
        onRow: (SheetRow) -> Unit
    ) {
        // Hyperlinks are emitted in one block after </sheetData>, so a single
        // streaming pass collects both: rows are buffered (only populated
        // columns, so a few hundred KB) and the link map is applied afterwards.
        val handler = SheetHandler(sharedStrings, sheetRels)
        runSax(stream, handler)

        val links = handler.hyperlinksByRow
        for (row in handler.rows) {
            val rowLinks = links[row.rowNumber]
            onRow(
                if (rowLinks.isNullOrEmpty()) row
                else SheetRow(row.rowNumber, row.cells, rowLinks)
            )
        }
    }

/**
 * Runs a SAX parse with external entities and DTDs disabled.
 *
 * A malformed part must not abort the whole import, so a parse failure is
 * swallowed: rows delivered before the failure are still usable.
 */
private fun runSax(
        stream: InputStream,
        handler: org.xml.sax.helpers.DefaultHandler
    ) {
        try {
            newSaxReader().parse(stream, handler)
        } catch (e: Exception) {
            // Keep whatever parsed cleanly, but make the failure visible: a
            // silently empty sheet is indistinguishable from "the workbook has
            // no data here", which is exactly the kind of bug that hides.
            android.util.Log.w(
                "ProcurementImport",
                "SAX parse ended early: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    companion object {
        /** "AB12" -> 27. Handles multi-letter columns. */
        fun columnIndex(ref: String?): Int? {
            if (ref.isNullOrEmpty()) return null
            var idx = 0
            var seen = false
            for (ch in ref) {
                val v = when (ch) {
                    in 'A'..'Z' -> ch - 'A' + 1
                    in 'a'..'z' -> ch - 'a' + 1
                    else -> break
                }
                idx = idx * 26 + v
                seen = true
            }
            return if (seen) idx - 1 else null
        }

        internal fun decodeTarget(target: String): String =
            try {
                java.net.URLDecoder.decode(target.replace("+", "%20"), "UTF-8")
            } catch (_: Exception) {
                target
            }
    }
}
