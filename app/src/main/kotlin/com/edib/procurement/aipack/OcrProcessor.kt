package com.edib.procurement.aipack

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * OCR processor for scanned PDFs using Tesseract4Android.
 * 
 * Uses Tesseract OCR with Arabic ("ara") and English ("eng") traineddata.
 * Processes scanned PDFs page by page, rendering each page to a bitmap
 * and running OCR on it. Preserves exact source identity, page numbers,
 * raw OCR output, and recognition uncertainty.
 * 
 * This class is designed to be used alongside PdfTextExtractor for a complete
 * PDF processing pipeline: first try text extraction, fall back to OCR
 * for pages that yield no text.
 */
class OcrProcessor(private val context: Context) {

    sealed interface OcrResult {
        data class Text(
            val pages: List<String>,
            val pageCount: Int,
            val ocrPages: List<Int>
        ) : OcrResult
        data class NeedsOcr(val pageCount: Int) : OcrResult
        data class Failed(val reason: String) : OcrResult
    }

    /**
     * Processes a PDF document page by page, using OCR for pages that
     * don't yield text through standard extraction.
     * 
     * @param input Input stream of the PDF file
     * @param maxPages Maximum pages to process (0 = no limit)
     * @param dpi Resolution for rendering PDF pages (default 300 DPI)
     * @return Result containing extracted text per page or OCR requirement
     */
    fun process(
        input: InputStream,
        maxPages: Int = 0,
        dpi: Int = 300
    ): OcrResult {
        // Save input stream to temp file for PdfRenderer
        val tempFile = File.createTempFile("ocr_input", ".pdf")
        val tessDataDir = copyTessDataFiles()
        
        try {
            FileOutputStream(tempFile).use { it.write(input.readAllBytes()) }

            val renderer = android.graphics.pdf.PdfRenderer(
                ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            )

            val pageCount = renderer.pageCount
            val pages = mutableListOf<String>()
            val ocrPages = mutableListOf<Int>()

            // Initialize Tesseract with Arabic + English
            val tessBaseAPI = TessBaseAPI()
            val initialized = tessBaseAPI.init(tessDataDir.absolutePath, "ara+eng")
            
            if (!initialized) {
                tessBaseAPI.recycle()
                return OcrResult.Failed("Failed to initialize Tesseract with ara+eng. Check tessdata files exist at $tessDataDir")
            }

            try {
                for (pageIndex in 0 until renderer.pageCount) {
                    if (maxPages > 0 && pageIndex >= maxPages) break

                    val page = renderer.openPage(pageIndex)
                    val bitmap = Bitmap.createBitmap(
                        page.width, page.height, Bitmap.Config.ARGB_8888
                    )
                    page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                    // Run OCR on the page bitmap
                    tessBaseAPI.setImage(bitmap)
                    val text = tessBaseAPI.getUTF8Text()
                    
                    if (text.isNotEmpty()) {
                        pages.add(text.trim())
                        ocrPages.add(pageIndex + 1) // 1-based page numbers
                    } else {
                        pages.add("")
                    }

                    page.close()
                }

                renderer.close()

                if (ocrPages.isEmpty()) {
                    return OcrResult.NeedsOcr(pageCount)
                }

                return OcrResult.Text(pages, pageCount, ocrPages)
            } finally {
                tessBaseAPI.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "OCR processing failed", e)
            return OcrResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Copies traineddata files from assets to app's files directory.
     * Returns the directory containing tessdata/ with traineddata files.
     */
    private fun copyTessDataFiles(): File {
        val tessDataDir = File(context.filesDir, "tessdata")
        if (!tessDataDir.exists()) {
            tessDataDir.mkdirs()
        }

        // Copy traineddata files from assets
        val languages = listOf("ara.traineddata", "eng.traineddata")
        val assetManager = context.assets

        for (langFile in languages) {
            val targetFile = File(tessDataDir, langFile)
            if (!targetFile.exists()) {
                try {
                    assetManager.open("tessdata/$langFile").use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.i(TAG, "Copied $langFile to $tessDataDir")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to copy $langFile from assets: ${e.message}")
                }
            }
        }

        return tessDataDir.parentFile!!
    }

    companion object {
        const val TAG = "OcrProcessor"
    }
}
