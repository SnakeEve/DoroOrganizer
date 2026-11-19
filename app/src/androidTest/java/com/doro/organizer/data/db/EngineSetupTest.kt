package com.doro.organizer.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.SeedGenerator
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.assertDbInvariants
import com.doro.organizer.testing.longOf
import com.doro.organizer.testing.stringsOf
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The "verify at setup" items of data-layer.md § Size and performance that the read model relies on, checked on the
 * shipped SQLCipher build. The JSON functions of § Rows are proven by NoteRowTest's round trip.
 */
@RunWith(AndroidJUnit4::class)
class EngineSetupTest {
    @get:Rule
    val databases = TestDatabases()

    private lateinit var db: AppDatabase

    @Before
    fun createDatabase() {
        db = databases.create()
    }

    @After
    fun invariantsHold() = assertDbInvariants(db.openHelper.writableDatabase)

    /** The ORDER BY sorts in a temporary B-tree, which must never spill into an unencrypted file (security.md § 3.4). */
    @Test
    fun temporaryData_staysInMemory() {
        val sql = db.openHelper.writableDatabase
        val options = sql.stringsOf("PRAGMA compile_options")
        val compiled = options.firstNotNullOfOrNull { TEMP_STORE.matchEntire(it.orEmpty()) }?.groupValues?.get(1)
        val pragma = sql.longOf("PRAGMA temp_store")

        // SQLite's temp_store table: 3 is always memory, 2 is memory unless the pragma asks for a file (1), and the
        // default 1 is memory only when the pragma asks for it (2). 0 is always a file.
        val inMemory = when (compiled?.toInt() ?: 1) {
            3 -> true
            2 -> pragma != 1L
            1 -> pragma == 2L
            else -> false
        }

        assertTrue("TEMP_STORE=$compiled, temp_store=$pragma", inMemory)
    }

    /** DEC-24: a note at the cap in 3-byte UTF-8 (600 KB) fits the cursor window and loads unchanged. */
    @Test
    fun contentAtTheCap_loadsWhole() = runTest(timeout = 10.seconds) {
        val content = "€".repeat(SeedGenerator.MAX_CONTENT_CHARS)
        val id = TestRows(db.openHelper.writableDatabase).note(content, createdAt = FakeAppTime().nowMillis())

        assertEquals(content, db.noteDao().noteDetail(id)?.content)
    }

    private companion object {
        val TEMP_STORE = Regex("""TEMP_STORE=(\d)""")
    }
}
