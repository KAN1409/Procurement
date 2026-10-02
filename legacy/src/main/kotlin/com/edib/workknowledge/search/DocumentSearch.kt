package com.edib.workknowledge.search

import com.edib.workknowledge.data.SearchDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One source-aware hit, carrying enough provenance to open the exact place the
 * text came from.
 */
data class SearchHit(
    val chunkId: Long,
    val projectId: Long,
    val documentId: Long,
    val filename: String,
    val projectName: String,
    val pageNumber: Int? = null,
    val sheetName: String? = null,
    val rowStart: Int? = null,
    val rowEnd: Int? = null,
    val cellRange: String? = null,
    val snippet: String,
    /** Higher is better. See [DocumentSearch.scoreOf]. */
    val score: Float,
    val exactMatch: Boolean
) {
    /** Human-readable provenance, e.g. "Master Bathroom · Rows 42–47". */
    val location: String
        get() = buildString {
            sheetName?.let { append(it) }
            if (rowStart != null && rowEnd != null) {
                if (isNotEmpty()) append(" · ")
                append("Rows $rowStart–$rowEnd")
            }
            pageNumber?.let {
                if (isNotEmpty()) append(" · ")
                append("Page $it")
            }
            if (isEmpty()) append("Indexed content")
        }
}

/**
 * Exact / keyword retrieval over the FTS index.
 *
 * Works with zero embeddings: this is the only retrieval path available when
 * no AI endpoint is configured, which is why it must not depend on vectors.
 *
 * A hit for `MR-104` points at the Excel row containing `MR-104`; a hit for
 * `PR-023` points at PDF page 3. Provenance comes from the same columns the
 * RAG path uses, so both routes agree on where a fact lives.
 */
class DocumentSearch(private val searchDao: SearchDao) {

    suspend fun search(
        query: String,
        limit: Int = 25,
        projectId: Long? = null,
        documentIds: List<Long>? = null
    ): List<SearchHit> = withContext(Dispatchers.IO) {
        val match = FtsQueryBuilder.build(query) ?: return@withContext emptyList()
        val raw = if (documentIds.isNullOrEmpty()) {
            searchDao.matchBodies(query = match, limit = limit, projectId = projectId)
        } else {
            searchDao.matchBodiesInDocuments(
                query = match,
                limit = limit,
                documentIds = documentIds
            )
        }
        rank(
            query = query,
            hits = raw.map {
                Candidate(
                    chunkId = it.rowid,
                    projectId = it.projectId,
                    documentId = it.documentId,
                    filename = it.filename,
                    projectName = it.projectName,
                    pageNumber = it.pageNumber,
                    sheetName = it.chunkSheet,
                    rowStart = it.rowStart,
                    rowEnd = it.rowEnd,
                    cellRange = it.cellRange,
                    snippet = it.content
                )
            }
        )
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) { searchDao.count() }

    /**
     * Ranks an FTS result set.
     *
     * Exact identifier hits are boosted hard. `MR-104` is a code someone typed:
     * a literal match is the answer, and a merely-related passage must not
     * outrank it.
     *
     * Split out from the database call so the policy is unit-testable.
     */
    fun rank(query: String, hits: List<Candidate>): List<SearchHit> {
        val needle = query.trim().lowercase()
        return hits
            .map { candidate ->
                val exact = containsExact(candidate.snippet, candidate.filename, needle)
                candidate.toHit(scoreOf(exact, candidate), exact)
            }
            .sortedWith(
                compareByDescending<SearchHit> { it.exactMatch }
                    .thenByDescending { it.score }
                    .thenBy { it.chunkId }
            )
    }

    internal fun containsExact(snippet: String, filename: String, needleLower: String): Boolean {
        if (needleLower.isEmpty()) return false
        val haystack = "$filename\n$snippet".lowercase()
        if (haystack.contains(needleLower)) return true
        // The tokenizer splits MR-104 into "mr" and "104". Treat the
        // punctuation-stripped form as an exact hit too, so the boost reflects
        // what the user typed rather than how SQLite tokenised it.
        val squashed = needleLower.replace(NON_ALNUM, "")
        if (squashed.length >= 3) {
            return haystack.replace(NON_ALNUM, "").contains(squashed)
        }
        return false
    }

    /**
     * Relevance without a database ranking function.
     *
     * `bm25()` is not present in every SQLite build Android ships, and a query
     * referencing it fails outright when it is missing, so relevance is
     * derived from the retrieved text itself. A literal identifier match is
     * boosted far above a passage that merely contains the terms, which is the
     * behaviour that matters: `MR-104` must resolve to the MR-104 row.
     */
    internal fun scoreOf(exact: Boolean, candidate: Candidate): Float {
        val text = candidate.snippet.lowercase()
        val identifierHits = countIdentifierMatches(candidate, text)
        val termHits = countTermHits(candidate, text)
        var score = if (exact) EXACT_BASE else 0f
        score += identifierHits * IDENTIFIER_BONUS
        score += termHits * TERM_BONUS
        // A short passage containing the term is usually the precise hit; a
        // long paragraph containing it once is usually noise.
        score -= (text.length / 400f).coerceAtMost(LENGTH_PENALTY_CAP)
        return score
    }

    private fun countIdentifierMatches(candidate: Candidate, text: String): Int {
        var count = 0
        val file = candidate.filename.lowercase()
        if (candidate.filename.isNotBlank() && text.contains(file)) count++
        candidate.sheetName?.let { if (text.contains(it.lowercase())) count++ }
        return count
    }

    private fun countTermHits(candidate: Candidate, text: String): Int {
        val haystack = (candidate.filename + " " + text).lowercase()
        return IDENTIFIER_PATTERN.findAll(haystack).count()
    }

    data class Candidate(
        val chunkId: Long,
        val projectId: Long,
        val documentId: Long,
        val filename: String,
        val projectName: String,
        val pageNumber: Int?,
        val sheetName: String?,
        val rowStart: Int?,
        val rowEnd: Int?,
        val cellRange: String?,
        val snippet: String
    ) {
        fun toHit(score: Float, exact: Boolean) = SearchHit(
            chunkId = chunkId,
            projectId = projectId,
            documentId = documentId,
            filename = filename,
            projectName = projectName,
            pageNumber = pageNumber,
            sheetName = sheetName,
            rowStart = rowStart,
            rowEnd = rowEnd,
            cellRange = cellRange,
            snippet = snippet,
            score = score,
            exactMatch = exact
        )
    }

    private companion object {
        const val EXACT_BASE = 10f
        const val IDENTIFIER_BONUS = 0.35f
        const val TERM_BONUS = 0.10f
        const val LENGTH_PENALTY_CAP = 0.5f

        /** Codes such as MR-104, PR-023, CL-201, or any ABC-123 style token. */
        val IDENTIFIER_PATTERN = Regex("[a-z]{1,6}-[0-9]{1,6}")
        val NON_ALNUM = Regex("[^a-z0-9]+")
        // Keeps Latin letters, digits, underscore, and the Arabic block, so
        // Arabic identifiers are searchable too.
        val TOKEN_SPLIT = Regex("[^A-Za-z0-9_\\u0600-\\u06FF]+")
    }
}
