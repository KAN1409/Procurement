package com.edib.procurement.rates

import com.edib.procurement.database.RateCandidateRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CandidateClusteringTest {
    private fun example(id: Long, documentId: Long?, path: String, rate: Double = 740.0,
                        status: String = "PENDING") = RateCandidateRow(
        id, "key-$id", documentId, "same.docx", path, "", "docx", null, 1L,
        "توريد سقف", "م2", 720.0, rate, "EGP", "SUPPLY_INSTALL",
        "line 11", "720 م2 740", 80, "TABLE", status, null
    )

    @Test fun matchingValuesAcrossDistinctSourcesAreNotRepeatedEvidence() {
        val result = CandidateClustering.evidenceSummary(listOf(
            example(4994, 10, "SBH/same.docx"), example(4718, 20, "JPH/same.docx")
        ))
        assertEquals(0, result.repeatedEvidenceGroups)
        assertEquals(1, result.matchingValueGroups)
    }

    @Test fun sameSourceEvidenceIsDetectedWithoutDroppingRows() {
        val rows = listOf(example(1, 10, "SBH/same.docx"), example(2, 10, "SBH/same.docx"))
        assertEquals(1, CandidateClustering.evidenceSummary(rows).repeatedEvidenceGroups)
        assertEquals(2, CandidateClustering.group(rows).single().candidates.size)
    }

    @Test fun unknownSourcesAndStaleRowsAreConservative() {
        val result = CandidateClustering.evidenceSummary(listOf(
            example(1, null, ""), example(2, null, "", 850.0),
            example(3, null, "", status = "STALE")
        ))
        assertEquals(2, result.unknownSources)
        assertEquals(0, result.repeatedEvidenceGroups)
        assertEquals(0, result.matchingValueGroups)
    }

    @Test fun equalConfidenceUsesStableCandidateId() {
        assertEquals(listOf(2L, 9L), CandidateClustering.group(listOf(
            example(9, 10, "A"), example(2, 20, "B")
        )).single().candidates.map { it.id })
    }

    @Test fun rateSortKeepsDaoOrderWithinGroupsAndOrdersGroupsByFirstRow() {
        val low = example(1, 10, "A", 100.0)
        val high = example(2, 20, "B", 900.0)
        val middle = example(3, 30, "C", 400.0).copy(itemRaw = "توريد أرضية")
        val asc = CandidateClustering.group(listOf(low, middle, high), "RATE_ASC")
        assertEquals(listOf(1L, 2L), asc.first().candidates.map { it.id })
        assertEquals(3L, asc.last().candidates.single().id)
        val desc = CandidateClustering.group(listOf(high, middle, low), "RATE_DESC")
        assertEquals(listOf(2L, 1L), desc.first().candidates.map { it.id })
        assertEquals(3L, desc.last().candidates.single().id)
    }

    @Test fun sourceSortPreservesIncomingDaoOrdering() {
        val first = example(3, 10, "A/same.docx")
        val second = example(2, 20, "B/same.docx").copy(itemRaw = "توريد أرضية")
        val third = example(1, 30, "C/same.docx")
        val grouped = CandidateClustering.group(listOf(first, second, third), "SOURCE")
        assertEquals(listOf(3L, 1L), grouped.first().candidates.map { it.id })
        assertEquals(2L, grouped.last().candidates.single().id)
    }

    @Test fun searchExposesSources() {
        assertEquals(true, CandidateClustering.showSources("gypsum", false))
        assertEquals(true, CandidateClustering.showSources("", true))
        assertEquals(false, CandidateClustering.showSources("", false))
        assertEquals(true, CandidateClustering.showSources("", false, "RATE_ASC"))
        assertEquals(true, CandidateClustering.showSources("", false, "RATE_DESC"))
        assertEquals(true, CandidateClustering.showSources("", false, "SOURCE"))
        assertEquals(false, CandidateClustering.showSources("", false, "CONFIDENCE"))
    }

    @Test fun linearMeterVariantsGroupButNeverMixWithArea() {
        assertEquals(CandidateClustering.key("وزر", "م.ط", "INSTALL", "EGP"),
            CandidateClustering.key("وزر", "lm", "INSTALL", "EGP"))
        assertNotEquals(CandidateClustering.key("وزر", "م.ط", "INSTALL", "EGP"),
            CandidateClustering.key("وزر", "م2", "INSTALL", "EGP"))
    }

    @Test fun equivalentUnitsGroupTogether() {
        assertEquals(
            CandidateClustering.key("توريد رخام", "م٢", "SUPPLY", "EGP"),
            CandidateClustering.key("توريد  رخام", "sqm", "SUPPLY", "egp")
        )
    }

    @Test fun incompatibleUnitsScopesAndCurrenciesStaySeparate() {
        val baseline = CandidateClustering.key("توريد رخام", "م2", "SUPPLY", "EGP")
        assertNotEquals(baseline, CandidateClustering.key("توريد رخام", "م", "SUPPLY", "EGP"))
        assertNotEquals(baseline, CandidateClustering.key("توريد رخام", "م2", "INSTALL", "EGP"))
        assertNotEquals(baseline, CandidateClustering.key("توريد رخام", "م2", "SUPPLY", "USD"))
    }


    @Test fun galalaSpellingVariantsDoNotSilentlyMergeCommercialGroups() {
        val taMarbuta = example(81, 101, "A/po.docx", 740.0)
            .copy(itemRaw = "توريد رخام جلالة")
        val ha = example(82, 102, "B/po.docx", 850.0)
            .copy(itemRaw = "توريد رخام جلاله")
        val grouped = CandidateClustering.group(listOf(taMarbuta, ha))
        assertEquals(2, grouped.size)
        assertEquals(setOf(81L, 82L), grouped.flatMap { it.candidates }.map { it.id }.toSet())
        assertEquals(setOf(740.0, 850.0), grouped.flatMap { it.candidates }.map { it.rate }.toSet())
    }

    @Test fun materialSpecificationAndScopeStaySeparated() {
        val polished = example(90, 110, "A/po.docx").copy(
            itemRaw = "توريد رخام جلالة مصقول", inferredScope = "SUPPLY")
        val honed = example(91, 111, "B/po.docx").copy(
            itemRaw = "توريد رخام جلالة مط", inferredScope = "SUPPLY")
        val installed = example(92, 112, "C/po.docx").copy(
            itemRaw = "توريد رخام جلالة مصقول", inferredScope = "INSTALL")
        assertEquals(3, CandidateClustering.group(listOf(polished, honed, installed)).size)
    }

    @Test fun onlyPendingCandidatesAreGroupedAndRatesRemainUnchanged() {
        fun candidate(id: Long, status: String, rate: Double) = RateCandidateRow(
            id = id, sourceKey = "source-$id", sourceDocumentId = id,
            sourceFileName = "source.docx", sourceRelativePath = "source.docx",
            sourceUri = "", sourceExtension = "docx", sourceMimeType = null,
            sourceModifiedAt = 1L, itemRaw = "توريد رخام", unitRaw = "م2",
            quantity = 6.75, rate = rate, currency = "EGP", inferredScope = "SUPPLY",
            evidenceLocator = "line 31", evidenceText = "6.75 م2 800",
            confidence = 80, detectionMethod = "TABLE_HEADER", status = status,
            confirmedRateId = null
        )
        val grouped = CandidateClustering.group(listOf(
            candidate(1, "PENDING", 800.0),
            candidate(2, "PENDING", 900.0),
            candidate(3, "STALE", 1000.0),
            candidate(4, "DISMISSED", 1100.0)
        ))
        assertEquals(1, grouped.size)
        assertEquals(setOf(800.0, 900.0), grouped.single().candidates.map { it.rate }.toSet())
        assertEquals(setOf(1L, 2L), grouped.single().candidates.map { it.id }.toSet())
        assertEquals(listOf(6.75, 6.75), grouped.single().candidates.map { it.quantity })
        assertEquals(2, CandidateClustering.rateVariantCount(grouped.single().candidates))
    }
}
