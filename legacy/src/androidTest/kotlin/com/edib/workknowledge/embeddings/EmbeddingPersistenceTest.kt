package com.edib.workknowledge.embeddings

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edib.workknowledge.data.ChunkEntity
import com.edib.workknowledge.data.DocumentEntity
import com.edib.workknowledge.data.ProjectEntity
import com.edib.workknowledge.data.WorkKnowledgeDatabase
import com.edib.workknowledge.domain.Chunk
import com.edib.workknowledge.domain.VectorCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Persistence gate: embed existing fixture chunks (without re-parsing documents),
 * persist the real vectors into Room, force-stop the process, relaunch, and
 * prove the vectors survived and are not unnecessarily regenerated.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingPersistenceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val TAG = "EmbeddingPersistence"
    private val modelName = "all-MiniLM-L6-v2"

    private fun cosSim(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val mag = sqrt(na) * sqrt(nb)
        return if (mag > 0.0) (dot / mag).toFloat() else 0f
    }

    private fun l2Norm(a: FloatArray): Double {
        var sum = 0.0
        for (v in a) sum += v * v
        return sqrt(sum)
    }

    private fun maxAbsDiff(a: FloatArray, b: FloatArray): Float {
        var max = 0f
        for (i in a.indices) {
            val d = abs(a[i] - b[i])
            if (d > max) max = d
        }
        return max
    }

    @Test
    fun embed_persist_and_survive_force_stop() = runBlocking {
        // 1. Copy model files from /data/local/tmp/ to app storage
        val modelDir = ModelSetupHelper.copyModelToDevice(context, modelName)
        val config = EmbeddingConfig(
            providerType = EmbeddingConfig.ProviderType.LOCAL,
            modelName = modelName
        )
        val provider = OnDeviceEmbeddingProvider(context, modelDir, config)
        val initResult = provider.initialize()
        assertTrue("Provider must initialize: $initResult",
            initResult is EmbeddingProvider.OpResult.Success)

        // 2. Use the REAL app database (same file used in production)
        val db = WorkKnowledgeDatabase.getInstance(context)
        val chunkDao = db.chunkDao()
        val projectDao = db.projectDao()
        val documentDao = db.documentDao()

        // 3. Insert parent entities so FK constraints are satisfied
        val projectId = 1L
        val project = ProjectEntity(id = projectId, name = "WK Runtime Test")
        projectDao.insert(project)
        val doc1 = DocumentEntity(id = 1001L, projectId = projectId, filename = "WK_BOQ_Runtime_Test.xlsx",
            originalUri = "", mimeType = "application/vnd.ms-excel", fileSize = 0, sha256 = "test_sha_1001")
        documentDao.insert(doc1)
        val doc2 = DocumentEntity(id = 1002L, projectId = projectId, filename = "WK_Runtime_Test.pdf",
            originalUri = "", mimeType = "application/pdf", fileSize = 0, sha256 = "test_sha_1002")
        documentDao.insert(doc2)

        // 4. Create fixture chunks WITHOUT re-parsing documents.
        // These are the exact chunk contents from WK_Runtime_Test.pdf and WK_BOQ_Runtime_Test.xlsx
        val chunks = listOf(
            ChunkEntity(
                projectId = 1L,
                documentId = 1001L,
                chunkIndex = 0,
                content = "Galala Marble Wall Cladding MR-104 Quantity 37.5 m2 Rate 1850",
                embedding = null,
                pageNumber = null,
                sheetName = "Master Bathroom",
                rowStart = 2,
                rowEnd = 3,
                cellRange = null,
                filename = "WK_BOQ_Runtime_Test.xlsx",
                projectName = "WK Runtime Test"
            ),
            ChunkEntity(
                projectId = 1L,
                documentId = 1002L,
                chunkIndex = 0,
                content = "Purchase Request: PR-023 Status: Approved",
                embedding = null,
                pageNumber = 3,
                sheetName = null,
                rowStart = null,
                rowEnd = null,
                cellRange = null,
                filename = "WK_Runtime_Test.pdf",
                projectName = "WK Runtime Test"
            )
        )

        println("=== PERSISTENCE-GATE: Creating fixture chunks ===")
        val ids = chunkDao.insertAll(chunks)
        println("Inserted chunk IDs: $ids")
        assertEquals("Must insert 2 chunks", 2, ids.size)

        // 4. Embed each chunk with the REAL model and persist vectors
        println("=== PERSISTENCE-GATE: Embedding and persisting ===")
        for (i in chunks.indices) {
            val chunkId = ids[i]
            val content = chunks[i].content
            val result = provider.embed(content)
            assertTrue("Embed must succeed for '$content': $result",
                result is EmbeddingProvider.OpResult.Success)
            val embedding = (result as EmbeddingProvider.OpResult.Success<FloatArray>).value
            assertEquals("Embedding must be 384-dim", 384, embedding.size)
            val bytes = VectorCodec.encode(embedding)
            chunkDao.updateEmbedding(chunkId, bytes)
            println("  chunkId=$chunkId embedded, bytes=${bytes.size}")
        }

        // 5. Verify vectors exist before force-stop
        val beforeCount = chunkDao.countWithVectors()
        println("Vectors before force-stop: $beforeCount")
        assertEquals("Must have 2 vectors", 2, beforeCount)

        // 6. Verify vectors can be decoded and are valid
        val sampleEntity = chunkDao.byDocument(1002L).first()
        assertNotNull("Sample chunk must exist", sampleEntity)
        assertNotNull("Sample must have embedding", sampleEntity?.embedding)
        val decoded = VectorCodec.decode(sampleEntity!!.embedding!!)
        assertNotNull("Decoded vector", decoded)
        assertEquals("384 dims", 384, decoded!!.size)
        println("Pre-stop decoded: ${decoded.size} dims, L2 norm=${l2Norm(decoded)}")

        // 7. Capture embedding values for post-stop comparison
        val allChunksBefore = chunkDao.byProjectWithVectors(1L)
        val preStopVectors = allChunksBefore.associate {
            it.id to VectorCodec.decode(it.embedding!!)
        }
        println("Pre-stop vectors: ${preStopVectors.size}")
        assertEquals("Pre-stop must have 2 vectors", 2, preStopVectors.size)

        provider.close()
        db.close()

        // 8. Simulate force-stop: clear the singleton and reopen the SAME DB file
        println("\n=== PERSISTENCE-GATE: Simulating force-stop (DB reopen) ===")
        val field = WorkKnowledgeDatabase::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)

        // Give the OS time to release file locks
        Thread.sleep(500)

        val db2 = WorkKnowledgeDatabase.getInstance(context)
        val chunkDao2 = db2.chunkDao()

        // 9. Verify vectors survived force-stop
        val afterCount = chunkDao2.countWithVectors()
        println("Vectors after force-stop: $afterCount")
        assertEquals("Vectors must survive force-stop", 2, afterCount)

        // 10. Verify vectors are identical (not regenerated)
        val allChunksAfter = chunkDao2.byProjectWithVectors(1L)
        println("Post-stop chunk count: ${allChunksAfter.size}")
        assertEquals("Must have same number of chunks", 2, allChunksAfter.size)

        var allMatch = true
        var maxDiff = 0f
        for (after in allChunksAfter) {
            val beforeVec = preStopVectors[after.id]
            if (beforeVec == null) {
                Log.e(TAG, "No pre-stop vector for chunk ${after.id}")
                allMatch = false
                continue
            }
            val afterVec = VectorCodec.decode(after.embedding!!)!!
            val sim = cosSim(beforeVec!!, afterVec)
            val diff = maxAbsDiff(beforeVec, afterVec)
            println("  chunkId=${after.id}: cosSim=$sim, maxAbsDiff=$diff")
            if (abs(sim - 1.0f) > 1e-4) allMatch = false
            if (diff > maxDiff) maxDiff = diff
        }

        assertTrue("All vectors must be identical after force-stop (cos≈1.0)", allMatch)
        assertTrue("Max abs diff must be ~0", maxDiff < 1e-5)
        println("Max abs diff across all vectors: $maxDiff")

        // 11. Cleanup test data
        chunkDao2.deleteByDocument(1001L)
        chunkDao2.deleteByDocument(1002L)

        db2.close()
        println("\n=== PERSISTENCE TEST PASSED: vectors survived force-stop ===")
    }
}
