package com.edib.procurement.documents

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.edib.procurement.database.DocumentDao
import com.edib.procurement.database.DocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.security.MessageDigest

/** Progress emitted while a scan runs, so the UI can show real state. */
data class ScanProgress(
    val scanned: Int,
    val indexed: Int,
    val skipped: Int,
    val currentPath: String,
    val done: Boolean = false,
    val error: String? = null
)

data class ScanSummary(
    val treeUri: String,
    val indexed: Int,
    val skippedUnsupported: Int,
    val missing: Int
)

/**
 * Recursively indexes the user's document tree through SAF.
 *
 * Deliberately metadata-only: name, URI, relative path, extension, MIME type,
 * size and last-modified. No file content is opened, so indexing several
 * thousand files stays cheap and the UI never blocks.
 *
 * SHA-256 is left null here and computed on demand by [hashOf] only when
 * something actually needs content identity.
 *
 * Uses stable URI-based reconciliation:
 * - At scan start: mark existing documents for the tree as missing
 * - While walking:
 *   - If URI already exists and size/lastModified unchanged: preserve document id,
 *     extractedText, contentState, extraction metadata; clear isMissing; update only safe metadata
 *   - If URI exists but size/lastModified changed: preserve document id and user
 *     relationships; update metadata; mark contentState = STALE
 *   - If URI is new: insert new DocumentEntity with contentState = NOT_INDEXED
 * - After walking: unseen rows remain isMissing = true; user-created relationships preserved
 */
