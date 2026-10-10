package com.edib.procurement.rates

import com.edib.procurement.database.RateListRow
import kotlin.math.abs

/**
 * Broad commercial-memory ranking.
 *
 * Unit is intentionally not a filter. A target price only changes ordering
 * (and optionally currency compatibility); it never converts one unit to another.
 */
fun rankRelatedRates(
    rows: List<RateListRow>,
    targetRate: Double?,
    targetCurrency: String,
    status: String?,
    scope: String?
): List<RateListRow> {
    val currency = targetCurrency.trim().uppercase()
    val filtered = rows.asSequence()
        .filter { status == null || it.status == status }
        .filter { scope == null || it.scope == scope }
        .filter {
            targetRate == null ||
                currency.isBlank() ||
                it.currency.trim().uppercase() == currency
        }
        .toList()

    return if (targetRate != null) {
        filtered.sortedWith(
            compareBy<RateListRow> { abs(it.rate - targetRate) }
                .thenByDescending { it.observedDate }
                .thenByDescending { it.id }
        )
    } else {
        filtered.sortedWith(
            compareByDescending<RateListRow> { it.observedDate }
                .thenByDescending { it.id }
        )
    }
}
