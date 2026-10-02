package com.edib.procurement.sharing

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.net.Uri
import com.edib.procurement.documents.DocumentTreeScanner

/**
 * Opens the ORIGINAL document in an installed app.
 *
 * This app is deliberately not an Office editor. It hands the document's own
 * content URI to Android with ACTION_VIEW and lets the system chooser pick a
 * PDF viewer, Microsoft Excel/Word, Google Sheets or anything else installed.
 *
 * Nothing is copied and nothing is modified: the same SAF URI the user granted
 * is what gets opened.
 */
object ExternalDocumentOpener {

    enum class OpenResult { OPENED, NO_APP, NO_FILE, ERROR }

    fun open(
        context: Context,
        documentUri: String,
        extension: String?,
        declaredMime: String?
    ): OpenResult {
        val uri = try {
            Uri.parse(documentUri)
        } catch (e: Exception) {
            return OpenResult.ERROR
        }

        // Fall back to our own extension table when the provider reported
        // something generic, so the chooser shows the right apps.
        val mime = declaredMime
            ?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: DocumentTreeScanner.mimeFor(extension)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Without this the receiving app often cannot read a SAF URI whose
            // grant came from a tree the user picked.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooser = Intent.createChooser(intent, "Open with").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(chooser)
            OpenResult.OPENED
        } catch (_: ActivityNotFoundException) {
            OpenResult.NO_APP
        } catch (_: SecurityException) {
            OpenResult.NO_FILE
        } catch (_: Exception) {
            OpenResult.ERROR
        }
    }

    /**
     * Opens an already-granted content URI.
     *
     * Used for generated files, which live behind the app's FileProvider. No
     * filesystem path is involved, because another app cannot read one.
     */
    fun openUri(context: Context, uri: Uri, extension: String?): OpenResult {
        val mime = DocumentTreeScanner.mimeFor(extension)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(Intent.createChooser(intent, "Open with"))
            OpenResult.OPENED
        } catch (e: ActivityNotFoundException) {
            OpenResult.NO_APP
        } catch (e: Exception) {
            OpenResult.ERROR
        }
    }

    /**
     * Shares a generated file via the standard Android Sharesheet.
     *
     * The URI comes from the app's own FileProvider and carries a temporary read
     * grant, so the receiving app (ChatGPT, a mail client, anything) can read the
     * file without the app exporting it to shared storage.
     */
    /**
     * Share several generated files at once.
     *
     * Uses ACTION_SEND_MULTIPLE so one chooser action delivers the index and all
     * parts together.
     *
     * The intent type is derived from the files rather than fixed to zip. That
     * matters in practice: a receiving app filters on MIME, and labelling a
     * markdown index as application/zip caused ChatGPT to silently drop it while
     * accepting every part. "*&#47;*" is the deliberate choice when a set mixes
     * kinds, because it is the only value every target accepts.
     */
    fun shareFiles(context: Context, contentUris: List<Uri>, title: String = "Share AI Pack")
            : OpenResult {
        if (contentUris.isEmpty()) return OpenResult.NO_FILE
        val send = Intent(
            if (contentUris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
        ).apply {
            type = if (contentUris.size == 1) "*/*" else "*/*"
            if (contentUris.size == 1) putExtra(Intent.EXTRA_STREAM, contentUris.first())
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(contentUris))
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Per-clip type so a markdown index is announced as markdown. Some
        // receivers read ClipData rather than EXTRA_STREAM.
        val clip = ClipData.newUri(
            context.contentResolver, title, contentUris.first()
        )
        for (i in 1 until contentUris.size) {
            clip.addItem(ClipData.Item(contentUris[i]))
        }
        send.clipData = clip
        val chooser = Intent.createChooser(send, title).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(chooser)
            OpenResult.OPENED
        } catch (t: Throwable) {
            OpenResult.NO_APP
        }
    }

    fun shareFile(context: Context, contentUri: Uri, mime: String, title: String): OpenResult {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Some receivers only honour the grant when it is also on the chooser.
        val chooser = Intent.createChooser(send, title).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(chooser)
            OpenResult.OPENED
        } catch (e: ActivityNotFoundException) {
            OpenResult.NO_APP
        } catch (e: Exception) {
            OpenResult.ERROR
        }
    }
}
