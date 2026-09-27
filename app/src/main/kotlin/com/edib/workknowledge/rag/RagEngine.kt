package com.edib.workknowledge.rag

import com.edib.workknowledge.domain.Chunk
import com.edib.workknowledge.domain.Document
import com.edib.workknowledge.embeddings.EmbeddingProvider
import com.edib.workknowledge.llm.ChatModelProvider
import com.edib.workknowledge.llm.ModelConfig
import com.edib.workknowledge.retrieval.OpResult as VectorOpResult
import com.edib.workknowledge.retrieval.ScoredChunk
import com.edib.workknowledge.retrieval.VectorRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface RagEngine {
    data class QueryScope(
        val type: ScopeType,
        val projectId: Long? = null,
        val documentIds: List<Long> = emptyList(),
        val sheetName: String? = null,
        val pageNumber: Int? = null
    ) {
        enum class ScopeType { PROJECT, DOCUMENTS, SINGLE_DOCUMENT, ALL_PROJECTS }
    }
    
    data class QueryResult(
        val answer: String,
        val citations: List<Citation>,
        val retrievedChunks: List<ScoredChunk>,
        val scope: QueryScope
    )
    
    data class Citation(
        val chunkId: Long,
        val documentId: Long,
        val documentName: String,
        val pageNumber: Int?,
        val sheetName: String?,
        val rowStart: Int?,
        val rowEnd: Int?,
        val cellRange: String?,
        val snippet: String
    )
    
    data class ScoredChunk(
        val chunk: Chunk,
        val score: Float
    )
    
    suspend fun query(
        question: String,
        scope: RagEngine.QueryScope,
        conversationHistory: List<ChatModelProvider.ChatMessage> = emptyList()
    ): RagEngine.OpResult<QueryResult>
    
    sealed interface OpResult<out T> {
        data class Success<T>(val value: T) : OpResult<T>
        data class Failure(val error: String) : OpResult<Nothing>
    }
}

class DefaultRagEngine(
    private val embeddingProvider: EmbeddingProvider,
    private val vectorRepository: VectorRepository,
    private val chatModelProvider: ChatModelProvider,
    private val config: RagConfig = RagConfig()
) : RagEngine {
    
    override suspend fun query(
        question: String,
        scope: RagEngine.QueryScope,
        conversationHistory: List<ChatModelProvider.ChatMessage>
    ): RagEngine.OpResult<RagEngine.QueryResult> {
        return withContext(Dispatchers.IO) {
            try {
                // 1. embed the question
                val queryEmbedding = when (val e = embeddingProvider.embed(question)) {
                    is EmbeddingProvider.OpResult.Success -> e.value
                    is EmbeddingProvider.OpResult.Failure ->
                        return@withContext RagEngine.OpResult.Failure(
                            "Could not embed the question (${e.error}). " +
                                "Check the embedding provider in Settings."
                        )
                }

                // 2. retrieve, filtered by scope BEFORE any scoring
                val retrieved = when (
                    val r = vectorRepository.search(
                        projectId = scope.projectId ?: 0L,
                        queryEmbedding = queryEmbedding,
                        topK = config.topK,
                        documentIds = scope.documentIds.ifEmpty { null },
                        sheetName = scope.sheetName,
                        pageNumber = scope.pageNumber
                    )
                ) {
                    is VectorOpResult.Success -> r.value
                    is VectorOpResult.Failure ->
                        return@withContext RagEngine.OpResult.Failure("Retrieval failed: ${r.error}")
                }

                if (retrieved.isEmpty()) {
                    return@withContext RagEngine.OpResult.Success(
                        RagEngine.QueryResult(
                            answer = "No indexed content was found in the selected scope. " +
                                "Import a document for this project, or widen the scope.",
                            citations = emptyList(),
                            retrievedChunks = emptyList(),
                            scope = scope
                        )
                    )
                }

                // 3. drop near-duplicate chunks so the context is not wasted
                val kept = deduplicateChunks(retrieved)

                // 4. ground the answer in exactly those chunks
                val answer = when (
                    val g = chatModelProvider.generateResponse(
                        systemPrompt = config.systemPrompt,
                        userMessage = question,
                        contextChunks = kept.map { it.chunk },
                        conversationHistory = conversationHistory
                    )
                ) {
                    is ChatModelProvider.OpResult.Success -> g.value
                    is ChatModelProvider.OpResult.Failure ->
                        return@withContext RagEngine.OpResult.Failure("Generation failed: ${g.error}")
                }

                // 5. citations are derived from the chunks actually used, never invented
                val citations = kept.map { scored ->
                    val c = scored.chunk
                    RagEngine.Citation(
                        chunkId = c.id,
                        documentId = c.documentId,
                        documentName = "",
                        pageNumber = c.pageNumber,
                        sheetName = c.sheetName,
                        rowStart = c.rowStart,
                        rowEnd = c.rowEnd,
                        cellRange = c.cellRange,
                        snippet = c.content.take(200)
                    )
                }

                RagEngine.OpResult.Success(
                    RagEngine.QueryResult(
                        answer = answer,
                        citations = citations,
                        retrievedChunks = kept.map { RagEngine.ScoredChunk(it.chunk, it.score) },
                        scope = scope
                    )
                )
            } catch (t: Throwable) {
                RagEngine.OpResult.Failure("RAG query failed: ${t.message}")
            }
        }
    }

    private fun deduplicateChunks(chunks: List<ScoredChunk>): List<ScoredChunk> {
        // Simple deduplication: remove chunks with very similar content
        val result = mutableListOf<ScoredChunk>()
        val seenContent = mutableSetOf<String>()
        
        for (scoredChunk in chunks) {
            // Use first 100 chars as fingerprint
            val fingerprint = scoredChunk.chunk.content.take(100).lowercase()
            if (fingerprint !in seenContent) {
                seenContent.add(fingerprint)
                result.add(scoredChunk)
            }
        }
        
        return result
    }
}

data class RagConfig(
    val topK: Int = 12,
    val maxContextChunks: Int = 8,
    val systemPrompt: String = ModelConfig.DEFAULT_SYSTEM_PROMPT,
    val enableHybridSearch: Boolean = true,
    val rerankResults: Boolean = false
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT = """You are a precise document analysis assistant for architecture, engineering, and construction projects.

CRITICAL RULES:
1. Answer ONLY using the supplied source context. Do not use external knowledge.
2. If the answer cannot be established from the supplied sources, say: "The available documents do not contain enough information to answer this question."
3. Preserve numbers exactly as they appear in sources. Do not round or calculate unless explicitly asked.
4. Every factual claim must be traceable to a specific source chunk.
5. Distinguish between facts from documents and your own inferences.
6. For calculations, use the structured data provided - do not perform arithmetic yourself.
7. Never fabricate filenames, page numbers, sheet names, row numbers, quantities, costs, dates, or approvals.
8. If multiple sources conflict, mention the conflict and cite both.

CITATION FORMAT:
When citing sources, use the format: [DocumentName • Sheet/Page • Row/Page Range]
Examples: [BOQ Rev 02.xlsx • Master Bathroom • Rows 42–46]
         [Material Approval.pdf • Page 6]

RESPONSE STRUCTURE:
- Direct answer
- Supporting details (if needed)
- Sources (as citation chips)"""
    }
}
