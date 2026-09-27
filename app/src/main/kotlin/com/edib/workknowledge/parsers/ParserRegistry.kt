package com.edib.workknowledge.parsers

import android.content.Context
import com.edib.workknowledge.domain.Document
import java.util.Locale

/**
 * The one and only ParserRegistry for this package.
 *
 * Resolution order:
 *  1. exact MIME type match
 *  2. file extension match (handles the many MIME types Android reports for
 *     .xlsx/.xlsm depending on the document provider)
 */
class ParserRegistry(context: Context) {

    private val appContext = context.applicationContext

    private val parsers: List<DocumentParser> = listOf(
        PdfDocumentParser(appContext),
        ExcelDocumentParser(appContext),
        TextDocumentParser(appContext)
    )

    fun getParserForDocument(document: Document): DocumentParser? =
        getParserForMimeType(document.mimeType)
            ?: getParserForExtension(document.filename.substringAfterLast('.', ""))

    fun getParserForMimeType(mimeType: String): DocumentParser? {
        if (mimeType.isBlank()) return null
        val normalized = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
        return parsers.firstOrNull { normalized in it.supportedMimeTypes }
    }

    fun getParserForExtension(extension: String): DocumentParser? {
        val mime = ParserLookup.mimeTypeForExtension(extension)
        return getParserForMimeType(mime)
    }

    fun getAllParsers(): List<DocumentParser> = parsers

    fun supportedExtensions(): List<String> = ParserLookup.SUPPORTED_EXTENSIONS
}
