package com.doro.organizer.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.net.toUri
import java.io.File
import java.io.FileNotFoundException

/**
 * The document provider of the backup tests (testing.md § 5, L-08), debug builds only. It runs in the app process, so
 * a test reads [modes] directly. Files live in `cacheDir/recording-provider/<last path segment>`. Mode `"w"` opens
 * without truncating, as some real providers do, so only `"wt"` replaces a longer old document. It answers the
 * queries a backup and a restore make (`OpenableColumns.SIZE`) and `DocumentsContract.deleteDocument`, which
 * arrives as `call`.
 * A real `DocumentsProvider` must be exported with `MANAGE_DOCUMENTS`, which a test-only component must not be.
 */
class RecordingDocumentsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val file = fileOf(uri)
        return MatrixCursor(arrayOf(OpenableColumns.SIZE)).apply {
            if (file.exists()) addRow(arrayOf(if (sizeUnknown) null else reportedSize ?: file.length()))
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        synchronized(modes) { modes += mode }
        if (mode == "r") {
            failRead?.let { throw it }
        } else {
            failOpen?.let { throw it }
            if (openReturnsNull) return null
        }
        // A descriptor that cannot be written: the stream opens, then the first write fails.
        if (failWrites && mode != "r") return ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY)
        val flags = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE
            "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            else -> throw FileNotFoundException("unsupported mode")
        }
        return ParcelFileDescriptor.open(fileOf(uri), flags)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != DELETE_DOCUMENT) return super.call(method, arg, extras)
        @Suppress("DEPRECATION") // getParcelable(String, Class) needs API 33
        val uri = checkNotNull(extras?.getParcelable<Uri>(EXTRA_URI)) { "no document" }
        if (!fileOf(uri).delete()) throw FileNotFoundException("no document")
        return null
    }

    override fun getType(uri: Uri) = "application/octet-stream"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    private fun fileOf(uri: Uri): File = File(directory(checkNotNull(context)), checkNotNull(uri.lastPathSegment))

    companion object {
        /** `DocumentsContract.METHOD_DELETE_DOCUMENT` and `EXTRA_URI`, which the SDK hides. */
        private const val DELETE_DOCUMENT = "android:deleteDocument"
        private const val EXTRA_URI = "uri"

        /** Every mode `openFile` was called with, in order. */
        val modes = mutableListOf<String>()

        /** Makes every later write fail after its stream was opened. */
        @Volatile
        var failWrites = false

        /** Thrown by every later open for writing, before any stream exists (context Documents, testing.md L-13). */
        @Volatile
        var failOpen: Exception? = null

        /** Thrown by every later open for reading (a restore's `COPY_INPUT`, context Documents of testing.md L-13). */
        @Volatile
        var failRead: Exception? = null

        /** Answered as `OpenableColumns.SIZE` instead of the file's length: a provider that misreports it (L-11). */
        @Volatile
        var reportedSize: Long? = null

        /** Answers `OpenableColumns.SIZE` with null: a provider that does not know the size. */
        @Volatile
        var sizeUnknown = false

        /** Makes every later open for writing return no descriptor, so `openOutputStream` returns null. */
        @Volatile
        var openReturnsNull = false

        fun directory(context: Context): File = File(context.cacheDir, "recording-provider").apply { mkdirs() }

        /** The document named [name] of this provider. */
        fun uri(context: Context, name: String): Uri =
            "content://${context.packageName}.testdocs/document/$name".toUri()
    }
}
