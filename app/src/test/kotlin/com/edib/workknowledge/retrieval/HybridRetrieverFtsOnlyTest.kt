package com.edib.workknowledge.retrieval

import com.edib.workknowledge.data.SearchDao
import com.edib.workknowledge.domain.Chunk
import com.edib.workknowledge.embeddings.EmbeddingProvider
import com.edib.workknowledge.rag.toCitation
import com.edib.workknowledge.search.DocumentSearch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hybrid retrieval with no embedding endpoint.
 *
 * These exercise the real [DocumentSearch] ranking path by feeding it known
 * candidates through a stub DAO, so what is under test is what retrieval does
 * with hits and whether it works at all without an AI endpoint. FTS tokenisation
 * is covered separately by FtsQueryBuilderTest.
 *
 * This is NOT semantic retrieval verification.
 */
class HybridRetrieverFtsOnlyTest {

    private fun bodyHit(
        chunkId: Long,
        content: String,
        filename: String,
        documentId: Long = 1L,
        page: Int? = null,
        sheet: String? = null,
        rows: Pair<Int, Int>? = null
    ) = SearchDao.BodyHit(
        rowid = chunkId,
        content = content,
        filename = filename,
        projectName = "WK Runtime Verification",
        sheetName = sheet.orEmpty(),
        projectId = 1L,
        documentId = documentId,
        pageNumber = page,
        chunkSheet = sheet,
        rowStart = rows?.first,
        rowEnd = rows?.second,
        cellRange = null
    )

    private val masterBathroom = bodyHit(
        chunkId = 1L,
        content = "SHEET: Master Bathroom ROWS: 2-3 Row 2: Item Code: MR-104; " +
            "Description: Galala Marble Wall Cladding; Unit: m2; Quantity: 37.5",
        filename = "WK_BOQ_Runtime_Test.xlsx",
        sheet = "Master Bathroom",
        rows = 2 to 3
    )

    private val pdfPage3 = bodyHit(
        chunkId = 5L,
        content = "Purchase Request: PR-023 Status: Approved",
        filename = "WK_Runtime_Test.pdf",
        documentId = 2L,
        page = 3
    )

    private val ceilings = bodyHit(
        chunkId = 2L,
        content = "SHEET: Ceilings ROWS: 2-2 Row 2: Item Code: CL-201; Gypsum Board Ceiling",
        filename = "WK_BOQ_Runtime_Test.xlsx",
        sheet = "Ceilings",
        rows = 2 to 2
    )

    private fun retrieverFor(hit: SearchDao.BodyHit, provider: EmbeddingProvider? = null) =
        HybridRetriever(
            search = DocumentSearch(SingleHit(hit)),
            vectorRepository = NoopVectorRepository,
            embeddingProvider = provider
        )

    @Test
    fun `MR-104 returns the Excel chunk with its row provenance`() = runBlocking {
        val top = retrieverFor(masterBathroom).retrieve("MR-104").first()
        assertEquals(1L, top.chunkId)
        assertEquals("WK_BOQ_Runtime_Test.xlsx", top.filename)
        assertEquals("Master Bathroom", top.sheetName)
        assertEquals(2, top.rowStart)
        assertEquals(3, top.rowEnd)
        assertTrue(top.exactMatch)
        assertEquals(RetrievalSource.FTS, top.source)
    }

    @Test
    fun `PR-023 returns the PDF chunk on page 3`() = runBlocking {
        val top = retrieverFor(pdfPage3).retrieve("PR-023").first()
        assertEquals(5L, top.chunkId)
        assertEquals("WK_Runtime_Test.pdf", top.filename)
        assertEquals(3, top.pageNumber)
        assertNull("a PDF hit has no sheet", top.sheetName)
    }

    @Test
    fun `CL-201 returns the Ceilings sheet`() = runBlocking {
        val top = retrieverFor(ceilings).retrieve("CL-201").first()
        assertEquals("Ceilings", top.sheetName)
        assertEquals(2, top.rowStart)
        assertEquals("WK_BOQ_Runtime_Test.xlsx", top.filename)
    }

