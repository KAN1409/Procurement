package com.edib.procurement.followup

/**
 * One row of the SUPPLIER LIST sheet: a raw alias and the canonical name it
 * should resolve to.
 */
data class SupplierAlias(
    val aliasRaw: String,
    val canonicalRaw: String,
    /** Optional free text from the sheet's adjustment column, e.g. "adjusted". */
    val note: String? = null
)

/** Outcome of resolving a contractor cell. */
sealed class ContractorResolution {
    /** A known alias matched. */
    data class Resolved(
        val contractorRaw: String,
        val aliasRaw: String,
        val matchKind: MatchKind
    ) : ContractorResolution()

    /**
     * Nothing matched. The complete original cell text is kept so it can be
     * shown to the user and mapped by hand; the original text is never deleted.
     */
    data class Unresolved(val contractorRaw: String) : ContractorResolution()

    /** The cell was blank. */
    object Blank : ContractorResolution()
}

enum class MatchKind {
    EXACT,
    /** Alias differed only in whitespace/unicode/case. */
    NORMALIZED,
    /** Alias matched the start of the cell and the rest was operational notes. */
    LONGEST_PREFIX
}

/**
 * Resolves the workbook's `NEW Company` cell to a canonical contractor.
 *
 * The cell frequently carries operational notes appended to the name
 * ("الالفى للموبيليا- تم تغييره الى شركة زون ديزاين", "… - رجع من الحسابات الينا
 * موافقة بتاريخ 22 مايو"), so an exact whole-cell match is not required.
 *
 * Resolution order:
 *  1. exact alias match on the trimmed raw text
 *  2. exact match on the fully normalised text
 *  3. longest valid prefix match against known aliases
 *
 * The longest-prefix step is what stops a short alias from swallowing a longer
 * different one: among all aliases that the cell starts with, the longest wins.
 */
class SupplierNormalizer(aliases: List<SupplierAlias>) {

    /** normalised alias -> canonical raw name. */
    private val byNormalized: MutableMap<String, String> = LinkedHashMap()

    /** exact (trimmed) alias -> canonical raw name, wins over normalised. */
    private val byExact: MutableMap<String, String> = LinkedHashMap()

    init {
        for (a in aliases) {
            val canon = a.canonicalRaw.trim()
            if (canon.isEmpty()) continue
            val aliasTrim = a.aliasRaw.trim()
            if (aliasTrim.isEmpty()) continue
            val aliasNorm = TextNormalizer.normalize(aliasTrim)
            // First definition wins: the SUPPLIER LIST is ordered and earlier
            // rows are the primary spelling.
            if (!byExact.containsKey(aliasTrim)) byExact[aliasTrim] = canon
            if (aliasNorm.isNotEmpty() && !byNormalized.containsKey(aliasNorm)) {
                byNormalized[aliasNorm] = canon
            }
        }
    }

    val aliasCount: Int get() = byNormalized.size

    /** Sorted by length descending so the first prefix hit is the longest. */
    private val prefixes: List<Pair<String, String>> = byNormalized.entries
        .map { it.key to it.value }
        .sortedByDescending { it.first.length }

    fun resolve(rawCompany: String?): ContractorResolution {
        val raw = rawCompany?.trim() ?: return ContractorResolution.Blank
        if (raw.isEmpty()) return ContractorResolution.Blank

        byExact[raw]?.let { return ContractorResolution.Resolved(it, raw, MatchKind.EXACT) }

        val normalized = TextNormalizer.normalize(raw)
        if (normalized.isEmpty()) return ContractorResolution.Unresolved(raw)

        byNormalized[normalized]?.let {
            return ContractorResolution.Resolved(it, raw, MatchKind.NORMALIZED)
        }

        for ((aliasNorm, canon) in prefixes) {
            if (normalized.length > aliasNorm.length && normalized.startsWith(aliasNorm)) {
                // The remainder must be a real note separator, otherwise a cell
                // that merely begins with a similar name would match wrongly.
                val rawTail = tailAfterAlias(raw, aliasNorm)
                if (rawTail != null) {
                    return ContractorResolution.Resolved(canon, raw, MatchKind.LONGEST_PREFIX)
                }
            }
        }

        return ContractorResolution.Unresolved(raw)
    }

/**
 * Locates the remainder of the raw cell after the alias matched in its
 * normalised form.
 *
 * A note must be introduced by a *hard* separator (hyphen, dash, colon, slash,
 * bracket) once surrounding spaces are ignored. A bare space is not enough:
 * "الالفى" is a generic stem, so "الالفى زون ديزاين" is a genuinely different
 * company rather than that alias plus a note. Requiring punctuation keeps a
 * short generic alias from swallowing an unrelated name while still matching
 * the workbook's "- رجع من الحسابات …" style.
 */
private fun tailAfterAlias(raw: String, aliasNormalized: String): String? {
        val rawNorm = TextNormalizer.normalize(raw)
        if (!rawNorm.startsWith(aliasNormalized)) return null
        val tail = rawNorm.substring(aliasNormalized.length).trimStart()
        if (tail.isEmpty()) return ""
        val first = tail.first()
        val isHardSeparator = first == '-' || first == '–' || first == '—' ||
                first == ':' || first == '/' || first == '\\' || first == '|' ||
                first == '(' || first == '،'
        if (!isHardSeparator) return null
        return tail.trim()
    }
}
