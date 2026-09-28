package com.edib.workknowledge.comparison

import kotlin.math.abs

/**
 * Deterministic comparison of two workbook revisions.
 *
 * Operates on [WorkbookSnapshot]s, i.e. on actual cell values, formulas and
 * coordinates. No text parsing, no embeddings, and no model involvement: a
 * model may later *explain* the result, but it never produces it.
 *
 * Numeric cells are compared as numbers, so 2400 and 2400.0 are the same rate
 * while 2400 and 2550 are a real change. Units travel with the value because the
 * unit is its own column and is compared as such.
 */
class StructuralWorkbookDiff {

    /** Tolerance below which a numeric difference is treated as noise. */
    private val numericEpsilon = 1e-9

    fun diff(old: WorkbookSnapshot, new: WorkbookSnapshot): WorkbookDiff {
        val oldSheets = old.sheetNames
        val newSheets = new.sheetNames

        val addedSheets = (newSheets - oldSheets).sorted()
        val removedSheets = (oldSheets - newSheets).sorted()

        val cellChanges = mutableListOf<CellChange>()
        val addedRows = mutableListOf<RowChange>()
        val removedRows = mutableListOf<RowChange>()
        val relocations = mutableListOf<RowRelocation>()
        val unkeyedRowNotes = mutableListOf<String>()

        for (sheetName in (oldSheets intersect newSheets).sorted()) {
            val before = old.sheet(sheetName)!!
            val after = new.sheet(sheetName)!!

            val (changes, added, removed, moved, notes) = diffSheet(sheetName, before, after)
            cellChanges += changes
            addedRows += added
            removedRows += removed
            relocations += moved
            unkeyedRowNotes += notes
        }

        return WorkbookDiff(
            oldDocument = old.documentName,
            newDocument = new.documentName,
            addedSheets = addedSheets,
            removedSheets = removedSheets,
            addedRows = addedRows,
            removedRows = removedRows,
            changedCells = cellChanges.sortedWith(
                compareBy<CellChange> { it.sheet }
                    .thenBy { it.key ?: "" }
                    .thenBy { it.column }
            ),
            relocations = relocations,
            notes = unkeyedRowNotes
        )
    }

    private fun diffSheet(
        sheetName: String,
        before: SheetSnapshot,
        after: SheetSnapshot
    ): SheetDiff {
        val changes = mutableListOf<CellChange>()
        val added = mutableListOf<RowChange>()
        val removed = mutableListOf<RowChange>()
        val moved = mutableListOf<RowRelocation>()
        val notes = mutableListOf<String>()

        val beforeRows = before.rows
        val afterRows = after.rows

        // Columns present in one revision only are themselves a change.
        val newColumns = after.headers.filterNot { it in before.headers }
        val goneColumns = before.headers.filterNot { it in after.headers }

        if (before.keyColumn != null && after.keyColumn != null) {
            val beforeByKey = beforeRows.mapNotNull { r -> r.key?.let { it to r } }.toMap()
            val afterByKey = afterRows.mapNotNull { r -> r.key?.let { it to r } }.toMap()

            for ((key, row) in afterByKey) {
                val old = beforeByKey[key]
                if (old == null) {
                    added += RowChange(sheetName, key, row.rowNumber, cellsOf(row))
                } else {
                    if (old.rowNumber != row.rowNumber) {
                        moved += RowRelocation(sheetName, key, old.rowNumber, row.rowNumber)
                    }
                    changes += diffRow(sheetName, key, old, row)
                }
            }
            for ((key, row) in beforeByKey) {
                if (key !in afterByKey) {
                    removed += RowChange(sheetName, key, row.rowNumber, cellsOf(row))
                }
            }
            // Rows that carry no key at all cannot be paired safely.
            val beforeUnkeyed = beforeRows.count { it.key.isNullOrBlank() }
            val afterUnkeyed = afterRows.count { it.key.isNullOrBlank() }
            if (beforeUnkeyed > 0 || afterUnkeyed > 0) {
                notes += "$sheetName: $beforeUnkeyed old / $afterUnkeyed new row(s) have no " +
                    "${after.keyColumn ?: before.keyColumn} value and were not compared by key."
            }
        } else {
            // No stable key: fall back to positional comparison, and say so,
            // because a positional match can pair unrelated items.
            val column = "row position"
            notes += "$sheetName: no key column found (looked for Item Code / Code / ID); " +
                "rows were paired by position, which can mispair reordered items."
            val n = minOf(beforeRows.size, afterRows.size)
            for (i in 0 until n) {
                changes += diffRow(sheetName, "${i + 1}", beforeRows[i], afterRows[i])
            }
            for (i in n until beforeRows.size) {
                removed += RowChange(sheetName, "${i + 1}", beforeRows[i].rowNumber, cellsOf(beforeRows[i]))
            }
            for (i in n until afterRows.size) {
                added += RowChange(sheetName, "${i + 1}", afterRows[i].rowNumber, cellsOf(afterRows[i]))
            }
        }

        // A column that exists only in one revision is a structural change in
        // its own right, so it is recorded rather than silently ignored.
        newColumns.forEach {
            changes += CellChange(
                sheet = sheetName, key = null, column = it,
                kind = ChangeKind.COLUMN_ADDED,
                oldValue = null, newValue = null,
                oldDisplay = null, newDisplay = null,
                detail = "column exists only in ${'$'}new"
            )
        }
        goneColumns.forEach {
            changes += CellChange(
                sheet = sheetName, key = null, column = it,
                kind = ChangeKind.COLUMN_REMOVED,
                oldValue = null, newValue = null,
                oldDisplay = null, newDisplay = null,
                detail = "column exists only in the old revision"
            )
        }

        return SheetDiff(changes, added, removed, moved, notes)
    }

