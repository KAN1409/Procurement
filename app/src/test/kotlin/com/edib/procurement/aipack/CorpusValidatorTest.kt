package com.edib.procurement.aipack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The corpus validation report.
 *
 * Its whole purpose is to be honest about a corpus that has NOT been proven on
 * real business files yet. These tests pin that honesty: counts come from the
 * given data, image-only PDFs are reported rather than "fixed", and the verdict
 * never overstates what was checked.
 */
class CorpusValidatorTest {

    private val v = CorpusValidator(java.io.File("."))

    private fun doc(
        id: Long, name: String, ext: String, size: Long = 1000, links: Int = 0
    ) = CorpusValidator.CorpusDocument(id, name, ext, size, links, "uri:$id")

    private val allText: (CorpusValidator.CorpusDocument) -> CorpusValidator.Outcome =
        { CorpusValidator.Outcome.TEXT }

    @Test
    fun `counts documents and bytes across kinds`() {
        val docs = listOf(
            doc(1, "a.pdf", "pdf", 500),
            doc(2, "b.pdf", "pdf", 700),
            doc(3, "c.xlsx", "xlsx", 9000)
        )
        val r = v.report(docs, allText)
        assertEquals(3, r.totalDocuments)
        assertEquals(10200, r.totalBytes)
        assertEquals(2, r.byKind.first { it.extension == "pdf" }.documents)
        assertEquals("largest kind first", "xlsx", r.byKind.first().extension)
    }

    @Test
    fun `separates linked from unlinked`() {
        val docs = listOf(
            doc(1, "a.pdf", "pdf", links = 2),
            doc(2, "b.pdf", "pdf", links = 0)
        )
        val r = v.report(docs, allText)
        assertEquals(1, r.linkedDocuments)
        assertEquals(1, r.unlinkedDocuments)
    }

    @Test
    fun `image-only pdfs are counted as OCR-required, not as text`() {
        val docs = (1..4).map { doc(it.toLong(), "f$it.pdf", "pdf") }
        val r = v.report(docs, probe = { d ->
            if (d.id <= 3L) CorpusValidator.Outcome.NO_TEXT else CorpusValidator.Outcome.TEXT
        })
        assertEquals(1, r.pdfTextReadable)
        assertEquals(3, r.pdfOcrRequired)
        assertEquals(0, r.pdfFailed)
    }

    @Test
    fun `the verdict says OCR has not been run`() {
        val docs = listOf(doc(1, "a.pdf", "pdf"))
        val r = v.report(docs, probe = { _ -> CorpusValidator.Outcome.NO_TEXT })
        assertTrue(r.verdict.contains("OCR has not been run"))
    }

    @Test
    fun `a clean corpus reports plainly`() {
        val docs = (1..3).map { doc(it.toLong(), "f$it.pdf", "pdf") }
        val r = v.report(docs, allText)
        assertEquals("All probed documents yielded text.", r.verdict)
    }

    @Test
    fun `docx and xlsx failures are counted separately`() {
        val docs = listOf(
            doc(1, "a.docx", "docx"),
            doc(2, "b.docx", "docx"),
            doc(3, "c.xlsx", "xlsx")
        )
        val r = v.report(docs, probe = { d ->
            if (d.extension == "docx" && d.id == 2L) CorpusValidator.Outcome.FAILED
            else if (d.extension == "docx") CorpusValidator.Outcome.TEXT
            else CorpusValidator.Outcome.FAILED
        })
        assertEquals(1, r.docxReadable)
        assertEquals(1, r.docxFailed)
        assertEquals(0, r.xlsxReadable)
        assertEquals(1, r.xlsxFailed)
    }

    @Test
    fun `an empty corpus says so rather than reporting success`() {
        val r = v.report(emptyList(), allText)
        assertEquals(0, r.totalDocuments)
        assertEquals("No documents indexed.", r.verdict)
    }

    @Test
    fun `a large corpus is sampled and says so`() {
        val docs = (1..500).map { doc(it.toLong(), "f$it.pdf", "pdf") }
        val r = v.report(docs, allText, sampleLimit = 50)
        assertTrue("the sample bound must be reported", r.sampled)
        assertEquals(500, r.totalDocuments)
        // Only the sample was probed.
        assertEquals(50, r.pdfTextReadable)
    }

    @Test
    fun `a fully probed corpus is not marked sampled`() {
        val docs = (1..5).map { doc(it.toLong(), "f$it.pdf", "pdf") }
        val r = v.report(docs, allText, sampleLimit = 50)
        assertTrue(!r.sampled)
    }

    @Test
    fun `unknown kinds are counted but not probed`() {
        val docs = listOf(doc(1, "a.bin", "bin"))
        val r = v.report(docs, probe = { CorpusValidator.Outcome.FAILED })
        assertEquals(1, r.totalDocuments)
        assertEquals(0, r.pdfFailed + r.docxFailed + r.xlsxFailed)
    }

    @Test
    fun `the tree name is carried through`() {
        val r = v.report(listOf(doc(1, "a.pdf", "pdf")), allText,
            treeName = "ProcurementDocs")
        assertEquals("ProcurementDocs", r.treeName)
    }
}
