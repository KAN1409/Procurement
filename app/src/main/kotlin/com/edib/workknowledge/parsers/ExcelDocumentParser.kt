package com.edib.workknowledge.parsers

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.edib.workknowledge.data.ChunkMetadata
import com.edib.workknowledge.domain.Chunk
import com.edib.workknowledge.domain.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.FormulaError
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.ss.util.CellReference
import java.io.InputStream

class ExcelDocumentParser(private val context: Context) : DocumentParser {
    override val supportedMimeTypes = listOf(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-excel.sheet.macroEnabled.12",
        "text/csv"
    )
    
    override suspend fun parse(document: Document): DocumentParser.ParseResult = withContext(Dispatchers.IO) {
        val uri = Uri.parse(document.originalUri)
        val contentResolver = context.contentResolver
        
        contentResolver.openInputStream(uri)?.use { inputStream ->
            parseExcel(inputStream, document)
        } ?: DocumentParser.ParseResult(emptyList(), error = "Failed to open document")
    }
    
    private fun parseExcel(inputStream: InputStream, document: Document): DocumentParser.ParseResult {
        var workbook: Workbook? = null
        var result: DocumentParser.ParseResult
        try {
            workbook = WorkbookFactory.create(inputStream)
            val sheetCount = workbook.numberOfSheets
            val allChunks = mutableListOf<Chunk>()
            var globalChunkIndex = 0

            for (sheetIdx in 0 until sheetCount) {
                val sheet = workbook.getSheetAt(sheetIdx)

                // Skip hidden sheets: they are usually scratch/pivot output and
                // indexing them adds noise to retrieval without adding facts.
                val visibility = workbook.getSheetVisibility(sheetIdx)
                if (visibility == org.apache.poi.ss.usermodel.SheetVisibility.HIDDEN ||
                    visibility == org.apache.poi.ss.usermodel.SheetVisibility.VERY_HIDDEN
                ) {
                    continue
                }

                val sheetChunks = parseSheet(sheet, document, globalChunkIndex)
                allChunks.addAll(sheetChunks)
                globalChunkIndex += sheetChunks.size
            }

            result = DocumentParser.ParseResult(
                chunks = allChunks,
                sheetCount = sheetCount
            )
        } catch (e: Exception) {
            result = DocumentParser.ParseResult(
                emptyList(),
                error = "Excel parsing failed: ${e.message}"
            )
        } finally {
            runCatching { workbook?.close() }
        }
        return result
    }
    
    private fun parseSheet(sheet: Sheet, document: Document, startChunkIndex: Int): List<Chunk> {
        val sheetName = sheet.sheetName
        val rows = mutableListOf<DocumentParser.ParsedRow>()
        
        // First pass: collect all rows with cell data
        for (rowIdx in 0..sheet.lastRowNum) {
            val row = sheet.getRow(rowIdx)
            if (row == null) continue
            
            val cells = mutableListOf<DocumentParser.ParsedCell>()
            var hasData = false
            
            for (cellIdx in 0..row.lastCellNum) {
                val cell = row.getCell(cellIdx)
                val coordinate = CellReference.convertNumToColString(cellIdx) + (rowIdx + 1)
                
                if (cell != null) {
                    val parsedCell = parseCell(cell, coordinate)
                    cells.add(parsedCell)
                    if (parsedCell.dataType != DocumentParser.CellDataType.BLANK) {
                        hasData = true
                    }
                } else {
                    cells.add(DocumentParser.ParsedCell(
                        columnIndex = cellIdx,
                        coordinate = coordinate,
                        displayedValue = "",
                        formula = null,
                        dataType = DocumentParser.CellDataType.BLANK
                    ))
                }
            }
            
            if (hasData || cells.isNotEmpty()) {
                rows.add(DocumentParser.ParsedRow(
                    rowIndex = rowIdx,
                    cells = cells,
                    isHeader = false // Will be determined later
                ))
            }
        }
        
        if (rows.isEmpty()) return emptyList()
        
        // Detect header row
        val headerRowIndex = detectHeaderRow(rows)
        val headerMapping = if (headerRowIndex != null) {
            rows[headerRowIndex].cells.map { it.displayedValue }.filter { it.isNotBlank() }
        } else null
        
        // Mark header row
        if (headerRowIndex != null && headerRowIndex < rows.size) {
            rows[headerRowIndex] = rows[headerRowIndex].copy(isHeader = true)
        }
        
        // Create chunks by row groups
        return createRowGroupChunks(
            sheetName = sheetName,
            rows = rows,
            headerRowIndex = headerRowIndex,
            headerMapping = headerMapping,
            document = document,
            startChunkIndex = startChunkIndex
        )
    }
    
