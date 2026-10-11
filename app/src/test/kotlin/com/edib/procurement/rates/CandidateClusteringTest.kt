package com.edib.procurement.rates

import com.edib.procurement.database.RateCandidateRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CandidateClusteringTest {
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
    }
}
