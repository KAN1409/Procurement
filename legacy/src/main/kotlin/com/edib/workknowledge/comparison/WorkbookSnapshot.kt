package com.edib.workknowledge.comparison

/**
 * A workbook read into structured form for deterministic comparison.
 *
 * This is deliberately *not* derived from the text chunks. Comparing two BOQ
 * revisions is an exact operation: it must compare cell values, not prose.
 * Round-tripping through the chunk text would lose numeric types, formulas and
 * units, and would make "37.5" and "37.50" look like a change.
 */
data class WorkbookSnapshot(
    val documentName: String,
    val sheets: List<SheetSnapshot>
) {
    fun sheet(name: String): SheetSnapshot? = sheets.firstOrNull { it.name == name }

    val sheetNames: Set<String> get() = sheets.map { it.name }.toSet()
}

data class SheetSnapshot(
    val name: String,
    val index: Int,
    val headers: List<String>,
    val headerRowNumber: Int?,
    val rows: List<RowSnapshot>
) {
    fun rowByKey(key: String): RowSnapshot? = rows.firstOrNull { it.key == key }

    /** Header that carries a stable identity, e.g. "Item Code". */
    val keyColumn: String? = headers.firstOrNull { KEY_HEADERS.contains(it.normalized()) }

    private companion object {
        /**
         * Header names treated as row identity, most reliable first.
         *
         * Description is deliberately absent: descriptions are not unique in a
         * real BOQ and two different items can share one, so matching on it
         * would silently pair unrelated rows.
         */
        val KEY_HEADERS = listOf(
            "item code", "item no", "item number", "item", "code",
            "reference", "ref", "sr no", "sr", "no", "number", "id", "sku"
        )

        fun String.normalized() = trim().lowercase().replace(Regex("[^a-z0-9 ]"), "")
    }
}

data class RowSnapshot(
    /** 1-based sheet row number, matching what a viewer shows. */
    val rowNumber: Int,
    val key: String?,
    val cells: Map<String, CellSnapshot>
) {
    fun value(header: String): CellSnapshot? = cells[header]

    operator fun get(header: String): CellSnapshot? = cells[header]
}

data class CellSnapshot(
    /** Column header this cell sits under. */
    val header: String,
    /** 0-based column index, so A=0. */
    val columnIndex: Int,
    /** Cell reference as it appears in the file, e.g. "D42". */
    val coordinate: String,
    /** Exactly as displayed, preserving units and formatting. */
    val displayValue: String,
    /** Formula text without the leading "=", when the cell has one. */
    val formula: String? = null,
    /** Numeric interpretation, or null when the cell is not a number. */
    val numericValue: Double? = null,
    val dataType: CellType = CellType.TEXT
) {
    val hasFormula: Boolean get() = formula != null
}

enum class CellType { TEXT, NUMBER, FORMULA, DATE, BOOLEAN, BLANK }