    private fun detectHeaderRow(rows: List<DocumentParser.ParsedRow>): Int? {
        // Skip initial rows that look like titles/metadata
        var startIdx = 0
        
        // Skip leading empty or title-like rows (up to 10 rows)
        while (startIdx < minOf(10, rows.size)) {
            val row = rows[startIdx]
            val nonEmptyCells = row.cells.filter { it.displayedValue.isNotBlank() }.size
            val textCells = row.cells.filter { 
                it.dataType == DocumentParser.CellDataType.TEXT && it.displayedValue.isNotBlank() 
            }.size
            
            // If row has mostly text and looks like a title (few cells, no numbers), skip
            if (nonEmptyCells <= 3 && textCells >= nonEmptyCells * 0.7) {
                startIdx++
            } else {
                break
            }
        }
        
        // Look for header row: row with multiple text cells followed by data rows
        for (i in startIdx until minOf(startIdx + 20, rows.size)) {
            val row = rows[i]
            val nonEmptyCells = row.cells.filter { it.displayedValue.isNotBlank() }.size
            val textCells = row.cells.filter { 
                it.dataType == DocumentParser.CellDataType.TEXT && it.displayedValue.isNotBlank() 
            }.size
            val numberCells = row.cells.filter { 
                it.dataType in setOf(DocumentParser.CellDataType.NUMBER, DocumentParser.CellDataType.FORMULA) 
            }.size
            
            // Good header candidate: multiple text cells, few/no numbers, followed by data
            if (nonEmptyCells >= 3 && textCells >= nonEmptyCells * 0.6 && numberCells == 0) {
                // Check if next row has data (numbers)
                if (i + 1 < rows.size) {
                    val nextRow = rows[i + 1]
                    val nextNumbers = nextRow.cells.filter { 
                        it.dataType in setOf(DocumentParser.CellDataType.NUMBER, DocumentParser.CellDataType.FORMULA) 
                    }.size
                    if (nextNumbers > 0) {
                        return i
                    }
                }
            }
        }
        
        // Fallback: first row with multiple non-empty cells
        for (i in startIdx until minOf(startIdx + 5, rows.size)) {
            val nonEmpty = rows[i].cells.filter { it.displayedValue.isNotBlank() }.size
            if (nonEmpty >= 3) return i
        }
        
        return null
    }
    
    private fun createRowGroupChunks(
        sheetName: String,
        rows: List<DocumentParser.ParsedRow>,
        headerRowIndex: Int?,
        headerMapping: List<String>?,
        document: Document,
        startChunkIndex: Int
    ): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        val headerRows = if (headerRowIndex != null) listOf(rows[headerRowIndex]) else emptyList()
        val dataRows = rows.filter { !it.isHeader }
        
        val rowsPerChunk = calculateRowsPerChunk(dataRows)
        
        var chunkIndex = startChunkIndex
        for (groupStart in dataRows.indices.step(rowsPerChunk)) {
            val groupEnd = minOf(groupStart + rowsPerChunk, dataRows.size)
            val groupRows = dataRows.subList(groupStart, groupEnd)
            
            if (groupRows.isEmpty()) continue
            
            val content = serializeRowGroup(
                sheetName = sheetName,
                headerRows = headerRows,
                headerMapping = headerMapping,
                dataRows = groupRows,
                startRowNum = groupRows.first().rowIndex + 1, // 1-based
                endRowNum = groupRows.last().rowIndex + 1
            )
            
            val rowStart = groupRows.first().rowIndex + 1
            val rowEnd = groupRows.last().rowIndex + 1
            
            val chunk = Chunk(
                id = 0,
                projectId = document.projectId,
                documentId = document.id,
                chunkIndex = chunkIndex,
                content = content,
                embedding = null,
                pageNumber = null,
                sheetName = sheetName,
                rowStart = rowStart,
                rowEnd = rowEnd,
                cellRange = null,
                metadata = ChunkMetadata(
                    headerRowValues = headerMapping,
                    sectionTitle = sheetName
                ),
                createdAt = System.currentTimeMillis()
            )
            chunks.add(chunk)
            chunkIndex++
        }
        
