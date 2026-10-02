package com.edib.procurement.aipack

import android.content.Context
import android.net.Uri
import android.util.Log
import com.edib.procurement.database.AiPackDao
import com.edib.procurement.database.AiPackEntity
import com.edib.procurement.database.AiPackSourceEntity
import com.edib.procurement.database.EntryRow
import com.edib.procurement.database.PackDao
import com.edib.procurement.database.PackDocumentRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds an AI Pack: one compact, AI-readable package for a whole scope.
 *
 * This is not an archive. The point is to cut the number of uploads and the
 * binary overhead a person would otherwise hand to a model, while keeping every
 * piece of real business data and the provenance of where it came from.
 *
 * Guarantees upheld here:
 *  - sources are opened read-only and never written back;
 *  - output streams to a temporary file, so nothing large is held in memory and
 *    a cancelled run leaves no half-written file that looks finished;
 *  - each document keeps a stable SOURCE-nnnn boundary, and a document linked to
 *    several selected entries contributes its content exactly once.
 */
class AiPackBuilder(
    private val context: Context,
    private val packDao: PackDao,
    private val aiPackDao: AiPackDao,
    private val workspace: File
) {

    private val workbookReader = OpenXmlWorkbookReader()
    private val docxReader = DocxTextReader()
    private val pdfExtractor = PdfTextExtractor(context)

    // The multipart builder reuses these exact extractors so a split export and
    // a single export of the same scope produce byte-identical source content.
    internal fun workbookReaderForSplit() = workbookReader
    internal fun docxReaderForSplit() = docxReader
    internal fun pdfExtractorForSplit() = pdfExtractor

    /**
     * Writes one document's SOURCE block.
     *
     * Shared by the single and the multipart paths on purpose: extraction is
     * verified logic, and having two copies is how a split pack silently starts
     * differing from a single one. The multipart builder supplies its own header
     * lines and calls this for the body only.
     */
    internal fun writeSourceBlock(
        text: PackTextWriter,
        doc: PackDocumentRow,
        links: List<com.edib.procurement.database.PackLinkRow>,
        sourceId: String,
        include: PackInclude,
        detail: PackDetail,
        onSource: (PackSource) -> Unit,
        onExtractProgress: (String, Int, Int) -> Unit = { _, _, _ -> }
    ) {
        val best = links.maxByOrNull { it.confidence }
        val ext = (doc.extension ?: "").lowercase()

        text.rule()
        text.line(sourceId)
        text.rule()
        text.line()
        text.line("Filename: ${doc.displayName}")
        text.line("Relative Path: ${doc.relativePath}")
        text.line("Type: ${ext.uppercase()}")
        text.line("Original Size: ${formatBytes(doc.size)}")
        text.line("Linked: ${if (best != null) "yes" else "no (selected manually)"}")
        best?.let {
            text.line("Link Type: ${if (it.isManual) "MANUAL" else it.linkType}")
            text.line("Match Method: ${it.matchMethod}")
            it.evidence?.lines()?.forEach { line -> text.line("  $line") }
        }
        text.line()

        val wanted = when (ext) {
            "pdf" -> include.pdf
            "xlsx", "xls" -> include.excel
            "docx" -> include.word
            "txt", "csv", "md" -> include.text
            else -> false
        }

        var status = ExtractionStatus.SUCCESS
        var detailText: String? = null
        // The extractors report OCR_REQUIRED / ERROR in their own body; this
        // stops the summary being written twice.
        var statusReportedInBody = false

        if (!wanted) {
            status = ExtractionStatus.SKIPPED
            detailText = "not included by the current options"
        } else {
            try {
                val outcome = openStream(Uri.parse(doc.documentUri))
                if (outcome == null) {
                    status = ExtractionStatus.ERROR
                    detailText = "file could not be opened"
                } else {
                    outcome.use { input ->
                        status = when (ext) {
                            "pdf" -> emitPdf(text, input)
                            "xlsx", "xls" -> {
                                emitWorkbook(text, input, detail, doc.displayName) { d, s ->
                                    onExtractProgress("Extracting ${doc.displayName}", s, 100)
                                }
                                ExtractionStatus.SUCCESS
                            }
                            "docx" -> emitDocx(text, input)
                            else -> {
                                emitPlainText(text, input, ext)
                                ExtractionStatus.SUCCESS
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                // One bad file must never cost the rest of the pack.
                status = ExtractionStatus.ERROR
                detailText = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "extraction failed for ${doc.displayName}", t)
            }
        }

        statusReportedInBody = status == ExtractionStatus.OCR_REQUIRED ||
            status == ExtractionStatus.ERROR

        if (!statusReportedInBody) {
            if (status == ExtractionStatus.ERROR) {
                text.line("Extraction: ERROR")
                text.line("Reason: $detailText")
            } else {
                text.line("Extraction: ${status.name}")
            }
        }
        text.line()

        if (status == ExtractionStatus.OCR_REQUIRED && detailText == null) {
            detailText = "no extractable text; the document is image-based"
        }

        onSource(
            PackSource(
                sourceId = sourceId,
                documentId = doc.id,
                filename = doc.displayName,
                relativePath = doc.relativePath,
                mimeType = doc.mimeType,
                sizeBytes = doc.size,
                linkedGroupCode = null,
                linkedEntryCode = null,
                linkType = best?.linkType,
                matchEvidence = best?.evidence,
                status = status,
                detail = detailText,
                checksum = doc.lazySha256,
                linkedManually = best?.isManual ?: false
            )
        )
    }

    suspend fun build(
        scope: PackScope,
        include: PackInclude,
        detail: PackDetail,
        zip: Boolean,
        onProgress: (PackProgress) -> Unit = {}
    ): PackResult = withContext(Dispatchers.IO) {

        val groupRows = packDao.groupsByIds(scope.groupIds)
        val allEntries =
            if (scope.groupIds.isEmpty()) emptyList()
            else packDao.entriesOfGroups(scope.groupIds)
        // A scope of "selected entries" narrows the export; a group or project
        // scope exports everything it contains.
        val entries = if (scope.entryIds.isEmpty()) allEntries
        else allEntries.filter { it.id in scope.entryIds }

        // ---- resolve the document set ---------------------------------
        val entryIds = entries.map { it.id }
        val linkedDocs = packDao.linkedDocuments(scope.groupIds, entryIds)
        val explicitDocs =
            if (scope.documentIds.isEmpty()) emptyList()
            else packDao.documentsByIds(scope.documentIds)

        // A document linked to two selected entries must still appear once.
        val byId = LinkedHashMap<Long, PackDocumentRow>()
        linkedDocs.forEach { byId[it.id] = it }
        explicitDocs.forEach { byId[it.id] = it }

        val linksByDoc = packDao.linksForDocuments(byId.keys.toList()).groupBy { it.documentId }

        val originalTotalBytes = byId.values.sumOf { it.size }

        // ---- output scaffolding ---------------------------------------
        val outDir = File(workspace, "aipacks")
        if (!outDir.exists()) outDir.mkdirs()

        // A completed pack always gets a dated, sortable name; a partial one
        // carries the ".part" marker so a crash can never leave something that
        // looks finished.
        val stamp = stamp()
        val baseName = PackNaming.fileStem(scope.name, scope.type)
        val finalFile = File(outDir, "$baseName.${if (zip) "zip" else "md"}")
        val tempFile = File(outDir, ".$baseName.${if (zip) "zip" else "md"}.$stamp.part")

        val progress = ProgressGate(onProgress)
        val sources = ArrayList<PackSource>()
        var packBytes = 0L
        var ocrRequired = 0
        var unsupported = 0
        var errors = 0
        var skipped = 0

        try {
            val fileOut = tempFile.outputStream().buffered(64 * 1024)
            val zipStream: ZipOutputStream? = if (zip) ZipOutputStream(fileOut) else null
            val textOut: OutputStream = zipStream ?: fileOut
            val text = PackTextWriter(textOut)

            if (zipStream != null) zipStream.putNextEntry(ZipEntry("AI_PACK.md"))

            val generatedAt =
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())

            // ---------------- header ------------------------------------
            text.line("# PROCUREMENT AI PACK")
            text.line()
            text.line("Format-Version: 1")
            text.line("Generated: $generatedAt")
            text.line("Scope-Type: ${scope.type.name}")
            text.line("Scope-Name: ${scope.name}")
            text.line()
            text.line("Follow-Up Groups: ${groupRows.size}")
            text.line("Entries: ${entries.size}")
            text.line("Documents: ${byId.size}")
            text.line()
            text.line("Original Total Size: ${humanBytes(originalTotalBytes)}")

            // Assign a stable source id per document, up front, so the
            // Follow-up section can reference SOURCE-nnnn before the document
            // section writes it.
            val sourceIdOf = HashMap<Long, String>()
            byId.keys.forEachIndexed { i, id -> sourceIdOf[id] = "SOURCE-%04d".format(i + 1) }

            // ---------------- follow-up section ------------------------
            if (include.followUpData) {
                var gi = 0
                for (g in groupRows) {
                    currentCoroutineContext().ensureActive()
                    gi++
                    progress.publish("Preparing Follow-up data", gi, groupRows.size)

                    text.rule()
                    text.line("FOLLOW-UP GROUP %03d".format(gi))
                    text.rule()
                    text.line()
                    text.line("Project: ${g.projectRaw ?: ""}")
                    text.line("Area: ${g.area ?: ""}")
                    text.line("Related To: ${g.relatedTo ?: ""}")
                    text.line("Anchor Code: ${g.rootCodeRaw ?: ""}")
                    text.line("Source Workbook Row: ${g.originalExcelRow}")
                    text.line()

                    val groupEntries = entries.filter { it.groupId == g.groupId }
                    for ((ei, e) in groupEntries.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        text.rule('-')
                        text.line("ENTRY %03d".format(ei + 1))
                        text.rule('-')
                        text.line()
                        writeEntry(text, e, detail, linksByDoc, sourceIdOf)
                    }
                }
            }

            // ---------------- document section -------------------------
            var di = 0
            val total = byId.size
            for (doc in byId.values) {
                currentCoroutineContext().ensureActive()
                di++
                progress.publish("Extracting documents", di, total)

                val sourceId = sourceIdOf.getValue(doc.id)
                val links = linksByDoc[doc.id].orEmpty()
                val best = links.maxByOrNull { it.confidence }
                val ext = (doc.extension ?: "").lowercase()

                // Whether this kind is wanted at all.
                val wanted = when (ext) {
                    "pdf" -> include.pdf
                    "xlsx", "xls", "csv" -> include.excel
                    "docx", "doc" -> include.word
                    "txt" -> include.text
                    else -> false
                }
                if (!wanted) {
                    skipped++
                    sources.add(
                        PackSource(
                            sourceId = sourceId, documentId = doc.id,
                            filename = doc.displayName, relativePath = doc.relativePath,
                            mimeType = doc.mimeType, sizeBytes = doc.size,
                            linkedGroupCode = null, linkedEntryCode = null,
                            linkType = best?.linkType, matchEvidence = best?.evidence,
                            status = ExtractionStatus.SKIPPED,
                            detail = "excluded by the current include settings",
                            checksum = doc.lazySha256,
                            linkedManually = best?.isManual ?: false
                        )
                    )
                    continue
                }

                text.rule()
                text.line(sourceId)
                text.rule()
                text.line()
                text.line("Filename: ${doc.displayName}")
                text.line("Relative Path: ${doc.relativePath}")
                text.line("Type: ${ext.uppercase()}")
                text.line("Original Size: ${humanBytes(doc.size)}")
                text.line("Linked: ${if (best != null) "yes" else "no (selected manually)"}")
                best?.let {
                    text.line("Link Type: ${if (it.isManual) "MANUAL" else it.linkType}")
                    text.line("Match Method: ${it.matchMethod}")
                    it.evidence?.lines()?.forEach { line -> text.line("  $line") }
                }
                text.line()

                var status = ExtractionStatus.SUCCESS
                var detailText: String? = null
                // Some extractors report their own outcome inside the body (an
                // OCR_REQUIRED or an ERROR banner). This remembers whether one
                // did, so the summary is not written twice.
                var statusReportedInBody = false

                try {
                    val uri = Uri.parse(doc.documentUri)
                    val outcome = openStream(uri)
                    if (outcome == null) {
                        status = ExtractionStatus.ERROR
                        detailText = "file could not be opened"
                    } else {
                        outcome.use { input ->
                            status = when (ext) {
                                "pdf" -> emitPdf(text, input)
                                "xlsx", "xls" -> {
                                    emitWorkbook(text, input, detail, doc.displayName) { d, s ->
                                        progress.publish("Extracting ${doc.displayName}", s, 100)
                                    }
                                    ExtractionStatus.SUCCESS
                                }
                                "csv", "txt" -> {
                                    emitPlainText(text, input, ext)
                                    ExtractionStatus.SUCCESS
                                }
                                "docx", "doc" -> emitDocx(text, input)
                                else -> {
                                    detailText = "no extractor for .$ext"
                                    ExtractionStatus.UNSUPPORTED
                                }
                            }
                        }
                        // The extractor wrote its own banner for these outcomes.
                        statusReportedInBody =
                            status == ExtractionStatus.OCR_REQUIRED ||
                                status == ExtractionStatus.ERROR
                    }
                } catch (t: Throwable) {
                    // One bad file must never cost the rest of the pack.
                    status = ExtractionStatus.ERROR
                    detailText = t.message ?: t.javaClass.simpleName
                    statusReportedInBody = false
                    Log.w(TAG, "extraction failed for ${doc.displayName}", t)
                }

                if (!statusReportedInBody) {
                    if (status == ExtractionStatus.ERROR) {
                        text.line("Extraction: ERROR")
                        text.line("Reason: $detailText")
                    } else {
                        text.line("Extraction: ${status.name}")
                    }
                }
                text.line()

                when (status) {
                    ExtractionStatus.SUCCESS -> Unit
                    ExtractionStatus.OCR_REQUIRED -> {
                        ocrRequired++
                        // Recorded in the manifest so a pack explains itself
                        // without having to be parsed back out of the text.
                        detailText = detailText ?: "no extractable text; the document is image-based"
                    }
                    ExtractionStatus.UNSUPPORTED -> unsupported++
                    ExtractionStatus.ERROR -> errors++
                    else -> Unit
                }

                sources.add(
                    PackSource(
                        sourceId = sourceId, documentId = doc.id,
                        filename = doc.displayName, relativePath = doc.relativePath,
                        mimeType = doc.mimeType, sizeBytes = doc.size,
                        linkedGroupCode = null, linkedEntryCode = null,
                        linkType = best?.linkType, matchEvidence = best?.evidence,
                        status = status, detail = detailText,
                        checksum = doc.lazySha256,
                        linkedManually = best?.isManual ?: false
                    )
                )
            }

            // ---------------- footer ------------------------------------
            text.endLine()
            text.line()
            text.line("Normalized Text Size: ${humanBytes(text.size)}")
            text.flush()
            packBytes = text.size

            if (zipStream != null) {
                // The manifest is written last so it can carry the real counts.
                zipStream.putNextEntry(ZipEntry("manifest.json"))
                val manifest = ManifestWriter.build(
                    scope, groupRows.size, entries.size, byId.size,
                    originalTotalBytes, packBytes, sources
                )
                val bytes = manifest.toByteArray(Charsets.UTF_8)
                zipStream.write(bytes)
                packBytes += bytes.size
                zipStream.closeEntry()
            } else {
                val manifest = ManifestWriter.build(
                    scope, groupRows.size, entries.size, byId.size,
                    originalTotalBytes, packBytes, sources
                )
                // A standalone markdown pack carries its manifest as a trailing
                // block, so nothing is lost when the file is shared on its own.
                text.line()
                text.line("----- manifest.json -----")
                manifest.lineSequence().forEach { text.line(it) }
                text.close()
            }

            if (zipStream == null) text.close() else text.close()
            packBytes = finalFileOrTempLength(tempFile)

            val result = PackResult(
                fileName = finalFile.name,
                absolutePath = finalFile.absolutePath,
                contentUri = null,
                scopeName = scope.name,
                scopeType = scope.type,
                groups = groupRows.size,
                entries = entries.size,
                documents = byId.size,
                originalTotalBytes = originalTotalBytes,
                packBytes = packBytes,
                succeeded = sources.count { it.status == ExtractionStatus.SUCCESS },
                ocrRequired = ocrRequired,
                unsupported = unsupported,
                errors = errors,
                skipped = skipped
            )

            if (finalFile.exists()) finalFile.delete()
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            // The history row is written only once the file is in place, so a
            // crash can never leave a record pointing at nothing.
            val finished = result.copy(packBytes = finalFile.length())
            recordHistory(finished, sources)
            finished
        } catch (t: Throwable) {
            // Cancellation or failure: never leave something that looks done.
            runCatching { tempFile.delete() }
            throw t
        }
    }

    private suspend fun recordHistory(result: PackResult, sources: List<PackSource>) {
        val packId = aiPackDao.insert(
            AiPackEntity(
                fileName = result.fileName,
                absolutePath = result.absolutePath,
                scopeName = result.scopeName,
                scopeType = result.scopeType.name,
                createdAt = System.currentTimeMillis(),
                groupCount = result.groups,
                entryCount = result.entries,
                documentCount = result.documents,
                originalBytes = result.originalTotalBytes,
                packBytes = result.packBytes,
                succeeded = result.succeeded,
                ocrRequired = result.ocrRequired,
                unsupported = result.unsupported,
                errors = result.errors,
                skipped = result.skipped
            )
        )
        if (packId > 0 && sources.isNotEmpty()) {
            aiPackDao.insertSources(
                sources.map {
                    AiPackSourceEntity(
                        packId = packId,
                        sourceId = it.sourceId,
                        documentId = it.documentId,
                        filename = it.filename,
                        relativePath = it.relativePath,
                        mimeType = it.mimeType,
                        sizeBytes = it.sizeBytes,
                        linkType = it.linkType,
                        linkedManually = it.linkedManually,
                        extractionStatus = it.status.name,
                        detail = it.detail
                    )
                }
            )
        }
    }

    private fun finalFileOrTempLength(temp: File): Long =
        if (temp.length() > 0) temp.length() else temp.length()

    // ------------------------------------------------------------ sections

    private fun writeEntry(
        text: PackTextWriter,
        e: EntryRow,
        detail: PackDetail,
        linksByDoc: Map<Long, List<com.edib.procurement.database.PackLinkRow>>,
        sourceIdOf: Map<Long, String>
    ) {
        text.line("Code: ${e.codeRaw ?: ""}")
        text.line("Contractor: ${e.manualContractorName ?: e.contractorName ?: ""}")
        if (detail == PackDetail.DETAILED || e.contractorName == null) {
            text.line("Raw Contractor: ${e.companyRaw ?: ""}")
        }
        text.line("Description: ${e.description ?: ""}")
        text.line("PR Date: ${e.prDate ?: ""}")
        text.line("Order Date: ${e.orderDate ?: ""}")
        text.line("PR With Engineer: ${e.prWithEngineer ?: ""}")
        text.line("Notes: ${e.notes ?: ""}")
        text.line("Financial Received Date: ${e.financialReceivedDate ?: ""}")
        text.line("Final Approval Date: ${e.finalApprovalDate ?: ""}")
        text.line("Check: ${e.checkValue ?: ""}")
        text.line()
        text.line("Linked Documents:")
        val mine = linksByDoc.entries
            .filter { (_, rows) -> rows.any { it.entryId == e.id } }
            .mapNotNull { sourceIdOf[it.key] }
            .sorted()
        if (mine.isEmpty()) text.line("- (none)") else mine.forEach { text.line("- $it") }
        text.line()
    }

    // ---------------------------------------------------------- extractors

    /**
     * Writes a PDF's text, page by page.
     *
     * Returns the status the extractor actually reached, which the caller records
     * in the manifest. A document that turned out to be image-only is reported
     * as OCR_REQUIRED even though the caller had optimistically started at
     * SUCCESS.
     */
    private fun emitPdf(text: PackTextWriter, input: InputStream): ExtractionStatus {
        return when (val r = pdfExtractor.extract(input)) {
            is PdfTextExtractor.Result.Text -> {
                r.pages.forEachIndexed { i, pageText ->
                    text.line("--- PAGE ${i + 1} ---")
                    text.line(pageText)
                    text.line()
                }
                ExtractionStatus.SUCCESS
            }

            is PdfTextExtractor.Result.NeedsOcr -> {
                // An image-only document: say so rather than emitting the few
                // stray characters the parser happened to find.
                text.line("Extraction: OCR_REQUIRED")
                text.line("Pages: ${r.pageCount}")
                ExtractionStatus.OCR_REQUIRED
            }

            is PdfTextExtractor.Result.Failed -> {
                text.line("Extraction: ERROR")
                text.line("Reason: ${r.reason}")
                ExtractionStatus.ERROR
            }
        }
    }

    private fun emitWorkbook(
        text: PackTextWriter,
        input: InputStream,
        detail: PackDetail,
        fileName: String,
        onSheetProgress: (String, Int) -> Unit
    ) {
        val writer = SheetTextWriter(text, detail == PackDetail.DETAILED)
        writer.onRow = { rows -> onSheetProgress(fileName, rows) }
        workbookReader.read(input, writer, useTsv = true)
    }

    /** Returns the status the extractor actually reached. */
    private fun emitDocx(text: PackTextWriter, input: InputStream): ExtractionStatus {
        val doc = runCatching { docxReader.read(input) }.getOrNull()
        if (doc == null || doc.isEmpty) {
            // A DOCX with no text runs is an image-based document.
            text.line("Extraction: OCR_REQUIRED")
            text.line("(no text runs found; the document is likely image-based)")
            return ExtractionStatus.OCR_REQUIRED
        }
        for (block in doc.blocks) {
            when (block) {
                is DocxTextReader.Block.Paragraph ->
                    if (block.heading) text.line("## ${block.text}") else text.line(block.text)

                is DocxTextReader.Block.Table -> {
                    for (r in block.rows) {
                        text.line(r.joinToString("\t") { it.replace('\t', ' ') })
                    }
                    text.line()
                }
            }
        }
        return ExtractionStatus.SUCCESS
    }

    private fun emitPlainText(text: PackTextWriter, input: InputStream, extension: String) {
        val bytes = input.readBytes()
        val decoded = decodeText(bytes, extension)
        decoded.lineSequence().forEach { text.line(it.trimEnd()) }
    }

    /**
     * UTF-8 first, with a BOM honoured and a conservative fallback.
     *
     * The fallback only triggers when the bytes are not valid UTF-8 at all, so
     * a mis-encoded Arabic file is reported rather than silently mangled.
     */
    private fun decodeText(bytes: ByteArray, extension: String): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            // Not UTF-8. Windows-1256 is the usual encoding for legacy Arabic
            // spreadsheets exported as text; decoding with replacement keeps the
            // pack readable rather than dropping the file.
            String(bytes, java.nio.charset.Charset.forName("windows-1256"))
        }
    }

    private fun openStream(uri: Uri): InputStream? =
        runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()

    /**
     * Throttles progress reporting.
     *
     * A pack may hold hundreds of documents. Publishing every single step would
     * recompose the UI hundreds of times for no visible gain, so progress is
     * emitted only when the percentage actually moves or the phase changes.
     * The final state is always published.
     *
     * Visible for tests: the throttling behaviour is worth pinning down, and a
     * private class cannot be exercised from a unit test.
     */
    internal class ProgressGate(
        private val emit: (PackProgress) -> Unit,
        private val minimumIntervalMs: Long = 120
    ) {
        private var lastEmit = 0L
        private var lastPercent = -1
        private var lastPhase = ""

        fun publish(phase: String, current: Int, total: Int) {
            val percent = if (total > 0) ((current * 100) / total).coerceIn(0, 100) else -1
            val now = System.currentTimeMillis()
            val phaseChanged = phase != lastPhase
            val movedEnough = percent != lastPercent
            val timeEnough = now - lastEmit >= minimumIntervalMs
            if (phaseChanged || (movedEnough && timeEnough)) {
                lastEmit = now
                lastPercent = percent
                lastPhase = phase
                emit(PackProgress(phase, current, total))
            }
        }
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun humanBytes(bytes: Long): String = formatBytes(bytes)

    internal companion object {
        const val TAG = "ProcurementPack"

        /** Test seam: builds a gate without running a whole pack. */
        fun ProgressGateForTest(emit: (PackProgress) -> Unit) = ProgressGate(emit)
    }
}
