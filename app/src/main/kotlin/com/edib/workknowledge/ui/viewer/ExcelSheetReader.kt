package com.edib.workknowledge.ui.viewer

import android.content.Context
import android.net.Uri
import com.edib.workknowledge.data.ChunkMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.SheetVisibility
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.ss.util.CellReference

/**
 * Read-only workbook reader for the source viewer.
 *
 * Only the requested window of rows is materialised: a 20k-row BOQ must not be
 * turned into 20k Compose rows, and the viewer is for checking a citation, not
 * for editing a spreadsheet.
 */
class ExcelSheetReader(private val context: Context) : AutoCloseable {

    private var workbook: Workbook? = null

    data class SheetSummary(val name: String, val rowCount: Int, val hidden: Boolean)

    data class CellText(val value: String, val formula: String?)

    data class SheetWindow(
        val sheetName: String,
        val headers: List<String>,
        /** Header row number (1-based), or null when no header was detected. */
        val headerRow: Int?,
        /** Displayed cells keyed by 1-based row number. */
        val rows: List<Pair<Int, List<CellText>>>
    )

    @Throws(Exception::class)
    suspend fun open(uri: Uri) = withContext(Dispatchers.IO) {
        close()
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not open the workbook.")
        workbook = WorkbookFactory.create(input)
    }

    suspend fun sheets(): List<SheetSummary> = withContext(Dispatchers.IO) {
        val wb = workbook ?: return@withContext emptyList()
        (0 until wb.numberOfSheets).map { i ->
            val sheet = wb.getSheetAt(i)
            SheetSummary(
                name = sheet.sheetName,
                rowCount = sheet.lastRowNum + 1,
                hidden = wb.getSheetVisibility(i) != SheetVisibility.VISIBLE
            )
        }
    }

    /**
     * Reads [windowRows] rows of [sheetName] starting at [startRow] (1-based).
     */
    suspend fun window(
        sheetName: String,
        startRow: Int,
        windowRows: Int
    ): SheetWindow? = withContext(Dispatchers.IO) {
        val wb = workbook ?: return@withContext null
        val idx = (0 until wb.numberOfSheets).firstOrNull { wb.getSheetName(it) == sheetName }
            ?: return@withContext null
        val sheet: Sheet = wb.getSheetAt(idx)

        val headerRowIndex = detectHeaderRow(sheet)
        val headers = headerRowIndex
            ?.let { r -> sheet.getRow(r)?.let { row -> headersOf(row) } }
            ?: emptyList()

        val out = ArrayList<Pair<Int, List<CellText>>>(windowRows)
        val first = startRow.coerceAtLeast(1)
        for (number in first until first + windowRows) {
            val row = sheet.getRow(number - 1) ?: continue
            if (isRowEmpty(row)) continue
            out.add(number to cellsOf(row, headers.size))
        }
        SheetWindow(sheetName, headers, headerRowIndex?.plus(1), out)
    }

    /**
     * Best-effort header detection: the first row that is mostly non-numeric
     * text and is followed by at least one numeric row. Never invents a header
     * that is not actually in the file.
     */
    private fun detectHeaderRow(sheet: Sheet): Int? {
        val limit = minOf(20, sheet.lastRowNum + 1)
        for (i in 0 until limit) {
            val row = sheet.getRow(i) ?: continue
            val filled = row.cells().filter { display(it).isNotBlank() }
            if (filled.size < 3) continue
            val numeric = filled.count {
                it.cellType == CellType.NUMERIC || it.cellType == CellType.FORMULA
            }
            if (numeric > filled.size / 2) continue
            val next = sheet.getRow(i + 1)
            val nextHasNumber = next?.cells()?.any {
                it.cellType == CellType.NUMERIC && display(it).isNotBlank()
            } == true
            if (nextHasNumber) return i
        }
        return null
    }

    private fun headersOf(row: Row): List<String> =
        row.cells().map { display(it).ifBlank { it.columnIndex.toString() } }

    private fun cellsOf(row: Row, width: Int): List<CellText> {
        val out = ArrayList<CellText>(width)
        for (i in 0 until width) {
            val cell = row.getCell(i)
            out.add(
                if (cell == null) CellText("", null)
                else CellText(display(cell), if (cell.cellType == CellType.FORMULA) cell.cellFormula else null)
            )
        }
        return out
    }

    private fun isRowEmpty(row: Row): Boolean = row.cells().all { display(it).isBlank() }

    private fun Row.cells(): List<Cell> {
        val last = lastCellNum.toInt()
        if (last <= 0) return emptyList()
        return (0 until last).mapNotNull { getCell(it) }
    }

    private fun display(cell: Cell): String = when (cell.cellType) {
        CellType.STRING -> cell.stringCellValue
        CellType.NUMERIC ->
            if (DateUtil.isCellDateFormatted(cell)) {
                cell.dateCellValue?.toString().orEmpty()
            } else {
                val v = cell.numericCellValue
                if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
            }
        CellType.BOOLEAN -> cell.booleanCellValue.toString()
        CellType.FORMULA -> runCatching {
            when (cell.cachedFormulaResultType) {
                CellType.NUMERIC -> cell.numericCellValue.toString()
                CellType.STRING -> cell.stringCellValue
                CellType.BOOLEAN -> cell.booleanCellValue.toString()
                else -> cell.cellFormula
            }
        }.getOrDefault(cell.cellFormula)
        CellType.ERROR -> "#ERR"
        else -> ""
    }

    override fun close() {
        runCatching { workbook?.close() }
        workbook = null
    }

    companion object {
        fun columnName(index: Int): String = CellReference.convertNumToColString(index)
    }
}