        return chunks
    }
    
    private fun calculateRowsPerChunk(dataRows: List<DocumentParser.ParsedRow>): Int {
        if (dataRows.isEmpty()) return 50
        
        // Estimate average tokens per row
        val sampleSize = minOf(10, dataRows.size)
        var totalChars = 0
        for (i in 0 until sampleSize) {
            totalChars += dataRows[i].cells.sumOf { it.displayedValue.length }
        }
        val avgCharsPerRow = totalChars / sampleSize
        
        // Target ~800 tokens per chunk (roughly 3200 chars)
        val targetChars = 3000
        return maxOf(10, minOf(100, targetChars / maxOf(1, avgCharsPerRow)))
    }
    
    private fun serializeRowGroup(
        sheetName: String,
        headerRows: List<DocumentParser.ParsedRow>,
        headerMapping: List<String>?,
        dataRows: List<DocumentParser.ParsedRow>,
        startRowNum: Int,
        endRowNum: Int
    ): String {
        val sb = StringBuilder()
        sb.append("SHEET: $sheetName\n")
        sb.append("ROWS: $startRowNum-$endRowNum\n\n")
        
        // Include headers
        if (headerMapping != null && headerMapping.isNotEmpty()) {
            sb.append("HEADERS: ${headerMapping.joinToString(" | ")}\n\n")
        }
        
        // Serialize each row
        for (row in dataRows) {
            val rowParts = mutableListOf<String>()
            for ((cellIdx, cell) in row.cells.withIndex()) {
                val header = headerMapping?.getOrNull(cellIdx) ?: "Col ${cellIdx + 1}"
                if (cell.displayedValue.isNotBlank()) {
                    rowParts.add("$header: ${cell.displayedValue}")
                }
            }
            if (rowParts.isNotEmpty()) {
                sb.append("Row ${row.rowIndex + 1}: ${rowParts.joinToString("; ")}\n")
            }
        }
        
        return sb.toString()
    }
    
    private fun parseCell(cell: Cell, coordinate: String): DocumentParser.ParsedCell {
        val dataType = when (cell.cellType) {
            CellType.STRING -> DocumentParser.CellDataType.TEXT
            CellType.NUMERIC -> if (DateUtil.isCellDateFormatted(cell)) {
                DocumentParser.CellDataType.DATE
            } else {
                DocumentParser.CellDataType.NUMBER
            }
            CellType.BOOLEAN -> DocumentParser.CellDataType.BOOLEAN
            CellType.FORMULA -> DocumentParser.CellDataType.FORMULA
            CellType.ERROR -> DocumentParser.CellDataType.ERROR
            CellType.BLANK, CellType._NONE -> DocumentParser.CellDataType.BLANK
        }
        
        val displayedValue = getDisplayedValue(cell)
        val formula = if (cell.cellType == CellType.FORMULA) cell.cellFormula else null
        
        return DocumentParser.ParsedCell(
            columnIndex = cell.columnIndex,
            coordinate = coordinate,
            displayedValue = displayedValue,
            formula = formula,
            dataType = dataType
        )
    }
    
    private fun getDisplayedValue(cell: Cell): String {
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    cell.dateCellValue?.toString() ?: cell.numericCellValue.toString()
                } else {
                    // Format numbers nicely
                    val value = cell.numericCellValue
                    if (value == Math.floor(value) && !value.isInfinite()) {
                        value.toLong().toString()
                    } else {
                        value.toString()
                    }
                }
            }
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> {
                try {
                    // Try to get cached value
                    when (cell.cachedFormulaResultType) {
                        CellType.NUMERIC -> {
                            val value = cell.numericCellValue
                            if (value == Math.floor(value) && !value.isInfinite()) {
                                value.toLong().toString()
                            } else {
                                value.toString()
                            }
                        }
                        CellType.STRING -> cell.stringCellValue
                        CellType.BOOLEAN -> cell.booleanCellValue.toString()
                        else -> cell.cellFormula
                    }
                } catch (e: Exception) {
                    cell.cellFormula
                }
            }
            CellType.ERROR -> "ERROR: ${FormulaError.forInt(cell.errorCellValue).name}"
            else -> ""
        }
    }
}
