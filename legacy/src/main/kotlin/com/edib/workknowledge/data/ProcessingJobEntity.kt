package com.edib.workknowledge.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "processing_jobs",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["documentId"]),
        Index(value = ["status"])
    ]
)
data class ProcessingJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val jobType: JobType,
    val status: JobStatus = JobStatus.PENDING,
    val progress: Int = 0,
    val totalSteps: Int = 0,
    val currentStep: String = "",
    val errorMessage: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
) {
    enum class JobType { PARSE, CHUNK, EMBED, SUMMARIZE }
    enum class JobStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }
}
