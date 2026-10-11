package com.edib.procurement.rates

import com.edib.procurement.database.RateCandidateRow
import java.util.Locale

/**
 * Presentation-only grouping of unreviewed observations.
 * Never approves rates, changes quantities, or writes to the database.
 */
object CandidateClustering {
    data class GroupKey(val item: String, val unit: String, val scope: String, val currency: String)
    data class Group(val key: GroupKey, val candidates: List<RateCandidateRow>)

    fun key(item: String, unit: String?, scope: String?, currency: String?): GroupKey {
        val normalizedItem = item.lowercase(Locale.ROOT)
            .replace(Regex("[\\p{Punct}،؛]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val normalizedUnit = when (unit?.trim()?.lowercase(Locale.ROOT)) {
            "م2", "م٢", "م²", "متر مربع", "بالمتر المسطح", "m2", "sqm", "sq.m" -> "m2"
            "م3", "م٣", "م³", "m3", "cum" -> "m3"
            "م", "متر", "lm", "linear meter" -> "lm"
            "عدد", "بالعدد", "no", "nos", "pcs", "piece" -> "each"
            else -> unit?.trim()?.lowercase(Locale.ROOT).orEmpty()
        }
        return GroupKey(normalizedItem, normalizedUnit, scope.orEmpty(), currency.orEmpty().uppercase(Locale.ROOT))
    }

    /** Counts distinct extracted numeric rates, not independent offers or approved prices. */
    fun rateVariantCount(rows: List<RateCandidateRow>): Int = rows.map { it.rate }.distinct().size

    /** Search results expose matching evidence without a second tap. */
    fun showSources(query: String, manuallyExpanded: Boolean): Boolean =
        query.isNotBlank() || manuallyExpanded

    fun group(candidates: List<RateCandidateRow>): List<Group> =
        candidates.asSequence()
            .filter { it.status == "PENDING" }
            .groupBy { key(it.itemRaw, it.unitRaw, it.inferredScope, it.currency) }
            .map { (key, rows) -> Group(key, rows.sortedByDescending { it.confidence }) }
            .sortedWith(compareByDescending<Group> { it.candidates.size }.thenBy { it.key.item })
}
