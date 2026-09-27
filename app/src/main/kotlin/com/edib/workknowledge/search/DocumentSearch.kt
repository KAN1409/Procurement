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
        val match = toMatchExpression(query) ?: return@withContext emptyList()
        val raw = searchDao.matchBodies(
            query = match,
            limit = limit,
            projectId = projectId,
            documentIds = documentIds
        )
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
                    snippet = it.content,
                    bm25Rank = it.rank
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
                candidate.toHit(scoreOf(exact, candidate.bm25Rank), exact)
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
     * SQLite's bm25() is negative, more relevant being more negative. It is
     * mapped onto a small positive range and then dominated by the exact-match
     * boost, so a literal identifier hit always outranks a fuzzy one.
     */
    internal fun scoreOf(exact: Boolean, bm25Rank: Double): Float {
        val relevance = (1f - (bm25Rank / -20.0).toFloat()).coerceIn(0f, 1f)
        return if (exact) 10f + relevance else relevance
    }

    /**
     * Builds a safe FTS4 MATCH expression.
     *
     * User input is never interpolated raw: FTS treats `-`, `"`, `*`, `:` and
     * friends as operators, so `PR-023` would otherwise throw or quietly mean
     * something else. Each token is quoted and the last gets a prefix `*`.
     */
    fun toMatchExpression(query: String): String? {
        val cleaned = query.trim()
        if (cleaned.isEmpty()) return null

        val tokens = cleaned
            .split(TOKEN_SPLIT)
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null

        return tokens
            .mapIndexed { index, token ->
                val quoted = "\"" + token.replace("\"", "\"\"") + "\""
                if (index == tokens.lastIndex) "$quoted*" else quoted
            }
            .joinToString(" AND ")
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
        val snippet: String,
        val bm25Rank: Double
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
        val NON_ALNUM = Regex("[^a-z0-9]+")
        // Keeps Latin letters, digits, underscore, and the Arabic block, so
        // Arabic identifiers are searchable too.
        val TOKEN_SPLIT = Regex("[^A-Za-z0-9_\\u0600-\\u06FF]+")
    }
}
