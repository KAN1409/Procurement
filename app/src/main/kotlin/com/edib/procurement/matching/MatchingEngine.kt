package com.edib.procurement.matching

import com.edib.procurement.database.DocumentDao
import com.edib.procurement.database.DocumentEntity
import com.edib.procurement.database.DocumentLinkEntity
import com.edib.procurement.database.FollowUpDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the matcher over the indexed documents and writes the results.
 *
 * Preservation rules, applied on every run:
 *
 *  - a MANUAL link is never touched, removed or downgraded;
 *  - an existing AUTO link is replaced only when the new result is also AUTO
 *    and points somewhere else;
 *  - a document that becomes UNLINKED keeps no automatic row at all, rather
 *    than a stale one.
 *
 * Nothing here destroys user data: every delete is guarded by `isManual = 0`.
 */
class MatchingEngine(
    private val followUpDao: FollowUpDao,
    private val documentDao: DocumentDao
) {

    suspend fun run(): MatchingReport = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()

        val groupRows = followUpDao.allGroupsWithRootCode()
        val entryRows = followUpDao.allEntryMatchRows()
        val matcher = DocumentMatcher.build(
            groupRows,
            entryRows,
            followUpDao.historicalPrPaths(),
            followUpDao.historicalOrderPaths()
        )

        val documents = followUpDao.allActiveDocuments()
        var auto = 0
        var suggested = 0
        var unlinked = 0
        var conflicts = 0
        var manualPreserved = 0

        for (doc: DocumentEntity in documents) {
            val existing = documentDao.linksForDocument(doc.id)

            // Manual links are authoritative. Count and skip.
            val manual = existing.filter { it.isManual || it.isLocked }
            if (manual.isNotEmpty()) {
                manualPreserved += manual.size
                // Remove only the automatic leftovers for this document, so the
                // manual relationship stands alone and nothing stale remains.
                documentDao.deleteAutomaticLinksFor(doc.id)
                continue
            }

            val result = matcher.match(matcher.from(doc))

            // Clear the previous automatic rows before writing the new decision.
            documentDao.deleteAutomaticLinksFor(doc.id)

            when (result.status) {
                LinkStatus.AUTO -> {
                    result.chosen?.let { write(it, LinkStatus.AUTO, doc.id) }
                    auto++
                }

                LinkStatus.SUGGESTED -> {
                    result.chosen?.let { write(it, LinkStatus.SUGGESTED, doc.id) }
                    // Persist the competing candidates too, so Needs Review can
                    // show "choose another" without re-running the matcher.
                    result.alternatives.take(5).forEach { write(it, LinkStatus.SUGGESTED, doc.id) }
                    if (result.isConflict) conflicts++
                    suggested++
                }

                LinkStatus.UNLINKED, LinkStatus.MANUAL -> unlinked++
            }
        }

        MatchingReport(
            documentsExamined = documents.size,
            autoLinked = auto,
            suggested = suggested,
            unlinked = unlinked,
            conflicts = conflicts,
            manualPreserved = manualPreserved,
            elapsedMs = System.currentTimeMillis() - start
        )
    }

    private suspend fun write(candidate: MatchCandidate, status: LinkStatus, documentId: Long) {
        val evidence = candidate.explanation()
        documentDao.insertLink(
            DocumentLinkEntity(
                documentId = documentId,
                groupId = candidate.groupId,
                entryId = candidate.entryId,
                linkType = linkTypeFor(candidate),
                matchMethod = methodFor(candidate),
                // A percentage is derived from the evidence for display, but the
                // status column is what actually decides behaviour.
                confidence = confidenceFor(candidate, status),
                evidence = evidence,
                isManual = false,
                isLocked = false,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private fun linkTypeFor(candidate: MatchCandidate): String =
        if (candidate.level == TargetLevel.ENTRY) "ORDER" else "RELATED"

    private fun methodFor(candidate: MatchCandidate): String = when {
        Evidence.EXACT_HISTORICAL_FILENAME in candidate.evidence -> "EXACT_FILENAME"
        Evidence.EXACT_ENTRY_CODE in candidate.evidence -> "EXACT_CODE"
        Evidence.GROUP_CODE in candidate.evidence -> "GROUP_CODE"
        Evidence.PROJECT_PATH in candidate.evidence -> "PROJECT_PATH"
        Evidence.CONTRACTOR_NAME in candidate.evidence -> "CONTRACTOR"
        else -> "DESCRIPTION"
    }

    private fun confidenceFor(candidate: MatchCandidate, status: LinkStatus): Int {
        if (status == LinkStatus.MANUAL) return 100
        // Derived from the strongest evidence present, never the other way
        // round: the evidence decides the status, the number only reports it.
        val best = candidate.evidence.maxOfOrNull { it.weight } ?: 0
        val bonus = if (candidate.evidence.size > 1) 3 else 0
        return when (status) {
            LinkStatus.AUTO -> (best + bonus).coerceIn(95, 100)
            LinkStatus.SUGGESTED -> (best / 2 + bonus).coerceIn(60, 90)
            else -> 0
        }
    }
}
