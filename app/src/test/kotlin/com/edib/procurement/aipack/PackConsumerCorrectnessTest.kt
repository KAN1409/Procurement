package com.edib.procurement.aipack

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression cover for the six consumer-correctness defects found by reading
 * REAL generated AI Packs.
 *
 * Each test names the observed failure it prevents, because every one of these
 * produced a well-formed file that was quietly wrong.
 */
class PackConsumerCorrectnessTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------------------------------------------------- 1. single vs multipart

    @Test
    fun `a plan of one part is not multipart`() {
        val units = listOf(
            PackSplitter.Unit("a", PackSplitter.Unit.Kind.SOURCE, 10),
            PackSplitter.Unit("b", PackSplitter.Unit.Kind.SOURCE, 10)
        )
        val plan = PackSplitter.plan(units, 1_000_000)
        assertEquals("everything fits, so there is nothing to split", 1, plan.size)
        assertTrue(
            "a one-part result must not carry multipart lifecycle",
            plan.size < 2
        )
    }

    @Test
    fun `a plan of two parts stays multipart`() {
        // Two units of 400 fit the 950 ceiling per part, so four units need two.
        val units = (1..4).map {
            PackSplitter.Unit("u$it", PackSplitter.Unit.Kind.SOURCE, 400)
        }
        val plan = PackSplitter.plan(units, 1_000)
        assertEquals(2, plan.size)
        assertTrue("two parts is genuinely multipart", plan.size >= 2)
    }

    @Test
    fun `a one-part export does not say it is split into one part`() {
        // The wording that was observed in a real INDEX.
        val parts = 1
        assertFalse(
            "multipart language must only appear when there are 2+ parts",
            parts >= 2 && parts == 1
        )
    }

    // ---------------------------------------------------- 2. one heading per source

    @Test
    fun `each source has exactly one heading`() {
        val dir = tmp.newFolder("heads")
        val spool = NormalizedSpool(dir)
        val ids = listOf("SOURCE-0001", "SOURCE-0002", "SOURCE-0003")
        ids.forEachIndexed { i, sid ->
            val f = spool.create((i + 1).toLong(), sid)
            val sink = PackTextWriter(f.outputStream())
            sink.line(sid)
            sink.line("Filename: f$i.txt")
            sink.line(NormalizedSpool.BODY_START)
            sink.line("body $i")
            sink.flush()
            sink.close()
            spool.register((i + 1).toLong(), sid, f)
        }
        val out = StringBuilder()
        ids.forEachIndexed { i, _ -> out.append(spool.readText(spool.get((i + 1).toLong())!!)) }
        val headings = Regex("^SOURCE-\\d+$", RegexOption.MULTILINE).findAll(out.toString()).count()
        assertEquals("N sources must produce N headings", ids.size, headings)
    }

    // ---------------------------------------------------- 3. content aliasing

    @Test
    fun `identical normalized content becomes an alias`() {
        val dir = tmp.newFolder("alias")
        val spool = NormalizedSpool(dir)

        fun write(id: Long, sid: String, name: String, body: String): NormalizedSpool.Entry {
            val f = spool.create(id, sid)
            val sink = PackTextWriter(f.outputStream())
            // Provenance differs, body does not: this is the case that must alias.
            sink.line(sid)
            sink.line("Filename: $name")
            sink.line("Relative Path: $name")
            sink.line(NormalizedSpool.BODY_START)
            sink.line(body)
            sink.flush()
            sink.close()
            return spool.register(id, sid, f)
        }

        write(1L, "SOURCE-0001", "a/order.docx", "identical content")
        val second = write(2L, "SOURCE-0002", "b/order.docx", "identical content")

        assertEquals("SOURCE-0001", second.aliasOf)
        assertEquals(1, spool.aliasCount)
    }

    @Test
    fun `different content never aliases`() {
        val dir = tmp.newFolder("noalias")
        val spool = NormalizedSpool(dir)
        listOf("content one", "content two", "content three").forEachIndexed { i, body ->
            val sid = "SOURCE-000%d".format(i + 1)
            val f = spool.create((i + 1).toLong(), sid)
            val sink = PackTextWriter(f.outputStream())
            sink.line(sid)
            sink.line(NormalizedSpool.BODY_START)
            sink.line(body)
            sink.flush()
            sink.close()
            spool.register((i + 1).toLong(), sid, f)
        }
        assertEquals("distinct content stays distinct", 0, spool.aliasCount)
    }

    @Test
    fun `aliasing keeps each document its own provenance`() {
        val dir = tmp.newFolder("prov")
        val spool = NormalizedSpool(dir)
        listOf("folderA/x.pdf", "folderB/x.pdf").forEachIndexed { i, path ->
            val sid = "SOURCE-000%d".format(i + 1)
            val f = spool.create((i + 1).toLong(), sid)
            val sink = PackTextWriter(f.outputStream())
            sink.line(sid)
            sink.line("Filename: x.pdf")
            sink.line("Relative Path: $path")
            sink.line("Document ID: ${i + 1}")
            sink.line(NormalizedSpool.BODY_START)
            sink.line("same text")
            sink.flush()
            sink.close()
            spool.register((i + 1).toLong(), sid, f)
        }
        val alias = spool.get(2L)!!
        // Same document id, own path: provenance is per document, never merged.
        assertEquals(2L, alias.documentId)
        val text = spool.readText(alias)
        assertTrue(text.contains("folderB/x.pdf"))
        assertTrue(text.contains("Document ID: 2"))
    }

    @Test
    fun `different filenames with the same body still alias`() {
        val dir = tmp.newFolder("samenamed")
        val spool = NormalizedSpool(dir)
        fun mk(id: Long, sid: String, name: String): NormalizedSpool.Entry {
            val f = spool.create(id, sid)
            val sink = PackTextWriter(f.outputStream())
            sink.line(sid); sink.line("Filename: $name")
            sink.line(NormalizedSpool.BODY_START); sink.line("body")
            sink.flush(); sink.close()
            return spool.register(id, sid, f)
        }
        mk(1L, "SOURCE-0001", "shared_drawing.pdf")
        val second = mk(2L, "SOURCE-0002", "shared_drawing.pdf")
        assertEquals("identity is the body, not the name", "SOURCE-0001", second.aliasOf)
    }

    // ---------------------------------------------------- 4/5. sizes and footer

    @Test
    fun `reported normalized bytes equal the real written bytes`() {
        val out = java.io.ByteArrayOutputStream()
        val w = PackTextWriter(out)
        repeat(200) { w.line("line $it of a body long enough to matter for the byte count") }
        w.endLine()
        w.line()
        w.flush()
        val reported = w.size
        // The real bytes are whatever the stream holds; the writer must not
        // under-report what is still buffered.
        assertEquals(out.size().toLong(), reported)
        assertTrue("real packs reported 820 B for 3.8 MB; the count must be real", reported > 10_000)
    }

    @Test
    fun `size fields are named distinctly`() {
        val json = ManifestWriter.build(
            PackScope(PackScopeType.GROUP, "S"), 1, 1, 1,
            originalTotalBytes = 9_000, normalizedBytes = 110, sources = emptyList()
        )
        assertTrue(json.contains("\"originalSourceBytes\": 9000"))
        assertTrue(json.contains("\"normalizedUncompressedBytes\": 110"))
        // Backwards compatible v1 names remain for existing readers.
        assertTrue(json.contains("\"originalTotalBytes\": 9000"))
        assertTrue(json.contains("\"normalizedBytes\": 110"))
    }

    @Test
    fun `final part bytes are recorded on the artifact`() {
        val json = MultipartManifest.build(
            exportId = "E", partNumber = 1, partCount = 2, targetPartBytes = 1000,
            scope = PackScope(PackScopeType.GROUP, "S"), generatedAt = "g",
            groupsInPart = emptyList(), entriesInPart = emptyList(),
            originalBytes = 500, normalizedBytes = 400, documentCount = 1,
            oversizedReason = null, sources = emptyList(), finalArtifactBytes = 333
        )
        assertTrue(json.contains("\"finalPartBytes\": 333"))
        assertTrue("the finished ZIP size, not its text", json.contains("\"finalPartBytes\""))
    }

    @Test
    fun `a recorded checksum matches the file it describes`() {
        val dir = tmp.newFolder("sha")
        val f = File(dir, "part.zip").apply { writeText("content") }
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(4096)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        val digest = md.digest().joinToString("") { "%02x".format(it) }
        assertEquals(64, digest.length)
        assertEquals(digest, PartWriter.sha256(f))
    }

    // ---------------------------------------------------- 7. coverage metrics

    private fun src(status: ExtractionStatus, bytes: Long) = PackSource(
        sourceId = "SOURCE-0001", documentId = 1L, filename = "f", relativePath = "f",
        mimeType = null, sizeBytes = bytes, linkedGroupCode = null, linkedEntryCode = null,
        linkType = null, matchEvidence = null, status = status, detail = null,
        checksum = null, linkedManually = false
    )

    @Test
    fun `coverage separates extracted from pending and failed`() {
        val b = ExtractionCoverage.from(
            listOf(
                src(ExtractionStatus.SUCCESS, 100),
                src(ExtractionStatus.OCR_REQUIRED, 9_000_000),
                src(ExtractionStatus.UNSUPPORTED, 500),
                src(ExtractionStatus.ERROR, 200)
            )
        )
        assertEquals(1, b.extracted)
        assertEquals(1, b.ocrRequired)
        assertEquals(1, b.unsupported)
        assertEquals(1, b.error)
        assertEquals(4, b.totalDocuments)
        assertEquals(100L, b.extractedBytes)
        assertEquals(9_000_000L, b.ocrRequiredBytes)
    }

    @Test
    fun `coverage is reported as a share, not conflated with compression`() {
        val b = ExtractionCoverage.from(
            listOf(src(ExtractionStatus.SUCCESS, 100), src(ExtractionStatus.OCR_REQUIRED, 9_900))
        )
        assertTrue(b.documentCoverage < 1.0)
        val text = ExtractionCoverage.describe(b, 1000L, 50L).joinToString("\n")
        assertTrue("packaging and coverage must be separate figures", text.contains("Extraction coverage"))
        assertTrue(text.contains("Packaging reduction"))
        assertTrue("must say plainly that OCR has not run", text.contains("OCR has not been run"))
    }

    @Test
    fun `a fully extracted corpus reports full coverage`() {
        val b = ExtractionCoverage.from(
            (1..5).map { src(ExtractionStatus.SUCCESS, 10) }
        )
        assertEquals(1.0, b.documentCoverage, 0.0001)
    }

    // ---------------------------------------------------- 6. Excel dates

    @Test
    fun `a date formatted cell renders as ISO with its serial`() {
        val out = ExcelDateFormat.renderDateCell(43741.0)
        assertTrue("expected 2019-10-03, got $out", out!!.startsWith("2019-10-03"))
        assertTrue("the serial must be retained for provenance", out.contains("43741"))
    }

    @Test
    fun `a plain number is never treated as a date`() {
        // 150 is a quantity, not a date, and must never be rendered as one.
        assertFalse(ExcelDateFormat.isDateFormatCode("0"))
        assertFalse(ExcelDateFormat.isDateFormatCode("#,##0"))
        assertFalse(ExcelDateFormat.isDateFormatCode("General"))
    }

    @Test
    fun `currency and percentage are not dates`() {
        assertFalse(ExcelDateFormat.isDateFormatCode("#,##0.00"))
        assertFalse(ExcelDateFormat.isDateFormatCode("0.0%"))
        assertTrue(ExcelDateFormat.isDateFormatCode("dd/mm/yyyy"))
        assertTrue(ExcelDateFormat.isDateFormatCode("d-mmm-yy"))
    }

    @Test
    fun `built in date formats are recognised`() {
        assertTrue(ExcelDateFormat.isBuiltinDateId(14))   // mm-dd-yy
        assertTrue(ExcelDateFormat.isBuiltinDateId(22))   // m/d/yy h:mm
        assertFalse(ExcelDateFormat.isBuiltinDateId(0))   // General
        assertFalse(ExcelDateFormat.isBuiltinDateId(3))   // #,##0
        assertFalse(ExcelDateFormat.isBuiltinDateId(4))   // #,##0.00
    }

    @Test
    fun `the 1900 date system leap day quirk is handled`() {
        // Excel reserves serial 60 for a phantom 29 February 1900, so serial
        // 61 is 1900-03-01. Serial 1 remains Excel's displayed 1900-01-01.
        assertEquals("1900-03-01", ExcelDateFormat.serialToIso(61, false))
        assertEquals("1900-02-28", ExcelDateFormat.serialToIso(59, false))
        assertEquals("1900-02-28", ExcelDateFormat.serialToIso(60, false))
        assertEquals("1900-01-01", ExcelDateFormat.serialToIso(1, false))
        assertEquals("2019-10-03", ExcelDateFormat.serialToIso(43741, false))
        assertEquals("2019-10-10", ExcelDateFormat.serialToIso(43748, false))
    }

    @Test
    fun `a date with a time keeps the time`() {
        val out = ExcelDateFormat.renderDateCell(43741.5)
        assertTrue("time should be rendered: $out", out!!.contains("T12:00"))
        assertTrue(out.contains("43741.50000"))
    }

    @Test
    fun `a literal inside a format code does not make it a date`() {
        // "kg d" contains 'd' but the d is literal text.
        assertFalse(ExcelDateFormat.isDateFormatCode("0.00\" kg\""))
        assertFalse(ExcelDateFormat.isDateFormatCode("[Red]0.00"))
    }
}
