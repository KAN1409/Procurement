package com.edib.workknowledge.llm

import com.edib.workknowledge.domain.Chunk

interface ChatModelProvider {
    val modelName: String
    
    suspend fun generateResponse(
        systemPrompt: String,
        userMessage: String,
        contextChunks: List<Chunk>,
        conversationHistory: List<ChatMessage>
    ): OpResult<String>
    
    suspend fun generateStreamingResponse(
        systemPrompt: String,
        userMessage: String,
        contextChunks: List<Chunk>,
        conversationHistory: List<ChatMessage>,
        onToken: (String) -> Unit
    ): OpResult<String>
    
    sealed interface OpResult<out T> {
        data class Success<T>(val value: T) : OpResult<T>
        data class Failure(val error: String) : OpResult<Nothing>
    }
    
    data class ChatMessage(
        val role: Role,
        val content: String
    ) {
        enum class Role { SYSTEM, USER, ASSISTANT }
    }
    
    data class ChatConfig(
        val temperature: Float = 0.1f,
        val topP: Float = 0.9f,
        val maxTokens: Int = 4096,
        val stopSequences: List<String> = emptyList()
    )
}

data class ModelConfig(
    val providerType: ProviderType = ProviderType.OLLAMA,
    val modelName: String = "llama3.1",
    val ollamaBaseUrl: String = "http://10.0.2.2:11434",
    val apiKey: String? = null,
    val openAiBaseUrl: String = "https://api.openai.com/v1",
    val temperature: Float = 0.1f,
    val maxTokens: Int = 4096,
    val timeoutSeconds: Int = 120,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT
) {
    enum class ProviderType { OLLAMA, OPENAI_COMPATIBLE, GROQ, LOCAL }
    
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
