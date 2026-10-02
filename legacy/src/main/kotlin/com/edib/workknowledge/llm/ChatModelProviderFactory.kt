package com.edib.workknowledge.llm

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import com.edib.workknowledge.domain.Chunk
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ChatModelProviderFactory(private val context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        "chat_model_config",
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

    fun getConfig(): ModelConfig {
        val providerType = ModelConfig.ProviderType.valueOf(
            string("provider_type") ?: ModelConfig.ProviderType.OLLAMA.name
        )
        val modelName = string("model_name") ?: "llama3.1"
        val ollamaBaseUrl = string("ollama_base_url") ?: "http://10.0.2.2:11434"
        val apiKey = string("api_key")
        val openAiBaseUrl = string("openai_base_url") ?: "https://api.openai.com/v1"
        val temperature = prefs.getFloat("temperature", 0.1f)
        val maxTokens = prefs.getInt("max_tokens", 4096)
        val systemPrompt = string("system_prompt") ?: ModelConfig.DEFAULT_SYSTEM_PROMPT
        
        return ModelConfig(
            providerType = providerType,
            modelName = modelName,
            ollamaBaseUrl = ollamaBaseUrl,
            apiKey = apiKey,
            openAiBaseUrl = openAiBaseUrl,
            temperature = temperature,
            maxTokens = maxTokens,
            systemPrompt = systemPrompt
        )
    }
    
    fun saveConfig(config: ModelConfig) {
        prefs.edit()
            .putString("provider_type", config.providerType.name)
            .putString("model_name", config.modelName)
            .putString("ollama_base_url", config.ollamaBaseUrl)
            .putString("api_key", config.apiKey ?: "")
            .putString("openai_base_url", config.openAiBaseUrl)
            .putFloat("temperature", config.temperature)
            .putInt("max_tokens", config.maxTokens)
            .putString("system_prompt", config.systemPrompt)
            .apply()
    }
    
    fun createProvider(config: ModelConfig = getConfig()): ChatModelProvider {
        return when (config.providerType) {
            ModelConfig.ProviderType.OLLAMA -> OllamaChatProvider(config)
            ModelConfig.ProviderType.OPENAI_COMPATIBLE -> OpenAiCompatibleChatProvider(config)
            ModelConfig.ProviderType.GROQ -> GroqChatProvider(config)
            ModelConfig.ProviderType.LOCAL -> LocalChatProvider(config)
        }
    }
}

