package com.edib.procurement.rates

import com.edib.procurement.database.RateListRow
import com.edib.procurement.database.RateScope
import com.edib.procurement.database.RateStatus
import com.edib.procurement.database.normalizeRateItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RateComparatorTest {

    @Test
    fun strictGateRejectsDifferentDimensionsAndScope() {
        val request = request(
            dimensions = "15 cm",
            scope = RateScope.SUPPLY_INSTALL
        )
        val candidates = listOf(
            row(id = 1, dimensions = "10 cm", scope = RateScope.SUPPLY_INSTALL),
            row(id = 2, dimensions = "15 cm", scope = RateScope.SUPPLY),
            row(id = 3, dimensions = "15 cm", scope = RateScope.SUPPLY_INSTALL)
        )

        val result = RateComparator.compare(request, candidates)

        assertEquals(3L, result.comparisonBasis?.id)
        assertEquals(3L, result.closestComparable?.id)
    }

    @Test
    fun selectedContractorUsesLatestAwardedForThatContractor() {
        val request = request(contractorId = 7L)
        val candidates = listOf(
            row(id = 1, contractorId = 7L, observedDate = 1000L, rate = 900.0),
            row(id = 2, contractorId = 7L, observedDate = 2000L, rate = 1000.0),
            row(id = 3, contractorId = 8L, observedDate = 3000L, rate = 800.0)
        )

        val result = RateComparator.compare(request, candidates)

        assertEquals(2L, result.latestAwardedForContractor?.id)
        assertEquals(2L, result.comparisonBasis?.id)
        assertEquals(3L, result.closestComparable?.id)
        assertEquals(200.0, result.deltaAmount ?: Double.NaN, 0.0)
        assertEquals(20.0, result.deltaPercent ?: Double.NaN, 0.0001)
        assertEquals(RateCheckVerdict.HIGHER, result.verdict)
    }

    @Test
    fun closestComparableRanksProjectThenLocationThenQuantityBeforeRecency() {
        val request = request(
            contractorId = 7L,
            projectId = 100L,
            location = "Lobby",
            quantity = 20.0
        )
        val candidates = listOf(
            row(
                id = 10,
                contractorId = 8L,
                projectId = 200L,
                location = "Lobby",
                quantity = 20.0,
                observedDate = 9999L
            ),
            row(
                id = 11,
                contractorId = 9L,
                projectId = 100L,
                location = "Roof",
                quantity = 20.0,
                observedDate = 9000L
            ),
            row(
                id = 12,
                contractorId = 10L,
                projectId = 100L,
                location = "Lobby",
                quantity = 50.0,
                observedDate = 8000L
            ),
            row(
                id = 13,
                contractorId = 11L,
                projectId = 100L,
                location = "Lobby",
                quantity = 22.0,
                observedDate = 7000L
            )
        )

        val result = RateComparator.compare(request, candidates)

        assertEquals(13L, result.closestComparable?.id)
    }

    @Test
    fun noStrictComparableDoesNotInferRate() {
        val request = request(material = "Galala")
        val result = RateComparator.compare(
            request,
            listOf(row(id = 1, material = "Carrara"))
        )

        assertNull(result.comparisonBasis)
        assertNull(result.deltaAmount)
        assertNull(result.deltaPercent)
        assertEquals(RateCheckVerdict.NO_COMPARABLE, result.verdict)
        assertTrue(result.explanation.any { it.contains("No strict awarded comparable") })
    }

    @Test
    fun quotedObservationIsNeverComparisonBasis() {
        val request = request()
        val quoted = row(id = 1).copy(status = RateStatus.QUOTED)
        val result = RateComparator.compare(request, listOf(quoted))

        assertNull(result.comparisonBasis)
        assertEquals(RateCheckVerdict.NO_COMPARABLE, result.verdict)
    }

    private fun request(
        material: String? = null,
        dimensions: String? = null,
        scope: String = RateScope.SUPPLY_INSTALL,
        contractorId: Long? = null,
        projectId: Long? = null,
        location: String? = null,
        quantity: Double? = null
    ) = RateCheckRequest(
        itemRaw = "وزرة رخام 15 سم",
        material = material,
        specification = null,
        dimensions = dimensions,
        unit = "lm",
        scope = scope,
        contractorId = contractorId,
        projectId = projectId,
        location = location,
        quantity = quantity,
        proposedRate = 1200.0,
        currency = "EGP"
    )

    private fun row(
        id: Long,
        material: String? = null,
        dimensions: String? = null,
        scope: String = RateScope.SUPPLY_INSTALL,
        contractorId: Long? = null,
        projectId: Long? = null,
        location: String? = null,
        quantity: Double? = null,
        observedDate: Long = 1000L,
        rate: Double = 1000.0
    ) = RateListRow(
        id = id,
        itemRaw = "وزرة رخام 15 سم",
        itemNormalized = normalizeRateItem("وزرة رخام 15 سم"),
        material = material,
        specification = null,
        dimensions = dimensions,
        unit = "lm",
        scope = scope,
        contractorId = contractorId,
        contractorName = contractorId?.let { "Contractor $it" },
        projectId = projectId,
        projectName = projectId?.let { "Project $it" },
        location = location,
        quantity = quantity,
        rate = rate,
        currency = "EGP",
        status = RateStatus.AWARDED,
        observedDate = observedDate,
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