    @Test
    fun `Piatra Grey returns the Master Bathroom chunk`() = runBlocking {
        val piatra = masterBathroom.copy(
            content = "SHEET: Master Bathroom ROWS: 2-3 Row 3: Item Code: MR-105; " +
                "Description: Piatra Grey Feature Band; Quantity: 12.75"
        )
        val top = retrieverFor(piatra).retrieve("Piatra Grey").first()
        assertEquals("Master Bathroom", top.sheetName)
    }

    @Test
    fun `retrieval works with no embedding provider at all`() = runBlocking {
        val r = retrieverFor(masterBathroom, provider = null).retrieve("MR-104")
        assertTrue(r.isNotEmpty())
        assertTrue(r.all { it.source == RetrievalSource.FTS })
    }

    @Test
    fun `a failing embedding provider degrades to exact results`() = runBlocking {
        val r = retrieverFor(masterBathroom, provider = FailingProvider).retrieve("MR-104")
        assertTrue("exact results must survive a provider failure", r.isNotEmpty())
        assertTrue(r.all { it.source == RetrievalSource.FTS })
    }

    @Test
    fun `topK is respected`() = runBlocking {
        val r = retrieverFor(masterBathroom).retrieve("MR-104", topK = 1)
        assertTrue(r.size <= 1)
    }

    @Test
    fun `a passage converts to a citation without losing provenance`() = runBlocking {
        val p = retrieverFor(masterBathroom).retrieve("MR-104").first()
        val citation = p.toCitation()
        assertEquals(1L, citation.chunkId)
        assertEquals("Master Bathroom", citation.sheetName)
        assertEquals(2, citation.rowStart)
        assertEquals(3, citation.rowEnd)
        assertEquals(p.chunkId, p.toChunk().id)
    }

    /** DAO stub returning exactly one candidate row. */
    private class SingleHit(private val hit: SearchDao.BodyHit) : SearchDao {
        override suspend fun insertAll(rows: List<com.edib.workknowledge.data.ChunkFtsEntity>) = Unit
        override suspend fun deleteByIds(ids: List<Long>) = Unit
        override suspend fun deleteById(chunkId: Long) = Unit
        override suspend fun deleteByDocument(documentId: Long) = Unit
        override suspend fun clearIndex() = Unit
        override suspend fun insertIndex(rows: List<com.edib.workknowledge.data.ChunkFtsEntity>) = Unit
        override suspend fun readAllForIndex(): List<com.edib.workknowledge.data.ChunkFtsEntity> = emptyList()
        override suspend fun count(): Int = 1
        override suspend fun match(query: String, limit: Int) = emptyList<SearchDao.FtsHit>()
        override suspend fun matchBodies(query: String, limit: Int, projectId: Long?) = listOf(hit)
        override suspend fun matchBodiesInDocuments(query: String, limit: Int, documentIds: List<Long>) =
            listOf(hit)
    }

    private object NoopVectorRepository : VectorRepository {
        override suspend fun addChunks(chunks: List<Chunk>) = OpResult.Success(Unit)
        override suspend fun search(
            projectId: Long, queryEmbedding: FloatArray, topK: Int,
            documentIds: List<Long>?, sheetName: String?, pageNumber: Int?
        ): OpResult<List<ScoredChunk>> = OpResult.Success(emptyList())
        override suspend fun getChunksForDocument(documentId: Long) = OpResult.Success(emptyList<Chunk>())
        override suspend fun deleteChunksForDocument(documentId: Long) = OpResult.Success(Unit)
        override suspend fun getChunkCount(projectId: Long) = OpResult.Success(0)
    }

    private object FailingProvider : EmbeddingProvider {
        override val modelName = "nomic-embed-text"
        override val dimensions = 768
        override suspend fun embed(text: String) =
            EmbeddingProvider.OpResult.Failure("no endpoint")
        override suspend fun embedBatch(texts: List<String>) =
            EmbeddingProvider.OpResult.Failure("no endpoint")
    }
}
