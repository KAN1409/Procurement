package com.edib.procurement.aipack

import com.edib.procurement.database.EntryRow
import com.edib.procurement.database.PackDocumentRow

/**
 * Estimates the normalized size of a pack BEFORE it is generated.
 *
 * This is deliberately an estimate, not a measurement. Parsing every source
 * twice just to show a number would double the cost of every export, and the
 * number is only used to say "expect roughly N parts" and to pre-split the plan
 * so the user is not surprised at the end.
 *
 * The estimate is derived from what the database already knows: the ORIGINAL
 * size of each document. Normalized text is much smaller than the source file
 * for Office and PDF containers, so a per-extension ratio is applied. The result
 * is rounded generously and the UI labels it an estimate.
 */
object PackSizeEstimator {

    /**
     * Ratio of normalized text bytes to original file bytes.
     *
     * These are deliberately conservative (they assume a fairly large text
     * yield). Under-estimating would claim fewer parts than are produced;
     * over-estimating only makes the count pessimistic, which is the safe
     * direction for a "roughly this many parts" hint.
     */
    private const val PDF_RATIO = 0.35
    private const val OFFICE_RATIO = 0.55
    private const val TEXT_RATIO = 1.0
    private const val OTHER_RATIO = 0.30

    /** A small fixed allowance for the Follow-up text of one entry. */
    const val ENTRY_METADATA_BYTES = 400L
    /** And for a group header. */
    const val GROUP_HEADER_BYTES = 250L

    fun ratioFor(extension: String?): Double = when (extension?.lowercase()) {
        "pdf" -> PDF_RATIO
        "xlsx", "xls", "docx", "pptx" -> OFFICE_RATIO
        "txt", "csv", "md", "json", "xml" -> TEXT_RATIO
        else -> OTHER_RATIO
    }

    /** Estimated normalized bytes for one document. */
    fun normalizedSizeOf(doc: PackDocumentRow): Long {
        val size = if (doc.size > 0) doc.size else 1_024L
        return (size * ratioFor(doc.extension)).toLong().coerceAtLeast(64L)
    }

    /**
     * Estimate for a whole scope.
     *
     * Returns the original total, the estimated normalized total and the number
     * of parts implied by [targetPartBytes]. Parts are counted from the
     * estimated total only, which is enough for a pre-generation hint; the real
     * part count comes from the planner once the actual text exists.
     */
    fun estimate(
        documents: List<PackDocumentRow>,
        entryCount: Int,
        targetPartBytes: Long,
        entryMetadataBytes: Long = ENTRY_METADATA_BYTES
    ): PackEstimate {
        val originalBytes = documents.sumOf { it.size }
        val docsBytes = documents.sumOf { normalizedSizeOf(it) }
        val followUpBytes = entryCount * entryMetadataBytes +
            (if (entryCount > 0) GROUP_HEADER_BYTES else 0L)
        val normalizedBytes = docsBytes + followUpBytes
        val parts = if (normalizedBytes <= targetPartBytes) 1
        else Math.ceil(normalizedBytes.toDouble() / targetPartBytes.toDouble()).toInt().coerceAtLeast(1)
        return PackEstimate(
            originalBytes = originalBytes,
            estimatedNormalizedBytes = normalizedBytes,
            targetPartBytes = targetPartBytes,
            estimatedParts = parts
        )
    }
}

/** A pre-generation size estimate, explicitly not a measurement. */
data class PackEstimate(
    val originalBytes: Long,
    val estimatedNormalizedBytes: Long,
    val targetPartBytes: Long,
    val estimatedParts: Int
) {
    val reductionRatio: Double
        get() = if (originalBytes <= 0) 0.0
        else 1.0 - (estimatedNormalizedBytes.toDouble() / originalBytes.toDouble())
}
