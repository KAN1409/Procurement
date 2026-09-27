package com.edib.workknowledge.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentSummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(summary: DocumentSummaryEntity): Long

    @Query("SELECT * FROM document_summaries WHERE documentId = :documentId")
    suspend fun byDocument(documentId: Long): DocumentSummaryEntity?

    @Query("SELECT * FROM document_summaries WHERE documentId = :documentId")
    fun observeByDocument(documentId: Long): Flow<DocumentSummaryEntity?>

    @Query("DELETE FROM document_summaries WHERE documentId = :documentId")
    suspend fun deleteByDocument(documentId: Long): Int
}
