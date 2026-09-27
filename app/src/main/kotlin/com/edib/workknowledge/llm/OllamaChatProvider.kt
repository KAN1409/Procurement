package com.edib.workknowledge.llm

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import com.edib.workknowledge.domain.Chunk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URL
import java.util.concurrent.TimeUnit

class OllamaChatProvider(
    private val config: ModelConfig,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(config.timeoutSeconds.toLong() * 4, TimeUnit.SECONDS)
        .writeTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .build()
) : ChatModelProvider {
    override val modelName: String = config.modelName
    
    private val gson = Gson()
    private val baseUrl = config.ollamaBaseUrl.trimEnd('/')
    private val chatUrl = "$baseUrl/api/chat"
    private val generateUrl = "$baseUrl/api/generate"
    
    override suspend fun generateResponse(
        systemPrompt: String,
        userMessage: String,
        contextChunks: List<Chunk>,
        conversationHistory: List<ChatModelProvider.ChatMessage>
    ): ChatModelProvider.OpResult<String> = withContext(Dispatchers.IO) {
        try {
            val prompt = buildPrompt(systemPrompt, userMessage, contextChunks, conversationHistory)
            
            val requestBody = JsonObject().apply {
                addProperty("model", modelName)
                addProperty("prompt", prompt)
                addProperty("stream", false)
                add("options", buildOptions())
            }
            
            val request = Request.Builder()
                .url(generateUrl)
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext ChatModelProvider.OpResult.Failure("HTTP ${response.code}: ${response.body?.string()}")
                }
                
                val responseBody = response.body?.string() ?: return@withContext ChatModelProvider.OpResult.Failure("Empty response")
                val json = JsonParser.parseString(responseBody).asJsonObject
                val responseText = json.get("response").asString
                
                ChatModelProvider.OpResult.Success(responseText)
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
            val prompt = buildPrompt(systemPrompt, userMessage, contextChunks, conversationHistory)
            
            val requestBody = JsonObject().apply {
                addProperty("model", modelName)
                addProperty("prompt", prompt)
                addProperty("stream", true)
                add("options", buildOptions())
            }
            
            val request = Request.Builder()
                .url(generateUrl)
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
                        
                        if (line.isNotBlank()) {
                            try {
                                val json = JsonParser.parseString(line).asJsonObject
                                if (json.has("response")) {
                                    val token = json.get("response").asString
                                    fullResponse.append(token)
                                    onToken(token)
                                }
                                if (json.get("done").asBoolean) break
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
    
    private fun buildPrompt(
        systemPrompt: String,
        userMessage: String,
        contextChunks: List<Chunk>,
        conversationHistory: List<ChatModelProvider.ChatMessage>
    ): String {
        val sb = StringBuilder()
        
        // System prompt
        sb.append("<|system|>\n$systemPrompt\n\n")
        
        // Context
        if (contextChunks.isNotEmpty()) {
            sb.append("SOURCE CONTEXT:\n")
            for ((idx, chunk) in contextChunks.withIndex()) {
                sb.append("--- SOURCE ${idx + 1} ---\n")
                sb.append("Document: ${chunk.fullCitationString}\n")
                sb.append("Content: ${chunk.content}\n\n")
            }
            sb.append("--- END SOURCE CONTEXT ---\n\n")
        }
        
        // Conversation history (last 6 messages to manage context)
        if (conversationHistory.isNotEmpty()) {
            sb.append("CONVERSATION HISTORY:\n")
            for (msg in conversationHistory.takeLast(6)) {
                val role = when (msg.role) {
                    ChatModelProvider.ChatMessage.Role.USER -> "User"
                    ChatModelProvider.ChatMessage.Role.ASSISTANT -> "Assistant"
                    ChatModelProvider.ChatMessage.Role.SYSTEM -> "System"
                }
                sb.append("$role: ${msg.content}\n")
            }
            sb.append("\n")
        }
        
        // Current user message
        sb.append("<|user|>\n$userMessage\n\n<|assistant|>")
        
        return sb.toString()
    }
    
    private fun buildOptions(): JsonObject {
        return JsonObject().apply {
            addProperty("temperature", config.temperature)
            addProperty("top_p", 0.9)
            addProperty("num_predict", config.maxTokens)
            add(
                "stop",
                JsonArray().apply {
                    add("<|user|>")
                    add("<|system|>")
                }
            )
        }
    }
    
    suspend fun testConnection(): ChatModelProvider.OpResult<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/tags")
                .get()
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    ChatModelProvider.OpResult.Success("Connected to Ollama at $baseUrl")
                } else {
                    ChatModelProvider.OpResult.Failure("Ollama not reachable: HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            ChatModelProvider.OpResult.Failure("Connection failed: ${e.message}")
        }
    }
    
    suspend fun pullModel(): ChatModelProvider.OpResult<String> = withContext(Dispatchers.IO) {
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
                    ChatModelProvider.OpResult.Success("Model $modelName pulled successfully")
                } else {
                    ChatModelProvider.OpResult.Failure("Failed to pull model: HTTP ${response.code}: ${response.body?.string()}")
                }
            }
        } catch (e: Exception) {
            ChatModelProvider.OpResult.Failure("Pull failed: ${e.message}")
        }
    }
}
