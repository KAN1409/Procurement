package com.edib.workknowledge.parsers

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.ByteArrayInputStream

/**
 * Test-side readers that drive the real PDFBox / POI extraction the app uses,
 * so fixture ground truth is verified by the same engine that will be
 * verified at runtime.
 */
class PageTextExtractor {

    /**
     * On a device the app initialises PDFBox's asset loader explicitly. On the
     * JVM the plain pdfbox artifact supplies the same tables as ordinary class
     * path resources, so PDFBox's static initialiser resolves them.
     *
     * Only needed by this test; production code calls PDFBoxResourceLoader.init.
     */
    private fun initResources() = Unit

    fun extract(bytes: ByteArray): List<String> {
        initResources()
        return PDDocument.load(ByteArrayInputStream(bytes)).use { doc ->
            (1..doc.numberOfPages).map { page ->
                val stripper = object : PDFTextStripper() {
                    init {
                        startPage = page
                        endPage = page
                        setSortByPosition(true)
                    }
                }
                stripper.getText(doc)
            }
        }
    }
}

class FixtureWorkbookReader {

    data class CellValue(
        val row: Int,
        val header: String,
        val columnIndex: Int,
        val value: String,
        val formula: String?
    )

    data class RowData(val rowNumber: Int, val values: Map<String, String>) {
        operator fun get(header: String): String? = values[header]
    }

    data class SheetData(
        val name: String,
        val headers: List<String>,
        val rows: List<RowData>,
        val cells: List<CellValue>
    )

    fun read(bytes: ByteArray): List<SheetData> =
        WorkbookFactory.create(ByteArrayInputStream(bytes)).use { wb: Workbook ->
            try {
                (0 until wb.numberOfSheets).map { i ->
                    readSheet(wb.getSheetAt(i))
                }
            } finally {
                // POI's close() spills a temp file and can fail if java.io.tmpdir
                // is not writable; the workbook is fully read by this point.
                runCatching { wb.close() }
            }
        }

    private fun readSheet(sheet: Sheet): SheetData {
        val headerRow: Row? = sheet.getRow(0)
        val headers = headerRow?.map { display(it) } ?: emptyList()

        val rows = mutableListOf<RowData>()
        val cells = mutableListOf<CellValue>()

        for (r in 1..sheet.lastRowNum) {
            val row = sheet.getRow(r) ?: continue
            val values = headers.mapIndexed { c, _ ->
                display(row.getCell(c))
            }
            if (values.all { it.isBlank() }) continue
            rows.add(RowData(r + 1, headers.mapIndexed { c, h -> h to values[c] }.toMap()))

            headers.forEachIndexed { c, h ->
                val cell = row.getCell(c) ?: return@forEachIndexed
                cells.add(
                    CellValue(
                        row = r + 1,
                        header = h,
                        columnIndex = c,
                        value = display(cell),
                        formula = if (cell.cellType == CellType.FORMULA) cell.cellFormula else null
                    )
                )
            }
        }
        return SheetData(sheet.sheetName, headers, rows, cells)
    }

    private fun display(cell: Cell?): String {
        if (cell == null) return ""
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.NUMERIC -> {
                val v = cell.numericCellValue
                if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
            }
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> runCatching {
                when (cell.cachedFormulaResultType) {
                    CellType.NUMERIC -> cell.numericCellValue.toString()
                    CellType.STRING -> cell.stringCellValue
                    else -> ""
                }
            }.getOrDefault("")
            else -> ""
        }
    }
}
