package com.edib.procurement.aipack

import android.content.Context
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream

/**
 * PDF text extraction, page by page.
 *
 * A PDF with no extractable text is reported as needing OCR rather than being
 * written out as whatever bytes happened to decode: the pack must never contain
 * garbage in place of a real document, and must never invent content.
 */
class PdfTextExtractor(private val context: Context? = null) {

    sealed interface Result {
        data class Text(val pages: List<String>) : Result
        data class NeedsOcr(val pageCount: Int) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * @param maxPagesPerDocument guards against a pathological file; text is
     *   still returned, simply capped, and the cap is reported in the manifest.
     */
    fun extract(input: InputStream, maxPagesPerDocument: Int = 2_000): Result {
        return try {
            initResources()
            PDDocument.load(input).use { document ->
                val pageCount = document.numberOfPages
                if (pageCount == 0) {
                    return Result.NeedsOcr(0)
                }
                val limit = minOf(pageCount, maxPagesPerDocument)
                val pages = ArrayList<String>(limit)
                var anyText = false

                for (i in 0 until limit) {
                    val stripper = PDFTextStripper().apply {
                        startPage = i + 1
                        endPage = i + 1
                        // Line-per-paragraph keeps the reading order without
                        // the column noise of the default layout mode.
                        sortByPosition = true
                    }
                    val text = runCatching { stripper.getText(document) }
                        .getOrDefault("")
                        .trim()
                    if (text.isNotEmpty()) anyText = true
                    pages.add(text)
                }

                // A scanned page yields no glyphs at all. If nothing anywhere
                // produced text, this is an image-only document and the caller
                // must mark it OCR_REQUIRED rather than guess.
                if (!anyText) Result.NeedsOcr(pageCount)
                else Result.Text(pages)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "PDF extraction failed: ${e.message}")
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun initResources() {
        if (initialised) return
        synchronized(this) {
            if (initialised) return
            // Required by the Android port: without it the bundled CMap and glyph
            // tables cannot be found and Arabic text comes out as blanks.
            PDFBoxResourceLoader.init(context)
            initialised = true
        }
    }

    private companion object {
        const val TAG = "ProcurementPack"
        @Volatile
        private var initialised = false
    }
}
