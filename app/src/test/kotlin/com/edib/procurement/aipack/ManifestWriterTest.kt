package com.edib.procurement.aipack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestWriterTest {

    private fun source(
        id: String,
        name: String,
        status: ExtractionStatus,
        entry: String? = null,
        detail: String? = null
    ) = PackSource(
        sourceId = id,
        documentId = 1L,
        filename = name,
        relativePath = "folder/$name",
        mimeType = null,
        sizeBytes = 100,
        linkedGroupCode = null,
        linkedEntryCode = entry,
        linkType = null,
        matchEvidence = null,
        status = status,
        detail = detail,
        checksum = null,
        linkedManually = false
    )

    @Test
    fun `manifest carries scope counts and per document outcomes`() {
        val json = ManifestWriter.build(
            scope = PackScope(
                type = PackScopeType.GROUP,
                name = "Jaz Negma",
                groupIds = listOf(7L)
            ),
            groups = 1,
            entries = 14,
            documents = 2,
            originalTotalBytes = 1000,
            normalizedBytes = 250,
            sources = listOf(
                source("SOURCE-0001", "a.pdf", ExtractionStatus.SUCCESS),
                source("SOURCE-0002", "b.pdf", ExtractionStatus.ERROR, detail = "corrupt"),
                source("SOURCE-0003", "c.pdf", ExtractionStatus.OCR_REQUIRED)
            )
        )

        assertTrue(json.contains("\"formatVersion\": 1"))
        assertTrue(json.contains("\"groups\": 1"))
        assertTrue(json.contains("\"entries\": 14"))
        assertTrue(json.contains("\"documents\": 2"))
        // The three byte counts are distinct quantities and are named so they
        // cannot be read as one another: source files, extracted text, and the
        // generated artifact.
        assertTrue(json.contains("\"originalSourceBytes\": 1000"))
        assertTrue(json.contains("\"normalizedUncompressedBytes\": 250"))
        assertTrue(json.contains("SOURCE-0001"))
        assertTrue(json.contains("\"extractionStatus\": \"SUCCESS\""))
        assertTrue(json.contains("\"extractionStatus\": \"ERROR\""))
        assertTrue(json.contains("\"extractionStatus\": \"OCR_REQUIRED\""))
    }

    @Test
    fun `failures are counted in the manifest`() {
        val json = ManifestWriter.build(
            PackScope(PackScopeType.PROJECT, "P"),
            groups = 1, entries = 1, documents = 3,
            originalTotalBytes = 0, normalizedBytes = 0,
            sources = listOf(
                source("SOURCE-0001", "a.pdf", ExtractionStatus.SUCCESS),
                source("SOURCE-0002", "b.pdf", ExtractionStatus.ERROR),
                source("SOURCE-0003", "c.pdf", ExtractionStatus.UNSUPPORTED)
            )
        )
        assertTrue(json.contains("\"extractionErrors\": 1"))
        assertTrue(json.contains("\"extractionUnsupported\": 1"))
        assertTrue(json.contains("\"extractionOcrRequired\": 0"))
    }

    @Test
    fun `quotes and newlines in filenames are escaped`() {
        val json = ManifestWriter.build(
            PackScope(PackScopeType.DOCUMENTS, "odd\"name"),
            groups = 0, entries = 0, documents = 1,
            originalTotalBytes = 0, normalizedBytes = 0,
            sources = listOf(source("SOURCE-0001", "a\"b\nc.pdf", ExtractionStatus.SUCCESS))
        )
        assertTrue(json.contains("a\\\"b\\nc.pdf"))
        // The document name must not break the JSON structure.
        assertTrue(json.trim().endsWith("}"))
    }
}

class PackTextWriterTest {

    @Test
    fun `blank lines are collapsed in compact mode`() {
        val out = java.io.ByteArrayOutputStream()
        val w = PackTextWriter(out)
        w.line("a")
        w.line("")
        w.line("")
        w.line("")
        w.line("b")
        w.close()
        val text = out.toString("UTF-8")
        assertEquals("a\n\nb\n", text)
    }

    @Test
    fun `size is tracked as bytes are written`() {
        val out = java.io.ByteArrayOutputStream()
        val w = PackTextWriter(out)
        w.line("hello")
        w.close()
        assertTrue(w.size > 0)
        assertEquals(out.size().toLong(), w.size)
    }

    @Test
    fun `rules are written at the requested length`() {
        val out = java.io.ByteArrayOutputStream()
        val w = PackTextWriter(out)
        w.rule('=', 20)
        w.close()
        assertEquals(20, out.toString("UTF-8").trim().length)
    }
}

class PackModelsTest {

    @Test
    fun `reduction is the fraction of original size not carried over`() {
        val r = PackResult(
            fileName = "x.zip", absolutePath = "/x.zip", contentUri = null,
            scopeName = "s", groups = 1, entries = 1, documents = 1,
            originalTotalBytes = 1000, packBytes = 25,
            succeeded = 1, ocrRequired = 0, unsupported = 0, errors = 0, skipped = 0
        )
        assertEquals(0.975, r.reduction, 0.001)
    }

    @Test
    fun `an empty scope has no reduction rather than dividing by zero`() {
        val r = PackResult(
            fileName = "x.zip", absolutePath = "/x.zip", contentUri = null,
            scopeName = "s", groups = 0, entries = 0, documents = 0,
            originalTotalBytes = 0, packBytes = 10,
            succeeded = 0, ocrRequired = 0, unsupported = 0, errors = 0, skipped = 0
        )
        assertEquals(0.0, r.reduction, 0.0)
    }
}
