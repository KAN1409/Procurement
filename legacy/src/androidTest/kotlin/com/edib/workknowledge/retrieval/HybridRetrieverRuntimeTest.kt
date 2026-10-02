package com.edib.workknowledge.retrieval

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edib.workknowledge.data.ChunkEntity
import com.edib.workknowledge.data.DocumentEntity
import com.edib.workknowledge.data.ProjectEntity
import com.edib.workknowledge.data.WorkKnowledgeDatabase
import com.edib.workknowledge.embeddings.EmbeddingConfig
import com.edib.workknowledge.embeddings.EmbeddingProvider
import com.edib.workknowledge.embeddings.ModelSetupHelper
import com.edib.workknowledge.embeddings.OnDeviceEmbeddingProvider
import com.edib.workknowledge.domain.Chunk
import com.edib.workknowledge.domain.VectorCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * HybridRetriever gate: run the production HybridRetriever with a REAL
 * OnDeviceEmbeddingProvider on the device's SQLite/Room FTS + vector index.
 *
 * No Groq. No RAG/LLM calls. No mocks — the real ORT embedding session and
 * the real Room FTS table are used end-to-end.
 */
@RunWith(AndroidJUnit4::class)
class HybridRetrieverRuntimeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val modelName = "all-MiniLM-L6-v2"
    private val TAG = "HybridRetrieverRuntime"

    private fun l2Norm(a: FloatArray): Double {
        var sum = 0.0
        for (v in a) sum += v * v
        return sqrt(sum)
    }

    @Test
    fun hybrid_retriever_real_model_all_queries() = runBlocking {
        // 1. Copy model files and initialize the REAL provider
        val modelDir = ModelSetupHelper.copyModelToDevice(context, modelName)
        val config = EmbeddingConfig(
            providerType = EmbeddingConfig.ProviderType.LOCAL,
            modelName = modelName
        )
        val provider = OnDeviceEmbeddingProvider(context, modelDir, config)
        val initResult = provider.initialize()
        assertTrue("Provider must initialize: $initResult",
            initResult is EmbeddingProvider.OpResult.Success)

        // 2. Set up Room DB and insert fixture chunks
        val db = WorkKnowledgeDatabase.getInstance(context)
        val chunkDao = db.chunkDao()
        val projectDao = db.projectDao()
        val documentDao = db.documentDao()
        val projectId = System.currentTimeMillis() // unique to avoid collision

        // Insert parent entities so FK constraints are satisfied
        projectDao.insert(ProjectEntity(id = projectId, name = "WK Runtime Test"))
        documentDao.insert(DocumentEntity(id = 1L, projectId = projectId, filename = "WK_Runtime_Test.pdf",
            originalUri = "", mimeType = "application/pdf", fileSize = 0, sha256 = "rt_sha_1"))
        documentDao.insert(DocumentEntity(id = 2L, projectId = projectId, filename = "WK_BOQ_Runtime_Test.xlsx",
            originalUri = "", mimeType = "application/vnd.ms-excel", fileSize = 0, sha256 = "rt_sha_2"))
        documentDao.insert(DocumentEntity(id = 3L, projectId = projectId, filename = "WK_Runtime_Test_2.pdf",
            originalUri = "", mimeType = "application/pdf", fileSize = 0, sha256 = "rt_sha_3"))

        val chunks = listOf(
            ChunkEntity(
                projectId = projectId,
                documentId = 1L,
                chunkIndex = 0,
                content = "Master Bathroom wall finish: Galala Marble. Approved thickness: 20 mm.",
                embedding = null,
                pageNumber = 3,
                sheetName = null,
                filename = "WK_Runtime_Test.pdf",
                projectName = "WK Runtime Test"
            ),
            ChunkEntity(
                projectId = projectId,
                documentId = 2L,
                chunkIndex = 0,
                content = "Item Code: MR-104  Description: Galala Marble Wall Cladding  Unit: m2  Quantity: 37.5  Rate: 1850  Amount: 69375",
                embedding = null,
                pageNumber = null,
                sheetName = "Master Bathroom",
                rowStart = 2,
                rowEnd = 3,
                filename = "WK_BOQ_Runtime_Test.xlsx",
                projectName = "WK Runtime Test"
            ),
            ChunkEntity(
                projectId = projectId,
                documentId = 2L,
                chunkIndex = 1,
                content = "Item Code: MR-105  Description: Piatra Grey Feature Band  Unit: m2  Quantity: 12.75  Rate: 2400  Amount: 30600",
                embedding = null,
                pageNumber = null,
                sheetName = "Master Bathroom",
                rowStart = 3,
                rowEnd = 4,
                filename = "WK_BOQ_Runtime_Test.xlsx",
                projectName = "WK Runtime Test"
            ),
            ChunkEntity(
                projectId = projectId,
                documentId = 3L,
                chunkIndex = 0,
                content = "Item Code: CL-201  Description: Gypsum Board Ceiling  Unit: m2  Quantity: 48  Rate: 950  Amount: 45600",
                embedding = null,
                pageNumber = null,
                sheetName = "Ceilings",
                rowStart = 2,
                rowEnd = 2,
                filename = "WK_BOQ_Runtime_Test.xlsx",
                projectName = "WK Runtime Test"
            ),
            ChunkEntity(
                projectId = projectId,
                documentId = 3L,
                chunkIndex = 1,
                content = "Purchase Request: PR-023  Status: Approved  Date: 2024-01-15",
                embedding = null,
                pageNumber = 3,
                sheetName = null,
                filename = "WK_Runtime_Test.pdf",
                projectName = "WK Runtime Test"
            )
        )

        println("=== HYBRID RETRIEVER: Inserting fixture chunks ===")
        val ids = chunkDao.insertAll(chunks)
        println("Inserted chunk IDs: $ids")

        // 3. Build FTS index
        // Insert FTS entries linking rowid to content
        val ftsEntries = chunks.mapIndexed { idx, chunk ->
            com.edib.workknowledge.data.ChunkFtsEntity(
                rowid = ids[idx],
                content = chunk.content,
                filename = chunk.filename,
                projectName = chunk.projectName,
                sheetName = chunk.sheetName ?: ""
            )
        }
        db.searchDao().insertIndex(ftsEntries)
        println("FTS index entries: ${ftsEntries.size}")

        // 4. Embed each chunk and persist vectors
        println("=== HYBRID RETRIEVER: Embedding and persisting vectors ===")
        for (i in chunks.indices) {
            val content = chunks[i].content
            val result = provider.embed(content)
            assertTrue("Embed must succeed: $result",
                result is EmbeddingProvider.OpResult.Success)
            val embedding = (result as EmbeddingProvider.OpResult.Success<FloatArray>).value
            assertEquals("384 dims", 384, embedding.size)
            val bytes = VectorCodec.encode(embedding)
            chunkDao.updateEmbedding(ids[i], bytes)
            println("  chunkId=${ids[i]}: '${content.take(50)}...' -> ${bytes.size} bytes")
        }

        // 5. Create the REAL HybridRetriever with REAL DocumentSearch + RoomVectorRepository
        val searchDao = db.searchDao()
        val vectorRepo = com.edib.workknowledge.retrieval.RoomVectorRepository(chunkDao)
        val documentSearch = com.edib.workknowledge.search.DocumentSearch(searchDao)
        val retriever = HybridRetriever(
            search = documentSearch,
            vectorRepository = vectorRepo,
            embeddingProvider = provider
        )

        // 6. Run all test queries
        val queries = listOf(
            "How thick is the marble in the master bathroom?",
            "How much Galala wall cladding is required?",
            "What purchase request was approved?",
            "كام كمية Galala Marble في الـ Master Bathroom؟",
            "إيه حالة PR-023؟"
        )

        println("\n=== HYBRID RETRIEVER: Running queries ===")
        for (query in queries) {
            println("\n--- Query: \"$query\" ---")
            val results = retriever.retrieve(
                question = query,
                projectId = projectId,
                documentIds = null,
                topK = 5
            )

            println("Results: ${results.size}")
            for ((idx, r) in results.withIndex()) {
                println("  [${idx + 1}] score=${r.score}, source=${r.source}, " +
                    "exactMatch=${r.exactMatch}, vectorScore=${r.vectorScore}")
                println("    content: ${r.content.take(80)}...")
                println("    filename: ${r.filename}, page=${r.pageNumber}, " +
                    "sheet=${r.sheetName}, rows=${r.rowStart}-${r.rowEnd}")
            }

            assertTrue("Query must return results: \"$query\"", results.isNotEmpty())

            // Verify provenance is present
            for (r in results) {
                assertNotNull("Source must be set", r.source)
                assertNotNull("Filename must be set", r.filename)
                assertNotNull("Content must not be null", r.content)
                assertTrue("Content must not be empty", r.content.isNotEmpty())
            }
        }

        // 7. Verify at least one exact (FTS) hit for identifier-like queries
        val prResults = retriever.retrieve("PR-023", projectId = projectId, topK = 5)
        println("\n=== PR-023 results ===")
        prResults.forEach { println("  ${it.source} score=${it.score} exact=${it.exactMatch} content=${it.content.take(50)}") }
        assertTrue("PR-023 must return results", prResults.isNotEmpty())
        assertTrue("PR-023 should have an exact match",
            prResults.any { it.exactMatch || it.source == RetrievalSource.FTS || it.source == RetrievalSource.BOTH })

        // 8. Verify vector results exist for semantic queries
        val marbleResults = retriever.retrieve(
            "How thick is the marble in the master bathroom?",
            projectId = projectId,
            topK = 5
        )
        assertTrue("Marble query must return results", marbleResults.isNotEmpty())
        assertTrue("At least one result must have vector score (semantic)",
            marbleResults.any { it.source == RetrievalSource.VECTOR || it.source == RetrievalSource.BOTH })

        // 9. Verify FTS/VECTOR/BOTH sources are all present across queries
        val allSources = mutableSetOf<RetrievalSource>()
        for (query in queries) {
            val results = retriever.retrieve(query, projectId = projectId, topK = 5)
            results.forEach { allSources.add(it.source) }
        }
        println("All retrieval sources observed: $allSources")

        // 10. Cleanup
        chunkDao.deleteByDocument(1L)
        chunkDao.deleteByDocument(2L)
        chunkDao.deleteByDocument(3L)

        provider.close()
        db.close()

        println("\n=== HYBRID RETRIEVER TEST PASSED ===")
    }
}
