package com.edib.procurement.aipack

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * An export that was killed mid-flight must not look finished.
 *
 * The danger is a directory holding part 1 and part 2 with no index and no
 * history row: files a user could reasonably believe are a usable export. These
 * tests pin both what is removed and, just as importantly, what is preserved.
 */
class StaleMultipartCleanupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(dir: File, name: String) = File(dir, name).apply { writeText("x") }

    @Test
    fun `temporary part files are always removed`() {
        val dir = tmp.newFolder("c1")
        write(dir, ".Scope_AI_PACK_2026-10-02-DOCUMENTS-000000_01.zip.20260101-000000.part")
        write(dir, "Scope_AI_PACK_2026-10-02-DOCUMENTS-000000_01.zip")   // recorded, so kept

        val r = StaleMultipartCleanup(dir, setOf("Scope_AI_PACK_2026-10-02-DOCUMENTS-000000_01.zip")).run()
        assertEquals(1, r.removedTemp.size)
        assertTrue(r.removedTemp[0].endsWith(".part"))
        assertTrue(File(dir, "Scope_AI_PACK_2026-10-02-DOCUMENTS-000000_01.zip").exists())
    }

    @Test
    fun `a half-rewritten manifest is removed`() {
        val dir = tmp.newFolder("c2")
        write(dir, ".manifest-Scope_AI_PACK_2026-10-02-DOCUMENTS-000000_01.zip.rewrite")
        val r = StaleMultipartCleanup(dir, emptySet()).run()
        assertEquals(1, r.removedTemp.size)
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun `parts with no history row are removed as an unfinished export`() {
        val dir = tmp.newFolder("c3")
        write(dir, "Scope_AI_PACK_2026-10-02-DOCUMENTS-000000_01.zip")
        write(dir, "Scope_AI_PACK_2026-10-02-DOCUMENTS-000000_02.zip")
        write(dir, "Scope_AI_PACK_2026-10-02-DOCUMENTS-000000_INDEX.md")

        val r = StaleMultipartCleanup(dir, emptySet()).run()
        assertEquals(2, r.removedOrphanParts.size)
        assertEquals(1, r.removedOrphanIndex.size)
        assertTrue(r.removedAny)
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun `a completed export is never removed`() {
        val dir = tmp.newFolder("c4")
        write(dir, "Scope_AI_PACK_2026-10-02-DOCUMENTS-000000_01.zip")
        write(dir, "Scope_AI_PACK_2026-10-02-DOCUMENTS-000000_02.zip")
        write(dir, "Scope_AI_PACK_2026-10-02-DOCUMENTS-000000_INDEX.md")

        // The index has a history row, so the export counts as complete.
        val r = StaleMultipartCleanup(dir, setOf("Scope_AI_PACK_2026-10-02-DOCUMENTS-000000_INDEX.md")).run()
        assertFalse(r.removedAny)
        assertEquals(3, dir.listFiles()!!.size)
    }

    @Test
    fun `unrelated files are left alone`() {
        val dir = tmp.newFolder("c5")
        write(dir, "some_other_pack.zip")
        write(dir, "notes.txt")
        val r = StaleMultipartCleanup(dir, emptySet()).run()
        assertFalse(r.removedAny)
        assertEquals(2, dir.listFiles()!!.size)
    }

    @Test
    fun `two exports are handled independently`() {
        val dir = tmp.newFolder("c6")
        write(dir, "Done_AI_PACK_2026-10-02-DOCUMENTS-111111_01.zip")
        write(dir, "Done_AI_PACK_2026-10-02-DOCUMENTS-111111_INDEX.md")
        write(dir, "Half_AI_PACK_2026-10-02-DOCUMENTS-222222_01.zip")
        write(dir, "Half_AI_PACK_2026-10-02-DOCUMENTS-222222_02.zip")

        val r = StaleMultipartCleanup(dir, setOf("Done_AI_PACK_2026-10-02-DOCUMENTS-111111_INDEX.md")).run()
        assertEquals(2, r.removedOrphanParts.size)
        assertTrue(File(dir, "Done_AI_PACK_2026-10-02-DOCUMENTS-111111_01.zip").exists())
        assertTrue(File(dir, "Done_AI_PACK_2026-10-02-DOCUMENTS-111111_INDEX.md").exists())
        assertFalse(File(dir, "Half_AI_PACK_2026-10-02-DOCUMENTS-222222_01.zip").exists())
    }

    @Test
    fun `phase 3 single packs are not mistaken for parts`() {
        val dir = tmp.newFolder("c7")
        // A single pack from phase 3 has no _AI_PACK_01 suffix.
        write(dir, "Scope_AI_PACK_2026-10-02-GROUP-123456.zip")
        val r = StaleMultipartCleanup(dir, emptySet()).run()
        assertFalse("a single pack must not be deleted", r.removedAny)
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test
    fun `part naming regex matches real part files`() {
        val rx = StaleMultipartCleanup.partFileRegex
        // The shape the writer actually produces: a dated stem plus the number.
        assertTrue(rx.matches("Scope_AI_PACK_2026-10-02-DOCUMENTS-221619_01.zip"))
        assertTrue(rx.matches("Scope_AI_PACK_2026-10-02-DOCUMENTS-221619_100.zip"))
        assertTrue(rx.matches("Jaz_Negma_AI_PACK_01.zip"))
        assertFalse(rx.matches("Jaz_Negma_AI_PACK_INDEX.md"))
        assertFalse(rx.matches("Scope_AI_PACK_2026-10-02-GROUP-123456.zip"))
    }

    @Test
    fun `a generated export's parts and index share one stem`() {
        // This is the exact naming the device produced.
        val index = "Documents_matching_split_doc_40_AI_PACK_2026-10-02-DOCUMENTS-221619_INDEX.md"
        val part1 = "Documents_matching_split_doc_40_AI_PACK_2026-10-02-DOCUMENTS-221619_01.zip"
        assertEquals(
            StaleMultipartCleanup.stemOf(index),
            StaleMultipartCleanup.stemOf(part1)
        )
    }

    @Test
    fun `a missing directory is not an error`() {
        val r = StaleMultipartCleanup(File(tmp.root, "nope"), emptySet()).run()
        assertFalse(r.removedAny)
    }
}
