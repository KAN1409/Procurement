package com.edib.procurement.rates

import com.edib.procurement.database.RateListRow
import com.edib.procurement.database.RateScope
import com.edib.procurement.database.RateStatus
import com.edib.procurement.database.normalizeRateItem
import org.junit.Assert.assertEquals
import org.junit.Test

class RateSearchTest {

    @Test
    fun targetRateRanksAcrossDifferentUnitsWithoutConvertingThem() {
        val rows = listOf(
            row(id = 1, rate = 780.0, unit = "lm"),
            row(id = 2, rate = 805.0, unit = "m²"),
            row(id = 3, rate = 900.0, unit = "m2")
        )

        val ranked = rankRelatedRates(
            rows = rows,
            targetRate = 800.0,
            targetCurrency = "EGP",
            status = null,
            scope = null
        )

        assertEquals(listOf(2L, 1L, 3L), ranked.map { it.id })
        assertEquals(listOf("m²", "lm", "m2"), ranked.map { it.unit })
    }

    @Test
    fun targetRateFiltersCurrencyButNotUnit() {
        val rows = listOf(
            row(id = 1, rate = 799.0, unit = "lm", currency = "USD"),
            row(id = 2, rate = 820.0, unit = "m2", currency = "EGP")
        )

        val ranked = rankRelatedRates(
            rows = rows,
            targetRate = 800.0,
            targetCurrency = "EGP",
            status = null,
            scope = null
        )

        assertEquals(listOf(2L), ranked.map { it.id })
    }

    @Test
    fun optionalStatusAndScopeFiltersAreIndependentOfUnit() {
        val rows = listOf(
            row(id = 1, rate = 800.0, unit = "lm", status = RateStatus.AWARDED, scope = RateScope.SUPPLY),
            row(id = 2, rate = 810.0, unit = "m2", status = RateStatus.QUOTED, scope = RateScope.SUPPLY),
            row(id = 3, rate = 820.0, unit = "m3", status = RateStatus.AWARDED, scope = RateScope.INSTALL)
        )

        val ranked = rankRelatedRates(
            rows = rows,
            targetRate = null,
            targetCurrency = "",
            status = RateStatus.AWARDED,
            scope = RateScope.SUPPLY
        )

        assertEquals(listOf(1L), ranked.map { it.id })
    }

    private fun row(
        id: Long,
        rate: Double,
        unit: String,
        currency: String = "EGP",
        status: String = RateStatus.AWARDED,
        scope: String = RateScope.SUPPLY_INSTALL
    ) = RateListRow(
        id = id,
        itemRaw = "رخام",
        itemNormalized = normalizeRateItem("رخام"),
        material = null,
        specification = null,
        dimensions = null,
        unit = unit,
        scope = scope,
        contractorId = null,
        contractorName = null,
        projectId = null,
        projectName = null,
        location = null,
        quantity = null,
        rate = rate,
        currency = currency,
        status = status,
        observedDate = 1000L + id,
        sourceDocumentId = id,
        sourceFileName = "source-$id.xlsx",
        sourceRelativePath = "Rates/source-$id.xlsx",
        sourceUri = "content://source/$id",
        sourceMimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        sourceExtension = "xlsx",
        evidenceLocator = "Item $id",
        evidenceText = null,
        notes = null
    )
}
