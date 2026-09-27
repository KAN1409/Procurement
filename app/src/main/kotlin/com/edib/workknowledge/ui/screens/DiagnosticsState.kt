package com.edib.workknowledge.ui.screens

/** Everything the diagnostics screen needs, gathered in one pass. */
data class DiagnosticsState(
    val documentCount: Int = 0,
    val chunkCount: Int = 0,
    val embeddingProvider: String = "Not configured",
    val embeddingModel: String = "—",
    val storedDimensions: Int = 0,
    val indexStatus: String = "Empty",
    val chatProvider: String = "Not configured",
    val chatModel: String = "—",
    val hasApiKey: Boolean = false,
    val ollamaUrl: String = "—",
    val ollamaStatus: String = "Not tested",
    val recentErrors: List<Pair<String, Long>> = emptyList()
)
