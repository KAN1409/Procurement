package com.edib.workknowledge.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["createdAt"])
    ]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: Role,
    val content: String,
    val citationsJson: String = "[]",
    val createdAt: Long = System.currentTimeMillis()
) {
    private fun citationListType() = object : TypeToken<List<Citation>>() {}.type

    fun getCitations(): List<Citation> =
        runCatching { Gson().fromJson<List<Citation>>(citationsJson, citationListType()) }
            .getOrNull()
            .orEmpty()

    /** Data-class properties are immutable; return a copy carrying the new JSON. */
    fun withCitations(citations: List<Citation>): ChatMessageEntity =
        copy(citationsJson = Gson().toJson(citations))

    enum class Role { USER, ASSISTANT, SYSTEM }

    data class Citation(
        val chunkId: Long,
        val documentId: Long,
        val documentName: String,
        val pageNumber: Int?,
        val sheetName: String?,
        val rowStart: Int?,
        val rowEnd: Int?,
        val cellRange: String?,
        val snippet: String
    )
}
