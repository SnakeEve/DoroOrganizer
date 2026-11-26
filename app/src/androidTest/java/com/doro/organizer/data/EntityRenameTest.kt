package com.doro.organizer.data

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.core.result.InputRule
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.EntityRef
import com.doro.organizer.model.MAX_NAME_CHARS
import com.doro.organizer.model.TextWhitespace
import com.doro.organizer.testing.ALL_NOTES
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.SENTINEL
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestKey
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.assertDbInvariants
import com.doro.organizer.testing.canonicalDump
import com.doro.organizer.testing.entityRepository
import com.doro.organizer.testing.longOf
import com.doro.organizer.testing.noteRepository
import com.doro.organizer.testing.ok
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.ownLogThrough
import com.doro.organizer.testing.runtimeWhitespaceDifferences
import com.doro.organizer.testing.shellLines
import com.doro.organizer.testing.stringsOf
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-65: `EntityRepository.rename` on SQLCipher (DEC-113, DEC-21, DEC-32; data-layer.md § Rename, INV-9,
 * INV-10). People P "Alice" and Q "Bob", project X "Apollo", notes n1 {P}, n2 {P, X}, n3 {Q}, all written at T0; every
 * rename runs at T1, one minute later, so a write of `updated_at` shows. The database is keyed with a raw key, as the
 * app's encrypted mode is; (k) repeats (a) in plain mode.
 */