    private fun diffRow(
        sheet: String,
        key: String,
        before: RowSnapshot,
        after: RowSnapshot
    ): List<CellChange> {
        val out = mutableListOf<CellChange>()

        // Compare every column either revision knows about.
        val headers = (before.cells.keys + after.cells.keys).distinct()
        for (header in headers) {
            val oldCell = before.cells[header]
            val newCell = after.cells[header]

            // A moved cell keeps its value; the relocation is reported instead.
            if (oldCell != null && newCell != null) {
                val formulaChanged = oldCell.formula != newCell.formula
                val numericChanged = when {
                    oldCell.numericValue != null && newCell.numericValue != null ->
                        abs(oldCell.numericValue - newCell.numericValue) > numericEpsilon
                    else -> oldCell.displayValue.trim() != newCell.displayValue.trim()
                }

                if (formulaChanged && numericChanged) {
                    out += cellChange(sheet, key, oldCell, newCell, ChangeKind.FORMULA_AND_VALUE)
                } else if (formulaChanged) {
                    out += cellChange(sheet, key, oldCell, newCell, ChangeKind.FORMULA)
                } else if (numericChanged) {
                    out += cellChange(sheet, key, oldCell, newCell, ChangeKind.VALUE)
                }
            } else if (oldCell == null && newCell != null && newCell.displayValue.isNotBlank()) {
                out += CellChange(
                    sheet = sheet, key = key, column = header,
                    kind = ChangeKind.VALUE_ADDED,
                    oldValue = null, newValue = newCell.numericValue,
                    oldDisplay = null, newDisplay = newCell.displayValue,
                    detail = null
                )
            } else if (newCell == null && oldCell != null && oldCell.displayValue.isNotBlank()) {
                out += CellChange(
                    sheet = sheet, key = key, column = header,
                    kind = ChangeKind.VALUE_REMOVED,
                    oldValue = oldCell.numericValue, newValue = null,
                    oldDisplay = oldCell.displayValue, newDisplay = null,
                    detail = null
                )
            }
        }
        return out
    }

    private fun cellChange(
        sheet: String,
        key: String,
        oldCell: CellSnapshot,
        newCell: CellSnapshot,
        kind: ChangeKind
    ) = CellChange(
        sheet = sheet,
        key = key,
        column = oldCell.header,
        kind = kind,
        oldValue = oldCell.numericValue,
        newValue = newCell.numericValue,
        oldDisplay = oldCell.displayValue,
        newDisplay = newCell.displayValue,
        detail = if (kind == ChangeKind.FORMULA || kind == ChangeKind.FORMULA_AND_VALUE) {
            "formula ${oldCell.formula ?: "(none)"} -> ${newCell.formula ?: "(none)"}"
        } else {
            null
        }
    )

    private fun cellsOf(row: RowSnapshot): Map<String, String> =
        row.cells.mapValues { it.value.displayValue }

    private data class SheetDiff(
        val changes: List<CellChange>,
        val added: List<RowChange>,
        val removed: List<RowChange>,
        val moved: List<RowRelocation>,
        val notes: List<String>
    )
}

data class WorkbookDiff(
    val oldDocument: String,
    val newDocument: String,
    val addedSheets: List<String>,
    val removedSheets: List<String>,
    val addedRows: List<RowChange>,
    val removedRows: List<RowChange>,
    val changedCells: List<CellChange>,
    val relocations: List<RowRelocation>,
    /** Anything the comparison could not do safely, stated rather than hidden. */
    val notes: List<String>
) {
    val isEmpty: Boolean
        get() = addedSheets.isEmpty() && removedSheets.isEmpty() && addedRows.isEmpty() &&
            removedRows.isEmpty() && changedCells.isEmpty() && relocations.isEmpty()

    /** One-line summary for the UI header. */
    fun summary(): String = buildString {
        append("1 sheet(s) added")
        append(", ${removedSheets.size} removed, ")
        append("${addedRows.size} row(s) added, ${removedRows.size} removed, ")
        append("${changedCells.size} cell(s) changed")
        if (relocations.isNotEmpty()) append(", ${relocations.size} row(s) moved")
    }
}

data class CellChange(
    val sheet: String,
    /** Row identity, e.g. "MR-104". */
    val key: String?,
    val column: String,
    val kind: ChangeKind,
    val oldValue: Double?,
    val newValue: Double?,
    val oldDisplay: String?,
    val newDisplay: String?,
    val detail: String? = null
) {
    /** e.g. "Quantity 37.5 -> 42.0" */
    fun describe(): String = when (kind) {
        // A formula change must show the formulas themselves. The cached values
        // of an uncalculated workbook are often both "0", which would render as
        // a meaningless "0 -> 0".
        ChangeKind.FORMULA -> detail ?: "$column formula changed"
        ChangeKind.FORMULA_AND_VALUE ->
            "$column: ${oldDisplay ?: "(empty)"} -> ${newDisplay ?: "(empty)"}; $detail"
        else -> "$column: ${oldDisplay ?: "(empty)"} -> ${newDisplay ?: "(empty)"}"
    }
}

enum class ChangeKind {
    VALUE, FORMULA, FORMULA_AND_VALUE, VALUE_ADDED, VALUE_REMOVED, COLUMN_ADDED, COLUMN_REMOVED
}

data class RowChange(
    val sheet: String,
    val key: String?,
    val rowNumber: Int,
    val cells: Map<String, String>
) {
    fun describe(): String = buildString {
        append(key ?: "row $rowNumber")
        val description = cells.entries.firstOrNull { it.key.equals("Description", true) }?.value
        if (!description.isNullOrBlank()) append(" — $description")
    }
}

data class RowRelocation(
    val sheet: String,
    val key: String?,
    val oldRow: Int,
    val newRow: Int
)
