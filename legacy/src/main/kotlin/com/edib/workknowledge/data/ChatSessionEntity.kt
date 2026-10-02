package com.edib.workknowledge.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@Entity(
    tableName = "chat_sessions",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["projectId"]),
        Index(value = ["updatedAt"])
    ]
)
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long?,
    val title: String,
    val scopeType: ScopeType = ScopeType.PROJECT,
    val scopeEntityIdsJson: String = "[]",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    private fun longListType() = object : TypeToken<List<Long>>() {}.type

    fun getScopeEntityIds(): List<Long> =
        runCatching { Gson().fromJson<List<Long>>(scopeEntityIdsJson, longListType()) }
            .getOrNull()
            .orEmpty()

    /** Data-class properties are immutable; return a copy carrying the new JSON. */
    fun withScopeEntityIds(ids: List<Long>): ChatSessionEntity =
        copy(scopeEntityIdsJson = Gson().toJson(ids))

    enum class ScopeType {
        PROJECT, DOCUMENTS, SINGLE_DOCUMENT, ALL_PROJECTS
    }
}
