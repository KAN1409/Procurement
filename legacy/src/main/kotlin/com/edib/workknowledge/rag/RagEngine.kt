package com.edib.workknowledge.rag

import com.edib.workknowledge.llm.ChatModelProvider
import com.edib.workknowledge.retrieval.RetrievalSource
import com.edib.workknowledge.retrieval.RetrievedPassage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Retrieval is delegated to [com.edib.workknowledge.retrieval.HybridRetriever]. */
interface RagEngine {

    data class QueryScope(
        val type: ScopeType,
        val projectId: Long? = null,
        val documentIds: List<Long> = emptyList()
    ) {
        enum class ScopeType { PROJECT, DOCUMENTS, SINGLE_DOCUMENT, ALL_PROJECTS }
    }

    /**
     * A citation built only from a passage that was actually retrieved.
     *
     * [chunkId] is the identity used to map the model's back-reference back to
     * the chunk, so a citation can never point at text the model never saw.
     */
    data class Citation(
        val chunkId: Long,
        val documentId: Long,
        val filename: String,
        val pageNumber: Int?,
        val sheetName: String?,
        val rowStart: Int?,
        val rowEnd: Int?,
        val cellRange: String?
    )

    data class QueryResult(
        val answer: String,
        val citations: List<Citation>,
        val passages: List<RetrievedPassage>,
        val scope: QueryScope,
        /** True when nothing was retrieved, so the answer is a refusal. */
        val notFound: Boolean
    )

    sealed interface OpResult<out T> {
        data class Success<T>(val value: T) : OpResult<T>
        data class Failure(val error: String) : OpResult<Nothing>
    }

    suspend fun query(
        question: String,
        scope: QueryScope,
        conversationHistory: List<ChatModelProvider.ChatMessage> = emptyList()
    ): OpResult<QueryResult>
}

/**
 * Grounded question answering over one project's indexed documents.
 *
 * Retrieval is *only* [com.edib.workknowledge.retrieval.HybridRetriever]: there
 * is one authoritative retrieval implementation, so a search result and an
 * answer's citation can never disagree about where a fact lives.
 *
 * When nothing is retrieved the model is not called at all. For
 * project-document Q&A an unconstrained guess is worse than an honest
 * "not in the available documents", so that case short-circuits.
 */
class DefaultRagEngine(
    private val retriever: com.edib.workknowledge.retrieval.HybridRetriever,
    private val chatModelProvider: ChatModelProvider,
    private val config: RagConfig = RagConfig()
) : RagEngine {

    override suspend fun query(
        question: String,
        scope: RagEngine.QueryScope,
        conversationHistory: List<ChatModelProvider.ChatMessage>
    ): RagEngine.OpResult<RagEngine.QueryResult> = withContext(Dispatchers.IO) {
        try {
            val passages = retriever.retrieve(
                question = question,
                projectId = scope.projectId,
                documentIds = scope.documentIds.ifEmpty { null },
                topK = config.topK
            )

            // No evidence, no model call.
            if (passages.isEmpty()) {
                return@withContext RagEngine.OpResult.Success(
                    RagEngine.QueryResult(
                        answer = NOT_FOUND_ANSWER,
                        citations = emptyList(),
                        passages = emptyList(),
                        scope = scope,
                        notFound = true
                    )
                )
            }

            val grounded = passages.take(config.maxContextChunks)

            // Citations are derived from the passages handed to the model, so
            // the two can never diverge.
            val allowedIds = grounded.map { it.chunkId }.toSet()
            val citations = grounded.map { it.toCitation() }

            val answer = when (
                val g = chatModelProvider.generateResponse(
                    systemPrompt = config.systemPrompt,
                    userMessage = buildGroundedQuestion(question, grounded),
                    contextChunks = grounded.map { it.toChunk() },
                    conversationHistory = conversationHistory
                )
            ) {
                is ChatModelProvider.OpResult.Success -> g.value
                is ChatModelProvider.OpResult.Failure ->
                    return@withContext RagEngine.OpResult.Failure("Generation failed: ${g.error}")
            }

            // Post-condition: every citation must correspond to a retrieved
            // passage. Defensive, because a citation that cannot be opened is
            // worse than no citation.
            check(citations.all { it.chunkId in allowedIds }) {
                "internal error: a citation referenced a chunk that was not retrieved"
            }

            RagEngine.OpResult.Success(
                RagEngine.QueryResult(
                    answer = answer,
                    citations = citations,
                    passages = grounded,
                    scope = scope,
                    notFound = false
                )
            )
        } catch (t: Throwable) {
            RagEngine.OpResult.Failure("RAG query failed: ${t.message}")
        }
    }

    /**
     * Labels each passage with its citation id so the model can refer to it,
     * while the ids themselves remain ours.
     */
    private fun buildGroundedQuestion(
        question: String,
        passages: List<RetrievedPassage>
    ): String = buildString {
        append(question)
        append("\n\n")
        append("You may reference the sources by their [S<n>] id. Do not invent ids.\n\n")
        passages.forEachIndexed { index, p ->
            append("[S${index + 1}] ")
            append(p.filename)
            p.location.takeIf { it.isNotBlank() }?.let { append(" — ") }
            p.location.takeIf { it.isNotBlank() }?.let { append(it) }
            p.source.let { append(" (${sourceLabel(it)})") }
            append("\n")
            append(p.content)
            append("\n\n")
        }
    }

    private fun sourceLabel(source: RetrievalSource) = when (source) {
        RetrievalSource.FTS -> "exact"
        RetrievalSource.VECTOR -> "semantic"
        RetrievalSource.BOTH -> "exact+semantic"
    }

    companion object {
        const val NOT_FOUND_ANSWER =
            "The available project documents do not contain enough information to " +
                "answer this question. No answer has been inferred."
    }
}

/** Maps a retrieved passage to a citation, preserving its provenance verbatim. */
fun RetrievedPassage.toCitation(): RagEngine.Citation = RagEngine.Citation(
    chunkId = chunkId,
    documentId = documentId,
    filename = filename,
    pageNumber = pageNumber,
    sheetName = sheetName,
    rowStart = rowStart,
    rowEnd = rowEnd,
    cellRange = cellRange
)

/** RAG behaviour, including citation integrity. */
data class RagConfig(
    val topK: Int = 12,
    val maxContextChunks: Int = 8,
    val systemPrompt: String = SYSTEM_PROMPT
) {
    companion object {
        const val SYSTEM_PROMPT = """You are a precise document analysis assistant for architecture, engineering and construction projects.

RULES
1. Answer only from the source passages provided. Do not use outside knowledge.
2. If the passages do not contain the answer, say the available documents do not contain enough information.
3. Preserve numbers exactly as written. Never round, convert units or estimate.
4. Reference sources only by the [S<n>] ids you were given. Never invent an id, a file, a page, a sheet or a row.
5. Distinguish what a source states from your own inference."""
    }
}
