package com.edib.workknowledge.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Entity(
    tableName = "documents",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["projectId"]),
        Index(value = ["sha256"], unique = true),
        Index(value = ["status"])
    ]
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val filename: String,
    val originalUri: String,
    val mimeType: String,
    val fileSize: Long,
    val sha256: String,
    val pageCount: Int = 0,
    val sheetCount: Int = 0,
    val status: ProcessingStatus = ProcessingStatus.QUEUED,
    val errorMessage: String? = null,
    val importedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val revisionOf: Long? = null
) {
    /**
     * Pipeline states.
     *
     * [SOURCE_READY] is a success state, not a partial failure: the document
     * was parsed, its chunks are stored and it is fully searchable through
     * exact search and the source viewer. Only optional AI enrichment
     * (semantic vectors, generated summary) is still outstanding, and it can be
     * added later without touching the source file again.
     */
    enum class ProcessingStatus {
        QUEUED, PARSING, CHUNKING, EMBEDDING, SUMMARIZING, READY, SOURCE_READY, FAILED
    }
    
    val formattedSize: String
        get() = formatFileSize(fileSize)
    
    val formattedImportedAt: String
        get() = formatRelativeTime(importedAt)
    
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
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
                val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                sdf.format(Date(timestamp))
            }
        }
    }
}
