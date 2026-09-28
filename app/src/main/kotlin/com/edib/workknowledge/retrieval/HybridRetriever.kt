package com.edib.workknowledge.retrieval

import com.edib.workknowledge.domain.Chunk
import com.edib.workknowledge.embeddings.EmbeddingProvider
import com.edib.workknowledge.search.DocumentSearch
import com.edib.workknowledge.search.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where a passage came from. Both means the two routes agreed on it. */
enum class RetrievalSource { FTS, VECTOR, BOTH }

/**
 * One retrieved passage with the provenance needed to open the exact source.
 *
 * Provenance is carried through merging untouched. Nothing downstream may
 * reconstruct a citation from a model's words, so it all lives here.
 */
data class RetrievedPassage(
    val chunkId: Long,
    val projectId: Long,
    val documentId: Long,
    val content: String,
    val filename: String,
    val projectName: String,
    val pageNumber: Int? = null,
    val sheetName: String? = null,
    val rowStart: Int? = null,
    val rowEnd: Int? = null,
    val cellRange: String? = null,
    val source: RetrievalSource,
    /** Final ordering score; higher is better. See [HybridRetriever]. */
    val score: Float,
    /** True when the passage literally contains what the user asked for. */
    val exactMatch: Boolean,
    /** Semantic similarity, when a vector route produced this passage. */
    val vectorScore: Float? = null
) {
    fun toChunk(): Chunk = Chunk(
        id = chunkId,
        projectId = projectId,
        documentId = documentId,
        chunkIndex = -1,
        content = content,
        pageNumber = pageNumber,
        sheetName = sheetName,
        rowStart = rowStart,
        rowEnd = rowEnd,
        cellRange = cellRange,
        filename = filename,
        projectName = projectName
    )

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
 * The single retrieval entry point.
 *
 * Two modes, and Mode A never depends on an AI endpoint:
 *
 *  A. no embeddings available -> exact/keyword retrieval only. A missing or
 *     failing embedding provider is not an error; it simply contributes
 *     nothing.
 *  B. embeddings available -> exact and semantic candidates are merged,
 *     de-duplicated by chunk identity, and ranked together.
 *
 * Ranking is deliberately simple and documented rather than clever:
 *
 *  exact identifier / literal match   +EXACT_BOOST   (dominates)
 *  term coverage and identifier count +FTS_WEIGHT
 *  vector similarity                 * VECTOR_WEIGHT
 *  duplicate found by both routes    keeps the max, source becomes BOTH
 *
 * The exact boost is large on purpose. A query like `MR-104` is a code someone
 * typed; there is exactly one right cell for it, and a merely-related passage
 * must never outrank it.
 *
 * `open` only so tests can substitute known candidates; production wiring always
 * uses the real [retrieve].
 */
open class HybridRetriever(
    private val search: DocumentSearch,
    private val vectorRepository: VectorRepository,
    private val embeddingProvider: EmbeddingProvider? = null
) {

    open suspend fun retrieve(
        question: String,
        projectId: Long? = null,
        documentIds: List<Long>? = null,
        topK: Int = 12
    ): List<RetrievedPassage> = withContext(Dispatchers.IO) {

        // An empty list means "no document filter", not "match no documents".
        // Passing [] straight through would reach `IN ()` in the vector query.
        val documentFilter = documentIds?.takeIf { it.isNotEmpty() }

        // ---- 1. exact / keyword ----
        val exactHits: List<SearchHit> = runCatching {
            search.search(
                query = question,
                limit = topK,
                projectId = projectId,
                documentIds = documentFilter
            )
        }.getOrDefault(emptyList())

        val merged = LinkedHashMap<Long, RetrievedPassage>()

        for (hit in exactHits) {
            merged[hit.chunkId] = RetrievedPassage(
                chunkId = hit.chunkId,
                projectId = hit.projectId,
                documentId = hit.documentId,
                content = hit.snippet,
                filename = hit.filename,
                projectName = hit.projectName,
                pageNumber = hit.pageNumber,
                sheetName = hit.sheetName,
                rowStart = hit.rowStart,
                rowEnd = hit.rowEnd,
                cellRange = hit.cellRange,
                source = RetrievalSource.FTS,
                score = ftsScore(hit),
                exactMatch = hit.exactMatch
            )
        }

        // ---- 2. semantic, only when an embedding provider is supplied ----
        val provider = embeddingProvider
        if (provider != null) {
            when (val embedded = provider.embed(question)) {
                is EmbeddingProvider.OpResult.Success -> {
                    when (
                        val result = vectorRepository.search(
                            projectId = projectId ?: 0L,
                            queryEmbedding = embedded.value,
                            topK = topK,
                            documentIds = documentFilter
                        )
                    ) {
                        is OpResult.Success -> for (scored in result.value) {
                            val semantic = semanticScore(scored.score)
                            val existing = merged[scored.chunk.id]
                            if (existing == null) {
                                merged[scored.chunk.id] = fromChunk(
                                    chunk = scored.chunk,
                                    source = RetrievalSource.VECTOR,
                                    score = semantic,
                                    exactMatch = false,
                                    vectorScore = scored.score
                                )
                            } else {
                                // Found by both routes: one entry, not two.
                                merged[scored.chunk.id] = existing.copy(
                                    score = maxOf(existing.score, semantic),
                                    source = RetrievalSource.BOTH,
                                    vectorScore = scored.score
                                )
                            }
                        }
                        // A provider failure must not fail retrieval: exact
                        // results are still valid answers.
                        is OpResult.Failure -> Unit
                    }
                }
                is EmbeddingProvider.OpResult.Failure -> Unit
            }
        }

        merged.values
            .sortedWith(
                compareByDescending<RetrievedPassage> { it.exactMatch }
                    .thenByDescending { it.score }
                    .thenBy { it.chunkId }
            )
            .take(topK)
    }

    private fun fromChunk(
        chunk: Chunk,
        source: RetrievalSource,
        score: Float,
        exactMatch: Boolean,
        vectorScore: Float?
    ) = RetrievedPassage(
        chunkId = chunk.id,
        projectId = chunk.projectId,
        documentId = chunk.documentId,
        content = chunk.content,
        filename = chunk.filename,
        projectName = chunk.projectName,
        pageNumber = chunk.pageNumber,
        sheetName = chunk.sheetName,
        rowStart = chunk.rowStart,
        rowEnd = chunk.rowEnd,
        cellRange = chunk.cellRange,
        source = source,
        score = score,
        exactMatch = exactMatch,
        vectorScore = vectorScore
    )

    /**
     * FTS contribution.
     *
     * [SearchHit.score] already encodes an exact-match boost from the search
     * layer, so this simply lifts it into the shared range and adds a small
     * term-coverage term.
     */
    private fun ftsScore(hit: SearchHit): Float {
        val base = if (hit.exactMatch) EXACT_BOOST else 0f
        val terms = hit.snippet.split(Regex("[^A-Za-z0-9]+"))
            .count { it.length >= 3 }
            .coerceAtMost(TERM_COVERAGE_CAP)
        return base + terms * FTS_TERM_WEIGHT
    }

    /** Cosine similarity is in [-1, 1]; map it onto a non-negative contribution. */
    private fun semanticScore(similarity: Float): Float =
        ((similarity + 1f) / 2f) * VECTOR_WEIGHT

    internal companion object {
        /** Large enough that a literal identifier always wins. */
        const val EXACT_BOOST = 100f
        const val FTS_TERM_WEIGHT = 0.5f
        const val TERM_COVERAGE_CAP = 10
        /** Semantic hits can contribute, but can never beat an exact hit. */
        const val VECTOR_WEIGHT = 10f
    }
}
