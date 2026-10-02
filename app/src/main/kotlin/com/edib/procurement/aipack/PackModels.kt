package com.edib.procurement.aipack

/**
 * One byte formatter shared by the pack body, the index and the part headers.
 *
 * They must agree: a reader comparing "Normalized Total Size" in the index with
 * the footer of a part should not see two different formats for the same number.
 */
fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0 / 1024)} MB"
    else -> "${"%.2f".format(bytes / 1024.0 / 1024 / 1024)} GB"
}

/**
 * What a pack would contain, shown in the configuration sheet before anything
 * is written. Every number is a database count, not a page size.
 */
data class PackPreview(
    val groups: Int,
    val entries: Int,
    val linkedDocuments: Int,
    val selectedDocuments: Int,
    val originalBytes: Long
) {
    val documents: Int get() = selectedDocuments
}

/** What the pack was built from. */
enum class PackScopeType { GROUP, ENTRIES, DOCUMENTS, PROJECT, CONTRACTOR }

data class PackScope(
    val type: PackScopeType,
    val name: String,
    val groupIds: List<Long> = emptyList(),
    val entryIds: List<Long> = emptyList(),
    val documentIds: List<Long> = emptyList()
)

/** How much non-semantic detail to keep. */
enum class PackDetail { COMPACT, DETAILED }

/** Which document kinds to extract. */
data class PackInclude(
    val followUpData: Boolean = true,
    val pdf: Boolean = true,
    val excel: Boolean = true,
    val word: Boolean = true,
    val text: Boolean = true,
    /** Files with no confident link, included only when the user asks. */
    val unlinkedDocuments: Boolean = false
)

/** What happened to one source document. */
enum class ExtractionStatus {
    SUCCESS, PARTIAL, UNSUPPORTED, OCR_REQUIRED, ERROR, SKIPPED
}

data class PackSource(
    val sourceId: String,
    val documentId: Long?,
    val filename: String,
    val relativePath: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val linkedGroupCode: String?,
    val linkedEntryCode: String?,
    val linkType: String?,
    val matchEvidence: String?,
    val status: ExtractionStatus,
    val detail: String?,
    val checksum: String?,
    val linkedManually: Boolean
)

/** Progress for the generation UI. */
data class PackProgress(
    val phase: String,
    val current: Int,
    val total: Int
)

/** The finished pack, once written. */
data class PackResult(
    val fileName: String,
    val absolutePath: String,
    val contentUri: String?,
    val scopeName: String,
    val scopeType: PackScopeType = PackScopeType.GROUP,
    val groups: Int,
    val entries: Int,
    val documents: Int,
    val originalTotalBytes: Long,
    val packBytes: Long,
    val succeeded: Int,
    val ocrRequired: Int,
    val unsupported: Int,
    val errors: Int,
    val skipped: Int
) {
    /** Fraction of the original size the pack represents, 0..1. */
    val reduction: Double
        get() = if (originalTotalBytes <= 0) 0.0
        else (1.0 - packBytes.toDouble() / originalTotalBytes).coerceIn(0.0, 1.0)
}
