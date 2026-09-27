package com.edib.workknowledge.comparison

import com.edib.workknowledge.data.DocumentEntity
import com.edib.workknowledge.domain.Chunk
import com.edib.workknowledge.domain.Document
import com.edib.workknowledge.parsers.DocumentParser
import com.edib.workknowledge.parsers.ParserRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.edib.workknowledge.comparison.DocumentComparisonEngine.CellChange
import com.edib.workknowledge.comparison.DocumentComparisonEngine.ChangedRow
import com.edib.workknowledge.comparison.DocumentComparisonEngine.ComparisonResult
import com.edib.workknowledge.comparison.DocumentComparisonEngine.ComparisonResultWrapper
import com.edib.workknowledge.comparison.DocumentComparisonEngine.RowDiff

interface DocumentComparisonEngine {
    data class ComparisonResult(
        val addedRows: List<RowDiff>,
        val removedRows: List<RowDiff>,
        val changedRows: List<ChangedRow>,
        val summary: String
    )
    
    data class RowDiff(
        val rowIndex: Int,
        val cells: Map<String, String>, // header -> value
        val rawRowData: String
    )
    
    data class ChangedRow(
        val rowIndex: Int,
        val changes: List<CellChange>,
        val oldRow: RowDiff,
        val newRow: RowDiff
    )
    
    data class CellChange(
        val header: String,
        val oldValue: String,
        val newValue: String,
        val changeType: ChangeType
    ) {
        enum class ChangeType { VALUE, FORMULA, BOTH }
    }
    
    suspend fun compareExcelDocuments(
        oldDocument: Document,
        newDocument: Document,
        oldDocumentEntity: DocumentEntity,
        newDocumentEntity: DocumentEntity
     ): ComparisonResultWrapper<ComparisonResult>

    sealed interface ComparisonResultWrapper<out T> {
        data class Success<T>(val value: T) : ComparisonResultWrapper<T>
        data class Failure(val error: String) : ComparisonResultWrapper<Nothing>
    }
}

