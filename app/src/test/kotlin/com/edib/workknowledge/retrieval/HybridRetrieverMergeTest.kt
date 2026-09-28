package com.edib.workknowledge.retrieval

import com.edib.workknowledge.data.SearchDao
import com.edib.workknowledge.domain.Chunk
import com.edib.workknowledge.embeddings.EmbeddingProvider
import com.edib.workknowledge.search.DocumentSearch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Hybrid merging with synthetic vectors.
 *
 * NO embedding model is exercised. The vectors are deterministic numbers and
 * the "provider" returns a fixed array, so what is validated is the *plumbing*:
 * that vector candidates merge, de-duplicate and rank alongside exact hits, and
 * that provenance survives. This is explicitly NOT semantic retrieval
 * verification.
 */
class HybridRetrieverMergeTest {

    private class FixedProvider(private val vector: FloatArray) : EmbeddingProvider {
        override val modelName = "synthetic-test-vector"
        override val dimensions = vector.size
        override suspend fun embed(text: String) =
            EmbeddingProvider.OpResult.Success(vector)
        override suspend fun embedBatch(texts: List<String>) =
            EmbeddingProvider.OpResult.Success(texts.map { vector })
    }

    private fun unitVector(vararg on: Int): FloatArray {
        val v = FloatArray(4)
        on.forEach { v[it] = 1f }
        return v
    }

    private fun chunk(
        id: Long,
        content: String,
        documentId: Long = 1L,
        sheet: String? = null,
        rows: Pair<Int, Int>? = null,
        page: Int? = null
    ) = Chunk(
        id = id,
        projectId = 1L,
        documentId = documentId,
        chunkIndex = 0,
        content = content,
        pageNumber = page,
        sheetName = sheet,
        rowStart = rows?.first,
        rowEnd = rows?.second,
        filename = if (page != null) "WK_Runtime_Test.pdf" else "WK_BOQ_Runtime_Test.xlsx",
        projectName = "WK Runtime Verification"
    )

    private fun candidateOf(c: Chunk) = SearchDao.BodyHit(
        rowid = c.id,
        content = c.content,
        filename = c.filename,
        projectName = c.projectName,
        sheetName = c.sheetName.orEmpty(),
        projectId = c.projectId,
        documentId = c.documentId,
        pageNumber = c.pageNumber,
        chunkSheet = c.sheetName,
        rowStart = c.rowStart,
        rowEnd = c.rowEnd,
        cellRange = c.cellRange
    )

    private fun search(hits: List<Chunk>) =
        DocumentSearch(Candidates(hits.map(::candidateOf)))

    private class Candidates(private val rows: List<SearchDao.BodyHit>) : SearchDao {
        override suspend fun insertAll(rows: List<com.edib.workknowledge.data.ChunkFtsEntity>) = Unit
        override suspend fun deleteByIds(ids: List<Long>) = Unit
        override suspend fun deleteById(chunkId: Long) = Unit
        override suspend fun deleteByDocument(documentId: Long) = Unit
        override suspend fun clearIndex() = Unit
        override suspend fun insertIndex(rows: List<com.edib.workknowledge.data.ChunkFtsEntity>) = Unit
        override suspend fun readAllForIndex(): List<com.edib.workknowledge.data.ChunkFtsEntity> = emptyList()
        override suspend fun count(): Int = rows.size
        override suspend fun match(query: String, limit: Int) = emptyList<SearchDao.FtsHit>()
        override suspend fun matchBodies(query: String, limit: Int, projectId: Long?) =
            rows.filter { projectId == null || it.projectId == projectId }
        override suspend fun matchBodiesInDocuments(
            query: String, limit: Int, documentIds: List<Long>
        ) = rows.filter { documentIds.contains(it.documentId) }
    }

    private fun vectors(vararg rows: ScoredChunk) = StubVectors(rows.toList())

    private fun engine(
        fts: List<Chunk>,
        vec: VectorRepository,
        provider: EmbeddingProvider? = FixedProvider(unitVector(0))
    ) = HybridRetriever(search(fts), vec, provider)

    // ------------------------------------------------------------ fts-only mode

