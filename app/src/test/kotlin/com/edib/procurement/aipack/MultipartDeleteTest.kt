package com.edib.procurement.aipack

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Deleting a multipart export must remove the WHOLE set.
 *
 * A history row for a split export records only the index path. Deleting just
 * that file would leave every part beside it on disk with nothing pointing at
 * them: invisible garbage that still costs the user storage, and that a later
 * export could collide with.
 */
class MultipartDeleteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * Mirrors the repository's rule: a file belongs to this export when its name
     * is the export stem followed by "_" and a zero-padded part number.
     */
    private val partSuffix = Regex("_\\d{2,3}\\.(zip|md)$")

    private fun deleteExport(dir: File, indexName: String): Int {
        val index = File(dir, indexName)
        var removed = 0
        if (index.delete()) removed++
        val stem = index.name.removeSuffix("_INDEX.md")
        dir.listFiles()?.forEach { f ->
            if (f.name == index.name) return@forEach
            val suffix = partSuffix.find(f.name)?.value ?: return@forEach
            if (f.name == stem + suffix && f.delete()) removed++
        }
        return removed
    }

    private fun exportFiles(dir: File, stem: String) {
        listOf("${stem}_01.zip", "${stem}_02.zip", "${stem}_03.zip", "${stem}_INDEX.md")
            .forEach { File(dir, it).writeText("x") }
    }

    @Test
    fun `deleting a multipart export removes the index and every part`() {
        val dir = tmp.newFolder("d1")
        val stem = "Scope_AI_PACK_2026-10-02-DOCUMENTS-000000"
        exportFiles(dir, stem)

        val removed = deleteExport(dir, "${stem}_INDEX.md")
        assertEquals("index + 3 parts", 4, removed)
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun `deleting one export leaves a different export untouched`() {
        val dir = tmp.newFolder("d2")
        exportFiles(dir, "A_AI_PACK_2026-10-02-DOCUMENTS-000000")
        exportFiles(dir, "B_AI_PACK_2026-10-02-DOCUMENTS-111111")

        deleteExport(dir, "A_AI_PACK_2026-10-02-DOCUMENTS-000000_INDEX.md")
        assertEquals(4, dir.listFiles()!!.size)
        assertTrue(File(dir, "B_AI_PACK_2026-10-02-DOCUMENTS-111111_01.zip").exists())
        assertFalse(File(dir, "A_AI_PACK_2026-10-02-DOCUMENTS-000000_01.zip").exists())
    }

    @Test
    fun `deleting an export with an unusual part count still removes all of them`() {
        val dir = tmp.newFolder("d3")
        val stem = "C_AI_PACK_2026-10-02-GROUP-222222"
        listOf("01", "02", "03", "04", "05", "06", "07", "08", "09", "10")
            .forEach { File(dir, "${stem}_$it.zip").writeText("x") }
        File(dir, "${stem}_INDEX.md").writeText("x")

        val removed = deleteExport(dir, "${stem}_INDEX.md")
        assertEquals(11, removed)
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun `deleting a hundred-part export removes every part`() {
        val dir = tmp.newFolder("d4")
        val stem = "D_AI_PACK_2026-10-02-GROUP-333333"
        (1..120).forEach { File(dir, "${stem}_%03d.zip".format(it)).writeText("x") }
        File(dir, "${stem}_INDEX.md").writeText("x")

        val removed = deleteExport(dir, "${stem}_INDEX.md")
        assertEquals(121, removed)
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun `a phase 3 single pack is not swept up by an export delete`() {
        val dir = tmp.newFolder("d5")
        val stem = "E_AI_PACK_2026-10-02-DOCUMENTS-444444"
        exportFiles(dir, stem)
        // A phase 3 single pack: no _01 suffix.
        File(dir, "Other_AI_PACK_2026-10-02-GROUP-555555.zip").writeText("x")

        deleteExport(dir, "${stem}_INDEX.md")
        assertEquals(1, dir.listFiles()!!.size)
        assertTrue(File(dir, "Other_AI_PACK_2026-10-02-GROUP-555555.zip").exists())
    }

    @Test
    fun `an export stem is not a prefix of another export`() {
        val dir = tmp.newFolder("d6")
        exportFiles(dir, "X_AI_PACK_2026-10-02-DOCUMENTS-1")
        exportFiles(dir, "X_AI_PACK_2026-10-02-DOCUMENTS-12")
        // The second stem starts with the first, but they are different exports.
        deleteExport(dir, "X_AI_PACK_2026-10-02-DOCUMENTS-1_INDEX.md")
        assertEquals("the longer export must survive", 4, dir.listFiles()!!.size)
    }
}
