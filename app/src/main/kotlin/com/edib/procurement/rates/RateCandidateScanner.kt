package com.edib.procurement.rates

import com.edib.procurement.database.RateCandidateDao
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

data class RateCandidateScanProgress(
    val running: Boolean = false,
    val documentsScanned: Int = 0,
    val candidatesDetected: Int = 0,
    val candidatesInserted: Int = 0,
    val currentFile: String? = null,
    val error: String? = null
)

data class RateCandidateScanResult(
    val documentsScanned: Int,
    val candidatesDetected: Int,
    val candidatesInserted: Int
)

class RateCandidateScanner(
    private val dao: RateCandidateDao
) {
    suspend fun scan(
        onProgress: (RateCandidateScanProgress) -> Unit = {}
    ): RateCandidateScanResult {
        var offset = 0
        var documentsScanned = 0
        var detected = 0
        var inserted = 0
        val batchSize = 25

        while (currentCoroutineContext().isActive) {
            val docs = dao.indexedCorpusDocuments(batchSize, offset)
            if (docs.isEmpty()) break
            for (doc in docs) {
                if (!currentCoroutineContext().isActive) break
                dao.markOldPendingStale(doc.id, doc.lastModified)
                val rows = RateCandidateParser.parse(doc, System.currentTimeMillis())
                detected += rows.size
                if (rows.isNotEmpty()) {
                    inserted += dao.insertAll(rows).count { it > 0L }
                }
                documentsScanned++
                onProgress(
                    RateCandidateScanProgress(
                        running = true,
                        documentsScanned = documentsScanned,
                        candidatesDetected = detected,
                        candidatesInserted = inserted,
                        currentFile = doc.displayName
                    )
                )
            }
            offset += docs.size
        }

        return RateCandidateScanResult(
            documentsScanned = documentsScanned,
            candidatesDetected = detected,
            candidatesInserted = inserted
        )
    }
}
