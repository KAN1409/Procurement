package com.edib.procurement.aipack

import com.edib.procurement.database.AiPackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * Generated file names.
 *
 * A scope name is whatever the user calls a project, so it can be Arabic, very
 * long, or full of path separators. The file name has to survive that; the
 * human-readable name lives in the manifest either way.
 */
class PackNamingTest {

    private val date = Date(1_767_225_600_000L) // fixed, so the test is stable

    @Test
    fun `an ascii scope keeps its shape`() {
        val stem = PackNaming.fileStem("MMH2-GR-AR-14-25", PackScopeType.GROUP, date)
        assertTrue(stem, stem.startsWith("MMH2-GR-AR-14-25_AI_PACK_"))
        assertTrue(stem, stem.length <= 80)
    }

    @Test
    fun `a project name with a space becomes underscore separated`() {
        val stem = PackNaming.fileStem("Jaz Negma", PackScopeType.PROJECT, date)
        assertFalse("no spaces in a file name", stem.contains(' '))
        assertTrue(stem, stem.startsWith("Jaz_Negma_AI_PACK_"))
    }

    @Test
    fun `path separators and illegal characters are folded away`() {
        listOf(
            "Area/Marsa Alam/H2",
            "Area\\Marsa Alam\\H2",
            "a:b*c?d\"e<f>g|h",
            "  spaced  out  "
        ).forEach { raw ->
            val stem = PackNaming.fileStem(raw, PackScopeType.GROUP, date)
            val illegal = Regex("[/\\\\:*?\"<>|]")
            assertFalse("'$raw' -> $stem keeps a separator", illegal.containsMatchIn(stem))
        }
    }

    @Test
    fun `raw arabic is replaced rather than written raw`() {
        val stem = PackNaming.fileStem("الالفى للموبيليا", PackScopeType.CONTRACTOR, date)
        // The stem must stay filesystem-safe ASCII; the Arabic survives in the
        // manifest and in the history row.
        assertTrue(stem, stem.all { it.isLetterOrDigit() || it in "_-." })
        assertTrue(stem, stem.contains("_AI_PACK_"))
    }

    @Test
    fun `a very long scope is capped`() {
        val stem = PackNaming.fileStem("x".repeat(500), PackScopeType.PROJECT, date)
        assertTrue("length was ${stem.length}", stem.length <= 80)
    }

    @Test
    fun `an empty or punctuation-only scope still yields a usable stem`() {
        assertTrue(PackNaming.fileStem("", PackScopeType.GROUP, date).isNotBlank())
        assertTrue(PackNaming.fileStem("   ", PackScopeType.GROUP, date).isNotBlank())
        assertTrue(PackNaming.fileStem("...", PackScopeType.GROUP, date).isNotBlank())
        assertTrue(PackNaming.fileStem("/", PackScopeType.GROUP, date).isNotBlank())
    }

    @Test
    fun `a reserved device name is escaped`() {
        // "con" would be a device on Windows.
        assertEquals("con_", PackNaming.sanitise("con"))
        assertEquals("nul_", PackNaming.sanitise("nul"))
    }

    @Test
    fun `each scope type is distinguishable in the name`() {
        val kinds = PackScopeType.entries.map {
            PackNaming.fileStem("Scope", it, date)
        }
        assertEquals("one distinct name per scope type", kinds.size, kinds.distinct().size)
    }
}

/**
 * Manifest stability.
 *
 * Format-Version 1 is frozen: a later build must still be able to read what
 * this version wrote, so the field names are asserted explicitly rather than
 * assumed.
 */
class ManifestCompatibilityTest {

    private fun sources() = listOf(
        PackSource(
            sourceId = "SOURCE-0001",
            documentId = 1L,
            filename = "a.pdf",
            relativePath = "d/a.pdf",
            mimeType = "application/pdf",
            sizeBytes = 10,
            linkedGroupCode = null,
            linkedEntryCode = "CODE-1",
            linkType = "ORDER",
            matchEvidence = "exact",
            status = ExtractionStatus.SUCCESS,
            detail = null,
            checksum = null,
            linkedManually = false
        )
    )

    @Test
    fun `v1 field names are present and unchanged`() {
        val json = ManifestWriter.build(
            scope = PackScope(PackScopeType.GROUP, "S", groupIds = listOf(1L)),
            groups = 1, entries = 2, documents = 1,
            originalTotalBytes = 100, normalizedBytes = 20,
            sources = sources()
        )
        listOf(
            "\"formatVersion\"", "\"generatedAt\"", "\"scope\"", "\"counts\"",
            "\"originalTotalBytes\"", "\"normalizedBytes\"", "\"documents\"",
            "\"sourceId\"", "\"filename\"", "\"relativePath\"", "\"mimeType\"",
            "\"sizeBytes\"", "\"linkType\"", "\"linkedManually\"",
            "\"matchEvidence\"", "\"extractionStatus\"", "\"detail\"",
            "\"checksumSha256\""
        ).forEach { key ->
            assertTrue("manifest must keep $key", json.contains(key))
        }
    }

    @Test
    fun `the format version is frozen at one`() {
        val json = ManifestWriter.build(
            PackScope(PackScopeType.GROUP, "S"), 0, 0, 0, 0, 0, emptyList()
        )
        assertTrue(json.contains("\"formatVersion\": 1"))
    }

    @Test
    fun `a v1 manifest stays parseable by a strict reader`() {
        val json = ManifestWriter.build(
            PackScope(PackScopeType.PROJECT, "Jaz Negma", groupIds = listOf(7L)),
            groups = 3, entries = 14, documents = 2,
            originalTotalBytes = 6037, normalizedBytes = 2875,
            sources = sources()
        )
        val root = org.json.JSONObject(json)
        assertEquals(1, root.getInt("formatVersion"))
        assertEquals(3, root.getJSONObject("counts").getInt("groups"))
        assertEquals(14, root.getJSONObject("counts").getInt("entries"))
        assertEquals(2875, root.getLong("normalizedBytes"))
        val docs = root.getJSONArray("documents")
        assertEquals(1, docs.length())
        val first = docs.getJSONObject(0)
        assertEquals("SOURCE-0001", first.getString("sourceId"))
        assertEquals("SUCCESS", first.getString("extractionStatus"))
    }

    @Test
    fun `an unknown future status still round-trips as a string`() {
        val json = ManifestWriter.build(
            PackScope(PackScopeType.GROUP, "S"), 0, 0, 1, 1, 1,
            listOf(
                sources().first().copy(
                    status = ExtractionStatus.PARTIAL,
                    detail = "partial read"
                )
            )
        )
        val first = org.json.JSONObject(json).getJSONArray("documents").getJSONObject(0)
        assertEquals("PARTIAL", first.getString("extractionStatus"))
        assertEquals("partial read", first.getString("detail"))
    }
}
