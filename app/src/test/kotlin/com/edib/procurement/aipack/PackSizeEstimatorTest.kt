package com.edib.procurement.aipack

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The size model must speak in the units the user actually uploads.
 *
 * Phase 4 predicted part sizes from the ORIGINAL file size times a fixed ratio,
 * so a target of 10 MB produced three parts totalling 0.7 MB: the target was
 * being applied to a quantity nobody uploads. These tests pin the corrected
 * model:
 *
 *  - the planner works from a MEASURED DEFLATE size, not a guessed ratio;
 *  - the target means the finished ZIP;
 *  - highly compressible and poorly compressible content behave differently,
 *    because the estimator measures rather than assumes;
 *  - a small export stays a single pack even when its originals are large.
 */
class PackSizeEstimatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val mb = 1024L * 1024L

    /** Writes content and returns the spool entry with both sizes measured. */
    private fun measure(name: String, bytes: ByteArray): NormalizedSpool.Entry {
        val dir = tmp.newFolder("spool-" + name)
        val spool = NormalizedSpool(dir)
        val f = spool.create(1L, "SOURCE-0001")
        f.writeBytes(bytes)
        return spool.register(1L, "SOURCE-0001", f)
    }

    private fun repetitive(mb_: Int): ByteArray {
        val line = "the same line over and over again and again and again\n"
        val sb = StringBuilder()
        while (sb.length < mb_ * 1024 * 1024) sb.append(line)
        return sb.toString().toByteArray()
    }

    /** Text with high entropy per character: poor compression. */
    private fun noisy(mb_: Int): ByteArray {
        val rnd = java.util.Random(42)
        val alphabet = (' '..'~').toList()
        val sb = StringBuilder()
        while (sb.length < mb_ * 1024 * 1024) {
            repeat(64) { sb.append(alphabet[rnd.nextInt(alphabet.size)]) }
            sb.append('\n')
        }
        return sb.toString().toByteArray()
    }

    // ------------------------------------------------------------- spool

    @Test
    fun `spool records the real uncompressed size`() {
        val e = measure("plain", "hello world\n".repeat(1000).toByteArray())
        assertTrue("normalized size must be measured", e.normalizedBytes > 0)
        assertEquals(e.file.length(), e.normalizedBytes)
    }

    @Test
    fun `spool predicts a compressed size smaller than the text`() {
        val e = measure("rep", repetitive(1))
        assertTrue(
            "compressed must be smaller than ${e.normalizedBytes}, was ${e.predictedZipBytes}",
            e.predictedZipBytes < e.normalizedBytes
        )
    }

    @Test
    fun `highly compressible content measures far smaller than poorly compressible`() {
        val rep = measure("rep2", repetitive(1)).predictedZipBytes
        val noisy = measure("noisy", noisy(1)).predictedZipBytes
        assertTrue(
            "repetitive (=$rep) should compress far better than noisy (=$noisy)",
            noisy > rep * 3
        )
    }

    @Test
    fun `measurement is per content, not one global ratio`() {
        val rep = measure("r3", repetitive(1))
        val noisy = measure("n3", noisy(1))
        val repRatio = PackSizeEstimator.compressionRatio(rep.normalizedBytes, rep.predictedZipBytes)
        val noisyRatio = PackSizeEstimator.compressionRatio(noisy.normalizedBytes, noisy.predictedZipBytes)
        assertTrue("ratios must differ by content, not be a constant",
            repRatio < noisyRatio / 4)
    }

    @Test
    fun `spool clears every file it created`() {
        val dir = tmp.newFolder("clearsp")
        val spool = NormalizedSpool(dir)
        val f = spool.create(5L, "SOURCE-0001")
        f.writeText("content")
        spool.register(5L, "SOURCE-0001", f)
        spool.clear()
        assertTrue("spool directory must be gone", !dir.exists())
    }

    @Test
    fun `spool copy reproduces the bytes exactly`() {
        val dir = tmp.newFolder("copysp")
        val spool = NormalizedSpool(dir)
        val bytes = "exact bytes \u0645\u0631\u062D\u0628\u0627".repeat(50).toByteArray()
        val f = spool.create(9L, "SOURCE-0001")
        f.writeBytes(bytes)
        val e = spool.register(9L, "SOURCE-0001", f)
        val out = java.io.ByteArrayOutputStream()
        spool.copyTo(e, out)
        assertTrue(bytes.contentEquals(out.toByteArray()))
    }

    // ------------------------------------------------------------ ceiling

    @Test
    fun `soft ceiling sits below the target`() {
        val ceiling = PackSizeEstimator.softCeiling(10 * mb)
        assertTrue("ceiling must be under target", ceiling < 10 * mb)
        assertEquals("5% margin", (10 * mb * 0.95).toLong(), ceiling)
    }

    @Test
    fun `a safety factor is configurable`() {
        assertEquals((10 * mb * 0.80).toLong(), PackSizeEstimator.softCeiling(10 * mb, 0.80))
        assertEquals((10 * mb * 0.99).toLong(), PackSizeEstimator.softCeiling(10 * mb, 0.99))
    }

    // ----------------------------------------------------------- prediction

    @Test
    fun `a small final package predicts ONE part despite huge originals`() {
        // The phase 4 failure mode: 26 MB of originals that compress to ~0.7 MB.
        val p = PackSizeEstimator.predict(
            originalBytes = 26 * mb,
            normalizedBytes = 26 * mb,
            predictedCompressedBytes = 700 * 1024,
            targetPartBytes = 10 * mb
        )
        assertEquals("a sub-target final size must be a single pack", 1, p.predictedParts)
        assertTrue(p.predictedFinalBytes < 10 * mb)
    }

    @Test
    fun `prediction reports all three quantities distinctly`() {
        val p = PackSizeEstimator.predict(100 * mb, 40 * mb, 41 * mb, 25 * mb)
        assertEquals(100 * mb, p.originalBytes)
        assertEquals(40 * mb, p.normalizedBytes)
        // Final includes the ZIP overhead allowance, so it is slightly above the
        // measured compressed total.
        assertTrue(p.predictedFinalBytes > 41 * mb)
        assertEquals(25 * mb, p.targetPartBytes)
    }

    @Test
    fun `prediction counts parts against the soft ceiling`() {
        val p = PackSizeEstimator.predict(
            originalBytes = 100 * mb,
            normalizedBytes = 60 * mb,
            predictedCompressedBytes = 38 * mb,
            targetPartBytes = 25 * mb
        )
        val ceiling = PackSizeEstimator.softCeiling(25 * mb)
        val expected = Math.ceil(p.predictedFinalBytes.toDouble() / ceiling).toInt()
        assertEquals(expected, p.predictedParts)
        assertTrue("more than one part expected", p.predictedParts > 1)
    }

    @Test
    fun `zip overhead is always allowed for`() {
        val p = PackSizeEstimator.predict(0, 0, 0, 25 * mb)
        assertTrue("even an empty pack has ZIP structure", p.predictedFinalBytes > 0)
        assertEquals(1, p.predictedParts)
    }

    @Test
    fun `prediction is deterministic`() {
        val a = PackSizeEstimator.predict(10 * mb, 6 * mb, 5 * mb, 25 * mb)
        val b = PackSizeEstimator.predict(10 * mb, 6 * mb, 5 * mb, 25 * mb)
        assertEquals(a, b)
    }
}
