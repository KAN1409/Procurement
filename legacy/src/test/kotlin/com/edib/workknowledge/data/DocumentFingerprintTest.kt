package com.edib.workknowledge.data

import com.edib.workknowledge.data.WorkRepository.ImportOutcome
import com.edib.workknowledge.data.WorkRepository.OpResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fingerprint and import-outcome behaviour, exercised without Android
 * framework classes so it runs on the plain JVM test classpath.
 */
class DocumentFingerprintTest {

    @Test
    fun `sha256 matches the known digest of an empty input`() {
        // Guards the hashing helper against an accidental change of algorithm.
        val digest = sha256Of(ByteArray(0))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            digest
        )
    }

    @Test
    fun `sha256 matches the known digest of abc`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Of("abc".toByteArray())
        )
    }

    @Test
    fun `different content produces different fingerprints`() {
        val a = sha256Of("BOQ Rev 01".toByteArray())
        val b = sha256Of("BOQ Rev 02".toByteArray())
        assertTrue("revisions must not collide", a != b)
    }

    @Test
    fun `identical content produces an identical fingerprint`() {
        val bytes = "Marble Approved".toByteArray()
        assertEquals(sha256Of(bytes), sha256Of(bytes.copyOf()))
    }

    @Test
    fun `import outcome reports a duplicate instead of re-queueing`() {
        val duplicate: ImportOutcome = ImportOutcome.Duplicate(7L, "BOQ Rev 01.xlsx")
        assertTrue(duplicate is ImportOutcome.Duplicate)
        assertEquals("BOQ Rev 01.xlsx", (duplicate as ImportOutcome.Duplicate).existingName)
    }

    @Test
    fun `mime type is derived from the file extension`() {
        val repo = MimeTypes
        assertEquals("application/pdf", repo.forName("drawing.pdf"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            repo.forName("BOQ Rev 02.xlsx")
        )
        assertEquals("application/vnd.ms-excel.sheet.macroEnabled.12", repo.forName("macro.xlsm"))
        assertEquals("text/csv", repo.forName("items.csv"))
        assertEquals("text/plain", repo.forName("notes.txt"))
        assertEquals("application/octet-stream", repo.forName("photo.jpg"))
    }

    private object MimeTypes {
        fun forName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "xlsm" -> "application/vnd.ms-excel.sheet.macroEnabled.12"
            "csv" -> "text/csv"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    companion object {
        fun sha256Of(bytes: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
