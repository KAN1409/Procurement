package com.edib.procurement.aipack

import android.content.Context
import android.net.Uri
import android.util.Log
import com.edib.procurement.database.ContentState
import com.edib.procurement.database.DocumentDao
import com.edib.procurement.database.DocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated content indexing pipeline.
 *
 * Reuses existing extraction implementations (PDFBox, DocxTextReader,
 * OpenXmlWorkbookReader, OcrProcessor) but does NOT require generating an AI Pack.
 *
 * Document → native extraction / OCR fallback → extractedText persistence
 * → extraction state persistence → searchable immediately
 */
class ContentIndexer(
    private val context: Context,
    private val documentDao: DocumentDao
) {

    private val pdfExtractor = PdfTextExtractor(context)
    private val docxReader = DocxTextReader()
    private val workbookReader = OpenXmlWorkbookReader()
    private val ocrProcessor = OcrProcessor(context)

    companion object {
        const val TAG = "ContentIndexer"
        const val EXTRACTOR_VERSION_PDF = "pdfbox-2.0.27"
        const val EXTRACTOR_VERSION_DOCX = "docx-sax-1.0"
        const val EXTRACTOR_VERSION_XLSX = "openxml-sax-1.0"
        const val EXTRACTOR_VERSION_OCR = "tesseract-4.1.1"
        const val EXTRACTOR_VERSION_TEXT = "plain-1.0"
    }

    /**
     * Result of indexing a single document.
     */
    sealed interface IndexResult {
        data class Success(
            val extractedText: String,
            val extractionMethod: String,
            val extractorVersion: String
        ) : IndexResult

        data class NeedsOcr(
            val pageCount: Int
        ) : IndexResult

        data class Unsupported(
            val reason: String
        ) : IndexResult

        data class Failed(
            val reason: String
        ) : IndexResult
    }

    /**
     * Indexes a single document by URI.
     *
     * Returns the indexing result and persists the extracted text and state to the database.
     */
    suspend fun indexDocument(doc: DocumentEntity): IndexResult {
        val ext = doc.extension?.lowercase() ?: ""
        val uri = Uri.parse(doc.documentUri)

        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            var result: IndexResult

            try {
                val input = context.contentResolver.openInputStream(uri)
                if (input == null) {
                    result = IndexResult.Failed("file could not be opened")
                } else {
                    result = when (ext) {
                        "pdf" -> indexPdf(input, doc)
                        "docx" -> indexDocx(input, doc)
                        "xlsx", "xls" -> indexWorkbook(input, doc)
                        "txt", "csv", "md", "json", "xml", "html", "htm", "log" -> indexPlainText(input, doc)
                        else -> IndexResult.Unsupported("extension $ext not supported for content indexing")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Content indexing failed for ${doc.documentUri}", e)
                result = IndexResult.Failed(e.message ?: e.javaClass.simpleName)
            }

            // Persist the result
            persistResult(doc, result, startTime)
            result
        }
    }

    private fun indexPdf(input: InputStream, doc: DocumentEntity): IndexResult {
        val pdfResult = pdfExtractor.extract(input)
        return when (pdfResult) {
            is PdfTextExtractor.Result.Text -> {
                val text = pdfResult.pages.joinToString("\n\n") { it.trim() }.trim()
                if (text.isNotEmpty()) {
                    IndexResult.Success(text, "pdfbox", EXTRACTOR_VERSION_PDF)
                } else {
                    IndexResult.NeedsOcr(pdfResult.pages.size)
                }
            }
            is PdfTextExtractor.Result.NeedsOcr -> {
                IndexResult.NeedsOcr(pdfResult.pageCount)
            }
            is PdfTextExtractor.Result.Failed -> {
                IndexResult.Failed(pdfResult.reason)
            }
        }
    }

    private fun indexDocx(input: InputStream, doc: DocumentEntity): IndexResult {
        val docxDoc = docxReader.read(input)
        if (docxDoc.isEmpty) {
            return IndexResult.Unsupported("docx contained no extractable text")
        }
        val text = docxDoc.blocks.joinToString("\n\n") { block ->
            when (block) {
                is DocxTextReader.Block.Paragraph -> block.text
                is DocxTextReader.Block.Table -> block.rows.joinToString("\n") { row -> row.joinToString("\t") }
            }
        }
        return IndexResult.Success(text.trim(), "docx", EXTRACTOR_VERSION_DOCX)
    }

    private fun indexWorkbook(input: InputStream, doc: DocumentEntity): IndexResult {
        val textBuilder = StringBuilder()
        var hasContent = false
        
        workbookReader.read(input, object : OpenXmlWorkbookReader.SheetSink {
            override fun startSheet(name: String, maxColumn: Int, mergedRanges: List<String>) {
                textBuilder.append("--- Sheet: $name ---\n")
            }

            override fun row(cells: List<OpenXmlWorkbookReader.Cell>) {
                val rowText = cells.joinToString("\t") { cell ->
                    cell.value.trim()
                }.trim()
                if (rowText.isNotEmpty()) {
                    textBuilder.append(rowText).append("\n")
                    hasContent = true
                }
            }

            override fun endSheet() {
                textBuilder.append("\n")
            }
        })

        if (!hasContent) {
            return IndexResult.Unsupported("workbook contained no extractable cell data")
        }
        return IndexResult.Success(textBuilder.toString().trim(), "poi", EXTRACTOR_VERSION_XLSX)
    }

    private fun indexPlainText(input: InputStream, doc: DocumentEntity): IndexResult {
        val text = input.bufferedReader().use { it.readText() }.trim()
        if (text.isEmpty()) {
            return IndexResult.Unsupported("text file is empty")
        }
        return IndexResult.Success(text, "text", EXTRACTOR_VERSION_TEXT)
    }

    /**
     * Handles OCR fallback for PDFs that need it.
     */
    suspend fun runOcrFallback(doc: DocumentEntity): IndexResult {
        val uri = Uri.parse(doc.documentUri)
        return withContext(Dispatchers.IO) {
            try {
                val input = context.contentResolver.openInputStream(uri)
                if (input == null) {
                    return@withContext IndexResult.Failed("file could not be opened for OCR")
                }
                input.use { stream ->
                    val ocrResult = ocrProcessor.process(stream)
                    when (ocrResult) {
                        is OcrProcessor.OcrResult.Text -> {
                            val text = ocrResult.pages.joinToString("\n\n") { it.trim() }.trim()
                            if (text.isNotEmpty()) {
                                IndexResult.Success(text, "tesseract", EXTRACTOR_VERSION_OCR)
                            } else {
                                IndexResult.Failed("OCR produced no text")
                            }
                        }
                        is OcrProcessor.OcrResult.NeedsOcr -> {
                            IndexResult.Failed("OCR still required after fallback")
                        }
                        is OcrProcessor.OcrResult.Failed -> {
                            IndexResult.Failed(ocrResult.reason)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "OCR fallback failed for ${doc.documentUri}", e)
                IndexResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun persistResult(doc: DocumentEntity, result: IndexResult, startedAt: Long) {
        val extractedAt = System.currentTimeMillis()
        val sourceSize = doc.size
        val sourceModified = doc.lastModified

        when (result) {
            is IndexResult.Success -> {
                documentDao.updateExtractedText(doc.id, result.extractedText)
                documentDao.updateContentState(
                    doc.id,
                    ContentState.INDEXED,
                    result.extractionMethod,
                    extractedAt,
                    result.extractorVersion,
                    null,
                    sourceSize,
                    sourceModified
                )
            }
            is IndexResult.NeedsOcr -> {
                documentDao.updateContentState(
                    doc.id,
                    ContentState.NEEDS_OCR,
                    "pdfbox",
                    extractedAt,
                    EXTRACTOR_VERSION_PDF,
                    "No extractable text; OCR required",
                    sourceSize,
                    sourceModified
                )
            }
            is IndexResult.Unsupported -> {
                documentDao.updateContentState(
                    doc.id,
                    ContentState.UNSUPPORTED,
                    null,
                    extractedAt,
                    null,
                    result.reason,
                    sourceSize,
                    sourceModified
                )
            }
            is IndexResult.Failed -> {
                documentDao.updateContentState(
                    doc.id,
                    ContentState.FAILED,
                    null,
                    extractedAt,
                    null,
                    result.reason,
                    sourceSize,
                    sourceModified
                )
            }
        }
    }
}
