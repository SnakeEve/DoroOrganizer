package com.doro.organizer.data.maintenance

import android.database.Cursor
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.model.backupFileName
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.KeyVaultRule
import com.doro.organizer.testing.MaintenanceSetup
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.maintenanceFiles
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Setup probe (SPEC T22) for docs/db-lifecycle.md § For sharing, the item on whether a receiving app sees the share's
 * `displayName` (recorded there as Verified at setup): the Uri that the production share path builds
 * (`DbMaintenance.backupForShare`, `ShareFiles.publish`, the four-argument `FileProvider.getUriForFile` of
 * androidx.core 1.19.1) answers an `OpenableColumns.DISPLAY_NAME` query with the dated backup name, not with the random
 * on-disk name. A receiving app reads the name exactly so: `ContentResolver.query` on the shared Uri, with the default
 * projection (null) or with `DISPLAY_NAME` and `SIZE`. This test queries from the app's own process, so it skips the
 * Uri grant a receiver needs; the provider's answer is the same.
 *
 * Failure messages name what the query returned by its kind (the display name, the on-disk name, null, another value
 * and its length), never the names themselves (DEC-70: backup names and Uris stay out of messages).
 */
@RunWith(AndroidJUnit4::class)
class SharedBackupDisplayNameTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val vaults = KeyVaultRule()

    @get:Rule
    val scope = ApplicationScopeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files by lazy { maintenanceFiles(databases, vaults, context.cacheDir) }
    private val setup by lazy { MaintenanceSetup(databases, vaults, scope.scope, files = files) }

    @Before
    fun cleanShareDirectory() {
        files.shareDir.deleteRecursively()
    }

    @After
    fun removeShareFiles() = cleanShareDirectory()

    @Test
    fun aReceiverQueryWithTheDefaultProjection_seesTheDatedDisplayName() = runTest(timeout = 120.seconds) {
        val shared = shareABackup()

        val observed = context.contentResolver.query(shared.uri, null, null, null, null).use { readDisplayName(it) }

        assertSeesTheDisplayName(shared, observed)
    }

    @Test
    fun aReceiverQueryForDisplayNameAndSize_seesTheDatedDisplayName() = runTest(timeout = 120.seconds) {
        val shared = shareABackup()
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)

        val observed =
            context.contentResolver.query(shared.uri, projection, null, null, null).use { readDisplayName(it) }

        assertSeesTheDisplayName(shared, observed)
    }

    /** A plain backup shared through the production entry point; the expected name differs from the on-disk name. */
    private suspend fun shareABackup(): MaintenanceResult.BackupShared {
        setup.start()
        val result = setup.maintenance.backupForShare(null)
        assertTrue("backupForShare did not succeed: ${result.javaClass.simpleName}", result is AppResult.Ok)
        val shared = (result as AppResult.Ok).value as MaintenanceResult.BackupShared
        // Booleans with constant messages: an equality assertion would print the backup names (DEC-70).
        assertTrue("displayName is not the dated backup name", shared.displayName == backupFileName(setup.time.today()))
        // Without this the test could not tell the four-argument getUriForFile from the three-argument one.
        assertTrue("displayName equals the random on-disk name", onDiskName() != shared.displayName)
        return shared
    }

    /** The name of the single file in the share directory; a failure states the file count, never a name. */
    private fun onDiskName(): String {
        val names = files.shareDir.listFiles()?.map { it.name }
        assertTrue("share directory unreadable or not one file; count=${names?.size}", names?.size == 1)
        return names!!.single()
    }

    /** The single row's DISPLAY_NAME, or an [Observed] that says why there is none. */
    private fun readDisplayName(cursor: Cursor?): Observed {
        if (cursor == null) return Observed.NoCursor
        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column < 0) return Observed.NoColumn(cursor.columnNames.toList())
        if (cursor.count != 1 || !cursor.moveToFirst()) return Observed.Rows(cursor.count)
        return Observed.Value(cursor.getString(column))
    }

    private fun assertSeesTheDisplayName(shared: MaintenanceResult.BackupShared, observed: Observed) {
        assertTrue(
            "DISPLAY_NAME of the shared Uri is ${observed.describe(shared.displayName, onDiskName())}, " +
                "expected the display name",
            observed is Observed.Value && observed.name == shared.displayName,
        )
    }

    private sealed interface Observed {
        /** Constant text plus counts and provider column names; never a file name (DEC-70). */
        fun describe(displayName: String, onDiskName: String): String

        data object NoCursor : Observed {
            override fun describe(displayName: String, onDiskName: String) = "no cursor (query returned null)"
        }

        class NoColumn(private val columns: List<String>) : Observed {
            override fun describe(displayName: String, onDiskName: String) = "no DISPLAY_NAME column; columns=$columns"
        }

        class Rows(private val count: Int) : Observed {
            override fun describe(displayName: String, onDiskName: String) = "not exactly one row; count=$count"
        }

        class Value(val name: String?) : Observed {
            override fun describe(displayName: String, onDiskName: String) = when (name) {
                null -> "null"
                displayName -> "the display name"
                onDiskName -> "the random on-disk file name"
                else -> "another value of length ${name.length}"
            }
        }
    }
}
