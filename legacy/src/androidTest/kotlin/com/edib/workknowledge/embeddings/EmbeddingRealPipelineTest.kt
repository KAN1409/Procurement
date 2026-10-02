package com.edib.workknowledge.embeddings

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
class EmbeddingRealPipelineTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val modelName = "all-MiniLM-L6-v2"
    private val TAG = "EmbeddingRealPipeline"

    // all-MiniLM-L6-v2 produces 384-dimensional embeddings
    private val expectedDims = 384

    private fun cosSim(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val mag = sqrt(na) * sqrt(nb)
        return if (mag > 0.0) (dot / mag).toFloat() else 0f
    }

    private fun l2Norm(a: FloatArray): Double {
        var sum = 0.0
        for (v in a) sum += v * v
        return sqrt(sum)
    }

    private fun maxAbsDiff(a: FloatArray, b: FloatArray): Float {
        var max = 0f
        for (i in a.indices) {
            val d = abs(a[i] - b[i])
            if (d > max) max = d
        }
        return max
    }

    @Test
    fun model_files_exist() = runBlocking {
        // Copy model files from /data/local/tmp/ to app storage
        val modelDir = ModelSetupHelper.copyModelToDevice(context, modelName)
        val modelFile = java.io.File(modelDir, "model.onnx")
        val vocabFile = java.io.File(modelDir, "vocab.txt")

        println("=== MODEL FILE CHECK ===")
        println("Model dir: $modelDir")
        println("Model exists: ${modelFile.exists()}")
        println("Vocab exists: ${vocabFile.exists()}")
        println("Model size: ${modelFile.length()} bytes")

        assertTrue("Model .onnx must exist", modelFile.exists())
        assertTrue("vocab.txt must exist", vocabFile.exists())
        assertTrue("Model file must be > 1MB", modelFile.length() > 1_000_000)
    }

    @Test
    fun real_embedding_pipeline() = runBlocking {
        val modelDir = ModelSetupHelper.copyModelToDevice(context, modelName)
        val config = EmbeddingConfig(
            providerType = EmbeddingConfig.ProviderType.LOCAL,
            modelName = modelName
        )

        val provider = OnDeviceEmbeddingProvider(context, modelDir, config)

        // --- Initialization ---
        val t0 = System.nanoTime()
        val initResult = provider.initialize()
        val initMs = (System.nanoTime() - t0) / 1_000_000.0
        println("=== INITIALIZATION ===")
        println("Init result: $initResult")
        println("Init latency: ${initMs}ms")

        assertTrue("Provider must initialize: $initResult",
            initResult is EmbeddingProvider.OpResult.Success)

        assertEquals("Dimensions must be $expectedDims", expectedDims, provider.dimensions)

        // --- Embed "Galala Marble" ---
        val t1 = System.nanoTime()
        val embedResult = provider.embed("Galala Marble")
        val inferMs = (System.nanoTime() - t1) / 1_000_000.0
        println("\n=== EMBEDDING PIPELINE ===")
        println("embed('Galala Marble') result: $embedResult")
        println("Inference latency: ${inferMs}ms")

        assertTrue("Embed must succeed: $embedResult",
            embedResult is EmbeddingProvider.OpResult.Success)
        val vec = (embedResult as EmbeddingProvider.OpResult.Success<FloatArray>).value

        // --- Dimension check ---
        println("Vector size: ${vec.size}")
        assertEquals("Embedding must be $expectedDims dimensions", expectedDims, vec.size)

        // --- Finiteness check ---
        var allFinite = true
        var hasNonZero = false
        for (v in vec) {
            if (v.isNaN() || v.isInfinite()) allFinite = false
            if (v != 0f) hasNonZero = true
        }
        assertTrue("All values must be finite", allFinite)
        assertTrue("Vector must not be all zeros", hasNonZero)

        // --- L2 norm check (approximately 1.0 after normalization) ---
        val norm = l2Norm(vec)
        println("L2 norm: $norm")
        assertTrue("L2 norm must be ~1.0, got $norm", abs(norm - 1.0) < 1e-3)

        // --- Determinism check ---
        println("\n=== DETERMINISM CHECK ===")
        val result2 = provider.embed("Galala Marble")
        assertTrue("Second embed must succeed",
            result2 is EmbeddingProvider.OpResult.Success)
        val vec2 = (result2 as EmbeddingProvider.OpResult.Success<FloatArray>).value

        val sim = cosSim(vec, vec2)
        val mae = maxAbsDiff(vec, vec2)
        println("Cosine similarity (same text): $sim")
        println("Max absolute difference: $mae")
        assertTrue("Same text must produce identical embeddings (cos≈1.0), got $sim",
            abs(sim - 1.0) < 1e-4)
        assertTrue("Max abs diff must be ~0, got $mae", abs(mae) < 1e-5)

        provider.close()
        println("\n=== REAL EMBEDDING PIPELINE TEST PASSED ===")
    }

    @Test
    fun deterministic_embeddings() = runBlocking {
        val modelDir = ModelSetupHelper.copyModelToDevice(context, modelName)
        val config = EmbeddingConfig(
            providerType = EmbeddingConfig.ProviderType.LOCAL,
            modelName = modelName
        )
        val provider = OnDeviceEmbeddingProvider(context, modelDir, config)
        provider.initialize()

        val texts = listOf(
            "Galala Marble",
            "Master Bathroom Galala Marble",
            "PR-023 approved purchase request",
            "رخام جلالة",
            "How thick is the marble?"
        )

        println("=== DETERMINISM REPORT ===")
        var allIdentical = true
        for (text in texts) {
            val r1 = provider.embed(text)
            val r2 = provider.embed(text)
            if (r1 is EmbeddingProvider.OpResult.Success &&
                r2 is EmbeddingProvider.OpResult.Success) {
                val v1 = (r1 as EmbeddingProvider.OpResult.Success<FloatArray>).value
                val v2 = (r2 as EmbeddingProvider.OpResult.Success<FloatArray>).value
                val sim = cosSim(v1, v2)
                val mae = maxAbsDiff(v1, v2)
                val deterministic = (abs(sim - 1.0f) < 1e-4) && (abs(mae) < 1e-5)
                println("  '$text': cos=$sim, maxAbsDiff=$mae, deterministic=$deterministic")
                if (!deterministic) allIdentical = false
            }
        }
        assertTrue("All embeddings must be deterministic", allIdentical)
        provider.close()
    }

    @Test
    fun semantic_similarity_report() = runBlocking {
        val modelDir = ModelSetupHelper.copyModelToDevice(context, modelName)
        val config = EmbeddingConfig(
            providerType = EmbeddingConfig.ProviderType.LOCAL,
            modelName = modelName
        )
        val provider = OnDeviceEmbeddingProvider(context, modelDir, config)
        provider.initialize()

        val testTexts = listOf(
            "Galala Marble",
            "marble wall cladding",
            "رخام جلالة",                      // Arabic for "Galala Marble"
            "Master Bathroom Galala Marble",
            "كام كمية Galala Marble في الـ Master Bathroom؟",  // Arabic: "How much Galala Marble in the Master Bathroom?"
            "PR-023",
            "approved purchase request",
            "إيه حالة PR-023؟",                   // Arabic: "What is the status of PR-023?"
            "unrelated weather text",
            "The weather is sunny and warm today"
        )

        val embeddings = testTexts.map { text ->
            val result = provider.embed(text)
            assertTrue("Embed must succeed for '$text': $result",
                result is EmbeddingProvider.OpResult.Success)
            (result as EmbeddingProvider.OpResult.Success<FloatArray>).value
        }

        println("=== SEMANTIC SIMILARITY REPORT ===")
        println("Model: all-MiniLM-L6-v2 (int8 quantized, 384-dim)")
        println("NOTE: all-MiniLM-L6-v2 is an English-first model. Arabic is")
        println("  tokenized as WordPiece subwords, which limits semantic quality.")
        println()

        // Core expected similarity groups
        val galalaIdx = 0
        val wallCladdingIdx = 1
        val arabicMarbleIdx = 2
        val bathroomMarbleIdx = 3

        val sim_galala_wall = cosSim(embeddings[galalaIdx], embeddings[wallCladdingIdx])
        val sim_galala_bathroom = cosSim(embeddings[galalaIdx], embeddings[bathroomMarbleIdx])
        val sim_galala_arabic = cosSim(embeddings[galalaIdx], embeddings[arabicMarbleIdx])
        val sim_arabic_bathroom = cosSim(embeddings[arabicMarbleIdx], embeddings[bathroomMarbleIdx])

        println("Galala Marble ↔ marble wall cladding: $sim_galala_wall")
        println("Galala Marble ↔ Master Bathroom Galala Marble: $sim_galala_bathroom")
        println("Galala Marble ↔ رخام جلالة: $sim_galala_arabic")
        println("رخام جلالة ↔ Master Bathroom Galala Marble: $sim_arabic_bathroom")

        // PR-023 group
        val prIdx = 5
        val pr_approved_idx = 6
        val pr_arabic_idx = 7

        val sim_pr_approved = cosSim(embeddings[prIdx], embeddings[pr_approved_idx])
        val sim_pr_arabic = cosSim(embeddings[prIdx], embeddings[pr_arabic_idx])

        println("PR-023 ↔ approved purchase request: $sim_pr_approved")
        println("PR-023 ↔ إيه حالة PR-023؟: $sim_pr_arabic")

        // Unrelated text
        val weatherIdx = 8
        val weatherIdx2 = 9
        val sim_weather = cosSim(embeddings[weatherIdx], embeddings[weatherIdx2])
        val sim_galala_weather = cosSim(embeddings[galalaIdx], embeddings[weatherIdx])

        println("unrelated weather text ↔ The weather is sunny: $sim_weather")
        println("Galala Marble ↔ unrelated weather text: $sim_galala_weather")

        // Full pairwise matrix
        println("\n=== Full similarity matrix ===")
        for (i in testTexts.indices) {
            for (j in (i + 1) until testTexts.size) {
                val sim = cosSim(embeddings[i], embeddings[j])
                println("${testTexts[i]} ↔ ${testTexts[j]}: $sim")
            }
        }

        // Assertions: English semantic relationships should hold
        assertTrue("Galala Marble should be similar to 'marble wall cladding'",
            sim_galala_wall > 0.3f)
        assertTrue("Galala Marble should be similar to 'Master Bathroom Galala Marble'",
            sim_galala_bathroom > 0.5f)
        assertTrue("Unrelated weather text should be dissimilar to Galala Marble",
            sim_galala_weather < 0.3f)

        Log.i("SemanticReport", "Galala↔wall=$sim_galala_wall Galala↔bathroom=$sim_galala_bathroom " +
            "Galala↔arabic=$sim_galala_arabic PR↔approved=$sim_pr_approved PR↔arabic=$sim_pr_arabic")

        provider.close()
        println("\n=== SEMANTIC MEASUREMENTS COMPLETED ===")
        println("(English semantics verified; Arabic quality reported honestly above)")
    }
}
