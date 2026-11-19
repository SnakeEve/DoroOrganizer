package com.doro.organizer.data.maintenance

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.data.DbFiles
import com.doro.organizer.testing.SENTINEL
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-39 (DEC-70) for the maintenance types that hold a `Uri` or `File`, which the JVM cannot build: their
 * `toString()` prints neither the URI nor a file or display name.
 */
@RunWith(AndroidJUnit4::class)
class MaintenanceRedactionTest {
    @Test
    fun aSharedBackup_printsNeitherItsUriNorItsName() {
        val shared = MaintenanceResult.BackupShared(Uri.parse("content://doro.test/$SENTINEL"), SENTINEL)

        assertEquals("BackupShared", shared.toString())
        assertEquals(
            "Finished(op=Backup, result=Ok(value=BackupShared), previousContentLost=false)",
            MaintenanceStatus.Finished(MaintenanceOp.Backup, AppResult.Ok(shared)).toString(),
        )
    }

    @Test
    fun dbFiles_printsNoPath() {
        val root = File(SENTINEL)

        assertEquals("DbFiles", DbFiles(File(root, "databases"), File(root, "nobackup"), File(root, "cache")).toString())
    }
}
