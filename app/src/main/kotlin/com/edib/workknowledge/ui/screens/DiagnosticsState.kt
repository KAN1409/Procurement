package com.edib.workknowledge.ui.screens

/**
 * Diagnostics reported as independent capabilities.
 *
 * The point of splitting these is that a device with no AI endpoint is not
 * "broken": it still parses, stores, searches and opens sources. Each capability
 * carries its own status so a user can see exactly what works.
 */
data class DiagnosticsState(
    // --- source data ---
    val projects: Int = 0,
    val documents: Int = 0,
    val chunks: Int = 0,
    val sourceStatus: CapabilityStatus = CapabilityStatus.UNKNOWN,
    val sourceDetail: String? = null,

    // --- exact search ---
    val ftsIndexed: Int = 0,
    val searchStatus: CapabilityStatus = CapabilityStatus.UNKNOWN,
    val searchDetail: String? = null,

    // --- embeddings ---
    val embeddingProvider: String = "Not configured",
    val embeddingModel: String = "—",
    val embeddedChunks: Int = 0,
    val embeddingDimension: Int = 0,
    val embeddingStatus: CapabilityStatus = CapabilityStatus.UNKNOWN,
    val embeddingDetail: String? = null,

    // --- chat model ---
    val chatProvider: String = "Not configured",
    val chatModel: String = "—",
    val chatStatus: CapabilityStatus = CapabilityStatus.UNKNOWN,
    val chatDetail: String? = null,

    // --- derived ---
    val hasApiKey: Boolean = false,
    val ollamaUrl: String = "—",
    val ollamaStatus: CapabilityStatus = CapabilityStatus.UNKNOWN,
    val recentErrors: List<Pair<String, Long>> = emptyList()
) {
    val semanticAvailable: Boolean
        get() = embeddingStatus == CapabilityStatus.READY

    val chatAvailable: Boolean
        get() = chatStatus == CapabilityStatus.READY

    /**
     * Search-only mode is a fully supported configuration, not a failure:
     * everything except semantic ranking and generated answers works.
     */
    val overall: String
        get() = when {
            embeddingStatus == CapabilityStatus.READY && chatStatus == CapabilityStatus.READY ->
                "Full AI mode"
            searchStatus == CapabilityStatus.READY -> "Search-only mode"
            else -> "Source only"
        }
}

enum class CapabilityStatus(val label: String) {
    READY("Ready"),
    PARTIAL("Partial"),
    UNAVAILABLE("Unavailable"),
    ERROR("Error"),
    UNKNOWN("Not tested")
}
