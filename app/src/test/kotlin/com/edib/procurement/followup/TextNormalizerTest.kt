package com.edib.procurement.followup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Code and text normalisation.
 *
 * The invariant under test: normalisation only ever affects the *search key*.
 * The raw value the workbook wrote is never altered, so "MMH2-GR-AR-14-25  B"
 * still displays with its original double space.
 */
class TextNormalizerTest {

    @Test
    fun `spacing differences around a suffix still match`() {
        val a = TextNormalizer.normalizeCode("MMH2-GR-AR-14-25  B")
        val b = TextNormalizer.normalizeCode("MMH2-GR-AR-14-25 B")
        assertEquals(a, b)
    }

    @Test
    fun `variant markers are never stripped`() {
        val raw = "MMH2-GR-AR-14-25 L adj"
        val normalized = TextNormalizer.normalizeCode(raw)
        assertTrue("the ADJ marker must survive", normalized.contains("ADJ"))
        assertTrue(normalized.endsWith("ADJ"))
    }

    @Test
    fun `Arabic variant word is preserved`() {
        val normalized = TextNormalizer.normalizeCode("MMH2-GR-AR-14-25  L ملحق")
        assertTrue(normalized.contains("ملحق"))
    }

    @Test
    fun `spaces around hyphens are normalised but the hyphen itself stays`() {
        assertEquals(
            TextNormalizer.normalizeCode("MMH2 - GR - AR - 14 - 25B"),
            TextNormalizer.normalizeCode("MMH2-GR-AR-14-25B")
        )
    }

    @Test
    fun `case is folded for latin letters`() {
        assertEquals(
            TextNormalizer.normalizeCode("mmh2-gr-ar-14-25b"),
            TextNormalizer.normalizeCode("MMH2-GR-AR-14-25B")
        )
    }

    @Test
    fun `different variants do not collide`() {
        assertNotEquals(
            TextNormalizer.normalizeCode("MMH2-GR-AR-14-25B"),
            TextNormalizer.normalizeCode("MMH2-GR-AR-14-25C")
        )
    }

    @Test
    fun `loose search key makes a spaced query find hyphenated codes`() {
        val key = TextNormalizer.codeSearchKey("MMH2 GR AR 14 25")
        val target = TextNormalizer.codeSearchKey("MMH2-GR-AR-14-25A")
        assertTrue("query '$key' should match '$target'", target.contains(key))
    }

    @Test
    fun `file stem drops the extension and normalises`() {
        assertEquals("MMH2-GR-AR-14-25A", TextNormalizer.nameStem("MMH2-GR-AR-14-25A.PDF"))
        assertEquals("pdf", TextNormalizer.extension("MMH2-GR-AR-14-25A.PDF"))
    }

    @Test
    fun `loose file key folds duplicate separators and casing`() {
        assertEquals(
            TextNormalizer.looseFileKey("MMH2--GR-AR-14-25-B.pdf"),
            TextNormalizer.looseFileKey("mmh2 gr ar 14 25 b.pdf")
        )
    }

    @Test
    fun `arabic orthographic variants fold for comparison`() {
        // Alef-with-hamza vs plain alef.
        assertEquals(
            TextNormalizer.normalize("أحمد"),
            TextNormalizer.normalize("احمد")
        )
    }

    @Test
    fun `trivially punctuated input is trimmed`() {
        assertEquals("MMH2", TextNormalizer.normalize("  -MMH2-  "))
    }

    @Test
    fun `null and blank normalise to empty`() {
        assertEquals("", TextNormalizer.normalize(null))
        assertEquals("", TextNormalizer.normalizeCode("   "))
        assertEquals("", TextNormalizer.normalizeCode(null))
    }
}
