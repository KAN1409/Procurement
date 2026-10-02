package com.edib.workknowledge.embeddings

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Copies model assets from /data/local/tmp/ (world-readable, placed by the
 * test harness) into the app's private files directory so that
 * [OnDeviceEmbeddingProvider] can find them at runtime.
 *
 * The source files are placed on the device by the test harness before
 * instrumentation execution. They must be world-readable (chmod 666) so the
 * app process (running as the app uid) can read them.
 */
object ModelSetupHelper {
    private const val TAG = "ModelSetupHelper"

    private val MODEL_FILES = listOf("model.onnx", "vocab.txt", "tokenizer.json",
        "tokenizer_config.json", "special_tokens_map.json")

    private const val SRC_DIR = "/data/local/tmp/"

    /**
     * Copies model files from [SRC_DIR] into context.getDir("models", MODE_PRIVATE)/all-MiniLM-L6-v2/.
     * Returns the model directory File.
     */
    fun copyModelToDevice(context: Context, modelName: String = "all-MiniLM-L6-v2"): File {
        val modelDir = File(context.getDir("models", Context.MODE_PRIVATE), modelName)
        modelDir.mkdirs()

        for (fileName in MODEL_FILES) {
            val srcFile = File(SRC_DIR, fileName)
            val destFile = File(modelDir, fileName)

            if (srcFile.exists()) {
                srcFile.copyTo(destFile, overwrite = true)
                Log.d(TAG, "Copied $fileName: ${srcFile.length()} -> ${destFile.length()} bytes")
            } else {
                Log.e(TAG, "Source file not found: $srcFile")
            }
        }

        // Verify all files are present
        val allPresent = MODEL_FILES.all { File(modelDir, it).exists() }
        if (!allPresent) {
            throw IllegalStateException(
                "Not all model files copied. Missing: " +
                    MODEL_FILES.filter { !File(modelDir, it).exists() }
            )
        }

        Log.d(TAG, "Model directory ready: $modelDir, total size: ${modelDir.totalSize()} bytes")
        return modelDir
    }

    private fun File.totalSize(): Long =
        if (isFile) length() else (listFiles()?.sumOf { it.totalSize() } ?: 0)
}
