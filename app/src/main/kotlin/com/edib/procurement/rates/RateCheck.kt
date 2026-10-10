package com.edib.procurement.rates

import com.edib.procurement.database.RateListRow
import com.edib.procurement.database.RateScope
import com.edib.procurement.database.RateStatus
import com.edib.procurement.database.normalizeRateItem
import com.edib.procurement.database.normalizeRateUnit
import java.util.Locale
import kotlin.math.abs

/**
 * A proposed commercial rate to check against trusted awarded history.
 *
 * Project/location/quantity are relevance context only. They never become
 * synthetic coefficients and never change the historical rate.
 */
data class RateCheckRequest(
    val itemRaw: String,
    val material: String? = null,
    val specification: String? = null,
    val dimensions: String? = null,
    val unit: String,
    val scope: String,
    val contractorId: Long? = null,
    val projectId: Long? = null,
    val location: String? = null,
    val quantity: Double? = null,
    val proposedRate: Double,
    val currency: String = "EGP"
)

data class RateCheckResult(
    val request: RateCheckRequest,
    /** Latest strict awarded match for the selected contractor, if selected. */
    val latestAwardedForContractor: RateListRow?,
    /** Best strict comparable after contextual ranking, never an inferred rate. */
    val closestComparable: RateListRow?,
    /** The observation used for the delta, contractor history first. */
    val comparisonBasis: RateListRow?,
    val deltaAmount: Double?,
    val deltaPercent: Double?,
    val verdict: RateCheckVerdict,
    val explanation: List<String>
)

enum class RateCheckVerdict {
    NO_COMPARABLE,
    SAME,
    LOWER,
    HIGHER
}

/**
 * Conservative comparator.
 *
 * Comparability gate:
 * - AWARDED only
 * - exact normalized item
 * - exact normalized unit
 * - exact scope
 * - exact currency
 * - exact normalized material/specification/dimensions, including blank vs set
 *
 * Once a candidate passes that gate, project/location/quantity only rank which
 * comparable is more relevant. They never alter, scale or average a rate.
 */
object RateComparator {

    fun compare(
        request: RateCheckRequest,
        candidates: List<RateListRow>
    ): RateCheckResult {
        require(request.itemRaw.isNotBlank()) { "Item description is required" }
        require(request.unit.isNotBlank()) { "Unit is required" }
        require(request.scope in RateScope.all) { "Unknown commercial scope" }
        require(request.proposedRate.isFinite() && request.proposedRate > 0.0) {
            "Proposed rate must be greater than zero"
        }
        require(request.quantity == null || (request.quantity.isFinite() && request.quantity >= 0.0)) {
            "Quantity must be zero or greater"
        }

        val strict = candidates
            .asSequence()
            .filter { it.status == RateStatus.AWARDED }
            .filter { it.itemNormalized == normalizeRateItem(request.itemRaw) }
            .filter { normalizeRateUnit(it.unit) == normalizeRateUnit(request.unit) }
            .filter { it.scope == request.scope }
            .filter { it.currency.trim().uppercase(Locale.ROOT) == request.currency.trim().uppercase(Locale.ROOT) }
            .filter { normalizeContext(it.material) == normalizeContext(request.material) }
            .filter { normalizeContext(it.specification) == normalizeContext(request.specification) }
            .filter { normalizeContext(it.dimensions) == normalizeContext(request.dimensions) }
            .toList()

        val latestForContractor = request.contractorId?.let { contractorId ->
            strict
                .filter { it.contractorId == contractorId }
                .maxWithOrNull(compareBy<RateListRow> { it.observedDate }.thenBy { it.id })
        }

        val closestPool = when {
            request.contractorId != null -> {
                val otherContractors = strict.filter { it.contractorId != request.contractorId }
                if (otherContractors.isNotEmpty()) otherContractors
                else strict.filter { it.id != latestForContractor?.id }
            }
            else -> strict
        }

        val closest = closestPool.minWithOrNull(contextComparator(request))
        val basis = latestForContractor ?: closest

        val deltaAmount = basis?.let { request.proposedRate - it.rate }
        val deltaPercent = basis
            ?.takeIf { it.rate != 0.0 }
            ?.let { (request.proposedRate - it.rate) / it.rate * 100.0 }

        val verdict = when {
            basis == null -> RateCheckVerdict.NO_COMPARABLE
            abs(deltaAmount ?: 0.0) < 0.000001 -> RateCheckVerdict.SAME
            (deltaAmount ?: 0.0) < 0.0 -> RateCheckVerdict.LOWER
            else -> RateCheckVerdict.HIGHER
        }

        val explanation = buildList {
            add("Exact item, material, specification, dimensions, unit, scope and currency only.")
            if (latestForContractor != null) {
                add("Delta uses the latest awarded rate for the selected contractor.")
            } else if (request.contractorId != null && closest != null) {
                add("No strict awarded match exists for the selected contractor; delta uses the closest strict comparable.")
            } else if (closest != null) {
                add("Delta uses the closest strict awarded comparable.")
            } else {
                add("No strict awarded comparable exists. No rate was inferred.")
            }
            if (closest != null) {
                add("Project, location and quantity rank relevance only; no location factor, quantity factor or price adjustment is applied.")
            }
            add("No averaging and no dimensional or scope scaling.")
        }

        return RateCheckResult(
            request = request,
            latestAwardedForContractor = latestForContractor,
            closestComparable = closest,
            comparisonBasis = basis,
            deltaAmount = deltaAmount,
            deltaPercent = deltaPercent,
            verdict = verdict,
            explanation = explanation
        )
    }

    private fun contextComparator(request: RateCheckRequest): Comparator<RateListRow> =
        Comparator { a, b ->
            // Same project first when the proposal identifies a project.
            val projectCompare = scoreSame(request.projectId, a.projectId)
                .compareTo(scoreSame(request.projectId, b.projectId))
            if (projectCompare != 0) return@Comparator -projectCompare

            // Same location next, using conservative normalized text equality.
            val locationCompare = scoreSameText(request.location, a.location)
                .compareTo(scoreSameText(request.location, b.location))
            if (locationCompare != 0) return@Comparator -locationCompare

            // Then quantity proximity when both sides have usable quantities.
            val aDistance = quantityDistance(request.quantity, a.quantity)
            val bDistance = quantityDistance(request.quantity, b.quantity)
            val quantityCompare = aDistance.compareTo(bDistance)
            if (quantityCompare != 0) return@Comparator quantityCompare

            // Finally recency. Newer awarded evidence wins an otherwise equal tie.
            val dateCompare = b.observedDate.compareTo(a.observedDate)
            if (dateCompare != 0) return@Comparator dateCompare

            b.id.compareTo(a.id)
        }

    private fun scoreSame(requested: Long?, actual: Long?): Int =
        if (requested != null && requested == actual) 1 else 0

    private fun scoreSameText(requested: String?, actual: String?): Int {
        val r = normalizeContext(requested)
        return if (r.isNotEmpty() && r == normalizeContext(actual)) 1 else 0
    }

    private fun quantityDistance(requested: Double?, actual: Double?): Double {
        if (requested == null || actual == null) return Double.POSITIVE_INFINITY
        if (!requested.isFinite() || !actual.isFinite()) return Double.POSITIVE_INFINITY
        return abs(requested - actual)
    }

    private fun normalizeContext(value: String?): String =
        value.orEmpty()
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
}
