package com.doro.organizer.data.maintenance

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.doro.organizer.core.log.AppLog
import com.doro.organizer.core.result.AppError
import com.doro.organizer.data.isStorageFull
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** The SAF document of a backup (db-lifecycle.md § To a document); no URI or name ever reaches a log (DEC-70). */
internal class SafDocuments(private val resolver: ContentResolver) {
    /** `OpenableColumns.SIZE`, or null when the provider does not report it or the query fails. */
    fun size(uri: Uri): Long? = try {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /** Best effort: a failed or unsupported deletion is ignored and logged by class only. */
    fun delete(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (e: Exception) {
            AppLog.d("backup: document delete failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * WRITE_DOCUMENT: copies [source] over the document with mode `"wt"` (`"w"` may keep the tail of a longer old
     * document). [onOpened] runs once the provider returned a stream: from then on the old content is gone.
     * [onProgress] receives the copied fraction. Every write, the flush, the close and the byte count must succeed.
     */
    fun write(uri: Uri, source: File, onOpened: () -> Unit, onProgress: (Float) -> Unit) {
        val stream = resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException("no output stream")
        onOpened()
        stream.use { out ->
            val copied = source.inputStream().use { copyWithProgress(it, out, source.length(), onProgress) }
            out.flush()
            try {
                (out as? FileOutputStream)?.fd?.sync()
            } catch (e: IOException) {
                // Pipe-backed providers cannot sync; the copy itself has succeeded.
            }
            if (copied != source.length()) throw IOException("byte count")
        }
    }
}

/** Context Documents of db-lifecycle.md § Classification table: what a failed SAF write means to the user. */
internal fun documentError(e: Throwable): AppError = when {
    e is CancellationException -> throw e
    e.isStorageFull() -> AppError.StorageFull
    e is IOException || e is SecurityException || e is IllegalArgumentException ||
        e is UnsupportedOperationException -> AppError.FileAccess
    else -> AppError.Unexpected
}
