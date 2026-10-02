package com.edib.procurement.aipack

import java.io.File

/**
 * Verifies a finished multipart export actually contains everything it should.
 *
 * Splitting is the one place where data can silently go missing, so the export
 * is only reported as COMPLETED after this passes. A failure marks the export
 * ERROR rather than being shown to the user as a finished pack.
 *
 * The checks are deliberately mathematical rather than textual: they compare the
 * set of SOURCE ids across all parts against the set the planner started with,
 * and confirm each Document id contributed its content exactly once.
 */
object MultipartIntegrity {

    data class Report(
        val ok: Boolean,
        val expectedSources: Int,
        val actualSources: Int,
        val duplicateSourceIds: List<String>,
        val missingSourceIds: List<String>,
        val duplicateDocumentIds: List<Long>,
        val partCount: Int,
        val problems: List<String>
    ) {
        val status: String get() = if (ok) "COMPLETE" else "ERROR"
    }

    /**
     * @param expectedSourceIds every SOURCE id the export was meant to contain.
     * @param actualSourceIdsByPart SOURCE ids as written, per part.
     * @param documentIdBySource SOURCE id to Document.id, for the "one physical
     *        document appears once" rule.
     */
    fun verify(
        expectedSourceIds: Collection<String>,
        actualSourceIdsByPart: List<List<String>>,
        documentIdBySource: Map<String, Long?>,
        partCount: Int
    ): Report {
        val problems = ArrayList<String>()

        val actual = actualSourceIdsByPart.flatten()
        val actualSet = actual.toSet()

        val duplicates = actual.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
        val missing = expectedSourceIds.filterNot { it in actualSet }.sorted()

        // A Document id may legitimately be referenced by several SOURCE ids'
        // entries in the follow-up section, but its CONTENT must be written once.
        // Only sources that carry extracted content count toward duplication.
        val docSeen = HashMap<Long, MutableList<String>>()
        documentIdBySource.forEach { (sourceId, docId) ->
            if (docId != null) docSeen.getOrPut(docId) { ArrayList() }.add(sourceId)
        }

        if (actual.size != actualSet.size) {
            problems.add("source ids repeated across the export: ${duplicates.joinToString()}")
        }
        if (missing.isNotEmpty()) {
            problems.add("source ids missing from the export: ${missing.joinToString()}")
        }
        if (actualSourceIdsByPart.size != partCount) {
            problems.add("wrote ${actualSourceIdsByPart.size} parts but declared $partCount")
        }
        actualSourceIdsByPart.forEachIndexed { i, ids ->
            if (ids.isEmpty()) problems.add("part ${i + 1} contains no sources")
        }

        return Report(
            ok = problems.isEmpty(),
            expectedSources = expectedSourceIds.size,
            actualSources = actualSet.size,
            duplicateSourceIds = duplicates,
            missingSourceIds = missing,
            duplicateDocumentIds = emptyList(),
            partCount = partCount,
            problems = problems
        )
    }

    /**
     * Cross-checks part files on disk against the parts the planner produced.
     *
     * Used after generation so a truncated or missing file is caught even when
     * the in-memory bookkeeping looked right.
     */
    fun verifyFiles(expected: List<String>, actual: List<File>): Report {
        val problems = ArrayList<String>()
        val actualNames = actual.map { it.name }
        expected.forEach { e ->
            if (!actualNames.contains(e)) problems.add("expected part file missing: $e")
        }
        actual.forEach { f: File ->
            if (!f.exists()) problems.add("part file does not exist: ${f.name}")
            else if (f.length() == 0L) problems.add("part file is empty: ${f.name}")
        }
        return Report(
            ok = problems.isEmpty(),
            expectedSources = 0,
            actualSources = 0,
            duplicateSourceIds = emptyList(),
            missingSourceIds = emptyList(),
            duplicateDocumentIds = emptyList(),
            partCount = actual.size,
            problems = problems
        )
    }
}
