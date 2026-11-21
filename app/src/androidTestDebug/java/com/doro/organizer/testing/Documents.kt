package com.doro.organizer.testing

import android.content.Context
import android.net.Uri
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.maintenance.MaintenanceResult
import com.doro.organizer.debug.RecordingDocumentsProvider
import java.io.File
import org.junit.Assert.assertEquals

/** A document of the debug `RecordingDocumentsProvider`, as the SAF pickers return it (testing.md § 5). */
class TestDocument(val file: File, val uri: Uri) {
    override fun toString() = "TestDocument(${file.name})"

    companion object {
        fun named(context: Context, name: String) = TestDocument(
            RecordingDocumentsProvider.directory(context).resolve(name),
            RecordingDocumentsProvider.uri(context, name),
        )

        /** What the `CreateDocument` picker returns for a new document: an empty file. */
        fun empty(context: Context, name: String) = named(context, name).apply { file.writeBytes(ByteArray(0)) }
    }
}

/** Clears every switch of the recording provider and its files; call before and after each test that uses it. */
fun resetRecordingProvider(context: Context) {
    RecordingDocumentsProvider.directory(context).deleteRecursively()
    synchronized(RecordingDocumentsProvider.modes) { RecordingDocumentsProvider.modes.clear() }
    RecordingDocumentsProvider.failWrites = false
    RecordingDocumentsProvider.failOpen = null
    RecordingDocumentsProvider.openReturnsNull = false
    RecordingDocumentsProvider.failRead = null
    RecordingDocumentsProvider.reportedSize = null
    RecordingDocumentsProvider.sizeUnknown = false
}

/** A verified backup of the live data at the document [name]; [password] null makes a plaintext backup. */
suspend fun MaintenanceSetup.backupTo(context: Context, name: String, password: String? = null): TestDocument {
    val document = TestDocument.empty(context, name)
    assertEquals(AppResult.Ok(MaintenanceResult.BackupSaved), maintenance.backupToDocument(document.uri, password?.toCharArray()))
    return document
}
