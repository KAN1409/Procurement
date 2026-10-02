package com.edib.workknowledge.embeddings

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URL
import java.util.concurrent.TimeUnit

class OllamaEmbeddingProvider(
    private val config: EmbeddingConfig,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(config.timeoutSeconds.toLong() * 2, TimeUnit.SECONDS)
        .writeTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .build()
) : EmbeddingProvider {
    override val modelName: String = config.modelName
    override val dimensions: Int = 768 // nomic-embed-text dimensions
    
    private val gson = Gson()
    private val baseUrl = config.ollamaBaseUrl.trimEnd('/')
    private val embeddingsUrl = "$baseUrl/api/embeddings"
    
    override suspend fun embed(text: String): EmbeddingProvider.OpResult<FloatArray> = withContext(Dispatchers.IO) {
        try {
            val requestBody = JsonObject().apply {
                addProperty("model", modelName)
                addProperty("prompt", text)
            }
            
            val request = Request.Builder()
                .url(embeddingsUrl)
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext EmbeddingProvider.OpResult.Failure("HTTP ${response.code}: ${response.body?.string()}")
                }
                
                val responseBody = response.body?.string() ?: return@withContext EmbeddingProvider.OpResult.Failure("Empty response")
                val json = JsonParser.parseString(responseBody).asJsonObject
                val embedding = json.getAsJsonArray("embedding")
                
                val floatArray = FloatArray(embedding.size())
                for (i in 0 until embedding.size()) {
                    floatArray[i] = embedding[i].asFloat
                }
                
                EmbeddingProvider.OpResult.Success(floatArray)
            }
        } catch (e: Exception) {
            EmbeddingProvider.OpResult.Failure("Embedding failed: ${e.message}")
        }
    }
    
    override suspend fun embedBatch(texts: List<String>): EmbeddingProvider.OpResult<List<FloatArray>> = withContext(Dispatchers.IO) {
        val results = mutableListOf<FloatArray>()
        
        // Process in batches
        for (batch in texts.chunked(config.batchSize)) {
            val batchResults = mutableListOf<FloatArray>()
            
            // For Ollama, we need to call embed for each text
            // Some Ollama versions support batch, but let's do sequential for compatibility
            for (text in batch) {
                when (val result = embed(text)) {
                    is EmbeddingProvider.OpResult.Success -> batchResults.add(result.value)
                    is EmbeddingProvider.OpResult.Failure ->
                        return@withContext EmbeddingProvider.OpResult.Failure(
                            "Batch embedding failed on item ${results.size + batchResults.size}: ${result.error}"
                        )
                }
            }
            
            results.addAll(batchResults)
        }
        
        EmbeddingProvider.OpResult.Success(results)
    }
    
    suspend fun testConnection(): EmbeddingProvider.OpResult<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/tags")
                .get()
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    EmbeddingProvider.OpResult.Success("Connected to Ollama at $baseUrl")
                } else {
                    EmbeddingProvider.OpResult.Failure("Ollama not reachable: HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            EmbeddingProvider.OpResult.Failure("Connection failed: ${e.message}")
        }
    }
    
    suspend fun pullModel(): EmbeddingProvider.OpResult<String> = withContext(Dispatchers.IO) {
        try {
            val requestBody = JsonObject().apply {
                addProperty("name", modelName)
            }
            
            val request = Request.Builder()
                .url("$baseUrl/api/pull")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    EmbeddingProvider.OpResult.Success("Model $modelName pulled successfully")
                } else {
                    EmbeddingProvider.OpResult.Failure("Failed to pull model: HTTP ${response.code}: ${response.body?.string()}")
                }
            }
        } catch (e: Exception) {
            EmbeddingProvider.OpResult.Failure("Pull failed: ${e.message}")
        }
    }
}
