package com.edib.workknowledge.parsers

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.edib.workknowledge.data.ChunkMetadata
import com.edib.workknowledge.domain.Chunk
import com.edib.workknowledge.domain.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class TextDocumentParser(private val context: Context) : DocumentParser {
    override val supportedMimeTypes = listOf("text/plain", "text/csv")
    
    override suspend fun parse(document: Document): DocumentParser.ParseResult = withContext(Dispatchers.IO) {
        val uri = Uri.parse(document.originalUri)
        val contentResolver = context.contentResolver
        
        contentResolver.openInputStream(uri)?.use { inputStream ->
            parseText(inputStream, document)
        } ?: DocumentParser.ParseResult(emptyList(), error = "Failed to open document")
    }
    
    private fun parseText(inputStream: InputStream, document: Document): DocumentParser.ParseResult {
        var result: DocumentParser.ParseResult
        try {
            val reader = InputStreamReader(inputStream, StandardCharsets.UTF_8)
            val content = reader.readText()
            
            if (content.trim().isEmpty()) {
                return DocumentParser.ParseResult(emptyList(), error = "Empty document")
            }
            
            // For CSV, use Excel parser logic
            if (document.mimeType == "text/csv") {
                return parseCsv(content, document)
            }
            
            // For plain text, chunk by paragraphs
            val paragraphs = content.split("\n\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            
            val chunks = paragraphs.mapIndexed { index, paragraph ->
                Chunk(
                    id = 0,
                    projectId = document.projectId,
                    documentId = document.id,
                    chunkIndex = index,
                    content = paragraph,
                    embedding = null,
                    pageNumber = null,
                    sheetName = null,
                    rowStart = null,
                    rowEnd = null,
                    cellRange = null,
                    metadata = ChunkMetadata(paragraphIndex = index),
                    createdAt = System.currentTimeMillis()
                )
            }
            
            // Split large chunks
            val finalChunks = chunks.flatMap { chunk ->
                if (chunk.content.length > 2000) {
                    splitLargeChunk(chunk)
                } else {
                    listOf(chunk)
                }
            }
            
            result = DocumentParser.ParseResult(chunks = finalChunks)
        } catch (e: Exception) {
            result = DocumentParser.ParseResult(
                emptyList(),
                error = "Text parsing failed: ${e.message}"
            )
        }
        return result
    }
    
    private fun parseCsv(content: String, document: Document): DocumentParser.ParseResult {
        val lines = content.lines().toList()
        if (lines.isEmpty()) return DocumentParser.ParseResult(emptyList(), error = "Empty CSV")
        
        // Simple CSV parsing - split by comma (handles basic cases)
        val rows = lines.map { line ->
            line.split(",").map { it.trim().trim('"') }
        }
        
        // Detect header
        val headerMapping = rows.firstOrNull()?.filter { it.isNotBlank() }
        val dataRows = if (headerMapping != null) rows.drop(1) else rows
        
        val chunks = mutableListOf<Chunk>()
        val rowsPerChunk = 50
        var chunkIndex = 0
        
        for (groupStart in dataRows.indices.step(rowsPerChunk)) {
            val groupEnd = minOf(groupStart + rowsPerChunk, dataRows.size)
            val groupRows = dataRows.subList(groupStart, groupEnd)
            
            val sb = StringBuilder()
            sb.append("FILE: ${document.filename}\n")
            sb.append("ROWS: ${groupStart + 1}-${groupEnd}\n\n")
            
            if (headerMapping != null && headerMapping.isNotEmpty()) {
                sb.append("HEADERS: ${headerMapping.joinToString(" | ")}\n\n")
            }
            
            for ((rowIdx, row) in groupRows.withIndex()) {
                val rowParts = mutableListOf<String>()
                for ((cellIdx, cell) in row.withIndex()) {
                    val header = headerMapping?.getOrNull(cellIdx) ?: "Col ${cellIdx + 1}"
                    if (cell.isNotBlank()) {
                        rowParts.add("$header: $cell")
                    }
                }
                if (rowParts.isNotEmpty()) {
                    sb.append("Row ${groupStart + rowIdx + 1}: ${rowParts.joinToString("; ")}\n")
                }
            }
            
            val chunk = Chunk(
                id = 0,
                projectId = document.projectId,
                documentId = document.id,
                chunkIndex = chunkIndex,
                content = sb.toString(),
                embedding = null,
                pageNumber = null,
                sheetName = "CSV",
                rowStart = groupStart + 1,
                rowEnd = groupEnd,
                cellRange = null,
                metadata = ChunkMetadata(headerRowValues = headerMapping),
                createdAt = System.currentTimeMillis()
            )
            chunks.add(chunk)
            chunkIndex++
        }

        return DocumentParser.ParseResult(chunks = chunks)
    }
    
    private fun splitLargeChunk(chunk: Chunk): List<Chunk> {
        val maxLength = 1500
        val overlap = 200
        val content = chunk.content
        val chunks = mutableListOf<Chunk>()
        var start = 0
        var localIndex = 0
        
        while (start < content.length) {
            val end = minOf(start + maxLength, content.length)
            val piece = content.substring(start, end)
            
            val newChunk = chunk.copy(
                chunkIndex = chunk.chunkIndex + localIndex,
                content = piece
            )
            chunks.add(newChunk)
            localIndex++
            
            if (end >= content.length) break
            start = end - overlap
        }
        
        return chunks
    }
}