open class OpenAiCompatibleChatProvider(
    private val config: ModelConfig,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) : ChatModelProvider {
    override val modelName: String = config.modelName
    
    private val gson = Gson()
    private val baseUrl = config.openAiBaseUrl.trimEnd('/')
    private val chatUrl = "$baseUrl/chat/completions"
    
    override suspend fun generateResponse(
        systemPrompt: String,
        userMessage: String,
        contextChunks: List<Chunk>,
        conversationHistory: List<ChatModelProvider.ChatMessage>
    ): ChatModelProvider.OpResult<String> = withContext(Dispatchers.IO) {
        try {
            val messages = buildMessages(systemPrompt, userMessage, contextChunks, conversationHistory)
            
            val requestBody = JsonObject().apply {
                addProperty("model", modelName)
                add("messages", gson.toJsonTree(messages))
                addProperty("temperature", config.temperature)
                addProperty("max_tokens", config.maxTokens)
                addProperty("stream", false)
            }
            
            val request = Request.Builder()
                .url(chatUrl)
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext ChatModelProvider.OpResult.Failure("HTTP ${response.code}: ${response.body?.string()}")
                }
                
                val responseBody = response.body?.string() ?: return@withContext ChatModelProvider.OpResult.Failure("Empty response")
                val json = JsonParser.parseString(responseBody).asJsonObject
                val content = json.getAsJsonArray("choices").get(0).asJsonObject
                    .getAsJsonObject("message").get("content").asString
                
                ChatModelProvider.OpResult.Success(content)
            }
        } catch (e: Exception) {
            ChatModelProvider.OpResult.Failure("Generation failed: ${e.message}")
        }
    }
    
    override suspend fun generateStreamingResponse(
        systemPrompt: String,
        userMessage: String,
        contextChunks: List<Chunk>,
        conversationHistory: List<ChatModelProvider.ChatMessage>,
        onToken: (String) -> Unit
    ): ChatModelProvider.OpResult<String> = withContext(Dispatchers.IO) {
        try {
            val messages = buildMessages(systemPrompt, userMessage, contextChunks, conversationHistory)
            
            val requestBody = JsonObject().apply {
                addProperty("model", modelName)
                add("messages", gson.toJsonTree(messages))
                addProperty("temperature", config.temperature)
                addProperty("max_tokens", config.maxTokens)
                addProperty("stream", true)
            }
            
            val request = Request.Builder()
                .url(chatUrl)
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            val fullResponse = StringBuilder()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext ChatModelProvider.OpResult.Failure("HTTP ${response.code}: ${response.body?.string()}")
                }
                
                val source = response.body?.byteStream() ?: return@withContext ChatModelProvider.OpResult.Failure("Empty response body")
                val reader = java.io.BufferedReader(java.io.InputStreamReader(source))
                
                reader.use {
                    var line: String?
                    while (true) {
                        line = reader.readLine()
                        if (line == null) break
                        
                        if (line.startsWith("data: ")) {
                            val data = line.substring(6)
                            if (data == "[DONE]") break
                            
                            try {
                                val json = JsonParser.parseString(data).asJsonObject
                                val delta = json.getAsJsonArray("choices").get(0).asJsonObject
                                    .getAsJsonObject("delta")
                                if (delta.has("content")) {
                                    val token = delta.get("content").asString
                                    fullResponse.append(token)
                                    onToken(token)
                                }
                            } catch (e: Exception) {
                                // Skip malformed lines
                            }
                        }
                    }
                }
            }
            
            ChatModelProvider.OpResult.Success(fullResponse.toString())
        } catch (e: Exception) {
            ChatModelProvider.OpResult.Failure("Streaming generation failed: ${e.message}")
        }
    }
    
    private fun buildMessages(
        systemPrompt: String,
        userMessage: String,
        contextChunks: List<Chunk>,
        conversationHistory: List<ChatModelProvider.ChatMessage>
    ): List<JsonObject> {
        val messages = mutableListOf<JsonObject>()
        
        // System message
        messages.add(JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", systemPrompt)
        })
        
        // Context as system message
        if (contextChunks.isNotEmpty()) {
            val contextSb = StringBuilder("SOURCE CONTEXT:\n")
            for ((idx, chunk) in contextChunks.withIndex()) {
                contextSb.append("--- SOURCE ${idx + 1} ---\n")
                contextSb.append("Document: ${chunk.fullCitationString}\n")
                contextSb.append("Content: ${chunk.content}\n\n")
            }
            contextSb.append("--- END SOURCE CONTEXT ---")
            
            messages.add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", contextSb.toString())
            })
        }
        
        // Conversation history
        for (msg in conversationHistory.takeLast(6)) {
            messages.add(JsonObject().apply {
                addProperty("role", msg.role.name.lowercase())
                addProperty("content", msg.content)
            })
        }
        
        // Current user message
        messages.add(JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", userMessage)
        })
        
        return messages
    }
}

class GroqChatProvider(
    private val config: ModelConfig
) : OpenAiCompatibleChatProvider(
    config.copy(openAiBaseUrl = "https://api.groq.com/openai/v1")
)

open class LocalChatProvider(private val config: ModelConfig) : ChatModelProvider {
    override val modelName: String = config.modelName
    
    // Placeholder for local LLM (llama.cpp, MediaPipe LLM Inference, etc.)
    override suspend fun generateResponse(
        systemPrompt: String,
        userMessage: String,
        contextChunks: List<Chunk>,
        conversationHistory: List<ChatModelProvider.ChatMessage>
    ): ChatModelProvider.OpResult<String> = withContext(Dispatchers.IO) {
        ChatModelProvider.OpResult.Failure("Local LLM not yet implemented. Use Ollama or OpenAI-compatible provider.")
    }
    
    override suspend fun generateStreamingResponse(
        systemPrompt: String,
        userMessage: String,
        contextChunks: List<Chunk>,
        conversationHistory: List<ChatModelProvider.ChatMessage>,
        onToken: (String) -> Unit
    ): ChatModelProvider.OpResult<String> = withContext(Dispatchers.IO) {
        ChatModelProvider.OpResult.Failure("Local LLM not yet implemented.")
    }
}