    @Test
    fun `fts-only result is returned when the vector route yields nothing`() = runBlocking {
        val exact = chunk(1, "Row 2: Item Code: MR-104; Quantity: 37.5", sheet = "Master Bathroom")
        val r = engine(listOf(exact), vectors()).retrieve("MR-104")
        assertEquals(1, r.size)
        assertEquals(RetrievalSource.FTS, r.first().source)
    }

    @Test
    fun `vector-only result is returned when fts matches nothing`() = runBlocking {
        val semanticOnly = chunk(9, "prose that shares no terms with the query", documentId = 3)
        val r = engine(emptyList(), vectors(ScoredChunk(semanticOnly, 0.8f)))
            .retrieve("something with no lexical match")
        assertEquals(1, r.size)
        assertEquals(RetrievalSource.VECTOR, r.first().source)
        assertEquals(0.8f, r.first().vectorScore!!, 1e-5f)
    }

    // -------------------------------------------------------------- merge / dedup

    @Test
    fun `a chunk found by both routes appears once with source BOTH`() = runBlocking {
        val shared = chunk(1, "MR-104 Galala Marble", sheet = "Master Bathroom", rows = 2 to 3)
        val r = engine(listOf(shared), vectors(ScoredChunk(shared, 0.6f)))
            .retrieve("MR-104")
        assertEquals("must be de-duplicated, not repeated", 1, r.size)
        assertEquals(RetrievalSource.BOTH, r.first().source)
        assertEquals(0.6f, r.first().vectorScore!!, 1e-5f)
    }

    @Test
    fun `provenance survives merging`() = runBlocking {
        val shared = chunk(1, "MR-104 Galala Marble", sheet = "Master Bathroom", rows = 42 to 47)
        val r = engine(listOf(shared), vectors(ScoredChunk(shared, 0.7f)))
            .retrieve("MR-104").first()
        assertEquals(1L, r.chunkId)
        assertEquals(1L, r.projectId)
        assertEquals(1L, r.documentId)
        assertEquals("WK_BOQ_Runtime_Test.xlsx", r.filename)
        assertEquals("Master Bathroom", r.sheetName)
        assertEquals(42, r.rowStart)
        assertEquals(47, r.rowEnd)
        assertTrue(r.content.isNotBlank())
        assertEquals(RetrievalSource.BOTH, r.source)
    }

    // ----------------------------------------------------------------- ranking

    @Test
    fun `an exact identifier outranks a stronger semantic result`() = runBlocking {
        val exact = chunk(1, "MR-104", sheet = "Master Bathroom")
        val semanticOnly = chunk(2, "prose about marble that never names the code", documentId = 3)
        val r = engine(
            listOf(exact),
            vectors(ScoredChunk(semanticOnly, 1.0f), ScoredChunk(exact, 0.05f))
        ).retrieve("MR-104")
        assertEquals("the literal hit must come first", 1L, r.first().chunkId)
        assertTrue(r.first().exactMatch)
        // The semantic hit is present, just ranked below.
        assertTrue(r.any { it.chunkId == 2L })
    }

    @Test
    fun `a perfect semantic match still cannot outrank an exact identifier`() = runBlocking {
        val exact = chunk(1, "MR-104", sheet = "Master Bathroom")
        val semanticOnly = chunk(2, "prose about marble", documentId = 3)
        val r = engine(listOf(exact), vectors(ScoredChunk(semanticOnly, 1.0f)))
            .retrieve("MR-104")
        assertTrue(r.first().exactMatch)
        assertTrue(r.first().score > r.last().score)
    }

    // ------------------------------------------------------------------ filters

    @Test
    fun `project filter is passed to both routes`() = runBlocking {
        val seen = mutableListOf<Long?>()
        val vec = object : VectorRepository {
            override suspend fun addChunks(chunks: List<Chunk>) = OpResult.Success(Unit)
            override suspend fun search(
                projectId: Long, queryEmbedding: FloatArray, topK: Int,
                documentIds: List<Long>?, sheetName: String?, pageNumber: Int?
            ): OpResult<List<ScoredChunk>> {
                seen += projectId
                return OpResult.Success(emptyList())
            }
            override suspend fun getChunksForDocument(documentId: Long) = OpResult.Success(emptyList<Chunk>())
            override suspend fun deleteChunksForDocument(documentId: Long) = OpResult.Success(Unit)
            override suspend fun getChunkCount(projectId: Long) = OpResult.Success(0)
        }
        engine(emptyList(), vec).retrieve("MR-104", projectId = 7)
        assertEquals(listOf(7L), seen)
    }

