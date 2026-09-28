package com.edib.workknowledge.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * All persistence and import orchestration.
 *
 * Two rules that the rest of the app depends on:
 *  1. Documents are keyed by the SHA-256 of their bytes, so importing the same
 *     file twice is detected instead of silently re-indexed.
 *  2. Every write is a suspend function on Dispatchers.IO; nothing here may be
 *     called from the main thread.
 */
class WorkRepository(context: Context) {

    private val appContext: Context = context.applicationContext
    private val db: WorkKnowledgeDatabase by lazy { WorkKnowledgeDatabase.getInstance(appContext) }
    private val gson = Gson()

    // ---------------------------------------------------------------- projects

    fun observeActiveProjects(): Flow<List<ProjectEntity>> = db.projectDao().observeActive()

    fun observeAllProjects(): Flow<List<ProjectEntity>> = db.projectDao().observeAll()

    suspend fun createProject(name: String, description: String = ""): OpResult<Long> =
        io {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return@io OpResult.Failure("Project name cannot be empty.")
            val id = db.projectDao().insert(ProjectEntity.create(trimmed, description.trim()))
            if (id == -1L) {
                OpResult.Failure("A project called \"$trimmed\" already exists.")
            } else {
                OpResult.Success(id)
            }
        }

    suspend fun renameProject(project: ProjectEntity, name: String, description: String): OpResult<Unit> =
        io {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return@io OpResult.Failure("Project name cannot be empty.")
            db.projectDao().update(
                project.copy(
                    name = trimmed,
                    description = description.trim(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            OpResult.Success(Unit)
        }

    suspend fun setProjectArchived(project: ProjectEntity, archived: Boolean): OpResult<Unit> = io {
        db.projectDao().update(
            project.copy(isArchived = archived, updatedAt = System.currentTimeMillis())
        )
        OpResult.Success(Unit)
    }

    suspend fun getProject(id: Long): OpResult<ProjectEntity?> = io {
        OpResult.Success(db.projectDao().byId(id))
    }

    /** Cascades to documents, chunks, summaries and jobs via foreign keys. */
    suspend fun deleteProject(id: Long): OpResult<Unit> = io {
        db.projectDao().delete(id)
        OpResult.Success(Unit)
    }

    suspend fun projectCounts(projectId: Long): Pair<Int, Int> = io {
        db.projectDao().documentCount(projectId) to db.chunkDao().countByProject(projectId)
    }

    // --------------------------------------------------------------- documents

    fun observeDocuments(projectId: Long): Flow<List<DocumentEntity>> =
        db.documentDao().observeByProject(projectId)

    suspend fun getDocument(id: Long): OpResult<DocumentEntity?> = io {
        OpResult.Success(db.documentDao().byId(id))
    }

    suspend fun documentCount(projectId: Long): Int = io { db.projectDao().documentCount(projectId) }

    suspend fun getSummary(documentId: Long): OpResult<DocumentSummaryEntity?> = io {
        OpResult.Success(db.documentSummaryDao().byDocument(documentId))
    }

    suspend fun chunkCount(documentId: Long): Int = io { db.chunkDao().countByDocument(documentId) }

    suspend fun setDocumentStatus(
        id: Long,
        status: DocumentEntity.ProcessingStatus,
        error: String? = null
    ): OpResult<Unit> = io {
        db.documentDao().updateStatus(id, status, error, System.currentTimeMillis())
        OpResult.Success(Unit)
    }

    /**
     * Imports one picked file.
     *
     * Returns [ImportOutcome.Duplicate] when the exact bytes are already
     * indexed, so the UI can tell the user instead of doing the work twice.
     */
    suspend fun importDocument(projectId: Long, uri: Uri): OpResult<ImportOutcome> = io {
        val resolver: ContentResolver = appContext.contentResolver
        val (name, size) = queryNameAndSize(resolver, uri)

        val sha = sha256(resolver, uri) ?: return@io OpResult.Failure(
            "Could not read the file. The app may no longer have permission."
        )

        db.documentDao().bySha256(sha)?.let { existing ->
            return@io OpResult.Success(
                ImportOutcome.Duplicate(existingId = existing.id, existingName = existing.filename)
            )
        }

        val mime = resolver.getType(uri) ?: mimeForName(name)
        val entity = DocumentEntity(
            projectId = projectId,
            filename = name,
            originalUri = uri.toString(),
            mimeType = mime,
            fileSize = size,
            sha256 = sha,
            status = DocumentEntity.ProcessingStatus.QUEUED
        )
        val id = db.documentDao().insert(entity)
        if (id == -1L) {
            OpResult.Failure("Could not register the document.")
        } else {
            db.projectDao().touch(projectId, System.currentTimeMillis())
            OpResult.Success(ImportOutcome.Queued(id, name))
        }
    }

    /** Links a revision to the document it supersedes, keeping the old one. */
    suspend fun markAsRevision(newId: Long, supersedesId: Long): OpResult<Unit> = io {
        db.documentDao().setRevisionOf(newId, supersedesId)
        OpResult.Success(Unit)
    }

    suspend fun getRevisions(documentId: Long): OpResult<List<DocumentEntity>> = io {
        OpResult.Success(db.documentDao().revisionsOf(documentId))
    }

    /**
     * Deletes the document and, by cascade, its chunks, summary and jobs.
     *
     * The full-text index is pruned first and explicitly: `chunks_fts` is a
     * virtual table, so it does not take part in a foreign-key cascade and
     * would otherwise leave searchable entries for a document that is gone.
     */
    suspend fun deleteDocument(id: Long): OpResult<Unit> = io {
        db.searchDao().deleteByDocument(id)
        db.documentDao().delete(id)
        OpResult.Success(Unit)
    }

    suspend fun jobFor(documentId: Long): OpResult<ProcessingJobEntity?> = io {
        OpResult.Success(db.processingJobDao().latest(documentId))
    }

    fun observeJob(documentId: Long): Flow<ProcessingJobEntity?> =
        db.processingJobDao().observeLatest(documentId)

    // ------------------------------------------------------------------ chunks

    suspend fun chunksFor(documentId: Long): OpResult<List<ChunkEntity>> =
        io { OpResult.Success(db.chunkDao().byDocument(documentId)) }

    suspend fun projectChunkCount(projectId: Long): Int = io { db.chunkDao().countByProject(projectId) }

    /** Index-wide totals, for diagnostics. */
    suspend fun totalDocumentCount(): Int = io { db.documentDao().count() }

    suspend fun totalProjectCount(): Int = io { db.projectDao().count() }

    suspend fun totalChunkCount(): Int = io { db.chunkDao().count() }

    suspend fun embeddedVectorCount(): Int = io { db.chunkDao().countWithVectors() }

    suspend fun embeddedVectorSize(): Int = io {
        db.chunkDao().sampleEmbedding()?.let { com.edib.workknowledge.domain.VectorCodec.decode(it)?.size } ?: 0
    }

    // ------------------------------------------------------------------- chat

    suspend fun createSession(
        projectId: Long?,
        title: String,
        scope: ChatSessionEntity.ScopeType,
        documentIds: List<Long>
    ): OpResult<Long> = io {
        val session = ChatSessionEntity(
            projectId = projectId,
            title = title.ifBlank { "New chat" },
            scopeType = scope,
            scopeEntityIdsJson = gson.toJson(documentIds)
        )
        OpResult.Success(db.chatSessionDao().insert(session))
    }

    fun observeSessions(projectId: Long?): Flow<List<ChatSessionEntity>> =
        db.chatSessionDao().observeAll()

    suspend fun addMessage(
        sessionId: Long,
        role: ChatMessageEntity.Role,
        content: String,
        citations: List<ChatMessageEntity.Citation> = emptyList()
    ): OpResult<Long> = io {
        val message = ChatMessageEntity(
            sessionId = sessionId,
            role = role,
            content = content,
            citationsJson = gson.toJson(citations)
        )
        val id = db.chatMessageDao().insert(message)
        db.chatSessionDao().touch(sessionId, System.currentTimeMillis())
        OpResult.Success(id)
    }

    fun observeMessages(sessionId: Long): Flow<List<ChatMessageEntity>> =
        db.chatMessageDao().bySession(sessionId)

    suspend fun recentMessages(sessionId: Long, limit: Int): List<ChatMessageEntity> =
        io { db.chatMessageDao().recent(sessionId, limit) }

    suspend fun deleteSession(id: Long): OpResult<Unit> = io {
        db.chatSessionDao().delete(id)
        OpResult.Success(Unit)
    }

    /**
     * Wipes every derived artefact for a document so it can be re-indexed
     * cleanly: chunks (and their vectors), the full-text index rows, the
     * generated summary, and jobs. The document row and its original file
     * reference are kept.
     */
    suspend fun clearDerivedData(documentId: Long): OpResult<Unit> = io {
        db.searchDao().deleteByDocument(documentId)
        db.chunkDao().deleteByDocument(documentId)
        db.documentSummaryDao().deleteByDocument(documentId)
        db.processingJobDao().deleteByDocument(documentId)
        OpResult.Success(Unit)
    }

    // ----------------------------------------------------------------- search

    suspend fun search(
        query: String,
        limit: Int = 25,
        projectId: Long? = null,
        documentIds: List<Long>? = null
    ): OpResult<List<com.edib.workknowledge.search.SearchHit>> = io {
        val engine = com.edib.workknowledge.search.DocumentSearch(db.searchDao())
        OpResult.Success(engine.search(query, limit, projectId, documentIds))
    }

    suspend fun searchIndexSize(): Int = io { db.searchDao().count() }

    // ----------------------------------------------------------------- helpers

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun queryNameAndSize(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "document"
        var size = 0L
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
        }
        return name to size
    }

    fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "xlsm" -> "application/vnd.ms-excel.sheet.macroEnabled.12"
        "csv" -> "text/csv"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    /**
     * SHA-256 over the file bytes.
     *
     * Streamed in 64 KiB blocks: a 200-page PDF must not be pulled into memory
     * just to be fingerprinted.
     */
    fun sha256(resolver: ContentResolver, uri: Uri): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        } ?: return null
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    sealed interface OpResult<out T> {
        data class Success<T>(val value: T) : OpResult<T>
        data class Failure(val message: String) : OpResult<Nothing>
    }

    sealed interface ImportOutcome {
        data class Queued(val documentId: Long, val filename: String) : ImportOutcome
        data class Duplicate(val existingId: Long, val existingName: String) : ImportOutcome
    }
}
