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

    @Test
    fun referencesAndCurrencyDoNotBecomeRates() {
        val doc = source("""
            Project 8 EGP building reference
            مبنى ٣ جنيه مشروع تجاري
            Marble project reference 8 m2
            رخام جلالة م٢ جنيه ٨
        """.trimIndent())
        val rows = RateCandidateParser.parse(doc, 1234L)
        assertEquals("Unexpected heuristic rows: " + rows.map { it.evidenceText }, 1, rows.size)
        assertEquals(8.0, rows.single().rate, 0.0)
    }

    @Test
    fun legitimateSmallRateAndArabicDigitsSurvive() {
        val rows = RateCandidateParser.parse(source("""
            Marble skirting lm 3 EGP
            رخام جلالة م٢ ۸۰۰ جنيه
        """.trimIndent()), 1234L)
        assertEquals(2, rows.size)
        assertEquals(3.0, rows[0].rate, 0.0)
        assertEquals(800.0, rows[1].rate, 0.0)
    }

    @Test
    fun measuredGypsumDescriptionReconstructsMainItemWithoutCommercialNumbers() {
        val fragment = "الإكسسوارات من إنتاج كناوف او جودين ميتال بسمك لا يقل عن 0.5 مم"
        val evidence = "بالمتر المسطح - توريد وتركيب أسقف معلقة من ألواح الجبس الأخضر المقاوم للرطوبة بسمك 12 مم لزوم الحمام طبقاً للتشكيلات المطلوبة والسعر يشمل توريد وتركيب جميع " + fragment + " م2 720 740 532,800.00"
        val reconstructed = RateCandidateParser.reconstructMeasuredItem(evidence, fragment)
        assertTrue("Main BOQ identity must survive", reconstructed?.contains("أسقف معلقة") == true)
        assertTrue("Accessories must remain as specification", reconstructed?.contains(fragment) == true)
        assertTrue("Rate must not enter item", reconstructed?.contains("532,800") == false)
        assertEquals(null, RateCandidateParser.reconstructMeasuredItem("ملاحظات أخرى " + fragment + " م2 720 740 532,800.00", fragment))
    }

    @Test
    fun mappedReferenceCodeCannotMasqueradeAsUnit() {
        val doc = source("""
            البند\tالوحدة\tسعر الوحدة
            بالعدد توريد قرصة رخام جلالة دائرية\t(ALMBT-UN-FU-0049-26)\t1
        """.trimIndent().replace("\\t", "\t"))
        val rows = RateCandidateParser.parse(doc, 1234L)
        assertTrue("Invalid PO reference must never produce a mapped unit/rate", rows.isEmpty())
        assertTrue(RateCandidateParser.suspiciousUnit("(ALMBT-UN-FU-0049-26)"))
        assertTrue(!RateCandidateParser.suspiciousUnit("م٢"))
        assertTrue(!RateCandidateParser.suspiciousUnit("عدد"))
        assertTrue(!RateCandidateParser.suspiciousUnit("متر مربع"))
        assertTrue(RateCandidateParser.suspiciousUnit("PO-22-M-26"))
        assertTrue(RateCandidateParser.suspiciousUnit("reference م2 2026"))
    }

    @Test fun dottedLinearMeterIsRecognizedAndNotFlagged() {
        assertTrue(!RateCandidateParser.suspiciousUnit("م.ط"))
        assertTrue(!RateCandidateParser.suspiciousUnit("م ط"))
        assertTrue(RateCandidateParser.suspiciousUnit("PO-22-M-26"))
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
