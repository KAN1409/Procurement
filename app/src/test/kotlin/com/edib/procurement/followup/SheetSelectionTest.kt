package com.edib.procurement.followup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sheet selection.
 *
 * Sheets are chosen by name, never by index, because their order changes
 * between workbook versions.
 */
class SheetSelectionTest {

    /** Mirrors FollowUpWorkbookImporter.pickSheet, including its normalisation. */
    private fun pickSheet(
        sheets: List<XlsxStreamReader.SheetInfo>,
        hints: List<String>
    ): XlsxStreamReader.SheetInfo? {
        val normalizedHints = hints.map { TextNormalizer.normalize(it) }
        sheets.forEach { s ->
            val n = TextNormalizer.normalize(s.name)
            if (normalizedHints.contains(n)) return s
        }
        sheets.forEach { s ->
            val n = TextNormalizer.normalize(s.name)
            if (normalizedHints.any { n.contains(it) }) return s
        }
        return null
    }

    private val supplierHints =
        listOf("supplier", "vendor", "company list").map { TextNormalizer.normalize(it) }
    private val orderHints =
        listOf("orders", "order", "follow up", "followup").map { TextNormalizer.normalize(it) }

    @Test
    fun `the uppercase supplier list sheet is found`() {
        // Regression: sheet names normalise to uppercase, so comparing them to
        // lowercase hints used to miss this sheet entirely and the import
        // produced zero contractors and zero aliases.
        val sheets = listOf(
            XlsxStreamReader.SheetInfo("Orders", "xl/worksheets/sheet1.xml"),
            XlsxStreamReader.SheetInfo("SUPPLIER LIST", "xl/worksheets/sheet2.xml")
        )
        val picked = pickSheet(sheets, supplierHints)
        assertNotNull("SUPPLIER LIST must be selectable", picked)
        assertEquals("SUPPLIER LIST", picked!!.name)
    }

    @Test
    fun `a differently cased supplier sheet is still found`() {
        val sheets = listOf(
            XlsxStreamReader.SheetInfo("Orders", "a.xml"),
            XlsxStreamReader.SheetInfo("Supplier List", "b.xml")
        )
        assertEquals("Supplier List", pickSheet(sheets, supplierHints)!!.name)
    }

    @Test
    fun `orders and supplier sheets are distinguished`() {
        val sheets = listOf(
            XlsxStreamReader.SheetInfo("Orders", "a.xml"),
            XlsxStreamReader.SheetInfo("SUPPLIER LIST", "b.xml")
        )
        assertEquals("Orders", pickSheet(sheets, orderHints)!!.name)
        assertEquals("SUPPLIER LIST", pickSheet(sheets, supplierHints)!!.name)
    }

    @Test
    fun `sheet order does not matter`() {
        val reversed = listOf(
            XlsxStreamReader.SheetInfo("SUPPLIER LIST", "b.xml"),
            XlsxStreamReader.SheetInfo("Orders", "a.xml")
        )
        assertEquals("Orders", pickSheet(reversed, orderHints)!!.name)
        assertEquals("SUPPLIER LIST", pickSheet(reversed, supplierHints)!!.name)
    }

    @Test
    fun `a workbook without a supplier sheet returns null rather than a wrong sheet`() {
        val sheets = listOf(XlsxStreamReader.SheetInfo("Orders", "a.xml"))
        assertNull(pickSheet(sheets, supplierHints))
    }
}
