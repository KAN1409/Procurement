package com.edib.workknowledge.parsers

import com.edib.workknowledge.domain.Chunk
import com.edib.workknowledge.domain.Document

sealed interface DocumentParser {
    val supportedMimeTypes: List<String>
    
    suspend fun parse(document: Document): ParseResult
    
    data class ParseResult(
        val chunks: List<Chunk>,
        val pageCount: Int = 0,
        val sheetCount: Int = 0,
        val error: String? = null
    )
    
    data class ParsedPage(
        val pageNumber: Int,
        val text: String,
        val paragraphs: List<Paragraph>
    )
    
    data class Paragraph(
        val text: String,
        val bounds: Rect? = null
    )
    
    data class Rect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )
    
    data class ParsedSheet(
        val sheetName: String,
        val rows: List<ParsedRow>,
        val headerRowIndex: Int?,
        val headerMapping: List<String>?
    )
    
    data class ParsedRow(
        val rowIndex: Int,
        val cells: List<ParsedCell>,
        val isHeader: Boolean = false
    )
    
    data class ParsedCell(
        val columnIndex: Int,
        val coordinate: String, // e.g., "A1"
        val displayedValue: String,
        val formula: String?,
        val dataType: CellDataType
    )
    
    enum class CellDataType { TEXT, NUMBER, DATE, BOOLEAN, FORMULA, ERROR, BLANK }
}

/**
 * Resolves a parser for a MIME type or file extension.
 *
 * Kept in its own file (see ParserRegistry.kt) so there is exactly one
 * ParserRegistry type in this package. Two same-named classes in one package
 * shadow each other and break every call site depending on import order.
 */
object ParserLookup {
    val PDF_MIME = "application/pdf"
    val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    val XLSM_MIME = "application/vnd.ms-excel.sheet.macroEnabled.12"
    val CSV_MIME = "text/csv"
    val TXT_MIME = "text/plain"

    val SUPPORTED_EXTENSIONS = listOf("pdf", "xlsx", "xlsm", "csv", "txt")

    fun mimeTypeForExtension(extension: String): String = when (extension.lowercase()) {
        "pdf" -> PDF_MIME
        "xlsx" -> XLSX_MIME
        "xlsm" -> XLSM_MIME
        "csv" -> CSV_MIME
        "txt" -> TXT_MIME
        else -> "application/octet-stream"
    }
}
