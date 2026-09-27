package com.edib.workknowledge.comparison

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType as PoiCellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.SheetVisibility
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.ss.util.CellReference
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStream

/**
 * Reads a workbook into a [WorkbookSnapshot].
 *
 * Shared with the source viewer so the row and column numbers a comparison
 * reports are the same ones the user sees when they open the file. A diff that
 * points at "row 42" is only useful if row 42 in the viewer is the row that
 * changed.
 *
 * The Context constructor is what the app uses; the no-arg constructor exists
 * so the same code path can be exercised against fixture bytes in a JVM test.
 */
class WorkbookSnapshotReader(private val context: Context? = null) : Closeable {

    private var workbook: Workbook? = null

    suspend fun open(uri: Uri) = withContext(Dispatchers.IO) {
        close()
        val resolver = context?.contentResolver
            ?: throw IllegalStateException("No context available to open a Uri.")
        val input: InputStream = resolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not open the workbook.")
        workbook = WorkbookFactory.create(input)
    }

    /** Builds a snapshot straight from bytes, for callers that already hold the file. */
    suspend fun open(bytes: ByteArray) = withContext(Dispatchers.IO) {
        close()
        workbook = WorkbookFactory.create(ByteArrayInputStream(bytes))
    }

    suspend fun snapshot(documentName: String): WorkbookSnapshot =
        withContext(Dispatchers.IO) {
            val wb = workbook ?: throw IllegalStateException("Workbook not open.")
            val sheets = (0 until wb.numberOfSheets).mapNotNull { index ->
                if (wb.getSheetVisibility(index) != SheetVisibility.VISIBLE) return@mapNotNull null
                readSheet(wb.getSheetAt(index), index)
            }
            WorkbookSnapshot(documentName = documentName, sheets = sheets)
        }

    private fun readSheet(sheet: org.apache.poi.ss.usermodel.Sheet, index: Int): SheetSnapshot {
        val headerRowIndex = detectHeaderRow(sheet)
        val headers = headerRowIndex
            ?.let { sheet.getRow(it) }
            ?.let { row -> (0 until maxColumns(row)).map { c -> display(row.getCell(c)) } }
            ?: emptyList()
        val headerRowNumber = headerRowIndex?.plus(1)

        val rows = mutableListOf<RowSnapshot>()
        for (r in 0..sheet.lastRowNum) {
            val row = sheet.getRow(r) ?: continue
            // The header itself is structure, not data.
            if (r == headerRowIndex) continue
            val cells = linkedMapOf<String, CellSnapshot>()
            for (c in 0 until maxColumns(row)) {
                val header = headers.getOrNull(c) ?: continue
                if (header.isBlank()) continue
                cells[header] = readCell(row.getCell(c), header, c, r + 1)
            }
            if (cells.values.all { it.displayValue.isBlank() }) continue
            rows.add(
                RowSnapshot(
                    rowNumber = r + 1,
                    key = null,
                    cells = cells
                )
            )
        }

        val snapshot = SheetSnapshot(
            name = sheet.sheetName,
            index = index,
            headers = headers.filter { it.isNotBlank() },
            headerRowNumber = headerRowNumber,
            rows = rows
        )
        val keyColumn = snapshot.keyColumn
        return if (keyColumn != null) {
            snapshot.copy(rows = rows.map { it.copy(key = it.cells[keyColumn]?.displayValue?.trim()) })
        } else {
            snapshot
        }
    }

    private fun readCell(
        cell: Cell?,
        header: String,
        columnIndex: Int,
        rowNumber: Int
    ): CellSnapshot {
        val coordinate = CellReference.convertNumToColString(columnIndex) + rowNumber
        if (cell == null) {
            return CellSnapshot(header, columnIndex, coordinate, "", null, null, CellType.BLANK)
        }
        val formula = if (cell.cellType == PoiCellType.FORMULA) cell.cellFormula else null
        val display = display(cell)
        val numeric = numericValue(cell)
        val type = when (cell.cellType) {
            PoiCellType.STRING -> CellType.TEXT
            PoiCellType.NUMERIC ->
                if (DateUtil.isCellDateFormatted(cell)) CellType.DATE else CellType.NUMBER
            PoiCellType.BOOLEAN -> CellType.BOOLEAN
            PoiCellType.FORMULA -> CellType.FORMULA
            PoiCellType.BLANK -> CellType.BLANK
            else -> CellType.TEXT
        }
        return CellSnapshot(
            header = header,
            columnIndex = columnIndex,
            coordinate = coordinate,
            displayValue = display,
            formula = formula,
            numericValue = numeric,
            dataType = type
        )
    }

