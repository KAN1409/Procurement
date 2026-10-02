package com.edib.workknowledge.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@Entity(
    tableName = "document_summaries",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["documentId"], unique = true)]
)
data class DocumentSummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val summary: String,
    val keyFactsJson: String = "[]",
    val generatedAt: Long = System.currentTimeMillis(),
    val modelUsed: String = "",
    val isStale: Boolean = false
) {
    private fun stringListType() = object : TypeToken<List<String>>() {}.type

    fun getKeyFacts(): List<String> =
        runCatching { Gson().fromJson<List<String>>(keyFactsJson, stringListType()) }
            .getOrNull()
            .orEmpty()

    /** Data-class properties are immutable; return a copy carrying the new JSON. */
    fun withKeyFacts(facts: List<String>): DocumentSummaryEntity =
        copy(keyFactsJson = Gson().toJson(facts))
}