@RunWith(AndroidJUnit4::class)
class EntityRenameTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val applicationScope = ApplicationScopeRule()

    private val time = FakeAppTime()
    private val t0 = time.nowMillis()
    private var db: AppDatabase? = null
    private lateinit var entities: EntityRepository
    private lateinit var notes: NoteRepository
    private val sql: SupportSQLiteDatabase get() = checkNotNull(db).openHelper.writableDatabase

    private var p = 0L
    private var q = 0L
    private var x = 0L
    private val n = LongArray(3)

    @After
    fun invariantsHold() {
        if (db != null) assertDbInvariants(sql)
    }

    @Test
    fun a_aPersonRename_storesTheTrimmedName_andItsOwnUpdatedAt_only() = runTest(timeout = 10.seconds) {
        plant()
        assertRenamesAliceAlone()
    }

    @Test
    fun b_aProjectRename_mirrorsIt() = runTest(timeout = 10.seconds) {
        plant()
        val before = canonicalDump(sql)

        assertEquals(true, entities.rename(EntityKind.Project, x, "\tArtemis \u3000").ok())

        assertEquals(listOf("Artemis", "${time.nowMillis()}", "$t0"), row("projects", x, "name", "updated_at", "created_at"))
        assertNull(sql.stringsOf("SELECT description FROM projects WHERE id = $x").single())
        assertNull(sql.stringsOf("SELECT status FROM projects WHERE id = $x").single())
        assertEquals(before.without("projects"), canonicalDump(sql).without("projects"))
    }

    @Test
    fun c_aBlankName_isInvalidInput_andChangesNothing() = runTest(timeout = 10.seconds) {
        plant()
        val before = canonicalDump(sql)

        listOf("", " ", "\u2003\u00A0").forEach { blank ->
            assertEquals(AppResult.Err(AppError.InvalidInput(InputRule.BlankName)), entities.rename(EntityKind.Person, p, blank))
            assertEquals(AppResult.Err(AppError.InvalidInput(InputRule.BlankName)), entities.rename(EntityKind.Project, x, blank))
        }

        assertEquals(before, canonicalDump(sql))
    }

    @Test
    fun d_200UnitsAreStored_201FailWithAConstantMessage_andWriteNothing() = runTest(timeout = 10.seconds) {
        plant()
        val longest = "n".repeat(MAX_NAME_CHARS)

        assertEquals(true, entities.rename(EntityKind.Person, p, " $longest ").ok())
        assertEquals(longest, name("persons", p))

        val before = canonicalDump(sql)
        val failure = renameFailure(EntityKind.Person, p, sentinelName(MAX_NAME_CHARS + 1))
        assertEquals("name longer than 200 chars", failure.message)
        assertFalse(failure.message.orEmpty().contains(SENTINEL))
        assertEquals(before, canonicalDump(sql))
    }

    @Test
    fun e_theSameNameAfterTheTrim_writesNothing() = runTest(timeout = 10.seconds) {
        plant()
        val before = canonicalDump(sql)

        assertEquals(false, entities.rename(EntityKind.Person, p, "Alice ").ok())

        assertEquals(before, canonicalDump(sql))
        assertEquals("$t0", row("persons", p, "updated_at").single())
    }

    @Test
    fun f_aChangeOfCaseAlone_isWritten() = runTest(timeout = 10.seconds) {
        plant()

        assertEquals(true, entities.rename(EntityKind.Person, p, "alice").ok())

        assertEquals(listOf("alice", "${time.nowMillis()}"), row("persons", p, "name", "updated_at"))
    }

    @Test
    fun g_aDeletedEntity_returnsFalse_andNothingIsWritten() = runTest(timeout = 10.seconds) {
        plant()
        entities.deletePerson(p).ok()
        entities.deleteProject(x).ok()
        val before = canonicalDump(sql)

        assertEquals(false, entities.rename(EntityKind.Person, p, "Alicia").ok())
        assertEquals(false, entities.rename(EntityKind.Project, x, "Artemis").ok())

        assertEquals(before, canonicalDump(sql))
    }

    @Test
    fun h_twoEntitiesMayHoldTheSameName_inAnyCase() = runTest(timeout = 10.seconds) {
        plant()

        assertEquals(true, entities.rename(EntityKind.Person, q, "Alice").ok())
        assertEquals(listOf("Alice", "Alice"), sql.stringsOf("SELECT name FROM persons ORDER BY id"))
        assertEquals(true, entities.rename(EntityKind.Person, q, "ALICE").ok())
        assertEquals(listOf("Alice", "ALICE"), sql.stringsOf("SELECT name FROM persons ORDER BY id"))
    }

    /**
     * The name rule of the app ([TextWhitespace], DEC-24) on this runtime, also where its `Char.isWhitespace()`
     * disagrees: ART's `trim()` strips U+180E, so only this case catches a `trim()` regression (SPEC §B B2, V38). The
     * stored name is asserted, not the result: two whitespace code points in a row store the same "y".
     */
    @Test
    fun i_namesAreTrimmedByTheAppsRule_whereTheRuntimeDiffers() = runTest(timeout = 10.seconds) {
        plant()
        runtimeWhitespaceDifferences().map(Int::toChar).forEach { c ->
            val label = "U+%04X".format(c.code)
            val whitespace = TextWhitespace.isWhitespace(c)

            entities.rename(EntityKind.Person, p, " ${c}y$c\t")
            assertEquals(label, if (whitespace) "y" else "${c}y$c", name("persons", p))

            val alone = entities.rename(EntityKind.Person, p, "$c")
            if (whitespace) {
                assertEquals(label, AppResult.Err(AppError.InvalidInput(InputRule.BlankName)), alone)
                assertEquals(label, "y", name("persons", p))
            } else {
                assertEquals(label, "$c", name("persons", p))
            }
        }
    }

    @Test
    fun j_everyReaderFollowsTheNewName_andTheListKeepsItsRowsAndOrder() = runTest(timeout = 10.seconds) {
        plant()
        val rowsBefore = notes.observeRows(ALL_NOTES).first()

        entities.rename(EntityKind.Person, p, "  Alicia\u2003").ok()

        val rowsAfter = notes.observeRows(ALL_NOTES).first()
        assertEquals(rowsBefore.map { it.id }, rowsAfter.map { it.id })
        assertEquals(listOf(EntityRef(p, "Alicia")), rowsAfter.single { it.id == n[0] }.persons)
        assertEquals(listOf(EntityRef(p, "Alicia")), rowsAfter.single { it.id == n[1] }.persons)
        assertEquals(listOf(EntityRef(q, "Bob")), rowsAfter.single { it.id == n[2] }.persons)
        assertEquals(EntityRef(p, "Alicia"), entities.observe(EntityKind.Person, p).first())
        assertEquals(listOf("Alicia", "Bob"), options())

        entities.rename(EntityKind.Person, p, "Zoe").ok()
        assertEquals("sorted into its new place by Collator (DEC-32)", listOf("Bob", "Zoe"), options())
    }

    @Test
    fun k_aPersonRename_inPlainMode() = runTest(timeout = 10.seconds) {
        plant(TestKey.Plain)
        assertRenamesAliceAlone()
    }

    /**
     * DEC-70, V1: the technique of I-40, in one method (one process); test code never logs the sentinel. A constant end
     * marker, logged after the renames, is awaited in the log, so every line the renames wrote is readable when the log
     * is searched.
     */
    @Test
    fun l_noNameReachesTheLog() = runTest(timeout = 30.seconds) {
        plant()
        shellLines("logcat -c")

        entities.rename(EntityKind.Person, p, "Alice $SENTINEL").ok()
        renameFailure(EntityKind.Person, p, sentinelName(MAX_NAME_CHARS + 1))
        Log.i(LOG_TAG, END_MARKER)

        val log = ownLogThrough(END_MARKER)
        assertTrue("the end marker did not reach this process's log (${log.size} lines read)", log.any { END_MARKER in it })
        assertEquals("Alice $SENTINEL", name("persons", p))
        assertFalse("the sentinel reached this process's log", log.any { SENTINEL in it })
    }

    /** (a): P renamed with whitespace around; every other row stays byte-equal. */
    private suspend fun assertRenamesAliceAlone() {
        val before = canonicalDump(sql)

        assertEquals(true, entities.rename(EntityKind.Person, p, "  Alicia\u2003").ok())

        val t1 = time.nowMillis()
        assertEquals(listOf("Alicia", "$t1", "$t0"), row("persons", p, "name", "updated_at", "created_at"))
        assertEquals(listOf<String?>(null, null), row("persons", p, "role", "notes"))
        assertEquals("notes, links, Q and the projects", before.withoutRow("persons", p), canonicalDump(sql).withoutRow("persons", p))
        assertEquals(1L, sql.longOf("SELECT COUNT(*) FROM persons WHERE id = $p"))
    }

    private fun plant(key: TestKey = RAW_KEY) {
        val holder = databases.holder(key)
        val room = holder.openDatabase()
        db = room
        entities = entityRepository(holder, time, applicationScope)
        notes = noteRepository(holder, time, applicationScope)
        val rows = TestRows(sql)
        p = rows.person("Alice", t0)
        q = rows.person("Bob", t0)
        x = rows.project("Apollo", t0)
        for (i in n.indices) n[i] = rows.note("n${i + 1}", createdAt = t0)
        rows.linkPerson(n[0], p)
        rows.linkPerson(n[1], p)
        rows.linkProject(n[1], x)
        rows.linkPerson(n[2], q)
        time.advanceBy(Duration.ofMinutes(1))
    }

    /** The failure of a rename to a name over `MAX_NAME_CHARS` units, a programming error (DEC-113 (3)). */
    private suspend fun renameFailure(kind: EntityKind, id: Long, name: String): IllegalArgumentException {
        val failure = runCatching { entities.rename(kind, id, name) }.exceptionOrNull()
        return failure as? IllegalArgumentException
            ?: throw AssertionError("expected IllegalArgumentException, was ${failure?.javaClass}")
    }

    private suspend fun options(): List<String> =
        entities.observeOptions(EntityKind.Person, emptyList(), null).first().map { it.name }

    private fun name(table: String, id: Long): String? = row(table, id, "name").single()

    private fun row(table: String, id: Long, vararg columns: String): List<String?> =
        sql.query("SELECT ${columns.joinToString()} FROM $table WHERE id = ?", arrayOf<Any>(id)).use { cursor ->
            assertTrue("$table $id exists", cursor.moveToFirst())
            List(cursor.columnCount) { cursor.getString(it) }
        }

    private fun Map<String, List<List<String?>>>.without(table: String) = filterKeys { it != table }

    /** The dump with the row of [id] left out of [table] (the first column of every entity table is its id). */
    private fun Map<String, List<List<String?>>>.withoutRow(table: String, id: Long) =
        mapValues { (name, rows) -> if (name == table) rows.filter { it.first() != "$id" } else rows }

    /** [units] UTF-16 units built from [SENTINEL]. */
    private fun sentinelName(units: Int) = SENTINEL.repeat(units / SENTINEL.length + 1).take(units)

    private companion object {
        val RAW_KEY = TestKey.Raw(ByteArray(32) { (it * 13 + 5).toByte() })
        const val LOG_TAG = "EntityRenameTest"
        const val END_MARKER = "EntityRenameTest: end of (l)"
    }
}
