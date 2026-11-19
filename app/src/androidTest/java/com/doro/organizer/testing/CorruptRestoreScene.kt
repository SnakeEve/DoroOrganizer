package com.doro.organizer.testing

import android.content.Context
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.HolderState
import com.doro.organizer.data.maintenance.MaintenanceOp
import com.doro.organizer.data.maintenance.MaintenanceOutcome
import com.doro.organizer.data.maintenance.MaintenanceResult
import com.doro.organizer.model.KeyMode
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals

/**
 * The scene of a restore from the Corrupt screen (testing.md L-12, L-43, L-51): seeded data, a backup of it ([atBackup],
 * [backup]), other data afterwards, then `Corrupt` through E3 and a damaged page 2.
 */
class CorruptRestoreScene(private val setup: MaintenanceSetup, private val vaults: KeyVaultRule, private val context: Context) {
    lateinit var backup: TestDocument
        private set

    lateinit var atBackup: Map<String, List<List<String?>>>
        private set

    /** Some data, a backup of it, then other data: what a restore must replace. [password] null: a plain backup. */
    suspend fun backupAndChangeData(password: String? = null) {
        setup.holder.openDatabase().seed(SeedGenerator(seed = 31, notes = 25).generate())
        atBackup = setup.dump()
        backup = setup.backupTo(context, "corrupt-restore.db", password)
        setup.holder.openDatabase().openHelper.writableDatabase.execSQL("DELETE FROM notes")
    }

    /**
     * `PASSWORD` mode with [databasePassword], the lock on, a live biometric slot ([withSlot]), a backup under
     * [backupPassword], then Corrupt; returns the damaged live file.
     */
    suspend fun passwordModeInCorrupt(databasePassword: String, backupPassword: String, withSlot: Boolean = true): File {
        setup.start()
        setup.changeKey(KeyMode.PASSWORD, new = databasePassword)
        setup.settings.setBiometricLock(true)
        if (withSlot) assertEquals(AppResult.Ok(Unit), setup.maintenance.createBiometricSlot(setup.armCipher()))
        backupAndChangeData(backupPassword)
        return setup.enterCorrupt()
    }

    /** Restored from Corrupt: the backup's counts, one `MaintenanceStarted`, `Restart`, Room closed, nothing staged. */
    fun assertRestored(result: AppResult<MaintenanceResult>) {
        val counts = listOf("notes", "persons", "projects").map { atBackup.getValue(it).size }
        assertEquals(AppResult.Ok(MaintenanceResult.Restored(counts[0], counts[1], counts[2], false)), result)
        assertEquals(listOf(false), setup.listener.startedFor(MaintenanceOp.Restore))
        assertEquals(MaintenanceOutcome.Restart, setup.listener.finished.last())
        assertEquals(HolderState.Closed, setup.holder.state.value)
        assertEquals(emptyList<String>(), setup.files.restoreDir.list()!!.toList())
        assertEquals(emptyList<String>(), setup.files.stagingDir.list()!!.toList())
    }

    /** The restore ended before its confirmation: no event, the key-state byte-identical, nothing staged left. */
    fun assertNothingCommitted(state: ByteArray) {
        assertArrayEquals(state, vaults.stateFile.readBytes())
        assertEquals(emptyList<Boolean>(), setup.listener.startedFor(MaintenanceOp.Restore))
        assertEquals(emptyList<String>(), setup.files.restoreDir.list()!!.toList())
        assertEquals(emptyList<String>(), setup.files.stagingDir.list()!!.toList())
        assertEquals(1, setup.files.generationsOnDisk().size) // the damaged file is kept (DEC-85)
    }

    override fun toString() = "CorruptRestoreScene"
}