    /**
     * Numeric interpretation of a cell, including a formula's cached result.
     *
     * Returning a Double is what lets a rate change from 2400 to 2400.0 be
     * correctly treated as *no change*.
     */
    private fun numericValue(cell: Cell): Double? = when (cell.cellType) {
        PoiCellType.NUMERIC ->
            if (DateUtil.isCellDateFormatted(cell)) null else cell.numericCellValue
        PoiCellType.FORMULA -> runCatching {
            when (cell.cachedFormulaResultType) {
                PoiCellType.NUMERIC -> cell.numericCellValue
                else -> null
            }
        }.getOrNull()
        else -> null
    }

    private fun display(cell: Cell?): String {
        if (cell == null) return ""
        return when (cell.cellType) {
            PoiCellType.STRING -> cell.stringCellValue
            PoiCellType.NUMERIC ->
                if (DateUtil.isCellDateFormatted(cell)) {
                    cell.dateCellValue?.toString().orEmpty()
                } else {
                    formatNumber(cell.numericCellValue)
                }
            PoiCellType.BOOLEAN -> cell.booleanCellValue.toString()
            PoiCellType.FORMULA -> runCatching {
                when (cell.cachedFormulaResultType) {
                    PoiCellType.NUMERIC -> formatNumber(cell.numericCellValue)
                    PoiCellType.STRING -> cell.stringCellValue
                    PoiCellType.BOOLEAN -> cell.booleanCellValue.toString()
                    else -> ""
                }
            }.getOrDefault("")
            PoiCellType.ERROR -> "#ERR"
            else -> ""
        }
    }

    /** Whole numbers lose the ".0" POI would otherwise show. */
    private fun formatNumber(value: Double): String =
        if (value == Math.floor(value) && !value.isInfinite() && Math.abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            value.toString()
        }

    private fun maxColumns(row: Row): Int {
        val last = row.lastCellNum.toInt()
        return if (last <= 0) 0 else last
    }

    /**
     * Finds the header row: mostly-text cells followed by at least one numeric
     * row.
     *
     * A work BOQ often has a title and a project name above the real table, so
     * row 1 cannot be assumed. If nothing qualifies, the first non-empty row is
     * used and no header names are invented.
     */
    private fun detectHeaderRow(sheet: org.apache.poi.ss.usermodel.Sheet): Int? {
        val limit = minOf(20, sheet.lastRowNum + 1)
        for (i in 0 until limit) {
            val row = sheet.getRow(i) ?: continue
            val filled = (0 until maxColumns(row))
                .mapNotNull { row.getCell(it) }
                .filter { display(it).isNotBlank() }
            if (filled.size < 3) continue
            val numeric = filled.count {
                it.cellType == PoiCellType.NUMERIC || it.cellType == PoiCellType.FORMULA
            }
            if (numeric > filled.size / 2) continue
            val next = sheet.getRow(i + 1)
            val nextHasNumber = next?.let { r ->
                (0 until maxColumns(r)).any { c ->
                    r.getCell(c)?.cellType == PoiCellType.NUMERIC && display(r.getCell(c)).isNotBlank()
                }
            } == true
            if (nextHasNumber) return i
        }
        // Fallback: the first row with several non-empty cells. Whatever it
        // contains is reported verbatim, never guessed at.
        for (i in 0 until limit) {
            val row = sheet.getRow(i) ?: continue
            val filled = (0 until maxColumns(row)).count { display(row.getCell(it)).isNotBlank() }
            if (filled >= 3) return i
        }
        return null
    }

    override fun close() {
        runCatching { workbook?.close() }
        workbook = null
    }
}
