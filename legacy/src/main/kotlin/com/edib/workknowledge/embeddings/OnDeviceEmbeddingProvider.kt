package com.edib.workknowledge.embeddings

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.Result
import ai.onnxruntime.OrtSession.SessionOptions
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.NodeInfo
import ai.onnxruntime.TensorInfo
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.runCatching
import kotlin.math.sqrt

/**
 * On-device embedding provider using ONNX Runtime for Android.
 * 
 * This provider loads a quantized ONNX model and tokenizer assets from the app's
 * private storage, runs tokenization and inference locally on the device.
 * 
 * Model: all-MiniLM-L6-v2 (quantized int8 ARM64)
 * - 384 dimensions
 * - BERT WordPiece tokenizer (vocab.txt)
 * - Mean pooling over last hidden states
 * - L2 normalization for cosine similarity
 * 
 * The model and tokenizer assets must be placed in the app's private storage
 * before initialization.
 */
class OnDeviceEmbeddingProvider(
    private val context: Context,
    private val modelDir: File,
    private val config: EmbeddingConfig
) : EmbeddingProvider {
    
    private val TAG = "OnDeviceEmbeddingProvider"
    private val MODEL_FILE = "model.onnx"
    private val TOKENIZER_FILE = "vocab.txt"
    private val TOKENIZER_CONFIG_FILE = "tokenizer_config.json"
    private val SPECIAL_TOKENS_FILE = "special_tokens_map.json"
    
    private var ortSession: OrtSession? = null
    private var tokenizer: WordPieceTokenizer? = null
    private var isInitialized = false
    private var modelDimensions = 384
    
    init {
        // Verify model directory exists
        if (!modelDir.exists() || !modelDir.isDirectory()) {
            throw IllegalArgumentException("Model directory does not exist: $modelDir")
        }
        
        val modelFile = File(modelDir, MODEL_FILE)
        if (!modelFile.exists()) {
            throw IllegalArgumentException("Model file not found: $modelFile")
        }
        
        val tokenizerFile = File(modelDir, TOKENIZER_FILE)
        if (!tokenizerFile.exists()) {
            throw IllegalArgumentException("Tokenizer vocab not found: $tokenizerFile")
        }
    }
    
    override val modelName: String = config.modelName
    override val dimensions: Int = if (isInitialized) modelDimensions else 384
    
    /**
     * Initializes the ONNX Runtime session and tokenizer.
     * Must be called before any embedding operations.
     */
    suspend fun initialize(): EmbeddingProvider.OpResult<Unit> = withContext(Dispatchers.IO) {
        if (isInitialized) {
            return@withContext EmbeddingProvider.OpResult.Success(Unit)
        }
        
        return@withContext try {
            // Initialize tokenizer
            val vocabFile = File(modelDir, TOKENIZER_FILE)
            if (!vocabFile.exists()) {
                return@withContext EmbeddingProvider.OpResult.Failure("Tokenizer vocab.txt not found")
            }
            
            tokenizer = WordPieceTokenizer(vocabFile)
            
            // Initialize ONNX Runtime session
            val sessionOptions = SessionOptions()
            sessionOptions.addConfigEntry("session.disable_cpu_mem_arena", "1")
            sessionOptions.addConfigEntry("session.disable_mem_pattern", "1")
            sessionOptions.setIntraOpNumThreads(2) // Limit threads for mobile
            
            val modelFile = File(modelDir, MODEL_FILE)
            ortSession = OrtEnvironment.getEnvironment().createSession(modelFile.absolutePath, sessionOptions)
            
            // Verify model dimensions from metadata
            val outputInfo = ortSession!!.getOutputInfo()
            var dimsFound = false
            for (entry in outputInfo.entries) {
                val name = entry.key
                val nodeInfo = entry.value
                if (name.contains("embedding") || name.contains("output") || name.contains("last_hidden_state")) {
                    val info = nodeInfo.info
                    if (info is TensorInfo) {
                        val shape = info.shape
                        if (shape.size >= 3) {
                            // Shape: [batch, seq_len, hidden] -> hidden is last dim
                            modelDimensions = shape[shape.size - 1].toInt()
                            dimsFound = true
                            Log.i(TAG, "Found embedding dimension from output '$name': $modelDimensions")
                            break
                        }
                    }
                }
            }
            if (!dimsFound) {
                modelDimensions = 384 // Default fallback for all-MiniLM-L6-v2
                Log.w(TAG, "Could not determine embedding dimension from model metadata, using default: $modelDimensions")
            }
            
            isInitialized = true
            Log.i(TAG, "OnDeviceEmbeddingProvider initialized with ${dimensions} dimensions")
            EmbeddingProvider.OpResult.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize on-device embedding provider", e)
            EmbeddingProvider.OpResult.Failure("Initialization failed: ${e.message}")
        }
    }
    
    override suspend fun embed(text: String): EmbeddingProvider.OpResult<FloatArray> = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            val initResult = initialize()
            if (initResult is EmbeddingProvider.OpResult.Failure) {
                return@withContext initResult
            }
        }
        
        return@withContext try {
            val tokenizerRef = tokenizer ?: return@withContext EmbeddingProvider.OpResult.Failure("Tokenizer not initialized")
            val session = ortSession ?: return@withContext EmbeddingProvider.OpResult.Failure("ONNX session not initialized")
            
            // Tokenize
            val encoding = tokenizerRef.encode(text, maxLength = 512)
            val inputIds = encoding.inputIds
            val attentionMask = encoding.attentionMask
            val tokenTypeIds = encoding.tokenTypeIds
            
            // Create ONNX tensors - shape [1, seq_len]
            val inputIdsTensor = OnnxTensor.createTensor(
                OrtEnvironment.getEnvironment(),
                LongBuffer.wrap(inputIds.map { it.toLong() }.toLongArray()),
                longArrayOf(1, inputIds.size.toLong())
            )
            val attentionMaskTensor = OnnxTensor.createTensor(
                OrtEnvironment.getEnvironment(),
                LongBuffer.wrap(attentionMask.map { it.toLong() }.toLongArray()),
                longArrayOf(1, attentionMask.size.toLong())
            )
            val tokenTypeIdsTensor = OnnxTensor.createTensor(
                OrtEnvironment.getEnvironment(),
                LongBuffer.wrap(tokenTypeIds.map { it.toLong() }.toLongArray()),
                longArrayOf(1, tokenTypeIds.size.toLong())
            )
            
            // Run inference
            val inputs = mapOf<String, OnnxTensor>(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attentionMaskTensor,
                "token_type_ids" to tokenTypeIdsTensor
            )
            
            val results: Result = session.run(inputs)
            
            // Get last hidden state (first output for BERT models)
            // results is Iterable<Map.Entry<String, OnnxValue>>
            var firstValue: OnnxValue? = null
            val resultsIterator = results.iterator()
            if (resultsIterator.hasNext()) {
                val entry = resultsIterator.next()
                firstValue = entry.value
            }
            val outputValue = firstValue ?: return@withContext EmbeddingProvider.OpResult.Failure("No output from ONNX model")
            // For a Float tensor of shape [1, seq_len, hidden], OnnxTensor.getValue()
            // returns float[][][] (Kotlin: Array<Array<FloatArray>>).
            val outputArray = outputValue.value as Array<Array<FloatArray>>
            // Shape: [batch=1, seq_len, hidden]
            val hiddenStates = outputArray[0] // Remove batch dim -> Array<FloatArray>
            
            // Mean pooling with attention mask
            val embedding = meanPool(hiddenStates, attentionMask)
            
            // L2 normalize
            normalize(embedding)
            
            EmbeddingProvider.OpResult.Success(embedding)
        } catch (e: Exception) {
            Log.e(TAG, "Embedding inference failed", e)
            EmbeddingProvider.OpResult.Failure("Embedding failed: ${e.message}")
        }
    }
    
    override suspend fun embedBatch(texts: List<String>): EmbeddingProvider.OpResult<List<FloatArray>> = withContext(Dispatchers.IO) {
        val results = mutableListOf<FloatArray>()
        for (text in texts) {
            val result = embed(text)
            if (result is EmbeddingProvider.OpResult.Failure) {
                return@withContext result
            }
            if (result is EmbeddingProvider.OpResult.Success) {
                results.add(result.value)
            }
        }
        EmbeddingProvider.OpResult.Success(results)
    }
    
    /**
     * Mean pooling over token embeddings using attention mask.
     */
    private fun meanPool(hiddenStates: Array<FloatArray>, attentionMask: IntArray): FloatArray {
        val seqLen = hiddenStates.size
        val hiddenSize = hiddenStates[0].size
        val sum = FloatArray(hiddenSize) { 0f }
        var tokenCount = 0
        
        for (i in 0 until seqLen) {
            if (attentionMask[i] == 1) {
                val tokenEmbedding = hiddenStates[i]
                for (j in 0 until hiddenSize) {
                    sum[j] += tokenEmbedding[j]
                }
                tokenCount++
            }
        }
        
        // Avoid division by zero
        val count = if (tokenCount > 0) tokenCount else 1
        for (j in 0 until hiddenSize) {
            sum[j] /= count
        }
        return sum
    }
    
    /**
     * L2 normalization for cosine similarity.
     */
    private fun normalize(embedding: FloatArray) {
        var sum = 0f
        for (v in embedding) {
            sum += v * v
        }
        val norm = sqrt(sum.toDouble()).toFloat()
        if (norm > 0f) {
            for (i in embedding.indices) {
                embedding[i] /= norm
            }
        }
    }
    
    fun close() {
        runCatching { ortSession?.close() }
        isInitialized = false
    }
    
    /**
     * WordPiece tokenizer using vocab.txt (BERT-style).
     */
    private class WordPieceTokenizer(private val vocabFile: File) {
        private val vocab = mutableMapOf<String, Int>()
        private val vocabArray = ArrayList<String>()
        private val unkToken = "[UNK]"
        private val clsToken = "[CLS]"
        private val sepToken = "[SEP]"
        private val padToken = "[PAD]"
        private val unkId: Int
        private val clsId: Int
        private val sepId: Int
        private val padId: Int
        private val maxLength = 512
        
        init {
            vocabFile.readLines().forEachIndexed { index, token ->
                val trimmed = token.trim()
                vocab[trimmed] = index
                vocabArray.add(trimmed)
            }
            unkId = vocab[unkToken] ?: 100
            clsId = vocab[clsToken] ?: 101
            sepId = vocab[sepToken] ?: 102
            padId = vocab[padToken] ?: 0
            Log.d("WordPieceTokenizer", "Loaded vocab size: ${vocab.size}, unk=$unkId, cls=$clsId, sep=$sepId, pad=$padId")
        }
        
        fun encode(text: String, maxLength: Int = 512): TokenizedOutput {
            // Basic BERT preprocessing: lowercase, then WordPiece tokenize
            val words = basicTokenize(text.lowercase())
            val tokens = mutableListOf<String>()
            tokens.add(clsToken)
            
            for (word in words) {
                val wordPieces = wordPieceTokenize(word)
                if (tokens.size + wordPieces.size + 1 >= maxLength) {
                    break
                }
                tokens.addAll(wordPieces)
            }
            tokens.add(sepToken)
            
            val inputIds = tokens.map { vocab[it] ?: unkId }.toIntArray()
            val attentionMask = IntArray(inputIds.size) { 1 }
            val tokenTypeIds = IntArray(inputIds.size) { 0 }
            
            // Pad to maxLength if needed
            val paddedIds = if (inputIds.size < maxLength) {
                inputIds + IntArray(maxLength - inputIds.size) { padId }
            } else {
                inputIds.copyOf(maxLength)
            }
            val paddedMask = if (attentionMask.size < maxLength) {
                attentionMask + IntArray(maxLength - attentionMask.size) { 0 }
            } else {
                attentionMask.copyOf(maxLength)
            }
            val paddedTypeIds = if (tokenTypeIds.size < maxLength) {
                tokenTypeIds + IntArray(maxLength - tokenTypeIds.size) { 0 }
            } else {
                tokenTypeIds.copyOf(maxLength)
            }
            
            return TokenizedOutput(paddedIds, paddedMask, paddedTypeIds)
        }
        
        /**
         * Basic tokenization: split on whitespace and punctuation.
         */
        private fun basicTokenize(text: String): List<String> {
            val words = mutableListOf<String>()
            val sb = StringBuilder()
            
            for (c in text) {
                if (c.isWhitespace() || isPunctuation(c)) {
                    if (sb.isNotEmpty()) {
                        words.add(sb.toString())
                        sb.clear()
                    }
                    if (isPunctuation(c) && !words.contains(c.toString())) {
                        // Keep punctuation as separate tokens
                        words.add(c.toString())
                    }
                } else {
                    sb.append(c)
                }
            }
            if (sb.isNotEmpty()) {
                words.add(sb.toString())
            }
            return words
        }
        
        private fun isPunctuation(c: Char): Boolean {
            val code = c.toInt()
            return (code >= 33 && code <= 47) || (code >= 58 && code <= 64) ||
                   (code >= 91 && code <= 96) || (code >= 123 && code <= 126)
        }
        
        /**
         * WordPiece tokenization: greedy longest-match-first.
         */
        private fun wordPieceTokenize(word: String): List<String> {
            if (vocab.containsKey(word)) {
                return listOf(word)
            }
            
            val tokens = mutableListOf<String>()
            var start = 0
            while (start < word.length) {
                var end = word.length
                var curSubstr: String? = null
                while (start < end) {
                    val substr = if (start == 0) word.substring(start, end) else "##" + word.substring(start, end)
                    if (vocab.containsKey(substr)) {
                        curSubstr = substr
                        break
                    }
                    end--
                }
                if (curSubstr != null) {
                    tokens.add(curSubstr)
                    start = end
                } else {
                    // Unknown character - fallback to [UNK]
                    tokens.add(unkToken)
                    break
                }
            }
            return tokens
        }
        
        data class TokenizedOutput(
            val inputIds: IntArray,
            val attentionMask: IntArray,
            val tokenTypeIds: IntArray
        )
    }
    
    companion object {
        private val TAG = "OnDeviceEmbeddingProvider"
        private val MODEL_FILE = "model.onnx"
        private val TOKENIZER_FILE = "vocab.txt"
        private val TOKENIZER_CONFIG_FILE = "tokenizer_config.json"
        private val SPECIAL_TOKENS_FILE = "special_tokens_map.json"
        
        /**
         * Creates an OnDeviceEmbeddingProvider from assets or downloads it if needed.
         * 
         * @param context Application context
         * @param modelName Name of the model (used for directory naming)
         * @param modelUrl Optional URL to download model from
         * @return Initialized provider or failure
         */
        @VisibleForTesting
        suspend fun create(
            context: Context,
            modelName: String,
            modelUrl: String? = null
        ): EmbeddingProvider.OpResult<OnDeviceEmbeddingProvider> = withContext(Dispatchers.IO) {
            val modelDir = File(context.filesDir, "models/$modelName")
            modelDir.mkdirs()
            
            // Check if model exists locally, otherwise download
            val modelFile = File(modelDir, "model.onnx")
            if (!modelFile.exists()) {
                if (modelUrl != null) {
                    if (!downloadModel(modelUrl, modelDir)) {
                        return@withContext EmbeddingProvider.OpResult.Failure("Failed to download model from $modelUrl")
                    }
                } else {
                    return@withContext EmbeddingProvider.OpResult.Failure("Model file not found and no download URL provided")
                }
            }
            
            val config = EmbeddingConfig(
                providerType = EmbeddingConfig.ProviderType.LOCAL,
                modelName = modelName
            )
            
            val provider = OnDeviceEmbeddingProvider(context, modelDir, config)
            val initResult = provider.initialize()
            if (initResult is EmbeddingProvider.OpResult.Failure) {
                return@withContext initResult
            }
            
            EmbeddingProvider.OpResult.Success(provider)
        }
        
        private suspend fun downloadModel(url: String, destination: File): Boolean {
            val client = OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .build()
            
            val request = Request.Builder().url(url).build()
            
            return try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use false
                    }
                    val sink = FileOutputStream(destination)
                    response.body?.byteStream()?.copyTo(sink)
                    sink.close()
                    return@use true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download model", e)
                false
            }
        }
    }
}