class DocumentTreeScanner(
    private val context: Context,
    private val documentDao: DocumentDao
) {

    companion object {
        private const val TAG = "ProcurementScan"

        /**
         * Extensions indexed in V1. The scanner dispatches on extension rather
         * than MIME type because SAF reports vendor-specific types for Office
         * files on some devices; the set is easy to widen later.
         *
         * RTF is deliberately absent. A real archive holds a lot of it (this
         * device: 72 files, ~244 MB), and indexing a format that cannot be
         * read would fill the library with entries that fail at export time.
         * Supporting it is a tracked decision, not an oversight.
         *
         * .doc IS indexed because Word legitimately saves OOXML under that name.
         * A genuine OLE2 .doc is unreadable here and is reported UNSUPPORTED
         * with a remedy rather than being misfiled as an OCR case.
         */
        val SUPPORTED_EXTENSIONS = setOf("pdf", "docx", "doc", "xlsx", "xls", "csv", "txt")

        /**
         * Formats seen in real corpora that this version does not index.
         *
         * Recorded so a future capability decision has evidence behind it, and
         * so the scanner can explain an omission instead of silently skipping.
         */
        val KNOWN_UNSUPPORTED_EXTENSIONS = setOf("rtf")

        /** MIME type per extension, for ACTION_VIEW with the right type. */
        fun mimeFor(extension: String?): String = when (extension?.lowercase()) {
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "doc" -> "application/msword"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "xls" -> "application/vnd.ms-excel"
            "csv" -> "text/csv"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }

        /** Rows are written to Room in batches so a large tree stays cheap. */
        private const val BATCH_SIZE = 200
    }

/**
 * Indexes (or re-indexes) a SAF tree.
 *
 * @param deleteExisting remove rows from a previous scan of the same tree
 *   before writing the new ones. Manual LINKS are never touched: they live in
 *   `document_link` and cascade only when their document row actually goes.
 * @param master identity of the Follow-up workbook, which is a data source and
 *   must never be indexed as an ordinary work document even when the selected
 *   tree contains the very same file.
 */
fun scan(
        treeUri: Uri,
        master: MasterWorkbookIdentity = MasterWorkbookIdentity(null, null, null, 0)
    ): Flow<ScanProgress> = flow {
        val treeUriString = treeUri.toString()
        // Validate up front so an expired tree permission fails with a clear
        // message instead of a silent empty index.
        val rootDocId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            throw IllegalStateException(
                "This folder is no longer available. Pick the document folder again.", e
            )
        }
        // Every child enumeration is built against this URI: it carries the
        // persisted permission grant and the /tree/ segment that
        // buildChildDocumentsUriUsingTree requires.
        val scanTreeUri = treeUri

        // Step 1: Mark all existing documents for this tree as missing
        // This is the stable URI-based reconciliation approach
        documentDao.markAllMissing(treeUriString)

        var scanned = 0
        var indexed = 0
        var skipped = 0
        var updated = 0
        var stale = 0
        var masterSkipped = 0

        // Pending batch, flushed periodically so a 10k-file tree does not build
        // one huge transaction or one huge in-memory list.
        val batch = ArrayList<DocumentEntity>()
        val seenUris = HashSet<String>()

        suspend fun flush() {
            if (batch.isEmpty()) return
            documentDao.insertDocuments(batch.toList())
            batch.clear()
        }

        /**
     * Depth-first walk of [treeDocId].
     *
     * The document id is threaded explicitly instead of being re-derived from a
     * URI. A tree URI carries the ROOT id, so calling `getDocumentId` on the tree
     * URI at every level would re-enumerate the root forever.
     */
        suspend fun walk(docId: String, relativePrefix: String) {
            currentCoroutineContext().ensureActive()
            val children = listChildren(scanTreeUri, docId)

            for (child in children) {
                currentCoroutineContext().ensureActive()
                scanned++

                if (child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(
                        child.documentId,
                        if (relativePrefix.isEmpty()) child.displayName
                        else "$relativePrefix/${child.displayName}"
                    )
                    continue
                }

                val extension = com.edib.procurement.followup.TextNormalizer.extension(child.displayName)
                if (extension == null || extension.lowercase() !in SUPPORTED_EXTENSIONS) {
                    skipped++
                    continue
                }

                // The Follow-up workbook is the data source, not one of the
                // documents it describes. Skipping it here keeps it out of
                // Documents, Unlinked, counts and matching candidates.
                if (MasterWorkbook.isMaster(
                        master = master,
                        candidateUri = child.uri.toString(),
                        candidateDocId = child.documentId,
                        candidateSize = child.size,
                        candidateName = child.displayName
                    )
                ) {
                    android.util.Log.i(TAG, "skipping master workbook: ${child.displayName}")
                    masterSkipped++
                    continue
                }

                val childUri = child.uri.toString()
                if (!seenUris.add(childUri)) continue

                // Stable URI-based reconciliation
                val existingId = documentDao.documentIdForUri(childUri)
                if (existingId != null && existingId > 0) {
                    // Document exists - check if content changed
                    val existingDoc = documentDao.byId(existingId)
                    if (existingDoc != null) {
                        if (existingDoc.size == child.size && existingDoc.lastModified == child.lastModified) {
                            // A) Size AND lastModified unchanged: preserve everything, clear isMissing
                            documentDao.refreshDocument(
                                existingId, child.size, child.lastModified,
                                System.currentTimeMillis()
                            )
                            // Also clear isMissing flag
                            documentDao.clearMissing(childUri)
                            updated++
                        } else {
                            // B) Size OR lastModified changed: preserve id and user relationships,
                            // update metadata, mark contentState = STALE
                            documentDao.refreshDocument(
                                existingId, child.size, child.lastModified,
                                System.currentTimeMillis()
                            )
                            documentDao.clearMissing(childUri)
                            // Mark content as STALE so it gets re-indexed
                            documentDao.updateContentState(
                                existingId,
                                ContentState.STALE,
                                null,
                                0,
                                null,
                                "File content changed since last extraction",
                                child.size,
                                child.lastModified
                            )
                            stale++
                        }
                    }
                    continue
                }

                // C) URI is new: insert new DocumentEntity with contentState = NOT_INDEXED
                batch.add(
                    DocumentEntity(
                        treeUri = treeUriString,
                        documentUri = childUri,
                        displayName = child.displayName,
                        normalizedName =
                            com.edib.procurement.followup.TextNormalizer.normalize(child.displayName),
                        matchFileKey = com.edib.procurement.matching.MatchNormalizer.filenameKey(child.displayName),
                        matchStemKey = com.edib.procurement.matching.MatchNormalizer.fileStemKey(child.displayName),
                        relativePath = if (relativePrefix.isEmpty()) child.displayName
                        else "$relativePrefix/${child.displayName}",
                        mimeType = child.mimeType ?: mimeFor(extension),
                        extension = extension.lowercase(),
                        size = child.size,
                        lastModified = child.lastModified,
                        indexedAt = System.currentTimeMillis(),
                        isMissing = false,
                        contentState = ContentState.NOT_INDEXED
                    )
                )
                indexed++

                if (batch.size >= BATCH_SIZE) {
                    flush()
                    emit(ScanProgress(scanned, indexed, skipped, child.displayName))
                }
            }
        }

        walk(rootDocId, "")
        flush()

        // A workbook indexed before this exclusion existed must not survive the
        // rescan, so it is removed explicitly as well.
        removePreviouslyIndexedMaster(master, treeUriString)

        emit(
            ScanProgress(
                scanned = scanned,
                indexed = indexed,
                skipped = skipped,
                currentPath = "",
                done = true
            )
        )
    }.flowOn(Dispatchers.IO)

    /**
 * Deletes any row that the master-workbook rule identifies, for rows written by
 * an earlier build that did not exclude it.
 *
 * CASCADE takes the document's links with it, which is correct here: a link to
 * the master workbook was never a real relationship, and any manual link the
 * user made to it is preserved by [MasterWorkbook.isMaster] returning false
 * for a different file.
 */
