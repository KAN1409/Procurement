package com.edib.workknowledge.parsers

import android.content.Context
import android.net.Uri
import com.edib.workknowledge.data.ChunkMetadata
import com.edib.workknowledge.domain.Chunk
import com.edib.workknowledge.domain.Document
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Page-aware PDF text extraction.
 *
 * Every chunk keeps its [pageNumber] so an answer can cite the exact page the
 * fact came from. Chunks never span a page boundary: a citation that says
 * "Page 6" must mean page 6 and nothing else.
 */
class PdfDocumentParser(context: Context) : DocumentParser {

    private val appContext = context.applicationContext

    override val supportedMimeTypes = listOf("application/pdf")

    override suspend fun parse(document: Document): DocumentParser.ParseResult =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(document.originalUri)
            // PDFBox-Android ships its assets inside the AAR; the loader must be
            // initialised once before any parsing happens.
            runCatching { PDFBoxResourceLoader.init(appContext) }

            appContext.contentResolver.openInputStream(uri)?.use { input ->
                parsePdf(input, document)
            } ?: DocumentParser.ParseResult(
                emptyList(),
                error = "Could not open the file. The app no longer has permission to read it."
            )
        }

    private fun parsePdf(inputStream: InputStream, document: Document): DocumentParser.ParseResult {
        var pdfDoc: PDDocument? = null
        var result: DocumentParser.ParseResult
        try {
            pdfDoc = PDDocument.load(inputStream)

            if (pdfDoc.isEncrypted) {
                // Never pretend an empty success: the user must know why nothing came out.
                return DocumentParser.ParseResult(
                    chunks = emptyList(),
                    pageCount = pdfDoc.numberOfPages,
                    error = "This PDF is password-protected. Remove the password and import it again."
                )
            }

            val pageCount = pdfDoc.numberOfPages
            val allChunks = mutableListOf<Chunk>()
            var globalChunkIndex = 0
            var extractableChars = 0

            for (pageNum in 1..pageCount) {
                val text = runCatching {
                    PageTextStripper(pageNum).getText(pdfDoc)
                }.getOrDefault("")
                extractableChars += text.trim().length

                val chunks = buildPageChunks(text, pageNum, document, globalChunkIndex)
                allChunks.addAll(chunks)
                globalChunkIndex += chunks.size
            }

            result = if (allChunks.isEmpty() || extractableChars < SCANNED_CHAR_THRESHOLD) {
                // Almost certainly a scan. Say so plainly instead of returning
                // an empty document that looks successfully indexed.
                DocumentParser.ParseResult(
                    chunks = emptyList(),
                    pageCount = pageCount,
                    error = PdfScannerNotice.message(pageCount, extractableChars)
                )
            } else {
                DocumentParser.ParseResult(chunks = allChunks, pageCount = pageCount)
            }
        } catch (e: OutOfMemoryError) {
            result = DocumentParser.ParseResult(
                emptyList(),
                error = "Not enough memory to read this PDF. Try splitting it into smaller files."
            )
        } catch (e: Exception) {
            result = DocumentParser.ParseResult(
                emptyList(),
                error = "PDF parsing failed: ${e.message ?: e::class.java.simpleName}"
            )
        } finally {
            runCatching { pdfDoc?.close() }
        }
        return result
    }

    private fun buildPageChunks(
        pageText: String,
        pageNumber: Int,
        document: Document,
        startIndex: Int
    ): List<Chunk> {
        if (pageText.isBlank()) return emptyList()

        val out = mutableListOf<Chunk>()
        var index = startIndex

        for ((paraIdx, paragraph) in splitIntoParagraphs(pageText).withIndex()) {
            if (paragraph.isBlank()) continue
            val pieces = if (paragraph.length > MAX_PARAGRAPH_CHARS) {
                splitOversized(paragraph, MAX_PARAGRAPH_CHARS, OVERLAP_CHARS)
            } else {
                listOf(paragraph)
            }
            for (piece in pieces) {
                out.add(
                    Chunk(
                        projectId = document.projectId,
                        documentId = document.id,
                        chunkIndex = index++,
                        content = piece,
                        pageNumber = pageNumber,
                        metadata = ChunkMetadata(
                            paragraphIndex = paraIdx,
                            sectionTitle = "Page $pageNumber"
                        )
                    )
                )
            }
        }
        return out
    }

    /**
     * Paragraph-first splitting that keeps the page's own line structure.
     * A hard line break is a weaker signal than a blank line, so blank lines
     * win; remaining newlines become spaces inside a paragraph.
     */
    private fun splitIntoParagraphs(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        return normalized
            .split(REGEX_BLANK_LINE)
            .map { block -> block.replace(REGEX_NEWLINE, " ").trim() }
            .filter { it.isNotEmpty() }
    }

    private fun splitOversized(text: String, max: Int, overlap: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val parts = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + max, text.length)
            if (end < text.length) {
                // prefer to break on a space so words are not cut in half
                val lastSpace = text.lastIndexOf(' ', end)
                if (lastSpace > start + max / 2) end = lastSpace
            }
            parts.add(text.substring(start, end).trim())
            if (end >= text.length) break
            start = end - overlap
            if (start < 0) start = 0
        }
        return parts.filter { it.isNotEmpty() }
    }


    companion object {
        private val REGEX_BLANK_LINE = Regex("\\n\\s*\\n")
        private val REGEX_NEWLINE = Regex("\\s*\\n\\s*")

        private const val MAX_PARAGRAPH_CHARS = 1600
        private const val OVERLAP_CHARS = 200

        /** Below this many extractable characters a PDF is treated as a scan. */
        private const val SCANNED_CHAR_THRESHOLD = 40
    }
}

/** Strips text for exactly one page. */
private class PageTextStripper(page: Int) : PDFTextStripper() {
    init {
        startPage = page
        endPage = page
        setSortByPosition(true)
    }
}

/**
 * Text for a scanned PDF, shown verbatim to the user.
 *
 * V1 ships no OCR engine, so this must be an explicit, honest limitation
 * rather than an empty-but-successful document.
 */
object PdfScannerNotice {
    const val NO_OCR_SUPPORTED =
        "No extractable text detected. This looks like a scanned document, " +
            "and OCR is not currently available, so nothing was indexed. " +
            "To use this file, run OCR on it first and import the result, or " +
            "import a text-based (not scanned) version."

    fun message(pageCount: Int, extractableChars: Int): String =
        "$NO_OCR_SUPPORTED ($pageCount page(s), $extractableChars character(s) of text found.)"
}
