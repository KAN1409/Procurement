package com.edib.workknowledge.rag

import com.edib.workknowledge.llm.ChatModelProvider
import com.edib.workknowledge.retrieval.HybridRetriever
import com.edib.workknowledge.retrieval.RetrievalSource
import com.edib.workknowledge.retrieval.RetrievedPassage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Citation integrity.
 *
 * Citations are built from retrieved passages, never from what the model said.
 * These tests use a stub model that deliberately invents a citation id, and
 * check that the invented id cannot leak into the answer's citation list.
 */
class CitationIntegrityTest {

    private val passage = RetrievedPassage(
        chunkId = 42L,
        projectId = 1L,
        documentId = 7L,
        content = "Approved thickness: 20 mm",
        filename = "WK_Runtime_Test.pdf",
        projectName = "WK Runtime Verification",
        pageNumber = 2,
        source = RetrievalSource.FTS,
        score = 100f,
        exactMatch = true
    )

    private fun engine(answer: String, passages: List<RetrievedPassage>) = DefaultRagEngine(
        retriever = StubRetriever(passages),
        chatModelProvider = StubChat(answer)
    )

    @Test
    fun `a citation carries the exact provenance of the retrieved passage`() = runBlocking {
        val r = engine("20 mm", listOf(passage)).query("thickness?", RagEngine.QueryScope(type = RagEngine.QueryScope.ScopeType.PROJECT))
        val result = (r as RagEngine.OpResult.Success).value
        val c = result.citations.single()
        assertEquals(42L, c.chunkId)
        assertEquals(7L, c.documentId)
        assertEquals("WK_Runtime_Test.pdf", c.filename)
        assertEquals(2, c.pageNumber)
    }

    @Test
    fun `a model that invents a citation id cannot add one`() = runBlocking {
        // The model claims a source that was never retrieved.
        val r = engine(
            "Thickness is 20 mm [S7] per Material Approval.pdf page 9.",
            listOf(passage)
        ).query("thickness?", RagEngine.QueryScope(type = RagEngine.QueryScope.ScopeType.PROJECT))
        val result = (r as RagEngine.OpResult.Success).value

        assertEquals(
            "exactly one citation, the one we retrieved",
            1,
            result.citations.size
        )
        assertEquals(42L, result.citations.single().chunkId)
        // The invented page must not appear anywhere in the citation metadata.
        assertTrue(result.citations.none { it.pageNumber == 9 })
    }

    @Test
    fun `a model claiming several sources cannot inflate the citation list`() = runBlocking {
        val second = passage.copy(chunkId = 43L, content = "Status: Approved", pageNumber = 3)
        val r = engine(
            "See [S1] and [S2] and [S3] and [S4].",
            listOf(passage, second)
        ).query("status?", RagEngine.QueryScope(type = RagEngine.QueryScope.ScopeType.PROJECT))
        val result = (r as RagEngine.OpResult.Success).value
        assertEquals(
            "citations are the retrieved set, not the model's count",
            2,
            result.citations.size
        )
        assertEquals(listOf(42L, 43L), result.citations.map { it.chunkId })
    }

    @Test
    fun `excel provenance survives into the citation`() = runBlocking {
        val excel = RetrievedPassage(
            chunkId = 9L, projectId = 1L, documentId = 8L,
            content = "Row 2: Item Code: MR-104; Quantity: 37.5",
            filename = "WK_BOQ_Runtime_Test.xlsx",
            projectName = "WK Runtime Verification",
            sheetName = "Master Bathroom",
            rowStart = 2, rowEnd = 3,
            source = RetrievalSource.BOTH,
            score = 100f, exactMatch = true
        )
        val r = engine("37.5", listOf(excel)).query("quantity?", RagEngine.QueryScope(type = RagEngine.QueryScope.ScopeType.PROJECT))
        val c = ((r as RagEngine.OpResult.Success).value).citations.single()
        assertEquals("WK_BOQ_Runtime_Test.xlsx", c.filename)
        assertEquals("Master Bathroom", c.sheetName)
        assertEquals(2, c.rowStart)
        assertEquals(3, c.rowEnd)
    }

    @Test
    fun `no retrieved evidence means no model call and an honest refusal`() = runBlocking {
        val chat = RecordingChat()
        val r = DefaultRagEngine(StubRetriever(emptyList()), chat)
            .query("approved paint manufacturer?", RagEngine.QueryScope(type = RagEngine.QueryScope.ScopeType.PROJECT))
        val result = (r as RagEngine.OpResult.Success).value

        assertTrue(result.notFound)
        assertTrue("nothing was retrieved, so nothing may be cited", result.citations.isEmpty())
        assertTrue(
            "the model must not be called without evidence",
            chat.calls == 0
        )
        assertTrue(
            "the refusal must say the documents do not contain it",
            result.answer.contains("do not contain")
        )
    }

    @Test
    fun `a model failure is reported as a failure, not as an answer`() = runBlocking {
        val r = DefaultRagEngine(StubRetriever(listOf(passage)), FailingChat)
            .query("thickness?", RagEngine.QueryScope(type = RagEngine.QueryScope.ScopeType.PROJECT))
        assertTrue(r is RagEngine.OpResult.Failure)
    }

