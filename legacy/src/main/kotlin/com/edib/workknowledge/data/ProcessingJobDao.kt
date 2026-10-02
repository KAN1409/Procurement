package com.edib.workknowledge.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProcessingJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: ProcessingJobEntity): Long

    @Update
    suspend fun update(job: ProcessingJobEntity): Int

    @Query(
        "SELECT * FROM processing_jobs WHERE documentId = :documentId " +
            "ORDER BY startedAt DESC LIMIT 1"
    )
    suspend fun latest(documentId: Long): ProcessingJobEntity?

    @Query(
        "SELECT * FROM processing_jobs WHERE documentId = :documentId " +
            "ORDER BY startedAt DESC LIMIT 1"
    )
    fun observeLatest(documentId: Long): Flow<ProcessingJobEntity?>

    @Query("DELETE FROM processing_jobs WHERE documentId = :documentId")
    suspend fun deleteByDocument(documentId: Long): Int
}
