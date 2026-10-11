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

    @Test fun searchExposesSources() {
        assertEquals(true, CandidateClustering.showSources("gypsum", false))
        assertEquals(true, CandidateClustering.showSources("", true))
        assertEquals(false, CandidateClustering.showSources("", false))
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
