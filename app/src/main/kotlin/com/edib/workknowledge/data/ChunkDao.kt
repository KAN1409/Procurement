package com.edib.workknowledge.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<ChunkEntity>): List<Long>

    @Query("DELETE FROM chunks WHERE documentId = :documentId")
    suspend fun deleteByDocument(documentId: Long): Int

    @Query("SELECT * FROM chunks WHERE id = :id")
    suspend fun byId(id: Long): ChunkEntity?

    @Query("SELECT * FROM chunks WHERE documentId = :documentId ORDER BY chunkIndex ASC")
    fun observeByDocument(documentId: Long): Flow<List<ChunkEntity>>

    @Query("SELECT * FROM chunks WHERE documentId = :documentId ORDER BY chunkIndex ASC")
    suspend fun byDocument(documentId: Long): List<ChunkEntity>

    @Query("SELECT COUNT(*) FROM chunks WHERE documentId = :documentId")
    suspend fun countByDocument(documentId: Long): Int

    @Query("SELECT COUNT(*) FROM chunks WHERE projectId = :projectId")
    suspend fun countByProject(projectId: Long): Int

    @Query("SELECT COUNT(*) FROM chunks")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM chunks WHERE embedding IS NOT NULL")
    suspend fun countWithVectors(): Int

    // Metadata-filtered candidate sets. These narrow the search in SQL so the
    // in-memory cosine pass only ever touches vectors that could match.

    @Query("SELECT * FROM chunks WHERE projectId = :projectId AND embedding IS NOT NULL")
    suspend fun byProjectWithVectors(projectId: Long): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE documentId = :documentId AND embedding IS NOT NULL")
    suspend fun byDocumentWithVectors(documentId: Long): List<ChunkEntity>

    @Query(
        "SELECT * FROM chunks WHERE projectId = :projectId AND sheetName = :sheetName " +
            "AND embedding IS NOT NULL ORDER BY rowStart ASC"
    )
    suspend fun bySheetWithVectors(projectId: Long, sheetName: String): List<ChunkEntity>

    @Query("SELECT embedding FROM chunks WHERE embedding IS NOT NULL LIMIT 1")
    suspend fun sampleEmbedding(): ByteArray?
}
