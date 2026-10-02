package com.edib.procurement.aipack

import com.edib.procurement.database.PackDocumentRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pre-generation estimate.
 *
 * It only has to be honest about being an estimate and roughly right in shape:
 * normalized much smaller than the original, and a part count derived from the
 * chosen target. It must never parse a source to produce these numbers.
 */
class PackSizeEstimatorTest {

    private fun doc(id: Long, size: Long, ext: String) = PackDocumentRow(
        id = id, displayName = "f$id.$ext", relativePath = "p/f$id.$ext",
        documentUri = "uri:$id", mimeType = null, size = size, extension = ext,
        lazySha256 = null
    )

    private val mb = 1024L * 1024L

    @Test
    fun `office and pdf containers estimate as much smaller than the source`() {
        assertTrue(PackSizeEstimator.ratioFor("pdf") < 1.0)
        assertTrue(PackSizeEstimator.ratioFor("xlsx") < 1.0)
        assertTrue(PackSizeEstimator.ratioFor("docx") < 1.0)
        // Plain text is already text, so it is not reduced.
        assertEquals(1.0, PackSizeEstimator.ratioFor("txt"), 0.0001)
    }

    @Test
    fun `a small scope estimates a single part`() {
        val e = PackSizeEstimator.estimate(
            documents = listOf(doc(1, 100_000, "pdf")),
            entryCount = 2,
            targetPartBytes = 25 * mb
        )
        assertEquals(1, e.estimatedParts)
        assertEquals(100_000, e.originalBytes)
        assertTrue(e.estimatedNormalizedBytes < e.originalBytes)
    }

    @Test
    fun `an empty scope estimates one part and zero bytes`() {
        val e = PackSizeEstimator.estimate(emptyList(), 0, 25 * mb)
        assertEquals(1, e.estimatedParts)
        assertEquals(0, e.originalBytes)
    }

    @Test
    fun `a large scope estimates several parts`() {
        val docs = (1..200).map { doc(it.toLong(), 2 * mb, "xlsx") }
        val e = PackSizeEstimator.estimate(docs, 500, 25 * mb)
        assertTrue("400 MB of source must imply multiple parts", e.estimatedParts > 1)
        assertEquals(200 * 2 * mb, e.originalBytes)
    }

    @Test
    fun `part count follows the target`() {
        val docs = (1..100).map { doc(it.toLong(), mb, "pdf") }
        val small = PackSizeEstimator.estimate(docs, 0, 10 * mb)
        val large = PackSizeEstimator.estimate(docs, 0, 50 * mb)
        assertTrue(
            "a smaller target must never imply fewer parts",
            small.estimatedParts >= large.estimatedParts
        )
    }

    @Test
    fun `reduction is reported between zero and one`() {
        val e = PackSizeEstimator.estimate(
            documents = (1..50).map { doc(it.toLong(), mb, "pdf") },
            entryCount = 10, targetPartBytes = 25 * mb
        )
        assertTrue(e.reductionRatio > 0.0)
        assertTrue(e.reductionRatio < 1.0)
    }

    @Test
    fun `entry metadata is counted so a scope with no documents is not free`() {
        val e = PackSizeEstimator.estimate(emptyList(), 100, 25 * mb)
        assertTrue(
            "follow-up text must contribute to the estimate",
            e.estimatedNormalizedBytes > 0
        )
    }

    @Test
    fun `a zero-size document still gets a sane estimate`() {
        val e = PackSizeEstimator.estimate(listOf(doc(1, 0, "pdf")), 0, 25 * mb)
        assertTrue(e.estimatedNormalizedBytes > 0)
    }
}
