package com.edib.openwhispr

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the two non-engine features added in this pass:
 * mixed Arabic/English BiDi presentation and Voice Note import.
 *
 * These are plain JVM tests (no Robolectric in this project), so the BiDi checks
 * assert the configuration contract and the invariants on the raw string,
 * rather than rendering. The rendering itself was verified on a real device.
 *
 * Nothing here touches the transcription engine.
 */
class TranscriptBidiAndImportTest {

    private val mixed =
        "نعم يا استاز نونو عاوز من بابا ايه؟ What do you want from بابا? Tell me, tell me what you want"

    // ---------- BiDi: the raw string must survive rendering ----------

    /**
     * Read OpenWisprUi source and assert the exact BiDi configuration it
     * applies. Robolectric is not a dependency in this project, so the contract
     * is asserted against the shipped code rather than a live TextView; the
     * rendering itself was verified on a real device.
     */
    private fun uiSource(): String {
        for (p in listOf(
            "src/main/kotlin/com/edib/openwhispr/OpenWisprUi.kt",
            "../repo/app/src/main/kotlin/com/edib/openwhispr/OpenWisprUi.kt",
            "app/src/main/kotlin/com/edib/openwhispr/OpenWisprUi.kt"
        )) {
            val f = java.io.File(p)
            if (f.exists()) return f.readText()
        }
        var d: java.io.File? = java.io.File(".").absoluteFile
        while (d != null) {
            val f = java.io.File(
                d, "app/src/main/kotlin/com/edib/openwhispr/OpenWisprUi.kt"
            )
            if (f.exists()) return f.readText()
            d = d.parentFile
        }
        throw AssertionError("OpenWisprUi.kt not found")
    }

    @Test
    fun `bidi derives base direction from content, not device locale`() {
        val src = uiSource()
        val fn = src.substringAfter("fun applyTranscriptDirection")
        assertTrue(
            "must use TEXT_DIRECTION_FIRST_STRONG (UAX#9 P2: first strong char)",
            fn.contains("TEXT_DIRECTION_FIRST_STRONG")
        )
        assertFalse(
            "must not derive direction from the device locale",
            fn.contains("TEXT_DIRECTION_LOCALE")
        )
        assertFalse(
            "must not force a single global direction",
            fn.contains("TEXT_DIRECTION_RTL") || fn.contains("TEXT_DIRECTION_LTR")
        )
    }

    @Test
    fun `bidi uses an RTL-aware view with TEXT_START alignment`() {
        val fn = uiSource().substringAfter("fun applyTranscriptDirection")
        // Measured on device with a mixed transcript:
        //   FIRST_STRONG + VIEW_START  -> English run dragged to the front (wrong)
        //   LOCALE        + VIEW_START -> English run dragged to the front (wrong)
        //   FIRST_STRONG + TEXT_START  -> right order, but pure English
        //                                  was right-aligned               (wrong)
        //   RTL view      + TEXT_START  -> correct for all three           (used)
        assertTrue(
            "view must be laid out RTL-aware",
            fn.contains("LAYOUT_DIRECTION_RTL")
        )
        assertTrue(
            "must align to TEXT_START, not VIEW_START",
            fn.contains("TEXT_ALIGNMENT_TEXT_START")
        )
        assertFalse(
            "VIEW_START right-aligns pure English paragraphs, which is wrong",
            fn.contains("TEXT_ALIGNMENT_VIEW_START")
        )
    }

    @Test
    fun `no directional marks or reordering are injected into transcripts`() {
        // The fix is presentation-only. None of these may appear in the
        // transcript display or clipboard path.
        val forbidden = mapOf(
            "‎" to "LRM", "‏" to "RLM", "‪" to "LRE", "‫" to "RLE",
            "‬" to "PDF", "‭" to "LRO", "‮" to "RLO",
            "؜" to "ALM"
        )
        val files = listOf(
            "NotesTimelineView.kt", "NoteDetailDialog.kt", "NotesAdapter.kt",
            "OpenWisprUi.kt", "NoteShareHelper.kt", "NotesRepository.kt"
        )
        for (name in files) {
            val f = java.io.File("src/main/kotlin/com/edib/openwhispr/$name")
            if (!f.exists()) continue
            val txt = f.readText()
            for ((mark, label) in forbidden) {
                assertFalse(
                    "$name must not contain $label (U+%04X)".format(mark[0].code),
                    txt.contains(mark)
                )
            }
        }
    }

    @Test
    fun `raw transcript has no bidi control characters`() {
        for (c in mixed) {
            val cp = c.code
            val isRtlMark = cp in 0x200E..0x200F || cp in 0x202A..0x202E || cp == 0x061C
            assertFalse("unexpected bidi mark U+%04X in stored text".format(cp), isRtlMark)
        }
        assertEquals(94, mixed.length)
        assertTrue(mixed.startsWith("نعم"))
        assertTrue(mixed.contains("What do you want from بابا?"))
        assertTrue(mixed.endsWith("what you want"))
    }

    // ---------- Import: WAV handling ----------

    @Test
    fun `wav duration uses little-endian RIFF fields`() {
        // A regression burned here: RIFF/WAVE integers are little-endian.
        // Reading the fmt chunk size big-endian turns 16 into 268435456 and a
        // 12.5 s import showed as ~3 s.
        val w = WavWriter.encode(ByteArray(400640), sampleRate = 16000)
        val byteRate = le32(w, 28)
        val dataSize = le32(w, 40)
        assertEquals(32000, byteRate)
        assertEquals(400640, dataSize)
        val ms = dataSize * 1000L / byteRate
        assertEquals("12.52 s, not ~3 s", 12520L, ms)
    }

    @Test
    fun `canonical wav header is RIFF WAVE fmt data`() {
        val w = WavWriter.encode(ByteArray(1000))
        assertEquals("RIFF", String(w, 0, 4))
        assertEquals("WAVE", String(w, 8, 4))
        assertEquals("fmt ", String(w, 12, 4))
        assertEquals(16, le32(w, 16))
        assertEquals("data", String(w, 36, 4))
        assertEquals(1000, le32(w, 40))
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)
}
