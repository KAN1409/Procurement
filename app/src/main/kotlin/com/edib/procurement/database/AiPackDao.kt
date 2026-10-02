package com.edib.procurement.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * History of generated AI packs.
 *
 * Read-only apart from [insert] and [delete]: nothing here can reach a source
 * document, so removing a pack cannot remove anything but the pack.
 */
@Dao
interface AiPackDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pack: AiPackEntity): Long

    @Query("SELECT * FROM ai_pack ORDER BY createdAt DESC")
    fun packsFlow(): Flow<List<AiPackEntity>>

    @Query("SELECT * FROM ai_pack WHERE id = :id")
    fun packFlow(id: Long): Flow<AiPackEntity?>

    @Query("SELECT * FROM ai_pack WHERE id = :id")
    suspend fun pack(id: Long): AiPackEntity?

    @Query("SELECT * FROM ai_pack ORDER BY createdAt DESC")
    suspend fun allPacks(): List<AiPackEntity>

    @Query("DELETE FROM ai_pack WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM ai_pack")
    suspend fun deleteAll()

    @Query("SELECT COALESCE(SUM(packBytes), 0) FROM ai_pack")
    fun totalBytesFlow(): Flow<Long>

    /**
     * Files whose row exists but whose file has gone, e.g. cleared by the
     * system's storage manager. Shown as FILE MISSING rather than crashing.
     */
    @Query("SELECT * FROM ai_pack")
    suspend fun withPaths(): List<AiPackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSources(rows: List<AiPackSourceEntity>)

    /** Sources of one pack, in source-id order, for the details screen. */
    @Query("SELECT * FROM ai_pack_source WHERE packId = :packId ORDER BY sourceId ASC")
    fun sourcesFlow(packId: Long): Flow<List<AiPackSourceEntity>>

    @Query("DELETE FROM ai_pack_source WHERE packId = :packId")
    suspend fun deleteSources(packId: Long)

    @Query("DELETE FROM ai_pack_source")
    suspend fun deleteAllSources()
}
