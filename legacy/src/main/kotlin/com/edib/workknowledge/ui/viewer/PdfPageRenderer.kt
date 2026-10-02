package com.edib.workknowledge.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Renders PDF pages with the platform [PdfRenderer].
 *
 * A citation that says "Page 6" must open page 6, so the renderer is opened
 * against the exact URI we stored at import time and pages are addressed
 * 1-based to match what a reader sees.
 */
class PdfPageRenderer(private val context: Context) : AutoCloseable {

    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var current: PdfRenderer.Page? = null

    val pageCount: Int get() = renderer?.pageCount ?: 0

    @Throws(IOException::class)
    fun open(uri: Uri) {
        close()
        val fd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Could not open the PDF for reading.")
        descriptor = fd
        renderer = PdfRenderer(fd)
    }

    /**
     * Renders [pageNumber] (1-based) at [targetWidthPx].
     * Returns null when the page cannot be rendered.
     */
    suspend fun renderPage(pageNumber: Int, targetWidthPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val r = renderer ?: return@withContext null
            if (pageNumber !in 1..r.pageCount) return@withContext null
            if (targetWidthPx <= 0) return@withContext null

            val result: Bitmap? = runCatching {
                current?.close()
                val page = r.openPage(pageNumber - 1)
                current = page
                val scale = targetWidthPx.toFloat() / page.width.coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(targetWidthPx, height, Bitmap.Config.ARGB_8888)
                // PDF pages assume white paper; without this they render black.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }.getOrNull()
            result
        }

    override fun close() {
        runCatching { current?.close() }
        current = null
        runCatching { renderer?.close() }
        renderer = null
        runCatching { descriptor?.close() }
        descriptor = null
    }
}
