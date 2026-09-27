package com.edib.workknowledge.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long): Int

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun bySession(sessionId: Long): Flow<List<ChatMessageEntity>>

    @Query(
        "SELECT * FROM chat_messages WHERE sessionId = :sessionId " +
            "ORDER BY createdAt DESC LIMIT :limit"
    )
    suspend fun recent(sessionId: Long, limit: Int): List<ChatMessageEntity>
}
