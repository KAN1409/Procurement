package com.edib.procurement.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One source document inside a generated AI Pack.
 *
 * Stored separately from the pack so the details screen can list what was
 * extracted and how it fared without re-reading the pack body.
 *
 * It records only where a document came from and what happened to it. There is
 * no handle that could be used to modify or delete the original, so removing a
 * pack history row cannot touch a source file.
 */
@Entity(
    tableName = "ai_pack_source",
    foreignKeys = [
        ForeignKey(
            entity = AiPackEntity::class,
            parentColumns = ["id"],
            childColumns = ["packId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["packId"])]
)
data class AiPackSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packId: Long,
    val sourceId: String,
    val documentId: Long?,
    val filename: String,
    val relativePath: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val linkType: String?,
    val linkedManually: Boolean,
    val extractionStatus: String,
    val detail: String?
)