    @Test
    fun `grounded answers never mark themselves not-found`() = runBlocking {
        val r = engine("20 mm", listOf(passage)).query("thickness?", RagEngine.QueryScope(type = RagEngine.QueryScope.ScopeType.PROJECT))
        assertFalse(((r as RagEngine.OpResult.Success).value).notFound)
    }

    private class StubRetriever(private val passages: List<RetrievedPassage>) : HybridRetriever(
        com.edib.workknowledge.search.DocumentSearch(NoopSearch),
        NoopVectors
    ) {
        override suspend fun retrieve(
            question: String, projectId: Long?, documentIds: List<Long>?, topK: Int
        ): List<RetrievedPassage> = passages
    }

    private class StubChat(private val answer: String) : ChatModelProvider {
        override val modelName = "stub"
        override suspend fun generateResponse(
            systemPrompt: String, userMessage: String,
            contextChunks: List<com.edib.workknowledge.domain.Chunk>,
            conversationHistory: List<ChatModelProvider.ChatMessage>
        ) = ChatModelProvider.OpResult.Success(answer)
        override suspend fun generateStreamingResponse(
            systemPrompt: String, userMessage: String,
            contextChunks: List<com.edib.workknowledge.domain.Chunk>,
            conversationHistory: List<ChatModelProvider.ChatMessage>, onToken: (String) -> Unit
        ) = ChatModelProvider.OpResult.Success(answer)
    }

    private class RecordingChat : ChatModelProvider {
        var calls = 0
        override val modelName = "stub"
        override suspend fun generateResponse(
            systemPrompt: String, userMessage: String,
            contextChunks: List<com.edib.workknowledge.domain.Chunk>,
            conversationHistory: List<ChatModelProvider.ChatMessage>
        ): ChatModelProvider.OpResult<String> {
            calls++
            return ChatModelProvider.OpResult.Success("guessed")
        }
        override suspend fun generateStreamingResponse(
            systemPrompt: String, userMessage: String,
            contextChunks: List<com.edib.workknowledge.domain.Chunk>,
            conversationHistory: List<ChatModelProvider.ChatMessage>, onToken: (String) -> Unit
        ) = ChatModelProvider.OpResult.Success("guessed")
    }

    private object FailingChat : ChatModelProvider {
        override val modelName = "stub"
        override suspend fun generateResponse(
            systemPrompt: String, userMessage: String,
            contextChunks: List<com.edib.workknowledge.domain.Chunk>,
            conversationHistory: List<ChatModelProvider.ChatMessage>
        ) = ChatModelProvider.OpResult.Failure("model unreachable")
        override suspend fun generateStreamingResponse(
            systemPrompt: String, userMessage: String,
            contextChunks: List<com.edib.workknowledge.domain.Chunk>,
            conversationHistory: List<ChatModelProvider.ChatMessage>, onToken: (String) -> Unit
        ) = ChatModelProvider.OpResult.Failure("model unreachable")
    }

    private object NoopSearch : com.edib.workknowledge.data.SearchDao {
        override suspend fun insertAll(rows: List<com.edib.workknowledge.data.ChunkFtsEntity>) = Unit
        override suspend fun deleteByIds(ids: List<Long>) = Unit
        override suspend fun deleteById(chunkId: Long) = Unit
        override suspend fun deleteByDocument(documentId: Long) = Unit
        override suspend fun clearIndex() = Unit
        override suspend fun insertIndex(rows: List<com.edib.workknowledge.data.ChunkFtsEntity>) = Unit
        override suspend fun readAllForIndex(): List<com.edib.workknowledge.data.ChunkFtsEntity> = emptyList()
        override suspend fun count(): Int = 0
        override suspend fun match(query: String, limit: Int) = emptyList<com.edib.workknowledge.data.SearchDao.FtsHit>()
        override suspend fun matchBodies(query: String, limit: Int, projectId: Long?) =
            emptyList<com.edib.workknowledge.data.SearchDao.BodyHit>()
        override suspend fun matchBodiesInDocuments(query: String, limit: Int, documentIds: List<Long>) =
            emptyList<com.edib.workknowledge.data.SearchDao.BodyHit>()
    }

    private object NoopVectors : com.edib.workknowledge.retrieval.VectorRepository {
        override suspend fun addChunks(chunks: List<com.edib.workknowledge.domain.Chunk>) =
            com.edib.workknowledge.retrieval.OpResult.Success(Unit)
        override suspend fun search(
            projectId: Long, queryEmbedding: FloatArray, topK: Int,
            documentIds: List<Long>?, sheetName: String?, pageNumber: Int?
        ) = com.edib.workknowledge.retrieval.OpResult.Success(emptyList<com.edib.workknowledge.retrieval.ScoredChunk>())
        override suspend fun getChunksForDocument(documentId: Long) =
            com.edib.workknowledge.retrieval.OpResult.Success(emptyList<com.edib.workknowledge.domain.Chunk>())
        override suspend fun deleteChunksForDocument(documentId: Long) =
            com.edib.workknowledge.retrieval.OpResult.Success(Unit)
        override suspend fun getChunkCount(projectId: Long) =
            com.edib.workknowledge.retrieval.OpResult.Success(0)
    }
}
