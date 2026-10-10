package com.edib.procurement.rates

import com.edib.procurement.database.RateCorpusDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RateCandidateParserTest {

    @Test
    fun headerMappedTableExtractsDescriptionUnitRateAndQuantity() {
        val doc = source(
            """
            --- Sheet: BOQ ---
            البند	الكمية	الوحدة	سعر الوحدة	الإجمالي
            توريد وتركيب رخام جلالة للأرضيات	20	م٢	٨٠٠	١٦٠٠٠
            """.trimIndent()
        )

        val rows = RateCandidateParser.parse(doc, now = 1234L)

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("توريد وتركيب رخام جلالة للأرضيات", row.itemRaw)
        assertEquals("م٢", row.unitRaw)
        assertEquals(20.0, row.quantity ?: Double.NaN, 0.0)
        assertEquals(800.0, row.rate, 0.0)
        assertEquals("SUPPLY_INSTALL", row.inferredScope)
        assertTrue(row.confidence >= 90)
        assertTrue(row.evidenceLocator.contains("BOQ"))
    }

    @Test
    fun heuristicRequiresUnitOrCurrencySoRandomNumbersDoNotFloodQueue() {
        val doc = source(
            """
            Project 2026
            Revision 14
            Marble skirting lm 775 EGP
            """.trimIndent()
        )

        val rows = RateCandidateParser.parse(doc, now = 1234L)

        assertEquals(1, rows.size)
        assertEquals(775.0, rows.single().rate, 0.0)
        assertEquals("lm", rows.single().unitRaw)
        assertEquals("EGP", rows.single().currency)
    }

    private fun source(text: String) = RateCorpusDocument(
        id = 7L,
        displayName = "source.xlsx",
        relativePath = "Rates/source.xlsx",
        documentUri = "content://source/7",
        mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        extension = "xlsx",
        lastModified = 999L,
        extractedText = text
    )
}
