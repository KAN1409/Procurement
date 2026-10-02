package com.edib.workknowledge.embeddings

import com.edib.workknowledge.domain.Chunk

interface EmbeddingProvider {
    val modelName: String
    val dimensions: Int
    
    suspend fun embed(text: String): OpResult<FloatArray>
    suspend fun embedBatch(texts: List<String>): OpResult<List<FloatArray>>
    
    sealed interface OpResult<out T> {
        data class Success<T>(val value: T) : OpResult<T>
        data class Failure(val error: String) : OpResult<Nothing>
    }
}

data class EmbeddingConfig(
    val providerType: ProviderType = ProviderType.OLLAMA,
    val modelName: String = "nomic-embed-text",
    val ollamaBaseUrl: String = "http://10.0.2.2:11434",
    val apiKey: String? = null,
    val batchSize: Int = 16,
    val timeoutSeconds: Int = 60
) {
    enum class ProviderType { OLLAMA, OPENAI_COMPATIBLE, LOCAL }
}
