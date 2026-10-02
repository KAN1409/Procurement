package com.edib.procurement.aipack

/**
 * Predicts the size of the AI Pack the user will actually upload.
 *
 * Three quantities are deliberately kept apart, because they answer different
 * questions and conflating them is what made the phase 4 target useless:
 *
 *  - ORIGINAL: the byte size of the source documents. Reported for reference
 *    only. It says nothing about upload size.
 *  - NORMALIZED: the uncompressed extracted text. Reported so a reader can see
 *    what extraction produced.
 *  - PREDICTED FINAL: what the generated ZIP is expected to occupy. THIS is the
 *    number the target applies to, because it is the number the user uploads.
 *
 * The prediction comes from DEFLATE measurements taken on the real normalized
 * blocks (see [NormalizedSpool]), never from the original file size and never
 * from a single global compression ratio.
 */
object PackSizeEstimator {

    /**
     * Overhead a ZIP adds around the compressed content: local file headers,
     * central directory and the end-of-archive record, plus the manifest.
     *
     * ZIP overhead is roughly constant per part rather than proportional, which
     * matters for a small part; 48 KB is a generous allowance.
     */
    const val ZIP_OVERHEAD_BYTES = 48L * 1024L

    /**
     * Fraction of the target parts are allowed to reach.
     *
     * The user picks "25 MB" meaning "a file I can upload". Landing exactly on
     * the limit is bad, so parts aim just below it and leave room for the
     * manifest and index overhead.
     */
    const val DEFAULT_SAFETY_FACTOR = 0.95

    /** The soft ceiling the planner packs against. */
    fun softCeiling(targetBytes: Long, safety: Double = DEFAULT_SAFETY_FACTOR): Long =
        (targetBytes * safety).toLong().coerceAtLeast(1024L)

    /**
     * Prediction for a whole export.
     *
     * [predictedFinalBytes] is the sum of the measured DEFLATE sizes plus one
     * overhead allowance; the part count is derived from that against the soft
     * ceiling.
     */
    data class Prediction(
        val originalBytes: Long,
        val normalizedBytes: Long,
        val predictedFinalBytes: Long,
        val targetPartBytes: Long,
        val softCeilingBytes: Long,
        val predictedParts: Int
    ) {
        /** Shown in the UI as "estimated final AI Pack". */
        val predictedFinalText: String get() = formatBytes(predictedFinalBytes)
    }

    fun predict(
        originalBytes: Long,
        normalizedBytes: Long,
        predictedCompressedBytes: Long,
        targetPartBytes: Long,
        safety: Double = DEFAULT_SAFETY_FACTOR
    ): Prediction {
        val ceiling = softCeiling(targetPartBytes, safety)
        val total = predictedCompressedBytes + ZIP_OVERHEAD_BYTES
        val parts = if (total <= ceiling) 1
        else Math.ceil(total.toDouble() / ceiling.toDouble()).toInt().coerceAtLeast(1)
        return Prediction(
            originalBytes = originalBytes,
            normalizedBytes = normalizedBytes,
            predictedFinalBytes = total,
            targetPartBytes = targetPartBytes,
            softCeilingBytes = ceiling,
            predictedParts = parts
        )
    }

    /** Per-kind ratio of compressed to uncompressed normalized text, for reference only. */
    fun compressionRatio(normalizedBytes: Long, compressedBytes: Long): Double =
        if (normalizedBytes <= 0) 1.0 else (compressedBytes.toDouble() / normalizedBytes)
}
