package com.edib.procurement.aipack

/**
 * A read-only report on the indexed document corpus.
 *
 * This exists so that a REAL business folder can be smoke-tested later without
 * claiming it has already been done. It answers, from database state and a
 * bounded probe of each source, the questions that decide whether extraction is
 * genuinely working on real paperwork:
 *
 *  - how much of the corpus there is, by kind and by size;
 *  - how much of it is linked to Follow-up work;
 *  - for PDFs, how many yield text and how many are image-only;
 *  - for DOCX and XLSX, how many open and parse at all.
 *
 * It performs NO OCR and modifies nothing. An image-only PDF is reported as
 * OCR_REQUIRED, which is exactly the evidence needed to justify a later OCR
 * decision without this report pretending to resolve it.
 */
class CorpusValidator(
    private val workspaceDir: java.io.File
) {

    data class KindCount(val extension: String, val documents: Int, val bytes: Long)

    data class Report(
        val totalDocuments: Int,
        val totalBytes: Long,
        val byKind: List<KindCount>,
        val linkedDocuments: Int,
        val unlinkedDocuments: Int,
        val pdfTextReadable: Int,
        val pdfOcrRequired: Int,
        val pdfFailed: Int,
        val docxReadable: Int,
        val docxFailed: Int,
        val xlsxReadable: Int,
        val xlsxFailed: Int,
        val sampled: Boolean,
        val sampleLimit: Int,
        val treeName: String?
    ) {
        /** A short honest verdict, never a claim that a real corpus was tested. */
        val verdict: String
            get() = when {
                totalDocuments == 0 -> "No documents indexed."
                pdfOcrRequired > 0 ->
                    "$pdfOcrRequired PDF(s) have no extractable text and would need OCR. " +
                        "OCR has not been run."
                else -> "All probed documents yielded text."
            }
    }

    /**
     * Builds the report from an already-resolved document list plus a probe.
     *
     * [probe] opens one document and reports how it went; the UI supplies it so
     * this class stays free of Android and Room dependencies and testable on the
     * JVM.
     */
    fun report(
        documents: List<CorpusDocument>,
        probe: (CorpusDocument) -> Outcome,
        treeName: String? = null,
        sampleLimit: Int = 200
    ): Report {
        val byExt = documents.groupBy { it.extension.lowercase() }
        val kinds = byExt.entries
            .map { (ext, list) -> KindCount(ext, list.size, list.sumOf { it.size }) }
            .sortedByDescending { it.bytes }

        // Only probe a bounded sample: this is a smoke-test aid, not a full
        // conversion, and a real folder can hold thousands of files.
        val sampled = documents.take(sampleLimit)
        var pdfOk = 0
        var pdfOcr = 0
        var pdfErr = 0
        var docxOk = 0
        var docxErr = 0
        var xlsxOk = 0
        var xlsxErr = 0

        for (d in sampled) {
            when (d.extension.lowercase()) {
                "pdf" -> when (probe(d)) {
                    Outcome.TEXT -> pdfOk++
                    // Image-only: reported, never "fixed" here.
                    Outcome.NO_TEXT -> pdfOcr++
                    Outcome.FAILED -> pdfErr++
                }
                "docx" -> when (probe(d)) {
                    Outcome.TEXT -> docxOk++
                    Outcome.FAILED -> docxErr++
                    Outcome.NO_TEXT -> docxErr++
                }
                "xlsx", "xls" -> when (probe(d)) {
                    Outcome.TEXT -> xlsxOk++
                    Outcome.FAILED -> xlsxErr++
                    Outcome.NO_TEXT -> xlsxErr++
                }
            }
        }

        return Report(
            totalDocuments = documents.size,
            totalBytes = documents.sumOf { it.size },
            byKind = kinds,
            linkedDocuments = documents.count { it.linkCount > 0 },
            unlinkedDocuments = documents.count { it.linkCount == 0 },
            pdfTextReadable = pdfOk,
            pdfOcrRequired = pdfOcr,
            pdfFailed = pdfErr,
            docxReadable = docxOk,
            docxFailed = docxErr,
            xlsxReadable = xlsxOk,
            xlsxFailed = xlsxErr,
            sampled = documents.size > sampleLimit,
            sampleLimit = sampleLimit,
            treeName = treeName
        )
    }

    /** How one probe went. */
    enum class Outcome { TEXT, NO_TEXT, FAILED }

    /** The minimum a document must expose to be reported on. */
    data class CorpusDocument(
        val id: Long,
        val displayName: String,
        val extension: String,
        val size: Long,
        val linkCount: Int,
        val documentUri: String
    )
}
