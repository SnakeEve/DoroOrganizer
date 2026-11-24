package com.doro.organizer.data.maintenance

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.doro.organizer.core.io.syncFile
import com.doro.organizer.core.log.AppLog
import com.doro.organizer.core.result.AppError
import com.doro.organizer.data.isStorageFull
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * The SAF documents of a backup and of a restore (db-lifecycle.md § To a document, § Restore); no URI or name ever
 * reaches a log (DEC-70).
 */
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
     * COPY_INPUT: the document's bytes, through a descriptor where the provider offers one, which [onDescriptor]
     * receives so that `cancelRestore()` can close it and unblock a pending read. A provider that cannot offer one
     * (`FileNotFoundException`) falls back to a plain stream; a null descriptor or stream is a `FileNotFoundException`.
     */
    fun openInput(uri: Uri, onDescriptor: (ParcelFileDescriptor) -> Unit): InputStream {
        val descriptor = try {
            resolver.openFileDescriptor(uri, "r")
        } catch (e: FileNotFoundException) {
            return resolver.openInputStream(uri) ?: throw FileNotFoundException("no input stream")
        } ?: throw FileNotFoundException("no descriptor")
        onDescriptor(descriptor)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
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
                (out as? FileOutputStream)?.fd?.let(::syncFile)
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
