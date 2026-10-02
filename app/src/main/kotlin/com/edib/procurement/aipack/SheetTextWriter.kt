package com.edib.procurement.aipack

/**
 * Renders a workbook sheet as tab separated text.
 *
 * TSV rather than a Markdown table: for a wide sheet it is dramatically
 * smaller, it cannot be corrupted by a cell containing a pipe, and it keeps cell
 * boundaries exact. Empty cells inside the table are preserved because they
 * carry the column's position; only fully empty trailing columns and rows are
 * dropped, because those are padding.
 */
class SheetTextWriter(
    private val out: PackTextWriter,
    private val detailed: Boolean
) : OpenXmlWorkbookReader.SheetSink {

    private var wroteHeader = false
    private var lastNonEmptyColumn = 0
    private var rowsWritten = 0

    /** Set by the reader so the builder can report sheet progress. */
    var onRow: ((rowNumber: Int) -> Unit)? = null

    override fun startSheet(name: String, maxColumn: Int, mergedRanges: List<String>) {
        out.endLine()
        out.line("--- SHEET: $name ---")
        if (detailed && mergedRanges.isNotEmpty()) {
            out.line("Merged ranges: ${mergedRanges.joinToString(" ")}")
        }
        wroteHeader = false
        lastNonEmptyColumn = 0
        rowsWritten = 0
    }

    override fun row(cells: List<OpenXmlWorkbookReader.Cell>) {
        var lastUsed = -1
        for (i in cells.indices) {
            val c = cells[i]
            if (c.value.isNotEmpty() || c.formula != null) lastUsed = i
        }
        if (lastUsed < 0) {
            // A wholly empty row is padding, unless it is inside the table and
            // the caller asked for structure to be preserved.
            if (!detailed || rowsWritten == 0) return
            // In detailed mode an interior blank row still separates sections.
            out.line("")
            return
        }
        val width = lastUsed + 1
        if (width > lastNonEmptyColumn) lastNonEmptyColumn = width

        if (!wroteHeader) {
            // The first row is the column header only if it looks like one:
            // in this workbook the first sheet row is a real record, and a
            // fabricated header would invent a column that does not exist.
            wroteHeader = true
        }

        val sb = StringBuilder()
        for (i in 0 until width) {
            if (i > 0) sb.append('\t')
            val cell = cells.getOrNull(i)
            val raw = cell?.value.orEmpty()
            val text = if (detailed && cell?.formula != null) {
                "$raw  [=${cell.formula}]"
            } else {
                raw
            }
            sb.append(text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' '))
        }
        out.line(sb.toString().trimEnd())
        rowsWritten++
        onRow?.invoke(rowsWritten)
    }

    override fun endSheet() {
        out.endLine()
        out.line("(rows: $rowsWritten)")
    }
}
