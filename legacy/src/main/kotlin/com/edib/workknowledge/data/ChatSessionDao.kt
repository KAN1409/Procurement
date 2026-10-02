package com.edib.workknowledge.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: ChatSessionEntity): Long

    @Update
    suspend fun update(session: ChatSessionEntity): Int

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun byId(id: Long): ChatSessionEntity?

    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ChatSessionEntity>>

    @Query("UPDATE chat_sessions SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)
}
