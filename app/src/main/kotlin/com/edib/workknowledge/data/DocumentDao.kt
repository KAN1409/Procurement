package com.edib.workknowledge.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(document: DocumentEntity): Long

    @Update
    suspend fun update(document: DocumentEntity): Int

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun byId(id: Long): DocumentEntity?

    @Query("SELECT * FROM documents WHERE sha256 = :sha256")
    suspend fun bySha256(sha256: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE projectId = :projectId ORDER BY filename ASC")
    fun observeByProject(projectId: Long): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE projectId = :projectId ORDER BY filename ASC")
    suspend fun byProject(projectId: Long): List<DocumentEntity>

    @Query("SELECT * FROM documents ORDER BY importedAt DESC")
    fun observeRecent(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE revisionOf = :documentId ORDER BY importedAt ASC")
    suspend fun revisionsOf(documentId: Long): List<DocumentEntity>

    @Query("SELECT COUNT(*) FROM documents")
    suspend fun count(): Int

    @Query("UPDATE documents SET revisionOf = :supersedes WHERE id = :id")
    suspend fun setRevisionOf(id: Long, supersedes: Long)

    @Query(
        "UPDATE documents SET status = :status, errorMessage = :error, updatedAt = :now " +
            "WHERE id = :id"
    )
    suspend fun updateStatus(
        id: Long,
        status: DocumentEntity.ProcessingStatus,
        error: String?,
        now: Long
    )
}
