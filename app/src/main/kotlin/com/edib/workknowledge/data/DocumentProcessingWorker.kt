package com.edib.workknowledge.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.edib.workknowledge.data.ProcessingJobEntity.JobStatus
import com.edib.workknowledge.data.ProcessingJobEntity.JobType
import com.edib.workknowledge.domain.Chunk
import com.edib.workknowledge.domain.Document
import com.edib.workknowledge.embeddings.EmbeddingProvider
import com.edib.workknowledge.embeddings.EmbeddingProviderFactory
import com.edib.workknowledge.llm.ChatModelProvider
import com.edib.workknowledge.llm.ChatModelProviderFactory
import com.edib.workknowledge.parsers.ParserRegistry
import com.edib.workknowledge.rag.HierarchicalSummarizationEngine
import com.edib.workknowledge.rag.SummarizationEngine

/**
 * Background ingestion pipeline:
 *   parse -> chunk -> embed -> summarize -> READY
 *
 * Every stage records its outcome on the document row so the UI can show real
 * progress and real failures rather than an optimistic "done".
 */
class DocumentProcessingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val documentId: Long = params.inputData.getLong(KEY_DOCUMENT_ID, -1L)

    override suspend fun doWork(): ListenableWorker.Result {
        return try {
            runIngestion()
        } catch (t: Throwable) {
            runCatching {
                WorkKnowledgeDatabase.getInstance(applicationContext)
                    .documentDao()
                    .updateStatus(
                        documentId,
                        DocumentEntity.ProcessingStatus.FAILED,
                        t.message ?: t::class.java.simpleName,
                        System.currentTimeMillis()
                    )
            }
            ListenableWorker.Result.failure()
        }
    }

    private suspend fun runIngestion(): ListenableWorker.Result {
        if (documentId <= 0L) return ListenableWorker.Result.failure()

        val db = WorkKnowledgeDatabase.getInstance(applicationContext)
        val documentDao = db.documentDao()
        val chunkDao = db.chunkDao()
        val summaryDao = db.documentSummaryDao()
        val jobDao = db.processingJobDao()

        val documentEntity = documentDao.byId(documentId)
            ?: return ListenableWorker.Result.failure()
        val document = Document.fromEntity(documentEntity)

        val job = jobDao.latest(documentId)
            ?: ProcessingJobEntity(documentId = documentId, jobType = JobType.PARSE)
                .also { jobDao.insert(it) }

        val totalSteps = 5
        suspend fun setStage(stage: String, status: DocumentEntity.ProcessingStatus, done: Int) {
            jobDao.update(
                job.copy(
                    status = JobStatus.RUNNING,
                    currentStep = stage,
                    progress = done,
                    totalSteps = totalSteps
                )
            )
            documentDao.updateStatus(documentId, status, null, System.currentTimeMillis())
        }

        setStage("Reading file", DocumentEntity.ProcessingStatus.PARSING, 1)

        // ---- parse ----
        val parser = ParserRegistry(applicationContext).getParserForDocument(document)
            ?: return fail(
                documentDao, jobDao, job, documentId, totalSteps,
                "No parser for this file type (${document.mimeType})."
            )

        val parsed = parser.parse(document)
        if (parsed.error != null) return fail(
            documentDao, jobDao, job, documentId, totalSteps, parsed.error!!
        )
        if (parsed.chunks.isEmpty()) return fail(
            documentDao, jobDao, job, documentId, totalSteps,
            "No content could be extracted from this document."
        )

        documentDao.update(documentEntity.copy(
            pageCount = parsed.pageCount,
            sheetCount = parsed.sheetCount,
            status = DocumentEntity.ProcessingStatus.CHUNKING,
            errorMessage = null,
            updatedAt = System.currentTimeMillis()
        ))

        // ---- persist chunks (re-index is idempotent) ----
        setStage("Creating ${parsed.chunks.size} chunks", DocumentEntity.ProcessingStatus.CHUNKING, 2)
        chunkDao.deleteByDocument(documentId)
        chunkDao.insertAll(parsed.chunks.mapIndexed { i, c -> c.toEntity().copy(chunkIndex = i) })

        // ---- embed ----
        setStage("Embedding 0 / ${parsed.chunks.size}", DocumentEntity.ProcessingStatus.EMBEDDING, 3)
        val embeddings: EmbeddingProvider = EmbeddingProviderFactory(applicationContext).createProvider()
        val embedded = embedAll(embeddings, parsed.chunks) { done, total ->
            jobDao.update(
                job.copy(
                    status = JobStatus.RUNNING,
                    currentStep = "Embedding $done / $total",
                    progress = 3,
                    totalSteps = totalSteps
                )
            )
        }
        if (embedded == null) return fail(
            documentDao, jobDao, job, documentId, totalSteps,
            "Embedding failed. Check the embedding provider in Settings."
        )

        // rewrite rows carrying vectors
        chunkDao.deleteByDocument(documentId)
        chunkDao.insertAll(embedded.mapIndexed { i, c -> c.toEntity().copy(chunkIndex = i) })

        // ---- summarize ----
        setStage("Generating summary", DocumentEntity.ProcessingStatus.SUMMARIZING, 4)
        val chat: ChatModelProvider = ChatModelProviderFactory(applicationContext).createProvider()
        val summarizer: SummarizationEngine = HierarchicalSummarizationEngine(chat)
        when (val s = summarizer.summarizeDocument(document, embedded)) {
            is SummarizationEngine.OpResult.Success -> {
                val value = s.value
                summaryDao.insert(
                    DocumentSummaryEntity(
                        documentId = documentId,
                        summary = value.summary,
                        keyFactsJson = "[]",
                        modelUsed = value.metadata.modelUsed
                    ).withKeyFacts(value.keyFacts)
                )
            }
            is SummarizationEngine.OpResult.Failure ->
                // A summary is derived data, not the authority: a failed summary
                // must not fail ingestion, but it must be visible.
                jobDao.update(
                    job.copy(
                        status = JobStatus.RUNNING,
                        currentStep = "Summary failed: ${s.error}",
                        progress = 4,
                        totalSteps = totalSteps
                    )
                )
        }

        // ---- ready ----
        documentDao.updateStatus(documentId, DocumentEntity.ProcessingStatus.READY, null, System.currentTimeMillis())
        jobDao.update(
            job.copy(
                status = JobStatus.COMPLETED,
                currentStep = "Ready",
                progress = totalSteps,
                totalSteps = totalSteps,
                completedAt = System.currentTimeMillis()
            )
        )
        return ListenableWorker.Result.success(workDataOf(KEY_DOCUMENT_ID to documentId))
    }

    private suspend fun fail(
        documentDao: DocumentDao,
        jobDao: ProcessingJobDao,
        job: ProcessingJobEntity,
        documentId: Long,
        totalSteps: Int,
        message: String
    ): ListenableWorker.Result {
        documentDao.updateStatus(
            documentId, DocumentEntity.ProcessingStatus.FAILED, message, System.currentTimeMillis()
        )
        jobDao.update(
            job.copy(
                status = JobStatus.FAILED,
                currentStep = "Failed",
                errorMessage = message,
                progress = job.progress,
                totalSteps = totalSteps,
                completedAt = System.currentTimeMillis()
            )
        )
        return ListenableWorker.Result.failure(workDataOf(KEY_ERROR to message))
    }

    private suspend fun embedAll(
        provider: EmbeddingProvider,
        chunks: List<Chunk>,
        onProgress: suspend (done: Int, total: Int) -> Unit
    ): List<Chunk>? {
        var done = 0
        val out = ArrayList<Chunk>(chunks.size)
        for (chunk in chunks) {
            when (val r = provider.embed(chunk.content)) {
                is EmbeddingProvider.OpResult.Success -> {
                    out.add(chunk.copy(embedding = r.value))
                    done++
                    onProgress(done, chunks.size)
                }
                is EmbeddingProvider.OpResult.Failure -> return null
            }
        }
        return out
    }

    companion object {
        const val KEY_DOCUMENT_ID = "document_id"
        const val KEY_ERROR = "error"
    }
}
