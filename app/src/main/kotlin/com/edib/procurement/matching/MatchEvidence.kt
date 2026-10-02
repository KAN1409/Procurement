package com.edib.procurement.matching

/**
 * What kind of link was produced.
 *
 * The ordering is the decision, not a score: [AUTO] is only ever assigned when
 * one of the explicit strong rules in [DocumentMatcher] holds.
 */
enum class LinkStatus {
    /** A strong rule matched and no competing candidate exists. */
    AUTO,

    /** The user made this link. Always wins over anything automatic. */
    MANUAL,

    /** Evidence is real but ambiguous or incomplete. Needs review. */
    SUGGESTED,

    /** No candidate worth showing. */
    UNLINKED
}

/**
 * One independent observation about a document/candidate pairing.
 *
 * Evidence is recorded rather than folded into an opaque number, because a
 * wrong automatic link has to be explainable and correctable. A confidence
 * percentage is derived from these flags for display only; it is never the
 * thing that drives the decision.
 */
enum class Evidence(val code: String, val label: String, val weight: Int) {
    /** The document's file name equals the historical PR/order file name. */
    EXACT_HISTORICAL_FILENAME("EXACT_FILENAME", "exact historical file name", 100),

    /** The document's file stem equals the entry's code. */
    EXACT_ENTRY_CODE("EXACT_CODE", "exact entry code in file name", 90),

    /** The document's file stem equals a group's root code, but no entry. */
    GROUP_CODE("GROUP_CODE", "group root code in file name", 70),

    /** The document sits under a folder named after the project. */
    PROJECT_PATH("PROJECT_PATH", "document is in the project folder", 40),

    /** A known contractor name or alias appears in the file name. */
    CONTRACTOR_NAME("CONTRACTOR_NAME", "contractor name in file name", 20),

    /** Weak shared tokens between the description and the file name. */
    DESCRIPTION_TOKENS("DESCRIPTION", "description token overlap", 10),

    /** The document lives in a folder whose year agrees with the entry. */
    YEAR_CONTEXT("YEAR", "matching year in the path", 15)
}

/** The strongest kind of target a candidate points at. */
enum class TargetLevel { ENTRY, GROUP }

/**
 * A possible link between one indexed document and one follow-up record.
 *
 * A candidate is never persisted on its own: it becomes a `document_link` only
 * after [DocumentMatcher] decides its status.
 */
data class MatchCandidate(
    val documentId: Long,
    val documentName: String,
    val documentRelativePath: String,

    val groupId: Long,
    val groupRootCode: String?,
    val projectRaw: String?,
    val projectNormalized: String,

    val entryId: Long? = null,
    val entryCode: String? = null,
    val entryCodeNormalized: String? = null,

    val contractorNormalized: String? = null,

    val level: TargetLevel,
    val evidence: Set<Evidence>
) {
    /** Human-readable justification, shown verbatim in the UI. */
    fun explanation(): String =
        evidence.sortedByDescending { it.weight }.joinToString("\n") { "✓ ${it.label}" }
            .ifEmpty { "no strong evidence" }
}

/**
 * The outcome of matching one document.
 *
 * Exactly one of [chosen] / [suggested] / nothing is populated, and [conflicts]
 * carries the alternatives whenever more than one candidate satisfied the same
 * strong rule.
 */
data class DocumentMatchResult(
    val documentId: Long,
    val status: LinkStatus,
    val chosen: MatchCandidate? = null,
    val alternatives: List<MatchCandidate> = emptyList(),
    /** Why the status is what it is, in one line. */
    val reason: String
) {
    val isConflict: Boolean get() = alternatives.isNotEmpty() && status != LinkStatus.AUTO
}

/** Aggregate counters for the run summary. */
data class MatchingReport(
    val documentsExamined: Int,
    val autoLinked: Int,
    val suggested: Int,
    val unlinked: Int,
    val conflicts: Int,
    val manualPreserved: Int,
    val elapsedMs: Long
)