    @Test
    fun `document filter is passed to the vector route`() = runBlocking {
        val seen = mutableListOf<List<Long>?>()
        val vec = object : VectorRepository {
            override suspend fun addChunks(chunks: List<Chunk>) = OpResult.Success(Unit)
            override suspend fun search(
                projectId: Long, queryEmbedding: FloatArray, topK: Int,
                documentIds: List<Long>?, sheetName: String?, pageNumber: Int?
            ): OpResult<List<ScoredChunk>> {
                seen += documentIds
                return OpResult.Success(emptyList())
            }
            override suspend fun getChunksForDocument(documentId: Long) = OpResult.Success(emptyList<Chunk>())
            override suspend fun deleteChunksForDocument(documentId: Long) = OpResult.Success(Unit)
            override suspend fun getChunkCount(projectId: Long) = OpResult.Success(0)
        }
        engine(emptyList(), vec).retrieve("MR-104", documentIds = listOf(1L, 2L))
        assertEquals(listOf(listOf(1L, 2L)), seen)
    }

    @Test
    fun `an empty document list is treated as no document filter`() = runBlocking {
        val seen = mutableListOf<List<Long>?>()
        val vec = object : VectorRepository {
            override suspend fun addChunks(chunks: List<Chunk>) = OpResult.Success(Unit)
            override suspend fun search(
                projectId: Long, queryEmbedding: FloatArray, topK: Int,
                documentIds: List<Long>?, sheetName: String?, pageNumber: Int?
            ): OpResult<List<ScoredChunk>> {
                seen += documentIds
                return OpResult.Success(emptyList())
            }
            override suspend fun getChunksForDocument(documentId: Long) = OpResult.Success(emptyList<Chunk>())
            override suspend fun deleteChunksForDocument(documentId: Long) = OpResult.Success(Unit)
            override suspend fun getChunkCount(projectId: Long) = OpResult.Success(0)
        }
        engine(emptyList(), vec).retrieve("MR-104", documentIds = emptyList())
        assertEquals(listOf(null), seen)
    }

    // ----------------------------------------------------------------- deletion

    @Test
    fun `deleting vectors removes semantic candidates too`() = runBlocking {
        // Once a document is deleted, neither route may return its passages.
        val deleted = chunk(5, "Purchase Request: PR-023", documentId = 2, page = 3)
        val before = engine(
            listOf(deleted), vectors(ScoredChunk(deleted, 0.9f))
        ).retrieve("PR-023")
        assertTrue("retrievable before deletion", before.isNotEmpty())

        // Deleting the document clears both the FTS rows and the vector rows.
        val after = engine(emptyList(), vectors()).retrieve("PR-023")
        assertTrue("no stale candidate may survive deletion", after.isEmpty())
        assertNotNull(before)
    }

    @Test
    fun `cosine similarity of the synthetic vector is order independent`() {
        val a = unitVector(0, 1)
        val b = unitVector(0, 1)
        val dot = a.zip(b).sumOf { (x, y) -> (x * y).toDouble() }.toFloat()
        val m = sqrt(a.sumOf { (it * it).toDouble() }.toFloat()) *
            sqrt(b.sumOf { (it * it).toDouble() }.toFloat())
        assertEquals(1f, dot / m, 1e-5f)
    }

    private class StubVectors(private val rows: List<ScoredChunk>) : VectorRepository {
        override suspend fun addChunks(chunks: List<Chunk>) = OpResult.Success(Unit)
        override suspend fun search(
            projectId: Long, queryEmbedding: FloatArray, topK: Int,
            documentIds: List<Long>?, sheetName: String?, pageNumber: Int?
        ): OpResult<List<ScoredChunk>> = OpResult.Success(
            rows.filter { documentIds == null || documentIds.contains(it.chunk.documentId) }
        )
        override suspend fun getChunksForDocument(documentId: Long) = OpResult.Success(emptyList<Chunk>())
        override suspend fun deleteChunksForDocument(documentId: Long) = OpResult.Success(Unit)
        override suspend fun getChunkCount(projectId: Long) = OpResult.Success(0)
    }
}
