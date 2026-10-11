package com.edib.procurement.rates

/**
 * Conservative review-time safety check. This is not an item-identity parser.
 * It only blocks a candidate when the unchanged item is demonstrably a suffix
 * fragment of the same evidence row, not the beginning of its description.
 */
object CandidateIdentityGuard {
    fun isTrailingFragment(item: String, evidence: String): Boolean {
        val normalizedItem = item.trim().replace(Regex("\\s+"), " ")
        val normalizedEvidence = evidence.trim().replace(Regex("\\s+"), " ")
        if (normalizedItem.length < 12 || normalizedEvidence.length <= normalizedItem.length + 60) return false
        val position = normalizedEvidence.indexOf(normalizedItem)
        return position > 0 && normalizedEvidence.substring(0, position).any { it.isLetter() }
    }

    fun blocksUnchangedItem(currentItem: String, candidateItem: String, evidence: String): Boolean =
        currentItem.trim().replace(Regex("\\s+"), " ") ==
            candidateItem.trim().replace(Regex("\\s+"), " ") &&
            isTrailingFragment(candidateItem, evidence)
}