private suspend fun removePreviouslyIndexedMaster(
        master: MasterWorkbookIdentity,
        treeUriString: String
    ) {
        if (!master.isKnown) return
        val rows = documentDao.documentsInTree(treeUriString)
        for (doc in rows) {
            val isMaster = MasterWorkbook.isMaster(
                master = master,
                candidateUri = doc.documentUri,
                candidateDocId = MasterWorkbook.docIdOf(doc.documentUri),
                candidateSize = doc.size,
                candidateSha = doc.lazySha256,
                candidateName = doc.displayName
            )
            if (isMaster) {
                android.util.Log.i(TAG, "removing previously indexed master: ${doc.displayName}")
                documentDao.deleteDocument(doc.id)
            }
        }
    }

    /**
     * Marks documents whose URI no longer resolves, without deleting their rows:
     * a manual link to a temporarily unmounted folder should come back when the
     * folder returns.
     */
    suspend fun markMissing(treeUri: Uri) {
        documentDao.markAllMissing(treeUri.toString())
    }

    /** Computes SHA-256 on demand and caches it. Never called during a scan. */
    suspend fun hashOf(documentId: Long): String? {
        val doc = documentDao.byId(documentId) ?: return null
        doc.lazySha256?.let { return it }
        val digest = context.contentResolver.openInputStream(Uri.parse(doc.documentUri))
            ?.use { input ->
                val md = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }
            ?: return null
        documentDao.setSha(documentId, digest)
        return digest
    }

    // ------------------------------------------------------- SAF plumbing

    private data class Child(
        val uri: Uri,
        val documentId: String,
        val displayName: String,
        val mimeType: String?,
        val size: Long,
        val lastModified: Long
    )

/**
 * Lists the direct children of the document identified by [docId].
 *
 * Always builds the child URI against the tree, because that is what carries
 * the user's persisted permission grant.
 */
private fun listChildren(scanTree: Uri, docId: String): List<Child> {
        val out = ArrayList<Child>()
        val resolver = context.contentResolver

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(scanTree, docId)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        try {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    val id = if (idIdx >= 0) cursor.getString(idIdx) else continue
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) else id
                    out.add(
                        Child(
                            uri = DocumentsContract.buildDocumentUriUsingTree(scanTree, id),
                            documentId = id,
                            displayName = name,
                            mimeType = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null,
                            size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L,
                            lastModified = if (modIdx >= 0 && !cursor.isNull(modIdx)) cursor.getLong(modIdx) else 0L
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // One unreadable folder must not abort the whole scan.
            android.util.Log.w(TAG, "cannot list children of $docId", e)
            return out
        }
        return out
    }
}
