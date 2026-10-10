package com.edib.procurement.aipack

/**
 * How much of the selected corpus actually made it into the pack.
 *
 * A headline figure such as "9.9 MB -> 4 KB = 99.96% reduction" is misleading on
 * its own: most of those 9.9 MB may not have been extracted at all. A pack that
 * silently skipped three quarters of its sources looks like a triumph of
 * compression and is actually a failure.
 *
 * These metrics keep the two ideas apart:
 *
 *  - PACKAGING REDUCTION: how much smaller the generated file is than the
 *    normalized text it contains. This is a property of the format.
 *  - EXTRACTION COVERAGE: how much of the source corpus is actually represented
 *    in the pack. This is a property of the parsers.
 *
 * Bytes are reported per extraction state, so a reader can see at a glance how
 * much is waiting on OCR, how much is in a format the app does not read, and how
 * much simply failed.
 */
object ExtractionCoverage {

    data class Bucket(
        val extracted: Int = 0,
        val ocrRequired: Int = 0,
        val unsupported: Int = 0,
        val error: Int = 0,
        val extractedBytes: Long = 0,
        val ocrRequiredBytes: Long = 0,
        val unsupportedBytes: Long = 0,
        val errorBytes: Long = 0
    ) {
        val totalDocuments: Int get() = extracted + ocrRequired + unsupported + error
        val totalBytes: Long
            get() = extractedBytes + ocrRequiredBytes + unsupportedBytes + errorBytes

        /** 0..1 share of documents whose text is in the pack. */
        val documentCoverage: Double
            get() = if (totalDocuments == 0) 0.0
            else extracted.toDouble() / totalDocuments

        /** 0..1 share of SOURCE BYTES whose text is in the pack. */
        val byteCoverage: Double
            get() = if (totalBytes == 0L) 0.0 else extractedBytes.toDouble() / totalBytes
    }

    fun from(sources: List<PackSource>): Bucket {
        var b = Bucket()
        for (s in sources) {
            val bytes = s.sizeBytes
            b = when (s.status) {
                ExtractionStatus.SUCCESS, ExtractionStatus.PARTIAL ->
                    b.copy(
                        extracted = b.extracted + 1,
                        extractedBytes = b.extractedBytes + bytes
                    )
                ExtractionStatus.OCR_REQUIRED ->
                    b.copy(
                        ocrRequired = b.ocrRequired + 1,
                        ocrRequiredBytes = b.ocrRequiredBytes + bytes
                    )
                ExtractionStatus.UNSUPPORTED ->
                    b.copy(
                        unsupported = b.unsupported + 1,
                        unsupportedBytes = b.unsupportedBytes + bytes
                    )
                ExtractionStatus.ERROR, ExtractionStatus.SKIPPED ->
                    b.copy(error = b.error + 1, errorBytes = b.errorBytes + bytes)
            }
        }
        return b
    }

    /** Human-readable report, written into the pack so it travels with the data. */
    fun describe(b: Bucket, normalizedBytes: Long, finalBytes: Long): List<String> {
        val out = ArrayList<String>()
        out.add("EXTRACTION COVERAGE")
        out.add("------------------")
        out.add(
            "Documents selected   ${b.totalDocuments}\n" +
                "Documents extracted   ${b.extracted}\n" +
                "OCR_REQUIRED          ${b.ocrRequired}\n" +
                "UNSUPPORTED           ${b.unsupported}\n" +
                "ERROR / skipped       ${b.error}"
        )
        out.add("")
        out.add(
            "Original sources          ${formatBytes(b.totalBytes)}\n" +
                "Extracted-source bytes    ${formatBytes(b.extractedBytes)}\n" +
                "Pending-OCR source bytes  ${formatBytes(b.ocrRequiredBytes)}\n" +
                "Unsupported source bytes  ${formatBytes(b.unsupportedBytes)}\n" +
                "Error source bytes        ${formatBytes(b.errorBytes)}"
        )
        out.add("")
        // The archive is still being written when this runs, so the final size
        // is not yet known. Saying so is better than printing a confident 0 B.
        out.add(
            "Normalized text            ${formatBytes(normalizedBytes)}\n" +
                "Final AI Pack             " +
                if (finalBytes > 0) formatBytes(finalBytes)
                else "(written after this text; see manifest finalArtifactBytes)"
        )
        out.add("")
        if (finalBytes > 0) {
            val packaging = if (normalizedBytes <= 0) 0.0
            else (1.0 - finalBytes.toDouble() / normalizedBytes)
            out.add(
                "Packaging reduction   ${"%.1f".format(packaging * 100)}%" +
                    "   (final file vs the text it contains)"
            )
        }
        out.add(
            "Extraction coverage   ${"%.1f".format(b.documentCoverage * 100)}% of documents" +
                ", ${"%.1f".format(b.byteCoverage * 100)}% of source bytes"
        )
        if (b.ocrRequired > 0) {
            out.add("")
            out.add("NOTE: $OCR_PENDING_NOTE")
        }
        return out
    }

    private const val OCR_PENDING_NOTE =
        "OCR has not been run. Those documents are represented by their " +
            "provenance only, so the reduction above describes packaging, not " +
            "how much of the corpus is present."
}
