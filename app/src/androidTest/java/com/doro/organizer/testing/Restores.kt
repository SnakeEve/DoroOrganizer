package com.doro.organizer.testing

import android.content.Context
import android.net.Uri
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.maintenance.DbMaintenance
import com.doro.organizer.data.maintenance.MaintenanceOrigin
import com.doro.organizer.data.maintenance.MaintenanceResult
import com.doro.organizer.data.maintenance.MaintenanceStatus
import com.doro.organizer.data.maintenance.RestorePreview
import com.doro.organizer.debug.RecordingDocumentsProvider
import com.doro.organizer.security.SlotCipher
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
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

/**
 * One answer of a restore dialog: given when a status matching [shows] is published. [give] answers through the
 * `DbMaintenance` members the UI calls. An [optional] answer may stay unasked (a fault sweep that ends the restore
 * early at some steps).
 */
class RestoreAnswer(
    private val label: String,
    val shows: (MaintenanceStatus) -> Boolean,
    val optional: Boolean = false,
    val give: suspend DbMaintenance.() -> Unit,
) {
    /** This answer, given only if its question is asked. */
    fun ifAsked() = RestoreAnswer("$label, if asked", shows, optional = true, give)

    override fun toString() = label

    companion object {
        fun backupPassword(password: String?, retry: Boolean = false) =
            RestoreAnswer("backup password (retry=$retry)", { it == MaintenanceStatus.NeedsBackupPassword(retry) }) {
                answerPassword(password?.toCharArray())
            }

        fun databasePassword(password: String?, error: AppError? = null) =
            RestoreAnswer("database password (error=$error)", { it == MaintenanceStatus.NeedsDatabasePassword(error) }) {
                answerPassword(password?.toCharArray())
            }

        /** [cipher] null is "Use password" (or a prompt that ended without a cipher). */
        fun fingerprint(afterKekFailure: Boolean = false, cipher: suspend () -> SlotCipher?) =
            RestoreAnswer("fingerprint (afterKekFailure=$afterKekFailure)", { it == MaintenanceStatus.NeedsBiometricUnlock(afterKekFailure) }) {
                answerBiometric(cipher())
            }

        /** Confirms after [inspect] has seen the preview. */
        fun confirm(inspect: (RestorePreview) -> Unit = {}) =
            RestoreAnswer("confirm", { it is MaintenanceStatus.NeedsConfirmation }) {
                inspect(pendingPreview())
                answerConfirmation(true)
            }

        fun decline() = RestoreAnswer("decline", { it is MaintenanceStatus.NeedsConfirmation }) { answerConfirmation(false) }

        /** The Cancel button of the checking dialog, pressed when [shows] matches. */
        fun cancelWhen(shows: (MaintenanceStatus) -> Boolean) = RestoreAnswer("cancel", shows) { cancelRestore() }
    }
}

/**
 * Runs `restore(source, origin)` and gives [answers] in order, each when its status shows, as the restore dialogs of
 * ui.md § 8.7 do; the status is acknowledged first so that no earlier `Finished` is mistaken for this restore's.
 * Every answer waits until the status has moved on before the next one is matched, so a repeated question is
 * answered again only after it was asked again. Strict both ways: the restore must ask every listed question that is
 * not [RestoreAnswer.optional], in order, and no further one; otherwise this fails (and cancels a restore left
 * waiting). Returns the restore's result.
 */
suspend fun DbMaintenance.restoreAnswering(
    source: Uri,
    origin: MaintenanceOrigin,
    vararg answers: RestoreAnswer,
): AppResult<MaintenanceResult> = coroutineScope {
    acknowledge()
    val result = async { restore(source, origin) }
    for ((index, answer) in answers.withIndex()) {
        val shown = status.first { answer.shows(it) || it is MaintenanceStatus.Finished }
        if (shown is MaintenanceStatus.Finished) {
            if (answers.drop(index).all { it.optional }) break
            throw AssertionError("the restore ended with ${shown.result} before it asked for ${answers.drop(index)}")
        }
        answer.give(this@restoreAnswering)
        status.first { it !== shown }
    }
    val end = status.first { it is MaintenanceStatus.Finished || it.isQuestion() }
    if (end !is MaintenanceStatus.Finished) {
        cancelRestore()
        result.await()
        throw AssertionError("the restore asked $end after the last answer")
    }
    result.await()
}

private fun MaintenanceStatus.isQuestion() = this is MaintenanceStatus.NeedsBackupPassword ||
    this is MaintenanceStatus.NeedsDatabasePassword || this is MaintenanceStatus.NeedsBiometricUnlock ||
    this is MaintenanceStatus.NeedsConfirmation

/** The preview of the confirmation a restore is waiting at. */
fun DbMaintenance.pendingPreview(): RestorePreview = (status.value as MaintenanceStatus.NeedsConfirmation).preview

/**
 * The gate's way into `Corrupt` (DEC-85): E3 closes Room, which checkpoints the WAL into the live file, then page 2 of
 * that file is overwritten. Returns the damaged file.
 */
suspend fun MaintenanceSetup.enterCorrupt(): File {
    val live = liveFile()
    holder.close()
    corruptPage(live, 2)
    return live
}
