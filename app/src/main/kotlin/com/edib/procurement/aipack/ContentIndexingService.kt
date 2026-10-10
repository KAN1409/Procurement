package com.edib.procurement.aipack

import android.content.Context
import android.util.Log
import com.edib.procurement.database.ContentState
import com.edib.procurement.database.DocumentDao
import com.edib.procurement.database.DocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Batch content indexing service.
 *
 * Processes NOT_INDEXED and STALE documents incrementally, without loading
 * the entire corpus into memory. Shows progress counts and is cancellable.
 * Resumes using persisted document states.
 *
 * Does NOT require AI Pack generation.
 */
class ContentIndexingService(
    private val context: Context,
    private val documentDao: DocumentDao,
    private val contentIndexer: ContentIndexer
) {

    companion object {
        const val TAG = "ContentIndexing"
        const val BATCH_SIZE = 50
    }

    /**
     * Progress state for the indexing operation.
     */
    data class Progress(
        val indexed: Int = 0,
        val needsOcr: Int = 0,
        val unsupported: Int = 0,
        val failed: Int = 0,
        val remaining: Int = 0,
        val currentFile: String = "",
        val isRunning: Boolean = false,
        val isCancelled: Boolean = false,
        val error: String? = null
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: kotlinx.coroutines.flow.StateFlow<Progress> = _progress.asStateFlow()

    private var job: Job? = null

    /**
     * Starts the content indexing process.
     * Processes all documents with contentState = NOT_INDEXED or STALE.
     * Skips INDEXED documents that haven't changed.
     */
    fun start() {
        if (job?.isActive == true) {
            Log.w(TAG, "Content indexing already running")
            return
        }

        job = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            _progress.update { it.copy(isRunning = true, isCancelled = false, error = null) }
            runIndexing()
        }
    }

    /**
     * Cancels the current indexing operation.
     */
    fun cancel() {
        job?.cancel()
        _progress.update { it.copy(isCancelled = true, isRunning = false) }
    }

    private suspend fun runIndexing() {
        var totalIndexed = 0
        var totalNeedsOcr = 0
        var totalUnsupported = 0
        var totalFailed = 0

        try {
            while (!currentCoroutineContext().isActive) {
                // Check if we should continue
            }

            // Get total count of documents to process
            val totalToProcess = documentDao.countNeedingIndexing()
            _progress.update { it.copy(remaining = totalToProcess) }

            var offset = 0
            var hasMore = true

            while (hasMore && currentCoroutineContext().isActive) {
                // Fetch a batch of documents needing indexing
                val docs = documentDao.documentsNeedingIndexing(BATCH_SIZE, offset)
                if (docs.isEmpty()) {
                    hasMore = false
                    break
                }

                _progress.update { it.copy(
                    remaining = maxOf(0, totalToProcess - offset - docs.size),
                    currentFile = docs.first().displayName
                ) }

                for (doc in docs) {
                    if (!currentCoroutineContext().isActive) break

                    _progress.update { it.copy(currentFile = doc.displayName) }

                    val result = contentIndexer.indexDocument(doc)

                    when (result) {
                        is ContentIndexer.IndexResult.Success -> totalIndexed++
                        is ContentIndexer.IndexResult.NeedsOcr -> totalNeedsOcr++
                        is ContentIndexer.IndexResult.Unsupported -> totalUnsupported++
                        is ContentIndexer.IndexResult.Failed -> totalFailed++
                    }

                    _progress.update { it.copy(
                        indexed = totalIndexed,
                        needsOcr = totalNeedsOcr,
                        unsupported = totalUnsupported,
                        failed = totalFailed,
                        remaining = maxOf(0, totalToProcess - offset - docs.size)
                    ) }
                }

                offset += docs.size
            }

        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "Content indexing cancelled")
            } else {
                Log.e(TAG, "Content indexing failed", e)
                _progress.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            }
        } finally {
            _progress.update { it.copy(isRunning = false, currentFile = "") }
        }
    }

    /**
     * Triggers OCR fallback for documents that need it.
     */
    fun runOcrFallback() {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val docs = documentDao.documentsWithContentState(ContentState.NEEDS_OCR)
            var processed = 0
            var succeeded = 0
            var failed = 0

            _progress.update { it.copy(isRunning = true, remaining = docs.size) }

            for (doc in docs) {
                if (!currentCoroutineContext().isActive) break

                _progress.update { it.copy(currentFile = doc.displayName, remaining = docs.size - processed) }

                val result = contentIndexer.runOcrFallback(doc)
                if (result is ContentIndexer.IndexResult.Success) {
                    succeeded++
                } else {
                    failed++
                }
                processed++

                _progress.update { it.copy(
                    indexed = succeeded,
                    failed = failed,
                    remaining = docs.size - processed
                ) }
            }

            _progress.update { it.copy(isRunning = false, currentFile = "") }
        }
    }
}
