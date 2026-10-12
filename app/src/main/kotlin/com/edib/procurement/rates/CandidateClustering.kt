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

    /** Read-only evidence relationships, never a claim of independent offers. */
    data class EvidenceSummary(val repeatedEvidenceGroups: Int, val matchingValueGroups: Int, val unknownSources: Int)

    fun evidenceSummary(rows: List<RateCandidateRow>): EvidenceSummary {
        val pending = rows.filter { it.status == "PENDING" }
        fun source(r: RateCandidateRow): String? =
            r.sourceDocumentId?.let { "id:$it" }
                ?: r.sourceUri.takeIf { it.isNotBlank() }?.let { "uri:$it" }
                ?: r.sourceRelativePath.takeIf { it.isNotBlank() }?.let { "path:$it" }
        val evidence = pending.mapNotNull { r ->
            val s = source(r) ?: return@mapNotNull null
            if (r.evidenceLocator.isBlank() || r.evidenceText.isBlank()) return@mapNotNull null
            Triple(s, r.evidenceLocator.trim(), r.evidenceText.trim())
        }
        val values = pending.groupBy { r ->
            listOf(r.itemRaw.trim(), r.unitRaw.orEmpty().trim(), r.quantity.toString(),
                r.rate.toString(), r.currency.orEmpty().trim(), r.inferredScope.orEmpty().trim())
        }
        return EvidenceSummary(
            evidence.groupingBy { it }.eachCount().count { it.value > 1 },
            values.count { it.value.size > 1 },
            pending.count { source(it) == null }
        )
    }

    /** Counts distinct extracted numeric rates, not independent offers or approved prices. */
    fun rateVariantCount(rows: List<RateCandidateRow>): Int = rows.map { it.rate }.distinct().size

    /** Search results expose matching evidence without a second tap. */
    fun showSources(query: String, manuallyExpanded: Boolean): Boolean =
        query.isNotBlank() || manuallyExpanded

    fun group(candidates: List<RateCandidateRow>, sort: String = "CONFIDENCE"): List<Group> =
        candidates.asSequence()
            .filter { it.status == "PENDING" }
            .groupBy { key(it.itemRaw, it.unitRaw, it.inferredScope, it.currency) }
            .map { (key, rows) ->
                Group(key, if (sort == "CONFIDENCE") {
                    rows.sortedWith(compareByDescending<RateCandidateRow> { it.confidence }.thenBy { it.id })
                } else {
                    // DAO already ordered the rows. Do not undo its rate/source ordering.
                    rows
                })
            }
            .let { groups ->
                if (sort == "CONFIDENCE") {
                    groups.sortedWith(compareByDescending<Group> { it.candidates.size }.thenBy { it.key.item }.thenBy { it.key.unit }.thenBy { it.key.scope }.thenBy { it.key.currency })
                } else {
                    // LinkedHashMap from groupBy retains the first occurrence of each
                    // group in the DAO result. A group's position reflects its first
                    // sorted candidate; its members retain DAO ordering.
                    groups
                }
            }
}
