package com.edib.workknowledge.rag

import com.edib.workknowledge.domain.Chunk
import com.edib.workknowledge.domain.Document
import com.edib.workknowledge.llm.ChatModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface SummarizationEngine {
    data class DocumentSummary(
        val summary: String,
        val keyFacts: List<String>,
        val metadata: SummaryMetadata
    )
    
    data class SummaryMetadata(
        val documentType: String,
        val chunkCount: Int,
        val modelUsed: String,
        val generatedAt: Long = System.currentTimeMillis(),
        val processingTimeMs: Long = 0
    )
    
    suspend fun summarizeDocument(
        document: Document,
        chunks: List<Chunk>
    ): SummarizationEngine.OpResult<DocumentSummary>
    
    sealed interface OpResult<out T> {
        data class Success<T>(val value: T) : OpResult<T>
        data class Failure(val error: String) : OpResult<Nothing>
    }
}

class HierarchicalSummarizationEngine(
    private val chatModelProvider: ChatModelProvider,
    private val config: SummarizationConfig = SummarizationConfig()
) : SummarizationEngine {
    
    override suspend fun summarizeDocument(
        document: Document,
        chunks: List<Chunk>
    ): SummarizationEngine.OpResult<SummarizationEngine.DocumentSummary> {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                val summaryText =
                    if (chunks.size <= config.singlePassThreshold) {
                        singlePassSummarize(document, chunks)
                    } else {
                        hierarchicalSummarize(document, chunks)
                    }

                when (summaryText) {
                    is SummarizationEngine.OpResult.Failure -> summaryText
                    is SummarizationEngine.OpResult.Success ->
                        SummarizationEngine.OpResult.Success(
                            SummarizationEngine.DocumentSummary(
                                summary = summaryText.value,
                                keyFacts = extractKeyFacts(summaryText.value),
                                metadata = SummarizationEngine.SummaryMetadata(
                                    documentType = document.mimeType,
                                    chunkCount = chunks.size,
                                    modelUsed = chatModelProvider.modelName,
                                    processingTimeMs = System.currentTimeMillis() - startTime
                                )
                            )
                        )
                }
            } catch (e: Exception) {
                SummarizationEngine.OpResult.Failure("Summarization failed: ${e.message}")
            }
        }
    }
    
    private suspend fun singlePassSummarize(document: Document, chunks: List<Chunk>): SummarizationEngine.OpResult<String> {
        val combinedContent = chunks
            .sortedBy { it.chunkIndex }
            .joinToString("\n\n--- CHUNK BREAK ---\n\n") { it.content }
        
        val prompt = buildSummarizationPrompt(document, combinedContent)

        return when (
            val r = chatModelProvider.generateResponse(
                systemPrompt = SUMMARIZATION_SYSTEM_PROMPT,
                userMessage = prompt,
                contextChunks = emptyList(),
                conversationHistory = emptyList()
            )
        ) {
            is ChatModelProvider.OpResult.Success ->
                SummarizationEngine.OpResult.Success(r.value)
            is ChatModelProvider.OpResult.Failure ->
                SummarizationEngine.OpResult.Failure(r.error)
        }
    }
    
    /**
     * Map-reduce: summarise each group of chunks, then reduce those section
     * summaries into one document summary. Keeps any single prompt small no
     * matter how long the document is.
     */
    private suspend fun hierarchicalSummarize(document: Document, chunks: List<Chunk>): SummarizationEngine.OpResult<String> {
        val sectionSummaries = mutableListOf<String>()

        for (chunkGroup in chunks.chunked(config.chunksPerSection)) {
            val groupContent = chunkGroup.joinToString("\n\n") { it.content }
            val prompt = buildSectionSummarizationPrompt(document, groupContent)

            when (val result = chatModelProvider.generateResponse(
                systemPrompt = SECTION_SUMMARIZATION_SYSTEM_PROMPT,
                userMessage = prompt,
                contextChunks = emptyList(),
                conversationHistory = emptyList()
            )) {
                is ChatModelProvider.OpResult.Success -> sectionSummaries.add(result.value)
                is ChatModelProvider.OpResult.Failure ->
                    return SummarizationEngine.OpResult.Failure(
                        "Section summarization failed: ${result.error}"
                    )
            }
        }
        
        // Step 2: Combine section summaries into final summary
        val combinedSections = sectionSummaries.joinToString("\n\n--- SECTION BREAK ---\n\n")
        val finalPrompt = buildFinalSummarizationPrompt(document, combinedSections)

        return when (
            val r = chatModelProvider.generateResponse(
                systemPrompt = SUMMARIZATION_SYSTEM_PROMPT,
                userMessage = finalPrompt,
                contextChunks = emptyList(),
                conversationHistory = emptyList()
            )
        ) {
            is ChatModelProvider.OpResult.Success ->
                SummarizationEngine.OpResult.Success(r.value)
            is ChatModelProvider.OpResult.Failure ->
                SummarizationEngine.OpResult.Failure(r.error)
        }
    }
    
    private fun buildSummarizationPrompt(document: Document, content: String): String {
        return """Please create a comprehensive summary of the following document.

DOCUMENT: ${document.filename}
TYPE: ${document.mimeType}
PAGES: ${document.pageCount}
SHEETS: ${document.sheetCount}

CONTENT:
$content

Provide a structured summary covering:
1. DOCUMENT PURPOSE - What is this document about?
2. KEY FACTS - Important quantities, dates, names, references, materials, costs, approvals
3. OUTSTANDING ITEMS - Any actions, risks, issues, or pending items
4. REVISION INFO - Version/revision details if present

Be precise with numbers and preserve exact values from the document."""
    }
    
    private fun buildSectionSummarizationPrompt(document: Document, content: String): String {
        return """Summarize this section of "${document.filename}".

CONTENT:
$content

Provide a concise summary of the key information in this section. Focus on facts, quantities, names, and actionable items."""
    }
    
    private fun buildFinalSummarizationPrompt(document: Document, sectionSummaries: String): String {
        return """Combine the following section summaries into a comprehensive document summary for "${document.filename}".

SECTION SUMMARIES:
$sectionSummaries

Provide a structured summary covering:
1. DOCUMENT PURPOSE - What is this document about?
2. KEY FACTS - Important quantities, dates, names, references, materials, costs, approvals
3. OUTSTANDING ITEMS - Any actions, risks, issues, or pending items
4. REVISION INFO - Version/revision details if present

Be precise with numbers and preserve exact values from the document."""
    }
    
    private fun extractKeyFacts(summary: String): List<String> {
        val facts = mutableListOf<String>()
        
        // Look for lines with key indicators
        val lines = summary.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            
            // Detect fact-like lines
            if (trimmed.contains(":", ignoreCase = true) &&
                (trimmed.contains("quantity", ignoreCase = true) ||
                 trimmed.contains("cost", ignoreCase = true) ||
                 trimmed.contains("total", ignoreCase = true) ||
                 trimmed.contains("amount", ignoreCase = true) ||
                 trimmed.contains("date", ignoreCase = true) ||
                 trimmed.contains("approved", ignoreCase = true) ||
                 trimmed.contains("rejected", ignoreCase = true) ||
                 trimmed.contains("material", ignoreCase = true) ||
                 trimmed.contains("specification", ignoreCase = true) ||
                 trimmed.contains("drawing", ignoreCase = true) ||
                 trimmed.matches(Regex(".*\\d+.*")))) {
                facts.add(trimmed)
            }
        }
        
        return facts.distinct().take(20)
    }
    
    companion object {
        const val SUMMARIZATION_SYSTEM_PROMPT = """You are a document summarization specialist for architecture, engineering, and construction projects.

Create precise, structured summaries that preserve all important facts, quantities, dates, names, and references.

RULES:
1. Preserve exact numbers, quantities, costs, and dates from the source.
2. Do not interpret or infer - only summarize what is explicitly stated.
3. Use the same terminology as the document.
4. Include revision/version information if present.
5. Note any approvals, rejections, or outstanding items.
6. Structure the summary with clear sections."""
        
        const val SECTION_SUMMARIZATION_SYSTEM_PROMPT = """You are summarizing a section of a technical document for architecture, engineering, or construction.

Extract the key facts, quantities, and actionable items. Be concise but precise with numbers."""
    }
}

data class SummarizationConfig(
    val singlePassThreshold: Int = 10,
    val chunksPerSection: Int = 5,
    val maxSummaryLength: Int = 2000
)
