package com.edib.workknowledge.domain

import com.edib.workknowledge.data.DocumentEntity
import java.util.concurrent.TimeUnit

data class Document(
    val id: Long,
    val projectId: Long,
    val filename: String,
    val originalUri: String,
    val mimeType: String,
    val fileSize: Long,
    val sha256: String,
    val pageCount: Int,
    val sheetCount: Int,
    val status: Status,
    val errorMessage: String?,
    val importedAt: Long,
    val updatedAt: Long,
    val revisionOf: Long?,
    val summary: String? = null,
    val chunkCount: Int = 0
) {
    enum class Status { QUEUED, PARSING, CHUNKING, EMBEDDING, SUMMARIZING, READY, FAILED }
    
    fun toEntity(): DocumentEntity {
        return DocumentEntity(
            id = id,
            projectId = projectId,
            filename = filename,
            originalUri = originalUri,
            mimeType = mimeType,
            fileSize = fileSize,
            sha256 = sha256,
            pageCount = pageCount,
            sheetCount = sheetCount,
            status = DocumentEntity.ProcessingStatus.valueOf(status.name),
            errorMessage = errorMessage,
            importedAt = importedAt,
            updatedAt = updatedAt,
            revisionOf = revisionOf
        )
    }
    
    companion object {
        fun fromEntity(entity: DocumentEntity, summary: String? = null, chunkCount: Int = 0): Document {
            return Document(
                id = entity.id,
                projectId = entity.projectId,
                filename = entity.filename,
                originalUri = entity.originalUri,
                mimeType = entity.mimeType,
                fileSize = entity.fileSize,
                sha256 = entity.sha256,
                pageCount = entity.pageCount,
                sheetCount = entity.sheetCount,
                status = Status.valueOf(entity.status.name),
                errorMessage = entity.errorMessage,
                importedAt = entity.importedAt,
                updatedAt = entity.updatedAt,
                revisionOf = entity.revisionOf,
                summary = summary,
                chunkCount = chunkCount
            )
        }
    }
    
    val formattedSize: String
        get() = formatFileSize(fileSize)
    
    val formattedImportedAt: String
        get() = formatRelativeTime(importedAt)
    
    val isReady: Boolean
        get() = status == Status.READY
    
    val isProcessing: Boolean
        get() = status in setOf(Status.PARSING, Status.CHUNKING, Status.EMBEDDING, Status.SUMMARIZING)
    
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
    
    private fun formatRelativeTime(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
        val hours = TimeUnit.MILLISECONDS.toHours(diff)
        val days = TimeUnit.MILLISECONDS.toDays(diff)
        
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "$minutes min ago"
            hours < 24 -> "$hours hr ago"
            days < 7 -> "$days day${if (days > 1) "s" else ""} ago"
            else -> {
                val sdf = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
                sdf.format(java.util.Date(timestamp))
            }
        }
    }
}
