package com.edib.workknowledge.comparison

import android.content.Context
import android.net.Uri
import com.edib.workknowledge.data.DocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Entry point for comparing two indexed workbooks.
 *
 * Reads both files fresh from their stored URIs into [WorkbookSnapshot]s and
 * hands them to [StructuralWorkbookDiff]. It never looks at the text chunks and
 * never involves a model: the diff is computed from cell values.
 */
class DocumentComparisonEngine(private val context: Context) {

    sealed interface OpResult<out T> {
        data class Success<T>(val value: T) : OpResult<T>
        data class Failure(val message: String) : OpResult<Nothing>
    }

    suspend fun compare(
        oldDocument: DocumentEntity,
        newDocument: DocumentEntity
    ): OpResult<WorkbookDiff> = withContext(Dispatchers.IO) {
        val oldUri = runCatching { Uri.parse(oldDocument.originalUri) }.getOrNull()
        val newUri = runCatching { Uri.parse(newDocument.originalUri) }.getOrNull()
        if (oldUri == null || newUri == null) {
            return@withContext OpResult.Failure("One of the documents has no readable file reference.")
        }

        try {
            val old = WorkbookSnapshotReader(context).use {
                it.open(oldUri)
                it.snapshot(oldDocument.filename)
            }
            val new = WorkbookSnapshotReader(context).use {
                it.open(newUri)
                it.snapshot(newDocument.filename)
            }
            OpResult.Success(StructuralWorkbookDiff().diff(old, new))
        } catch (t: Throwable) {
            OpResult.Failure(
                "Could not compare the workbooks: ${t.message ?: t::class.java.simpleName}"
            )
        }
    }
}
