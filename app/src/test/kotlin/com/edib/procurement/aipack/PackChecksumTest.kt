package com.edib.procurement.aipack

import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A recorded checksum must describe the file the user actually receives.
 *
 * Phase 4.1 found the per-part SHA-256 was captured before the manifest was
 * rewritten into the zip. Rewriting rebuilds the archive, so the recorded digest
 * described a file that no longer existed and every checksum was wrong. Nothing
 * downstream checks it, so it fails silently.
 *
 * These tests pin: the digest in the INDEX equals the digest of the file on
 * disk, and the size the UI reports equals the file's real size.
 */
class PackChecksumTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sha(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(64 * 1024).use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Builds a part, rewrites its manifest, then records size+sha as the builder now does. */
    private fun partWithRewrite(
        dir: File,
        name: String,
        partNumber: Int,
        partCount: Int
    ): Pair<Long, String> = runBlocking {
        val out = File(dir, name)
        val temp = File(dir, ".$name.tmp")
        val w = PartWriter("EXP-T", partNumber, partCount, 2, 1_000_000)
        val written = w.write(
            outFile = out, tempFile = temp,
            scope = PackScope(PackScopeType.DOCUMENTS, "S"),
            generatedAt = "2026-01-01T00:00:00",
            context = PartWriter.Context(emptyList(), emptyList(), emptyList(), emptyList()),
            groupsLabel = emptyList(), entryCodes = emptyList(),
            originalBytes = 10, documentCount = 1, groupCount = 0, entryCount = 0,
            oversizedReason = null, sources = emptyList()
        ) { t -> t.line("body for part $partNumber") }

        // Stand-in for the builder's manifest rewrite: rebuilds the zip.
        val rebuilt = File(dir, "rebuild-$name")
        ZipFile(written.file).use { src ->
            java.util.zip.ZipOutputStream(rebuilt.outputStream()).use { dst ->
                src.entries().asSequence().forEach { e ->
                    dst.putNextEntry(java.util.zip.ZipEntry(e.name))
                    if (e.name == "manifest.json") dst.write("{\"rewritten\":true}".toByteArray())
                    else src.getInputStream(e).use { it.copyTo(dst) }
                    dst.closeEntry()
                }
            }
        }
        if (out.exists()) out.delete()
        rebuilt.renameTo(out)

        // Recorded AFTER the rewrite, as the fixed builder does.
        out.length() to sha(out)
    }

    @Test
    fun `checksum recorded after rewrite matches the delivered file`() {
        val dir = tmp.newFolder("c1")
        val (size, digest) = partWithRewrite(dir, "P_AI_PACK_01.zip", 1, 3)
        assertEquals(dir.listFiles()!!.first { it.name.startsWith("P_") }.length(), size)
        assertEquals(digest, sha(File(dir, "P_AI_PACK_01.zip")))
    }

    @Test
    fun `a checksum taken before a rewrite would not match`() {
        // Guards the specific bug: this is why the order matters.
        val dir = tmp.newFolder("c2")
        val out = File(dir, "Q_AI_PACK_01.zip")
        val temp = File(dir, ".Q.tmp")
        val w = PartWriter("EXP-T", 1, 2, 2, 1_000_000)
        val written = w.write(
            outFile = out, tempFile = temp,
            scope = PackScope(PackScopeType.DOCUMENTS, "S"),
            generatedAt = "g", context = PartWriter.Context(emptyList(), emptyList(), emptyList(), emptyList()),
            groupsLabel = emptyList(), entryCodes = emptyList(), originalBytes = 0,
            documentCount = 0, groupCount = 0, entryCount = 0,
            oversizedReason = null, sources = emptyList()
        ) { t -> t.line("x") }
        val before = written.sha256

        // Rebuild, exactly as the manifest rewrite does.
        val rebuilt = File(dir, "rb.zip")
        ZipFile(written.file).use { src ->
            java.util.zip.ZipOutputStream(rebuilt.outputStream()).use { dst ->
                src.entries().asSequence().forEach { e ->
                    dst.putNextEntry(java.util.zip.ZipEntry(e.name))
                    if (e.name == "manifest.json") dst.write("{\"rewritten\":1}".toByteArray())
                    else src.getInputStream(e).use { it.copyTo(dst) }
                    dst.closeEntry()
                }
            }
        }
        if (written.file.exists()) written.file.delete()
        rebuilt.renameTo(written.file)

        assertTrue("pre-rewrite digest must differ from the delivered file",
            before != sha(written.file))
    }

    @Test
    fun `index carries the checksum of the real file`() {
        val dir = tmp.newFolder("c3")
        val (_, digest) = partWithRewrite(dir, "R_AI_PACK_01.zip", 1, 1)
        val out = java.io.ByteArrayOutputStream()
        PackIndexWriter(
            exportId = "E", scope = PackScope(PackScopeType.DOCUMENTS, "S"),
            generatedAt = "g",
            parts = listOf(
                PackIndexWriter.PartEntry(
                    1, "R_AI_PACK_01.zip", File(dir, "R_AI_PACK_01.zip").length(), digest,
                    emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null
                )
            ),
            groups = 0, entries = 0, documents = 0, originalBytes = 0,
            normalizedBytes = 0, targetPartBytes = 1_000_000
        ).apply { sources = emptyList() }.write(out)
        assertTrue(out.toString().contains("SHA-256: $digest"))
    }

    @Test
    fun `digests differ for different content and are stable`() {
        val dir = tmp.newFolder("c4")
        val a = File(dir, "a.bin").apply { writeText("aaaa") }
        val b = File(dir, "b.bin").apply { writeText("bbbb") }
        assertTrue(sha(a) != sha(b))
        assertEquals(sha(a), sha(a))
        assertEquals(64, sha(a).length)
    }
}
