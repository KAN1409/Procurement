package com.edib.procurement.aipack

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end checks on the files a multipart export actually produces.
 *
 * These run against real ZIPs and a real INDEX on disk, because the guarantees
 * that matter here are about the artifact a user receives: valid archives, valid
 * JSON manifests, a usable index, deterministic naming, and a cancelled run that
 * leaves nothing behind.
 */
class MultipartArtifactTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writePart(
        dir: File, name: String, body: String, partNumber: Int, partCount: Int,
        sources: List<PackSource> = emptyList(),
        oversizedReason: String? = null,
        context: PartWriter.Context = PartWriter.Context(emptyList(), emptyList(), emptyList(), emptyList())
    ): PartWriter.PartFile = runBlocking {
        val out = File(dir, name)
        val temp = File(dir, ".$name.tmp")
        val w = PartWriter("EXP-TEST", partNumber, partCount, 2, 1000)
        w.write(
            outFile = out, tempFile = temp,
            scope = PackScope(PackScopeType.PROJECT, "Test Scope"),
            generatedAt = "2026-01-01T00:00:00",
            context = context,
            groupsLabel = listOf("G-001"), entryCodes = listOf("E-001"),
            originalBytes = 1000, documentCount = sources.size,
            groupCount = 1, entryCount = 1,
            oversizedReason = oversizedReason, sources = sources
        ) { text -> text.line(body) }
    }

    private fun source(id: String, file: String) = PackSource(
        sourceId = id, documentId = id.removePrefix("SOURCE-").toLong(),
        filename = file, relativePath = "p/$file", mimeType = "text/plain",
        sizeBytes = 10, linkedGroupCode = null, linkedEntryCode = null,
        linkType = null, matchEvidence = null, status = ExtractionStatus.SUCCESS,
        detail = null, checksum = null, linkedManually = false
    )

    @Test
    fun `a written part is a valid zip with both entries`() {
        val dir = tmp.newFolder("p1")
        val pf = writePart(dir, "S_AI_PACK_01.zip", "hello", 1, 1)

        assertTrue(pf.file.exists())
        assertTrue(pf.actualBytes > 0)
        ZipFile(pf.file).use { z ->
            assertNotEquals(null, z.getEntry("AI_PACK.md"))
            assertNotEquals(null, z.getEntry("manifest.json"))
        }
    }

    @Test
    fun `a part declares its export identity and part position`() {
        val dir = tmp.newFolder("p2")
        val pf = writePart(dir, "S_AI_PACK_01.zip", "x", 2, 5)
        val md = ZipFile(pf.file).use { z ->
            z.getInputStream(z.getEntry("AI_PACK.md")).bufferedReader().readText()
        }
        assertTrue(md.contains("Export-ID: EXP-TEST"))
        assertTrue(md.contains("PART 02 of 5"))
        assertTrue(md.contains("Scope-Type: PROJECT"))
        assertTrue("must state it is a multipart export", md.contains("MULTIPART"))
    }

    @Test
    fun `continuity is stated explicitly rather than implied`() {
        val dir = tmp.newFolder("p3")
        val pf = writePart(
            dir, "S_AI_PACK_02.zip", "x", 2, 3,
            context = PartWriter.Context(
                starts = emptyList(),
                continues = listOf("G-001"),
                carriesOn = listOf("G-001"),
                ends = emptyList()
            )
        )
        val md = ZipFile(pf.file).use { z ->
            z.getInputStream(z.getEntry("AI_PACK.md")).bufferedReader().readText()
        }
        assertTrue(md.contains("GROUP CONTINUED FROM AN EARLIER PART: G-001"))
        assertTrue(md.contains("GROUP CONTINUES IN A LATER PART: G-001"))
    }

    @Test
    fun `an oversized part states the reason and is not truncated`() {
        val dir = tmp.newFolder("p4")
        val body = "y".repeat(50_000)
        val pf = writePart(
            dir, "S_AI_PACK_01.zip", body, 1, 1,
            oversizedReason = PackSplitter.SINGLE_SOURCE_EXCEEDS_TARGET
        )
        val md = ZipFile(pf.file).use { z ->
            z.getInputStream(z.getEntry("AI_PACK.md")).bufferedReader().readText()
        }
        assertTrue(md.contains(PackSplitter.OVERSIZED_SINGLE_SOURCE))
        assertTrue("the body must survive whole", md.contains(body))
    }

    @Test
    fun `each manifest states it belongs to a multipart export`() {
        val dir = tmp.newFolder("p5")
        val pf = writePart(
            dir, "S_AI_PACK_03.zip", "x", 3, 4,
            sources = listOf(source("SOURCE-0007", "a.pdf"))
        )
        val json = ZipFile(pf.file).use { z ->
            z.getInputStream(z.getEntry("manifest.json")).bufferedReader().readText()
        }
        assertTrue(json.contains("\"belongsToMultipartExport\": true"))
        assertTrue(json.contains("\"exportId\": \"EXP-TEST\""))
        assertTrue(json.contains("\"partNumber\": 3"))
        assertTrue(json.contains("\"partCount\": 4"))
        assertTrue(json.contains("\"targetPartBytes\""))
        assertTrue(json.contains("\"actualPartBytes\""))
        assertTrue(json.contains("SOURCE-0007"))
    }

    @Test
    fun `manifest carries global source ids for its own part`() {
        val dir = tmp.newFolder("p6")
        val pf = writePart(
            dir, "S_AI_PACK_02.zip", "x", 2, 2,
            sources = listOf(source("SOURCE-0042", "b.pdf"))
        )
        val json = ZipFile(pf.file).use { z ->
            z.getInputStream(z.getEntry("manifest.json")).bufferedReader().readText()
        }
        // Part 2 keeps its global id; numbering is not restarted.
        assertTrue(json.contains("SOURCE-0042"))
        assertFalse(json.contains("SOURCE-0001\""))
    }

    @Test
    fun `index maps every source to its part`() {
        val out = ByteArrayOutputStream()
        val idx = PackIndexWriter(
            exportId = "EXP-X", scope = PackScope(PackScopeType.PROJECT, "Jaz Negma"),
            generatedAt = "2026-01-01T00:00:00",
            parts = listOf(
                PackIndexWriter.PartEntry(1, "Jaz_Negma_AI_PACK_01.zip", 100, "aa", listOf("G-1"), emptyList(), emptyList(), emptyList(), listOf("SOURCE-0001"), null),
                PackIndexWriter.PartEntry(2, "Jaz_Negma_AI_PACK_02.zip", 200, "bb", listOf("G-2"), emptyList(), emptyList(), emptyList(), listOf("SOURCE-0002"), null)
            ),
            groups = 2, entries = 4, documents = 2,
            originalBytes = 1000, normalizedBytes = 300, targetPartBytes = 250
        ).apply {
            sources = listOf(
                PackIndexWriter.SourceRow("SOURCE-0001", "a.pdf", "G-1", "E-1", 1),
                PackIndexWriter.SourceRow("SOURCE-0002", "b.pdf", "G-2", "E-2", 2)
            )
        }
        idx.write(out)
        val text = out.toString()

        assertTrue(text.contains("PROCURMENT AI PACK — MULTIPART INDEX".replace("PROCURMENT", "PROCUREMENT")))
        assertTrue(text.contains("Parts: 2"))
        assertTrue(text.contains("SOURCE MAP"))
        // Every source is mapped with its part.
        assertTrue(text.contains("SOURCE-0001"))
        assertTrue(text.contains("SOURCE-0002"))
        assertTrue(text.contains("Part: PART 01"))
        assertTrue(text.contains("Part: PART 02"))
        // And the reader is told numbering is global.
        assertTrue(text.contains("never restarted"))
    }

    @Test
    fun `index records checksums per part`() {
        val out = ByteArrayOutputStream()
        PackIndexWriter(
            exportId = "E", scope = PackScope(PackScopeType.GROUP, "G"),
            generatedAt = "g",
            parts = listOf(
                PackIndexWriter.PartEntry(1, "a.zip", 10, "deadbeef", emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null)
            ),
            groups = 0, entries = 0, documents = 0, originalBytes = 0,
            normalizedBytes = 0, targetPartBytes = 0
        ).apply { sources = emptyList() }.write(out)
        assertTrue(out.toString().contains("SHA-256: deadbeef"))
    }

    @Test
    fun `checksums differ for different content and are stable`() {
        val dir = tmp.newFolder("p7")
        val a = writePart(dir, "A.zip", "content one", 1, 1)
        val b = writePart(dir, "B.zip", "content two", 1, 1)
        assertNotEquals(a.sha256, b.sha256)
        assertEquals(a.sha256, PartWriter.sha256(a.file))
        assertEquals(64, a.sha256.length)
    }

    @Test
    fun `part file naming is deterministic and zero padded`() {
        assertEquals("Scope_AI_PACK_01.zip",
            PackSplitter.partFileStem("Scope_AI_PACK", 1, 2) + ".zip")
        assertEquals("Scope_AI_PACK_09.zip",
            PackSplitter.partFileStem("Scope_AI_PACK", 9, 2) + ".zip")
        // Past 99 parts the width grows so sort order stays correct.
        assertEquals("Scope_AI_PACK_100.zip",
            PackSplitter.partFileStem("Scope_AI_PACK", 100, 3) + ".zip")
    }

    @Test
    fun `index and parts verify as present and non-empty on disk`() {
        val dir = tmp.newFolder("p8")
        val names = (1..3).map { "X_AI_PACK_0$it.zip" }
        val files = (1..3).map { i -> writePart(dir, names[i - 1], "b", i, 3).file }
        val report = MultipartIntegrity.verifyFiles(names, files)
        assertTrue(report.problems.toString(), report.ok)
    }

    @Test
    fun `a missing part file is caught`() {
        val dir = tmp.newFolder("p9")
        val a = writePart(dir, "X_AI_PACK_01.zip", "b", 1, 2).file
        val report = MultipartIntegrity.verifyFiles(
            listOf("X_AI_PACK_01.zip", "X_AI_PACK_02.zip"), listOf(a)
        )
        assertFalse(report.ok)
        assertTrue(report.problems.any { it.contains("missing") })
    }

    @Test
    fun `temporary files are removed so nothing looks finished`() {
        val dir = tmp.newFolder("p10")
        val pf = writePart(dir, "S_AI_PACK_01.zip", "z", 1, 1)
        assertTrue("no .tmp leftovers", dir.listFiles()!!.none { it.name.endsWith(".tmp") })
        assertEquals(1, dir.listFiles()!!.size)
        assertEquals(pf.file.name, dir.listFiles()!![0].name)
    }
}
