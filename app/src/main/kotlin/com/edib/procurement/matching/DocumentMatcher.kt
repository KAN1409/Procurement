package com.edib.procurement.matching

import com.edib.procurement.database.DocumentEntity
import com.edib.procurement.database.EntryMatchRow
import com.edib.procurement.database.GroupMatchRow
import com.edib.procurement.database.HistoricalPathRow

/**
 * Deterministic document matcher.
 *
 * Design rules, in priority order:
 *
 *  1. **Conservative.** A wrong automatic link is worse than an unlinked
 *     document, so every AUTO decision traces to an explicit strong rule, not to
 *     a score threshold.
 *  2. **Explainable.** Each candidate carries the set of evidence that produced
 *     it; the UI shows those flags, not a bare percentage.
 *  3. **No semantics.** Project-path, contractor and description evidence are
 *     recorded but can never, on their own, produce an AUTO link.
 *
 * AUTO requires one of:
 *   - a unique exact historical file-name match, or
 *   - a unique exact entry-code match with compatible group context, or
 *   - several independent strong signals converging on exactly one entry with
 *     no competing candidate.
 *
 * Everything else is SUGGESTED, or UNLINKED.
 */
class DocumentMatcher(
    private val groups: List<GroupMatchRow>,
    private val entriesById: Map<Long, EntryMatchRow>,
    /** Loose match key -> entries whose code folds to that key. */
    private val entriesByCodeKey: Map<String, List<EntryMatchRow>>,
    /** Normalized historical file name -> the entries that reference it. */
    private val historicalByFileKey: Map<String, List<Long>>,
    /** Normalized group root code -> groups carrying that root. */
    private val groupsByRootKey: Map<String, List<GroupMatchRow>>
) {

    /** A document reduced to the text the matcher reasons about. */
    data class Doc(
        val id: Long,
        val name: String,
        val relativePath: String,
        val fileKey: String,
        val stemKey: String
    )

    /**
     * The document stem folded the same way an entry code is folded.
     *
     * File-name keys are lower-cased and code keys are upper-cased, so both
     * sides go through [MatchNormalizer.codeKeyLoose] before being compared.
     */
    private fun looseStem(stemKey: String): String =
        MatchNormalizer.codeKeyLoose(stemKey)

    /**
     * Builds the lookup indexes for a matching run.
     *
     * All the work happens here, once: every document is then matched against
     * hash maps rather than against the whole entry table, which is what keeps
     * a large repository from degrading into O(documents x entries).
     */
    companion object {
        fun build(
            groupRows: List<GroupMatchRow>,
            entryRows: List<EntryMatchRow>,
            historicalPr: List<HistoricalPathRow>,
            historicalOrder: List<HistoricalPathRow>
        ): DocumentMatcher {
            val byId = entryRows.associateBy { it.entryId }
            val byCodeKey = entryRows
                .filter { !it.matchCodeKey.isNullOrBlank() }
                .groupBy { MatchNormalizer.codeKeyLoose(it.matchCodeKey) }

            val byFile = HashMap<String, MutableList<Long>>()
            fun addHistorical(rows: List<HistoricalPathRow>) {
                rows.forEach { row ->
                    val key = MatchNormalizer.filenameKey(PathBasename.of(row.path))
                    if (key.isEmpty()) return@forEach
                    byFile.getOrPut(key) { ArrayList() }.add(row.entryId)
                }
            }
            addHistorical(historicalPr)
            addHistorical(historicalOrder)

            val groupsByRoot = groupRows
                .filter { !it.rootCodeNormalized.isNullOrBlank() }
                .groupBy { MatchNormalizer.codeKeyLoose(it.rootCodeNormalized) }

            return DocumentMatcher(groupRows, byId, byCodeKey, byFile, groupsByRoot)
        }
    }

    fun from(doc: DocumentEntity): Doc = Doc(
        id = doc.id,
        name = doc.displayName,
        relativePath = doc.relativePath,
        fileKey = doc.matchFileKey,
        stemKey = doc.matchStemKey
    )

    // ------------------------------------------------------------- matching

    fun match(doc: Doc): DocumentMatchResult {
        val candidates = LinkedHashMap<Long, MatchCandidate>()

        // ---- Evidence A: exact historical file name ---------------------
        historicalCandidates(doc).forEach { candidates.mergeCandidate(it) }

        // ---- Evidence B: entry code ------------------------------------
        codeCandidates(doc).forEach { candidates.mergeCandidate(it) }

        // ---- Evidence C: group code ------------------------------------
        groupCandidates(doc).forEach { candidates.mergeCandidate(it) }

        // ---- Supporting evidence only ----------------------------------
        if (candidates.isEmpty()) {
            // Nothing strong at all. Weak signals are still reported when they
            // point somewhere, but never as an automatic link.
            val weak = weakCandidates(doc)
            if (weak.isEmpty()) {
                return DocumentMatchResult(doc.id, LinkStatus.UNLINKED, reason = "no candidate")
            }
            return DocumentMatchResult(
                documentId = doc.id,
                status = LinkStatus.SUGGESTED,
                chosen = weak.first(),
                alternatives = weak.drop(1),
                reason = "weak evidence only"
            )
        }

        return decide(doc, candidates.values.toList())
    }

    /**
     * Applies the strong rules to the surviving candidates.
     *
     * Kept separate so the decision table can be unit-tested without a database.
     */
    internal fun decide(doc: Doc, candidates: List<MatchCandidate>): DocumentMatchResult {
        // --- A: unique exact historical file name -----------------------
        val historical = candidates.filter { Evidence.EXACT_HISTORICAL_FILENAME in it.evidence }
        if (historical.isNotEmpty()) {
            val distinctTargets = historical.distinctBy { it.entryId ?: it.groupId }
            if (distinctTargets.size == 1) {
                return DocumentMatchResult(
                    documentId = doc.id,
                    status = LinkStatus.AUTO,
                    chosen = historical.first(),
                    reason = "unique exact historical file name"
                )
            }
            // The same name appears in more than one entry: ambiguous.
            return DocumentMatchResult(
                documentId = doc.id,
                status = LinkStatus.SUGGESTED,
                chosen = historical.first(),
                alternatives = historical.drop(1),
                reason = "historical file name matches ${distinctTargets.size} entries"
            )
        }

        // --- B: unique exact entry code ---------------------------------
        val exactCode = candidates.filter {
            Evidence.EXACT_ENTRY_CODE in it.evidence && it.level == TargetLevel.ENTRY
        }
        if (exactCode.isNotEmpty()) {
            val distinct = exactCode.distinctBy { it.entryId }
            if (distinct.size == 1) {
                return DocumentMatchResult(
                    documentId = doc.id,
                    status = LinkStatus.AUTO,
                    chosen = distinct.first(),
                    reason = "unique exact entry code"
                )
            }
            return DocumentMatchResult(
                documentId = doc.id,
                status = LinkStatus.SUGGESTED,
                chosen = distinct.first(),
                alternatives = distinct.drop(1),
                reason = "entry code '${distinct.first().entryCode}' occurs in ${distinct.size} entries"
            )
        }

        // --- C: group-level only ----------------------------------------
        val groupOnly = candidates.filter { it.level == TargetLevel.GROUP }
        val distinctGroups = groupOnly.distinctBy { it.groupId }
        if (distinctGroups.size == 1 && groupOnly.isNotEmpty()) {
            val only = groupOnly.first()
            val hasPathSupport = Evidence.PROJECT_PATH in only.evidence
            return DocumentMatchResult(
                documentId = doc.id,
                status = if (hasPathSupport) LinkStatus.SUGGESTED else LinkStatus.SUGGESTED,
                chosen = only,
                reason = if (hasPathSupport) "group code with project folder"
                else "group code only"
            )
        }

        // --- Weak: nothing strong enough --------------------------------
        val weak = candidates.filter { c ->
            c.evidence.none {
                it == Evidence.EXACT_HISTORICAL_FILENAME ||
                    it == Evidence.EXACT_ENTRY_CODE ||
                    it == Evidence.GROUP_CODE
            }
        }
        if (weak.isNotEmpty()) {
            return DocumentMatchResult(
                documentId = doc.id,
                status = LinkStatus.SUGGESTED,
                chosen = weak.first(),
                alternatives = weak.drop(1),
                reason = "supporting evidence only"
            )
        }

        return DocumentMatchResult(
            documentId = doc.id,
            status = LinkStatus.UNLINKED,
            reason = "no usable candidate"
        )
    }

    // ----------------------------------------------------------- evidence

    private fun historicalCandidates(doc: Doc): List<MatchCandidate> {
        val entryIds = historicalByFileKey[doc.fileKey] ?: return emptyList()
        // The same entry can appear in both the PR and order lists.
        return entryIds.distinct().mapNotNull { entryId ->
            val entry = entriesById[entryId] ?: return@mapNotNull null
            candidateFor(doc, entry, setOf(Evidence.EXACT_HISTORICAL_FILENAME))
        }
    }

    private fun codeCandidates(doc: Doc): List<MatchCandidate> {
        val key = looseStem(doc.stemKey)
        if (key.isEmpty()) return emptyList()
        val rows = entriesByCodeKey[key] ?: return emptyList()
        return rows.mapNotNull { row ->
            val evidence = buildSet {
                add(Evidence.EXACT_ENTRY_CODE)
                addAll(supportingEvidence(doc, row))
            }
            candidateFor(doc, row, evidence)
        }
    }

    private fun groupCandidates(doc: Doc): List<MatchCandidate> {
        val key = looseStem(doc.stemKey)
        if (key.isEmpty()) return emptyList()
        val groups = groupsByRootKey[key] ?: return emptyList()
        return groups.map { g ->
            MatchCandidate(
                documentId = doc.id,
                documentName = doc.name,
                documentRelativePath = doc.relativePath,
                groupId = g.groupId,
                groupRootCode = g.rootCodeRaw,
                projectRaw = g.projectRaw,
                projectNormalized = com.edib.procurement.followup.TextNormalizer
                    .normalize(g.projectRaw),
                level = TargetLevel.GROUP,
                evidence = buildSet {
                    add(Evidence.GROUP_CODE)
                    if (projectPathSupports(doc, g)) add(Evidence.PROJECT_PATH)
                }
            )
        }
    }

    /**
     * Candidates derived only from supporting signals.
     *
     * Used when nothing strong matched at all, so the review queue can still
     * offer something plausible without ever auto-linking.
     */
    private fun weakCandidates(doc: Doc): List<MatchCandidate> {
        val out = ArrayList<MatchCandidate>()
        val projectKey = projectFromPath(doc.relativePath) ?: return out

        groups.filter { it.projectRaw != null }
            .filter {
                com.edib.procurement.followup.TextNormalizer.normalize(it.projectRaw)
                    .contains(projectKey)
            }
            .take(3)
            .forEach { g ->
                out.add(
                    MatchCandidate(
                        documentId = doc.id,
                        documentName = doc.name,
                        documentRelativePath = doc.relativePath,
                        groupId = g.groupId,
                        groupRootCode = g.rootCodeRaw,
                        projectRaw = g.projectRaw,
                        projectNormalized = com.edib.procurement.followup.TextNormalizer
                            .normalize(g.projectRaw),
                        level = TargetLevel.GROUP,
                        evidence = setOf(Evidence.PROJECT_PATH)
                    )
                )
            }
        return out
    }

    /** Non-decisive signals that enrich an already-strong candidate. */
    private fun supportingEvidence(doc: Doc, entry: EntryMatchRow): Set<Evidence> {
        val out = LinkedHashSet<Evidence>()
        val group = groups.firstOrNull { it.groupId == entry.groupId }
        if (group != null && projectPathSupports(doc, group)) out.add(Evidence.PROJECT_PATH)
        entry.contractorNormalized
            ?.takeIf { it.isNotBlank() }
            ?.let { if (containsContractorToken(doc.name, it)) out.add(Evidence.CONTRACTOR_NAME) }
        entry.description
            ?.takeIf { it.isNotBlank() }
            ?.let { if (sharesDescriptionToken(doc.name, it)) out.add(Evidence.DESCRIPTION_TOKENS) }
        val years = MatchNormalizer.years(doc.relativePath)
        if (years.isNotEmpty()) {
            val codeYears = MatchNormalizer.years(entry.codeRaw)
            if (codeYears.any { it in years }) out.add(Evidence.YEAR_CONTEXT)
        }
        return out
    }

    private fun candidateFor(
        doc: Doc,
        entry: EntryMatchRow,
        evidence: Set<Evidence>
    ): MatchCandidate? {
        val group = groups.firstOrNull { it.groupId == entry.groupId } ?: return null
        return MatchCandidate(
            documentId = doc.id,
            documentName = doc.name,
            documentRelativePath = doc.relativePath,
            groupId = entry.groupId,
            groupRootCode = group.rootCodeRaw,
            projectRaw = group.projectRaw,
            projectNormalized = com.edib.procurement.followup.TextNormalizer
                .normalize(group.projectRaw),
            entryId = entry.entryId,
            entryCode = entry.codeRaw,
            entryCodeNormalized = entry.matchCodeKey,
            contractorNormalized = entry.contractorNormalized,
            level = TargetLevel.ENTRY,
            evidence = evidence
        )
    }

    private fun projectPathSupports(doc: Doc, group: GroupMatchRow): Boolean {
        val folder = projectFromPath(doc.relativePath) ?: return false
        val project = com.edib.procurement.followup.TextNormalizer.normalize(group.projectRaw)
        if (project.isEmpty()) return false
        return folder == project || project.contains(folder) || folder.contains(project)
    }

    /** The most specific folder name in the document's path. */
    private fun projectFromPath(path: String): String? {
        val segments = PathBasename.segments(path)
        if (segments.isEmpty()) return null
        val last = segments.last()
        return com.edib.procurement.followup.TextNormalizer.normalize(last)
            .takeIf { it.isNotEmpty() }
    }

    private fun containsContractorToken(name: String, contractorNormalized: String): Boolean {
        val n = com.edib.procurement.followup.TextNormalizer.normalize(name)
        val c = contractorNormalized
        if (c.length < 4) return false
        return n.contains(c) || c.contains(n.filter { it.isLetterOrDigit() }.take(12))
    }

    private fun sharesDescriptionToken(name: String, description: String): Boolean {
        val tokens = description.split(' ', '-', '/')
            .filter { it.length >= 5 }
            .map { com.edib.procurement.followup.TextNormalizer.normalize(it) }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        val n = com.edib.procurement.followup.TextNormalizer.normalize(name)
        return tokens.any { n.contains(it) }
    }

    /** Merges evidence for a candidate that was reached twice. */
    private fun LinkedHashMap<Long, MatchCandidate>.mergeCandidate(candidate: MatchCandidate) {
        val key = candidate.entryId ?: -candidate.groupId
        val existing = get(key)
        if (existing == null) put(key, candidate)
        else put(key, existing.copy(evidence = existing.evidence + candidate.evidence))
    }
}

/** Path helpers used by the matcher, kept free of Android dependencies. */
object PathBasename {

    fun of(path: String?): String {
        if (path.isNullOrBlank()) return ""
        val last = path.trim().replace('\\', '/').substringAfterLast('/')
        return last.trim()
    }

    /** Directory segments of a relative path, excluding the file name. */
    fun segments(relativePath: String?): List<String> {
        if (relativePath.isNullOrBlank()) return emptyList()
        return relativePath.trim().replace('\\', '/').split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .dropLast(1)
    }
}
