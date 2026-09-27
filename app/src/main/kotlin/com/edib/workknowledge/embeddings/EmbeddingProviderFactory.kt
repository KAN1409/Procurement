package com.edib.workknowledge.embeddings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class EmbeddingProviderFactory(private val context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        "embedding_config",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    
    /**
     * Reads a string setting, distinguishing "absent" from "empty".
     * Secrets are stored encrypted and must never be logged.
     */
    private fun string(key: String): String? =
        prefs.getString(key, null as String?)?.takeIf { it.isNotEmpty() }

    fun getConfig(): EmbeddingConfig {
        val providerType = EmbeddingConfig.ProviderType.valueOf(
            string("provider_type") ?: EmbeddingConfig.ProviderType.OLLAMA.name
        )
        val modelName = string("model_name") ?: "nomic-embed-text"
        val ollamaBaseUrl = string("ollama_base_url") ?: "http://10.0.2.2:11434"
        val apiKey = string("api_key")
        val batchSize = prefs.getInt("batch_size", 16)
        val timeoutSeconds = prefs.getInt("timeout_seconds", 60)
        
        return EmbeddingConfig(
            providerType = providerType,
            modelName = modelName,
            ollamaBaseUrl = ollamaBaseUrl,
            apiKey = apiKey,
            batchSize = batchSize,
            timeoutSeconds = timeoutSeconds
        )
    }
    
    fun saveConfig(config: EmbeddingConfig) {
        prefs.edit()
            .putString("provider_type", config.providerType.name)
            .putString("model_name", config.modelName)
            .putString("ollama_base_url", config.ollamaBaseUrl)
            .putString("api_key", config.apiKey ?: "")
            .putInt("batch_size", config.batchSize)
            .putInt("timeout_seconds", config.timeoutSeconds)
            .apply()
    }
    
    fun createProvider(config: EmbeddingConfig = getConfig()): EmbeddingProvider {
        return when (config.providerType) {
            EmbeddingConfig.ProviderType.OLLAMA -> OllamaEmbeddingProvider(config)
            EmbeddingConfig.ProviderType.OPENAI_COMPATIBLE -> OpenAiCompatibleEmbeddingProvider(config)
            EmbeddingConfig.ProviderType.LOCAL -> LocalEmbeddingProvider(config)
        }
    }
}

class OpenAiCompatibleEmbeddingProvider(
    private val config: EmbeddingConfig,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(config.timeoutSeconds.toLong() * 2, TimeUnit.SECONDS)
        .writeTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .build()
) : EmbeddingProvider {
    override val modelName: String = config.modelName
    override val dimensions: Int = 1536 // text-embedding-3-small default
    
    private val gson = Gson()
    private val baseUrl = config.ollamaBaseUrl.trimEnd('/') // Reuse field for base URL
    
    override suspend fun embed(text: String): EmbeddingProvider.OpResult<FloatArray> = withContext(Dispatchers.IO) {
        try {
            val requestBody = JsonObject().apply {
                addProperty("model", modelName)
                addProperty("input", text)
            }
            
            val request = Request.Builder()
                .url("$baseUrl/embeddings")
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext EmbeddingProvider.OpResult.Failure("HTTP ${response.code}: ${response.body?.string()}")
                }
                
                val responseBody = response.body?.string() ?: return@withContext EmbeddingProvider.OpResult.Failure("Empty response")
                val json = JsonParser.parseString(responseBody).asJsonObject
                val embedding = json.getAsJsonArray("data").get(0).asJsonObject.getAsJsonArray("embedding")
                
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
        try {
            val requestBody = JsonObject().apply {
                addProperty("model", modelName)
                add("input", gson.toJsonTree(texts))
            }
            
            val request = Request.Builder()
                .url("$baseUrl/embeddings")
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext EmbeddingProvider.OpResult.Failure("HTTP ${response.code}: ${response.body?.string()}")
                }
                
                val responseBody = response.body?.string() ?: return@withContext EmbeddingProvider.OpResult.Failure("Empty response")
                val json = JsonParser.parseString(responseBody).asJsonObject
                val dataArray = json.getAsJsonArray("data")
                
                val results = mutableListOf<FloatArray>()
                for (i in 0 until dataArray.size()) {
                    val embedding = dataArray[i].asJsonObject.getAsJsonArray("embedding")
                    val floatArray = FloatArray(embedding.size())
                    for (j in 0 until embedding.size()) {
                        floatArray[j] = embedding[j].asFloat
                    }
                    results.add(floatArray)
                }
                
                EmbeddingProvider.OpResult.Success(results)
            }
        } catch (e: Exception) {
            EmbeddingProvider.OpResult.Failure("Batch embedding failed: ${e.message}")
        }
    }
}

class LocalEmbeddingProvider(private val config: EmbeddingConfig) : EmbeddingProvider {
    override val modelName: String = config.modelName
    override val dimensions: Int = 384 // Smaller local model
    
    // Placeholder for on-device embedding (e.g., using MediaPipe, TensorFlow Lite, or ONNX Runtime)
    // This would load a quantized model and run inference locally
    override suspend fun embed(text: String): EmbeddingProvider.OpResult<FloatArray> = withContext(Dispatchers.IO) {
        // TODO: Implement local embedding using MediaPipe/ONNX/TFLite
        EmbeddingProvider.OpResult.Failure("Local embedding not yet implemented. Use Ollama or OpenAI-compatible provider.")
    }
    
    override suspend fun embedBatch(texts: List<String>): EmbeddingProvider.OpResult<List<FloatArray>> = withContext(Dispatchers.IO) {
        EmbeddingProvider.OpResult.Failure("Local embedding not yet implemented.")
    }
}
