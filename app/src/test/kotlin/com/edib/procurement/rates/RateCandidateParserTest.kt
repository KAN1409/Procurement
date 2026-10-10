package com.edib.procurement.rates

import com.edib.procurement.database.RateCorpusDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RateCandidateParserTest {

    @Test
    fun headerMappedTableExtractsDescriptionUnitRateAndQuantity() {
        val rows = RateCandidateParser.parse(
            source(
                """
                --- Sheet: BOQ ---
                البند	الكمية	الوحدة	سعر الوحدة	الإجمالي
                توريد وتركيب رخام جلالة للأرضيات	20	م٢	٨٠٠	١٦٠٠٠
                """.trimIndent()
            ),
            now = 1234L
        )

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("توريد وتركيب رخام جلالة للأرضيات", row.itemRaw)
        assertEquals("م٢", row.unitRaw)
        assertEquals(20.0, row.quantity ?: Double.NaN, 0.0)
        assertEquals(800.0, row.rate, 0.0)
        assertEquals("SUPPLY_INSTALL", row.inferredScope)
        assertTrue(row.confidence >= 90)
        assertEquals("TABLE_HEADER", row.detectionMethod)
    }

    @Test
    fun simpleUnitCurrencyLineStillProducesCandidate() {
        val rows = RateCandidateParser.parse(
            source("Marble skirting lm 775 EGP"),
            now = 1234L
        )

        assertEquals(1, rows.size)
        assertEquals(775.0, rows.single().rate, 0.0)
        assertEquals("lm", rows.single().unitRaw)
        assertEquals("EGP", rows.single().currency)
        assertTrue(rows.single().confidence >= 80)
    }

    @Test
    fun explicitRateLabelWinsEvenWhenProjectNumberExists() {
        val rows = RateCandidateParser.parse(
            source("Project 8 - Marble skirting lm - Unit Rate: 775 EGP"),
            now = 1234L
        )

        assertEquals(1, rows.size)
        assertEquals(775.0, rows.single().rate, 0.0)
        assertEquals("EXPLICIT_RATE_LABEL", rows.single().detectionMethod)
        assertTrue(rows.single().confidence >= 90)
    }

    @Test
    fun headerlessCommercialRowWithOneNumericCellIsAccepted() {
        val rows = RateCandidateParser.parse(
            source("Marble skirting	lm	775	EGP"),
            now = 1234L
        )

        assertEquals(1, rows.size)
        assertEquals(775.0, rows.single().rate, 0.0)
        assertEquals("UNIT_RATE_CURRENCY_ROW", rows.single().detectionMethod)
    }

    @Test
    fun headerlessRowWithMultipleNumericCellsIsRejectedAsAmbiguous() {
        val rows = RateCandidateParser.parse(
            source("Marble skirting	lm	20	775	EGP"),
            now = 1234L
        )

        assertTrue(rows.isEmpty())
    }

    @Test
    fun projectAndBuildingNumbersAreNotRatesJustBecauseCurrencyAndUnitExist() {
        val rows = RateCandidateParser.parse(
            source("Project 8	Building 3	Marble package	m2	EGP"),
            now = 1234L
        )

        assertTrue(rows.isEmpty())
    }

    @Test
    fun revisionNumberIsNotPromotedToRate() {
        val rows = RateCandidateParser.parse(
            source("Revision 14 marble skirting lm EGP"),
            now = 1234L
        )

        assertTrue(rows.isEmpty())
    }

    @Test
    fun procurementCodeFragmentsAreNotCommercialNumbers() {
        val rows = RateCandidateParser.parse(
            source("MMH2-GR-AR-14-25A	Marble skirting	lm	EGP"),
            now = 1234L
        )

        assertTrue(rows.isEmpty())
    }

    @Test
    fun quantityAndDimensionNumbersDoNotCompeteWithRealRate() {
        val rows = RateCandidateParser.parse(
            source("Quantity: 20 Marble skirting 15 cm lm 775 EGP"),
            now = 1234L
        )

        assertEquals(1, rows.size)
        assertEquals(775.0, rows.single().rate, 0.0)
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
