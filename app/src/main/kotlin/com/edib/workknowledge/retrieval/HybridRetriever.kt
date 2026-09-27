package com.edib.workknowledge.retrieval

import com.edib.workknowledge.domain.Chunk
import com.edib.workknowledge.embeddings.EmbeddingProvider
import com.edib.workknowledge.search.DocumentSearch
import com.edib.workknowledge.search.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * One retrieved passage, however it was found.
 */
data class RetrievedPassage(
    val chunk: Chunk,
    /** Final ordering score; higher is better. */
    val score: Float,
    val fromExactSearch: Boolean,
    val fromVectorSearch: Boolean
)

/**
 * Combines exact (FTS) and semantic (vector) retrieval.
 *
 * Design intent: the exact path is the primary one. A query like `MR-104` is
 * an identifier a person typed; there is exactly one right answer and it is in
 * a specific cell. Semantic retrieval is a fallback for natural-language
 * questions. This ordering is deliberate, not a scoring accident.
 *
 * The two paths are independent, so a device with no embedding endpoint still
 * retrieves everything it indexed, using exact search only.
 */
class HybridRetriever(
    private val search: DocumentSearch,
    private val vectorRepository: VectorRepository
) {

    /**
     * @param exactWeight  added to any hit that literally contains the query
     * @param vectorWeight applied to semantic-only hits
     */
    suspend fun retrieve(
        question: String,
        projectId: Long?,
        documentIds: List<Long>?,
        topK: Int,
        embeddingProvider: EmbeddingProvider? = null,
        exactWeight: Float = 1.0f,
        vectorWeight: Float = 0.5f
    ): List<RetrievedPassage> = withContext(Dispatchers.IO) {

        // ---- 1. exact / keyword ----
        val exactHits: List<SearchHit> = runCatching {
            search.search(
                query = question,
                limit = topK,
                projectId = projectId,
                documentIds = documentIds
            )
        }.getOrDefault(emptyList())

        val passages = LinkedHashMap<Long, RetrievedPassage>()

        for (hit in exactHits) {
            passages[hit.chunkId] = RetrievedPassage(
                chunk = placeholderChunk(hit),
                score = exactWeight + hit.score / 10f,
                fromExactSearch = true,
                fromVectorSearch = false
            )
        }

        // ---- 2. semantic, only if an embedding provider is available ----
        if (embeddingProvider != null) {
            when (val embedded = embeddingProvider.embed(question)) {
                is EmbeddingProvider.OpResult.Success -> {
                    val vectorResult = vectorRepository.search(
                        projectId = projectId ?: 0L,
                        queryEmbedding = embedded.value,
                        topK = topK,
                        documentIds = documentIds
                    )
                    if (vectorResult is OpResult.Success) {
                        for (scored in vectorResult.value) {
                            val existing = passages[scored.chunk.id]
                            if (existing == null) {
                                passages[scored.chunk.id] = RetrievedPassage(
                                    chunk = scored.chunk,
                                    score = vectorWeight + scored.score,
                                    fromExactSearch = false,
                                    fromVectorSearch = true
                                )
                            } else {
                                // Found by both routes: keep the stronger
                                // evidence and record that both agreed.
                                passages[scored.chunk.id] = existing.copy(
                                    score = max(existing.score, vectorWeight + scored.score),
                                    fromVectorSearch = true
                                )
                            }
                        }
                    }
                }
                is EmbeddingProvider.OpResult.Failure -> Unit // degrade to exact only
            }
        }

        passages.values
            .sortedWith(
                compareByDescending<RetrievedPassage> { it.fromExactSearch }
                    .thenByDescending { it.score }
                    .thenBy { it.chunk.id }
            )
            .take(topK)
    }

    /**
     * Rehydrates the chunk body for an exact-search hit.
     *
     * FTS rows intentionally do not duplicate the full text, so the caller
     * needs the body from the chunks table.
     */
    private fun placeholderChunk(hit: SearchHit): Chunk = Chunk(
        id = hit.chunkId,
        projectId = hit.projectId,
        documentId = hit.documentId,
        chunkIndex = -1,
        content = hit.snippet,
        pageNumber = hit.pageNumber,
        sheetName = hit.sheetName,
        rowStart = hit.rowStart,
        rowEnd = hit.rowEnd,
        cellRange = hit.cellRange,
        filename = hit.filename,
        projectName = hit.projectName
    )
}
