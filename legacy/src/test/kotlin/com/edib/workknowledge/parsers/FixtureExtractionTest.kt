package com.edib.workknowledge.parsers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parses the real fixture files with the same libraries the app uses at
 * runtime, and asserts the ground truth the runtime test depends on.
 *
 * This is what proves the fixtures are trustworthy before they are used to
 * judge the app: if extraction drifts, these fail first.
 */
class FixtureExtractionTest {

    /**
     * Fixtures are read from the test classpath, which is a snapshot taken by
     * the build. Reading them relative to the working directory instead would
     * silently pick up an edited file on disk.
     */
    private val pdfBytes = classpath("fixtures/WK_Runtime_Test.pdf")
    private val xlsxBytes = classpath("fixtures/WK_BOQ_Runtime_Test.xlsx")

    private fun classpath(path: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream(path)
            ?: error("fixture not on test classpath: $path")
        return stream.use { it.readBytes() }
    }

    /**
     * The PDF parser reads through a ContentResolver, which is unavailable on
     * the JVM, so extraction is driven directly here. Page handling is the
     * code under test.
     */
    @Test
    fun `pdf fixture has three pages with one fact per page`() {
        val extractor = PageTextExtractor()
        val pages = extractor.extract(pdfBytes)

        assertEquals("fixture must have exactly 3 pages", 3, pages.size)

        assertTrue(
            "page 1 must name the project",
            pages[0].contains("WK Runtime Test")
        )
        assertTrue(
            "page 1 must name the consultant",
            pages[0].contains("Horizon Design")
        )

        assertTrue(
            "page 2 must carry the marble thickness",
            pages[1].contains("Approved thickness: 20 mm")
        )
        assertTrue(
            "page 2 must carry the wall finish",
            pages[1].contains("Galala Marble")
        )

        assertTrue("page 3 must carry PR-023", pages[2].contains("PR-023"))
        assertTrue("page 3 must carry the status", pages[2].contains("Approved"))

        // Facts must not bleed between pages, otherwise citations are ambiguous.
        assertTrue("PR-023 must not appear on page 1", !pages[0].contains("PR-023"))
        assertTrue(
            "thickness must not appear on page 3", !pages[2].contains("20 mm")
        )
        assertTrue(
            "consultant must not appear on page 2", !pages[1].contains("Horizon Design")
        )
    }

    @Test
    fun `excel fixture exposes both sheets with the expected rows`() {
        val reader = FixtureWorkbookReader()
        val workbook = reader.read(xlsxBytes)

        assertEquals("workbook must expose 2 sheets", 2, workbook.size)
        assertEquals("Master Bathroom", workbook[0].name)
        assertEquals("Ceilings", workbook[1].name)

        val master = workbook[0]
        assertEquals("Item Code", master.headers[0])
        assertEquals("Description", master.headers[1])
        assertEquals("Quantity", master.headers[3])

        val mr104 = master.rows.first { it["Item Code"] == "MR-104" }
        assertEquals("Galala Marble Wall Cladding", mr104["Description"])
        assertEquals("m²", mr104["Unit"])
        assertEquals("37.5", mr104["Quantity"])
        assertEquals("1850", mr104["Rate"])

        val mr105 = master.rows.first { it["Item Code"] == "MR-105" }
        assertEquals("Piatra Grey Feature Band", mr105["Description"])
        assertEquals("12.75", mr105["Quantity"])
        assertEquals("2400", mr105["Rate"])

        val cl201 = workbook[1].rows.first { it["Item Code"] == "CL-201" }
        assertEquals("Gypsum Board Ceiling", cl201["Description"])
        assertEquals("48", cl201["Quantity"])
        assertEquals("950", cl201["Rate"])
    }

    @Test
    fun `excel amount column keeps both formula and computed value`() {
        val reader = FixtureWorkbookReader()
        val master = reader.read(xlsxBytes).first { it.name == "Master Bathroom" }

        val amountCell = master.cells.first {
            it.row == 2 && it.header == "Amount"
        }
        assertEquals(
            "the formula must survive extraction",
            "D2*E2",
            amountCell.formula
        )
        assertNotNull("a value must accompany the formula", amountCell.value)
    }

    @Test
    fun `excel row numbers are one-based and match the viewer`() {
        val reader = FixtureWorkbookReader()
        val master = reader.read(xlsxBytes).first { it.name == "Master Bathroom" }

        // Row 1 is the header, so MR-104 lives on sheet row 2. A citation that
        // says "Row 2" must match what the viewer shows.
        val mr104 = master.rows.first { it["Item Code"] == "MR-104" }
        assertEquals(2, mr104.rowNumber)
    }
}
