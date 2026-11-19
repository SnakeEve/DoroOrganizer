package com.doro.organizer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.security.DefaultKeyVault
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The production container builds `files` and `keyVault` on the same `noBackupFilesDir`, because every commit syncs
 * the key-state's directory through `DbFiles.noBackupDir` (architecture.md § Dependency injection, SPEC T14), and
 * its roots, derived without the creating getters, are the framework's directories.
 */
@RunWith(AndroidJUnit4::class)
class ContainerWiringTest {
    @Test
    fun dbFilesAndKeyVault_shareNoBackupFilesDir() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as DoroApplication
        val container = AppContainer(app) // no member is used beyond the two below
        val vault = container.keyVault as DefaultKeyVault
        assertEquals(app.noBackupFilesDir, container.files.noBackupDir)
        assertEquals(container.files.noBackupDir, vault.stateFile.parentFile)
        assertEquals(app.getDatabasePath("x").parentFile, container.files.databasesDir)
        assertEquals(File(app.cacheDir, "share"), container.files.shareDir)
    }
}
