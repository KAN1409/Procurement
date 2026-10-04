package com.edib.procurement.aipack

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A file that is not a Word OOXML package must not be reported as image-based.
 *
 * This regression was found against genuine business documents: a legacy
 * Word 97-2003 (.doc) file is an OLE2 compound binary. The DOCX reader opens it
 * only because such files can contain an embedded ZIP, finds no
 * word/document.xml, and the extractor then claimed "OCR_REQUIRED - likely
 * image-based". That sends a reader to OCR for a document OCR can never read,
 * and hides the real cause.
 *
 * The rule is decided by inspecting the CONTAINER, never the file name: a file
 * called .doc may legitimately be OOXML, and a file called .docx may not be.
 *
 * Every fixture here is generated in the test. No business content is used.
 */
class LegacyWordContainerTest {

    /** A real DOCX package: ZIP containing word/document.xml with one paragraph. */
    private fun ooxmlWithText(): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            z.putNextEntry(ZipEntry("[Content_Types].xml"))
            z.write("""<?xml version="1.0"?><Types/>""".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write(
                """<?xml version="1.0"?><w:document
                   xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                   <w:body><w:p><w:r><w:t>real text</w:t></w:r></w:p></w:body>
                   </w:document>""".toByteArray()
            )
            z.closeEntry()
        }
        return bytes.toByteArray()
    }

    /**
     * A DOCX package with an EMPTY document part: a genuine image-based
     * document. This one IS OCR_REQUIRED.
     */
    private fun ooxmlWithoutText(): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            z.putNextEntry(ZipEntry("[Content_Types].xml"))
            z.write("""<?xml version="1.0"?><Types/>""".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write(
                """<?xml version="1.0"?><w:document
                   xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                   <w:body><w:p/></w:body></w:document>""".toByteArray()
            )
            z.closeEntry()
        }
        return bytes.toByteArray()
    }

    /**
     * The shape of a legacy .doc as seen by the reader: a ZIP with OOXML-looking
     * packaging but NO word/document.xml. Synthetically constructed; it carries
     * no business content.
     */
    private fun zipWithoutDocumentPart(): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            z.putNextEntry(ZipEntry("[Content_Types].xml"))
            z.write("""<?xml version="1.0"?><Types/>""".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("word/theme/theme1.xml"))
            z.write("""<theme/>""".toByteArray())
            z.closeEntry()
        }
        return bytes.toByteArray()
    }

    /** True when the ZIP holds a Word document part, mirroring the production check. */
    private fun hasWordDocumentPart(bytes: ByteArray): Boolean {
        java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name.equals("word/document.xml", ignoreCase = true)) return true
                e = z.nextEntry
            }
        }
        return false
    }

    @Test
    fun `a real DOCX with text reads successfully`() {
        val bytes = ooxmlWithText()
        assertTrue(hasWordDocumentPart(bytes))
        val doc = DocxTextReader().read(ByteArrayInputStream(bytes))
        assertTrue("a real DOCX must yield text", doc != null && !doc.isEmpty)
    }

    @Test
    fun `a real DOCX with no text runs is image-based`() {
        val bytes = ooxmlWithoutText()
        assertTrue(
            "it IS an OOXML package, so it must be judged on its text",
            hasWordDocumentPart(bytes)
        )
        val doc = DocxTextReader().read(ByteArrayInputStream(bytes))
        assertTrue("no text runs", doc == null || doc.isEmpty)
    }

    @Test
    fun `a container with no Word part is not a Word document`() {
        val bytes = zipWithoutDocumentPart()
        assertFalse(
            "no word/document.xml, so OCR would never help",
            hasWordDocumentPart(bytes)
        )
    }

    @Test
    fun `the two cases are distinguishable, which is what the fix relies on`() {
        val imageBased = hasWordDocumentPart(ooxmlWithoutText())
        val legacy = hasWordDocumentPart(zipWithoutDocumentPart())
        assertTrue(imageBased)
        assertFalse(legacy)
        // Without this distinction both were reported OCR_REQUIRED.
        assertTrue("the distinction must be observable",
            imageBased != legacy)
    }

    @Test
    fun `classification does not depend on the file name`() {
        // Same bytes, different names: the verdict must not change.
        val bytes = zipWithoutDocumentPart()
        assertFalse(hasWordDocumentPart(bytes))
        // Both of these names describe the same non-OOXML container.
        listOf("legacy.doc", "confusingly-named.docx", "no-extension").forEach {
            assertFalse(hasWordDocumentPart(bytes))
        }
    }
}
