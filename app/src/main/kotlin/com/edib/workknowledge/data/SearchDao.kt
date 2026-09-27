package com.edib.workknowledge.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.RoomWarnings

/**
 * Exact / keyword retrieval.
 *
 * Works with zero embeddings: this is the only retrieval path available when
 * no AI endpoint is configured, which is why it must not depend on vectors.
 */
@Dao
interface SearchDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<ChunkFtsEntity>)

    @Query("DELETE FROM chunks_fts WHERE rowid IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM chunks_fts WHERE rowid = :chunkId")
    suspend fun deleteById(chunkId: Long)

    /**
     * Removes the index rows belonging to a document.
     *
     * Called explicitly alongside the chunk delete: an FTS table is a virtual
     * table and does not participate in a normal foreign-key cascade, so
     * without this a deleted document would leave searchable ghost entries.
     */
    @Query(
        "DELETE FROM chunks_fts WHERE rowid IN " +
            "(SELECT id FROM chunks WHERE documentId = :documentId)"
    )
    suspend fun deleteByDocument(documentId: Long)

    @Query("SELECT COUNT(*) FROM chunks_fts")
    suspend fun count(): Int

    /**
     * Raw MATCH query, ordered by SQLite's own bm25 relevance (lower is
     * better). Ranking and exact-match boosting happen in
     * [com.edib.workknowledge.search.DocumentSearch] so the behaviour is
     * unit-testable without a database.
     */
    @Query(
        "SELECT rowid, content, filename, projectName, sheetName, bm25(chunks_fts) AS rank " +
            "FROM chunks_fts WHERE chunks_fts MATCH :query LIMIT :limit"
    )
    suspend fun match(query: String, limit: Int): List<FtsHit>

    /**
     * Full body plus provenance, for the retrieval path.
     *
     * The optional filters are real SQL predicates, not ignored parameters:
     * scoping a search to one project or document must actually restrict the
     * rows returned, otherwise a chat scoped to one project could surface
     * passages from another.
     */
    @Query(
        "SELECT f.rowid, f.content, f.filename, f.projectName, f.sheetName, " +
            "bm25(chunks_fts) AS rank, c.projectId, c.documentId, c.pageNumber, " +
            "c.sheetName AS chunkSheet, c.rowStart, c.rowEnd, c.cellRange " +
            "FROM chunks_fts f " +
            "JOIN chunks c ON c.id = f.rowid " +
            "WHERE chunks_fts MATCH :query " +
            "AND (:projectId IS NULL OR c.projectId = :projectId) " +
            "AND (:documentIds IS NULL OR c.documentId IN (:documentIds)) " +
            "ORDER BY bm25(chunks_fts) LIMIT :limit"
    )
    suspend fun matchBodies(
        query: String,
        limit: Int,
        projectId: Long?,
        documentIds: List<Long>?
    ): List<BodyHit>

    /**
     * Rebuilds the whole index from [ChunkEntity]. Used after a migration or
     * whenever the index may be behind the source rows.
     *
     * Split into two abstract methods because Room's `@Transaction` on a
     * default-bodied interface method does not survive kapt stub generation.
     */
    @Query("DELETE FROM chunks_fts")
    suspend fun clearIndex()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIndex(rows: List<ChunkFtsEntity>)

    @Query(
        "SELECT id AS rowid, content, filename, projectName, " +
            "COALESCE(sheetName, '') AS sheetName FROM chunks"
    )
    @SuppressWarnings(RoomWarnings.CURSOR_MISMATCH)
    suspend fun readAllForIndex(): List<ChunkFtsEntity>

    data class FtsHit(
        val rowid: Long,
        val content: String,
        val filename: String,
        val projectName: String,
        val sheetName: String,
        val rank: Double
    )

    data class BodyHit(
        val rowid: Long,
        val content: String,
        val filename: String,
        val projectName: String,
        val sheetName: String,
        val rank: Double,
        val projectId: Long,
        val documentId: Long,
        val pageNumber: Int?,
        val chunkSheet: String?,
        val rowStart: Int?,
        val rowEnd: Int?,
        val cellRange: String?
    )
}
