package com.edib.workknowledge.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.ColumnInfo
import com.google.gson.Gson

@Entity(
    tableName = "chunks",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["documentId"]),
        Index(value = ["projectId"]),
        Index(value = ["chunkIndex"]),
        Index(value = ["pageNumber"]),
        Index(value = ["sheetName"]),
        Index(value = ["rowStart", "rowEnd"])
    ]
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val documentId: Long,
    val chunkIndex: Int,
    val content: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val embedding: ByteArray? = null,
    val pageNumber: Int? = null,
    val sheetName: String? = null,
    val rowStart: Int? = null,
    val rowEnd: Int? = null,
    val cellRange: String? = null,
    val metadataJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun getMetadata(): ChunkMetadata =
        Gson().fromJson(metadataJson, ChunkMetadata::class.java) ?: ChunkMetadata()

    /** Data-class properties are immutable; return a copy carrying the new JSON. */
    fun withMetadata(metadata: ChunkMetadata): ChunkEntity =
        copy(metadataJson = Gson().toJson(metadata))
}

data class ChunkMetadata(
    val paragraphIndex: Int? = null,
    val sectionTitle: String? = null,
    val headerRowValues: List<String>? = null,
    val formulaText: String? = null,
    val dataType: String? = null
)
