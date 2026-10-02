package com.edib.workknowledge.retrieval

import com.edib.workknowledge.data.ChunkDao
import com.edib.workknowledge.data.ChunkEntity
import com.edib.workknowledge.domain.Chunk
import com.edib.workknowledge.domain.VectorCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

interface VectorRepository {
    suspend fun addChunks(chunks: List<Chunk>): OpResult<Unit>
    suspend fun search(
        projectId: Long,
        queryEmbedding: FloatArray,
        topK: Int,
        documentIds: List<Long>? = null,
        sheetName: String? = null,
        pageNumber: Int? = null
    ): OpResult<List<ScoredChunk>>
    suspend fun getChunksForDocument(documentId: Long): OpResult<List<Chunk>>
    suspend fun deleteChunksForDocument(documentId: Long): OpResult<Unit>
    suspend fun getChunkCount(projectId: Long): OpResult<Int>
}

data class ScoredChunk(
    val chunk: Chunk,
    val score: Float
)

sealed interface OpResult<out T> {
    data class Success<T>(val value: T) : OpResult<T>
    data class Failure(val error: String) : OpResult<Nothing>
}

/**
 * Room-backed vector store.
 *
 * Scalability note (honest V1 limit): candidates are narrowed by metadata in
 * SQL first, but the surviving vectors are then scored in Kotlin. That is fine
 * for a few thousand chunks; beyond ~50k it should move to an approximate
 * index (HNSW) or an on-device extension. Dimension mismatches are rejected
 * rather than silently scored, so a model swap can never corrupt ranking.
 */
class RoomVectorRepository(
    private val chunkDao: ChunkDao
) : VectorRepository {

    override suspend fun addChunks(chunks: List<Chunk>): OpResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                chunkDao.insertAll(chunks.map { it.toEntity() })
                OpResult.Success(Unit)
            } catch (e: Exception) {
                OpResult.Failure("Failed to store chunks: ${e.message}")
            }
        }

    override suspend fun search(
        projectId: Long,
        queryEmbedding: FloatArray,
        topK: Int,
        documentIds: List<Long>?,
        sheetName: String?,
        pageNumber: Int?
    ): OpResult<List<ScoredChunk>> = withContext(Dispatchers.IO) {
        try {
            // 1. narrow by metadata in SQL, before touching any vector bytes
            val candidates: List<ChunkEntity> = when {
                !documentIds.isNullOrEmpty() ->
                    documentIds.flatMap { chunkDao.byDocumentWithVectors(it) }

                sheetName != null ->
                    chunkDao.bySheetWithVectors(projectId, sheetName)

                else ->
                    chunkDao.byProjectWithVectors(projectId)
            }

            if (candidates.isEmpty()) return@withContext OpResult.Success(emptyList())

            // 2. skip vectors we cannot legally compare against the query vector
            val sameDimension = candidates.filter { entity ->
                entity.embedding?.let { bytes ->
                    VectorCodec.decode(bytes)?.size == queryEmbedding.size
                } == true
            }

            if (sameDimension.isEmpty()) {
                val firstBytes = candidates.first().embedding ?: ByteArray(0)
                val foundSize = VectorCodec.decode(firstBytes)?.size
                return@withContext OpResult.Failure(
                    "Stored vectors use ${foundSize ?: "an unknown number of"} dimensions but the " +
                        "current embedding model returns ${queryEmbedding.size}. Re-index these " +
                        "documents to rebuild the index with the new model."
                )
            }

            // 3. score, then honour the page filter and take top K
            val scored = sameDimension.mapNotNull { entity ->
                val bytes = entity.embedding ?: return@mapNotNull null
                val vector = VectorCodec.decode(bytes) ?: return@mapNotNull null
                ScoredChunk(Chunk.fromEntity(entity), cosineSimilarity(queryEmbedding, vector))
            }

            val pageFiltered = if (pageNumber != null) {
                scored.filter { it.chunk.pageNumber == pageNumber }
            } else {
                scored
            }

            OpResult.Success(pageFiltered.sortedByDescending { it.score }.take(topK))
        } catch (e: Exception) {
            OpResult.Failure("Search failed: ${e.message}")
        }
    }

    override suspend fun getChunksForDocument(documentId: Long): OpResult<List<Chunk>> =
        withContext(Dispatchers.IO) {
            try {
                val entities = chunkDao.byDocument(documentId)
                OpResult.Success(entities.map { Chunk.fromEntity(it) })
            } catch (e: Exception) {
                OpResult.Failure("Failed to load chunks: ${e.message}")
            }
        }

    override suspend fun deleteChunksForDocument(documentId: Long): OpResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                chunkDao.deleteByDocument(documentId)
                OpResult.Success(Unit)
            } catch (e: Exception) {
                OpResult.Failure("Failed to delete chunks: ${e.message}")
            }
        }

    override suspend fun getChunkCount(projectId: Long): OpResult<Int> =
        withContext(Dispatchers.IO) {
            try {
                OpResult.Success(chunkDao.countByProject(projectId))
            } catch (e: Exception) {
                OpResult.Failure("Failed to count chunks: ${e.message}")
            }
        }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val magnitude = sqrt(normA) * sqrt(normB)
        return if (magnitude > 0f) dot / magnitude else 0f
    }
}