class ExcelComparisonEngine(
    private val parserRegistry: ParserRegistry
) : DocumentComparisonEngine {
    
    override suspend fun compareExcelDocuments(
        oldDocument: Document,
        newDocument: Document,
        oldDocumentEntity: DocumentEntity,
        newDocumentEntity: DocumentEntity
     ): ComparisonResultWrapper<ComparisonResult> = withContext(Dispatchers.IO) {
        try {
            // Parse both documents
            val oldParser = parserRegistry.getParserForDocument(oldDocument)
            val newParser = parserRegistry.getParserForDocument(newDocument)
            
            if (oldParser == null || newParser == null) {
                return@withContext ComparisonResultWrapper.Failure("No parser available for one or both documents")
            }
            
            val oldParseResult = oldParser.parse(oldDocument)
            val newParseResult = newParser.parse(newDocument)
            
            if (oldParseResult.error != null || newParseResult.error != null) {
                return@withContext ComparisonResultWrapper.Failure("Failed to parse one or both documents")
            }
            
            // Extract structured data from Excel chunks
            val oldSheets = extractSheetsFromChunks(oldParseResult.chunks)
            val newSheets = extractSheetsFromChunks(newParseResult.chunks)
            
            // Compare each sheet
            val allAdded = mutableListOf<RowDiff>()
            val allRemoved = mutableListOf<RowDiff>()
            val allChanged = mutableListOf<ChangedRow>()
            
            val allSheetNames = (oldSheets.keys + newSheets.keys).toSet()
            
            for (sheetName in allSheetNames) {
                val oldSheet = oldSheets[sheetName] ?: emptyList()
                val newSheet = newSheets[sheetName] ?: emptyList()
                
                val sheetComparison = compareSheets(oldSheet, newSheet, sheetName)
                allAdded.addAll(sheetComparison.addedRows)
                allRemoved.addAll(sheetComparison.removedRows)
                allChanged.addAll(sheetComparison.changedRows)
            }
            
            // Generate summary
            val summary = generateSummary(allAdded, allRemoved, allChanged)
            
            ComparisonResultWrapper.Success(ComparisonResult(
                addedRows = allAdded,
                removedRows = allRemoved,
                changedRows = allChanged,
                summary = summary
            ))
        } catch (e: Exception) {
            ComparisonResultWrapper.Failure("Comparison failed: ${e.message}")
        }
    }
    
    private fun extractSheetsFromChunks(chunks: List<com.edib.workknowledge.domain.Chunk>): Map<String, List<ParsedSheetData>> {
        val sheets = mutableMapOf<String, MutableList<ParsedSheetData>>()
        
        for (chunk in chunks) {
            val sheetName = chunk.sheetName ?: "Unknown"
            val sheetData = sheets.getOrPut(sheetName) { mutableListOf() }
            
            // Parse the chunk content back into structured data
            val parsed = parseChunkContent(chunk)
            sheetData.add(parsed)
        }
        
        return sheets.mapValues { (_, v) -> v }
    }
    
    private data class ParsedSheetData(
        val rowStart: Int,
        val rowEnd: Int,
        val headers: List<String>,
        val rows: List<Map<String, String>>
    )
    
    private fun parseChunkContent(chunk: com.edib.workknowledge.domain.Chunk): ParsedSheetData {
        val lines = chunk.content.lines().toList()
        val headers = chunk.metadata.headerRowValues ?: emptyList()
        val rows = mutableListOf<Map<String, String>>()
        
        var inDataSection = false
        for (line in lines) {
            if (line.startsWith("Row ")) {
                inDataSection = true
                val rowData = mutableMapOf<String, String>()
                
                // Parse "Row N: Header1: Value1; Header2: Value2"
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val rowContent = line.substring(colonIdx + 1).trim()
                    val pairs = rowContent.split(";")
                    
                    for (pair in pairs) {
                        val pairParts = pair.split(":", limit = 2)
                        if (pairParts.size == 2) {
                            val header = pairParts[0].trim()
                            val value = pairParts[1].trim()
                            rowData[header] = value
                        }
                    }
                }
                
                if (rowData.isNotEmpty()) {
                    rows.add(rowData)
                }
            }
        }
        
        return ParsedSheetData(
            rowStart = chunk.rowStart ?: 0,
            rowEnd = chunk.rowEnd ?: 0,
            headers = headers,
            rows = rows
        )
    }
    
    private fun compareSheets(
        oldSheet: List<ParsedSheetData>,
        newSheet: List<ParsedSheetData>,
        sheetName: String
    ): ComparisonResult {
        // Flatten all rows from all chunks, stamping each with its real sheet row
        // number so a diff can be pointed back at specific BOQ lines.
        val oldRows = oldSheet.flatMap { chunk -> chunk.rows }.withIndex().map { (idx, row) ->
            val base = oldSheet.firstOrNull()?.rowStart ?: 1
            row + mapOf(ROW_KEY to (base + idx).toString())
        }
        val newRows = newSheet.flatMap { chunk -> chunk.rows }.withIndex().map { (idx, row) ->
            val base = newSheet.firstOrNull()?.rowStart ?: 1
            row + mapOf(ROW_KEY to (base + idx).toString())
        }
        
        // Use first non-empty column as key for matching rows
        val keyColumn = findKeyColumn(oldRows + newRows)
        
        val oldRowMap = oldRows.associateBy { it[keyColumn] ?: "__no_key_${it[ROW_KEY]}" }
        val newRowMap = newRows.associateBy { it[keyColumn] ?: "__no_key_${it[ROW_KEY]}" }
        
        val allKeys = (oldRowMap.keys + newRowMap.keys).toSet()
        
        val added = mutableListOf<RowDiff>()
        val removed = mutableListOf<RowDiff>()
        val changed = mutableListOf<ChangedRow>()
        
        for (key in allKeys) {
            val oldRow = oldRowMap[key]
            val newRow = newRowMap[key]
            
            when {
                oldRow == null && newRow != null -> {
                    // Added row
                    added.add(RowDiff(
                        rowIndex = newRow[ROW_KEY]?.toInt() ?: 0,
                        cells = newRow - ROW_KEY,
                        rawRowData = newRow.values.joinToString(" | ")
                    ))
                }
                oldRow != null && newRow == null -> {
                    // Removed row
                    removed.add(RowDiff(
                        rowIndex = oldRow[ROW_KEY]?.toInt() ?: 0,
                        cells = oldRow - ROW_KEY,
                        rawRowData = oldRow.values.joinToString(" | ")
                    ))
                }
                oldRow != null && newRow != null -> {
                    // Check for changes
                    val changes = mutableListOf<CellChange>()
                    val allHeaders = (oldRow.keys + newRow.keys).filter { it != ROW_KEY }
                    
                    for (header in allHeaders) {
                        val oldVal = oldRow[header] ?: ""
                        val newVal = newRow[header] ?: ""
                        
                        if (oldVal != newVal) {
                            changes.add(CellChange(
                                header = header,
                                oldValue = oldVal,
                                newValue = newVal,
                                changeType = CellChange.ChangeType.VALUE
                            ))
                        }
                    }
                    
                    if (changes.isNotEmpty()) {
                        changed.add(ChangedRow(
                            rowIndex = oldRow[ROW_KEY]?.toInt() ?: 0,
                            changes = changes,
                            oldRow = RowDiff(
                                rowIndex = oldRow[ROW_KEY]?.toInt() ?: 0,
                                cells = oldRow - ROW_KEY,
                                rawRowData = oldRow.values.joinToString(" | ")
                            ),
                            newRow = RowDiff(
                                rowIndex = newRow[ROW_KEY]?.toInt() ?: 0,
                                cells = newRow - ROW_KEY,
                                rawRowData = newRow.values.joinToString(" | ")
                            )
                        ))
                    }
                }
            }
        }
        
        return ComparisonResult(added, removed, changed, "")
    }
    
    private fun findKeyColumn(rows: List<Map<String, String>>): String {
        // Look for common key columns: Item Code, ID, Code, Item, Description
        val candidateKeys = listOf("Item Code", "Item", "Code", "ID", "Description", "Item No", "Item Number")
        
        for (key in candidateKeys) {
            val nonEmptyCount = rows.count { it[key]?.isNotBlank() == true }
            if (nonEmptyCount > rows.size * 0.5) {
                return key
            }
        }
        
        // Fallback: first column with mostly unique values
        val firstRow = rows.firstOrNull() ?: return ROW_KEY
        for (key in firstRow.keys) {
            if (key == ROW_KEY) continue
            val uniqueValues = rows.map { it[key] }.filter { it != null }.distinct().size
            if (uniqueValues > rows.size * 0.7) {
                return key
            }
        }
        
        return ROW_KEY
    }
    
    private fun generateSummary(
        added: List<RowDiff>,
        removed: List<RowDiff>,
        changed: List<ChangedRow>
    ): String {
        val sb = StringBuilder()
        sb.append("Comparison Summary:\n")
        sb.append("- Rows added: ${added.size}\n")
        sb.append("- Rows removed: ${removed.size}\n")
        sb.append("- Rows changed: ${changed.size}\n")
        
        if (changed.isNotEmpty()) {
            sb.append("\nChanged columns:\n")
            val columnChanges = changed.flatMap { it.changes }.groupBy { it.header }
            for ((header, changes) in columnChanges) {
                sb.append("  - $header: ${changes.size} change(s)\n")
            }
        }
        
        return sb.toString()
    }

    private companion object {
        /** Reserved map key carrying a row's real 1-based sheet row number. */
        const val ROW_KEY = "__row_index__"
    }
}
