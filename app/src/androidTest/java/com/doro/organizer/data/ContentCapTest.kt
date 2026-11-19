package com.doro.organizer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.model.MAX_CONTENT_CHARS
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.assertDbInvariants
import com.doro.organizer.testing.draft
import com.doro.organizer.testing.noteRepository
import com.doro.organizer.testing.ok
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.testing.rows
import com.doro.organizer.testing.saved
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-08, storage part (DEC-24): notes at the 200,000-unit cap with the widest encodings save, list and
 * open on SQLCipher without `SQLiteBlobTooBigException`. Content over the cap is rejected by `save`
 * (`NoteWriteRulesTest.bounds_outOfRangeInput_throwsAndWritesNothing`); the editor's truncation is `ContentCap`'s.
 */
@RunWith(AndroidJUnit4::class)
class ContentCapTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val applicationScope = ApplicationScopeRule()

    private lateinit var holder: DatabaseHolder
    private lateinit var db: AppDatabase
    private lateinit var repository: NoteRepository

    @Before
    fun createDatabase() {
        holder = databases.holder()
        db = holder.openDatabase()
        repository = noteRepository(holder, FakeAppTime(), applicationScope)
    }

    @After
    fun invariantsHold() = assertDbInvariants(db.openHelper.writableDatabase)

    @Test
    fun threeByteCharacters_atTheCap() = roundTrip("€".repeat(MAX_CONTENT_CHARS)) // 600,000 UTF-8 bytes

    @Test
    fun emoji_atTheCap() = roundTrip("😀".repeat(MAX_CONTENT_CHARS / 2)) // surrogate pairs, 4 bytes each

    // İ folds to i + U+0307, so content_fold holds 400,000 units; the I-01 check in @After compares it in Kotlin.
    @Test
    fun dottedCapitalI_whoseFoldDoublesTheLength() = roundTrip("İ".repeat(MAX_CONTENT_CHARS))

    private fun roundTrip(content: String) = runTest(timeout = 60.seconds) {
        assertEquals(MAX_CONTENT_CHARS, content.length)

        val id = repository.save(draft(content = content)).saved().noteId

        assertEquals(listOf(id), db.noteDao().rows().map { it.id })
        assertEquals(content, repository.loadNote(id).ok()?.content)
    }
}
