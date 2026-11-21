package com.doro.organizer.ui.editor

import android.os.Bundle
import android.os.Parcel
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SAVED_STATE_REGISTRY_OWNER_KEY
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.enableSavedStateHandles
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.messages.DefaultAppMessages
import com.doro.organizer.data.DatabaseHolder
import com.doro.organizer.data.EntityRepository
import com.doro.organizer.data.NoteRepository
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.NewEntity
import com.doro.organizer.testing.ApplicationScopeRule
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.FakeSettingsStore
import com.doro.organizer.testing.TestDatabases
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.assertDbInvariants
import com.doro.organizer.testing.entityRepository
import com.doro.organizer.testing.noteRepository
import com.doro.organizer.testing.openDatabase
import com.doro.organizer.ui.common.PickerResult
import com.doro.organizer.ui.navigation.EditorKey
import com.doro.organizer.ui.navigation.NotePrefill
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-36 (DEC-37; ui.md § 6.8) against the real repositories: + then Back, a prefill-only draft and a removed
 * prefill insert nothing, while Save inserts even a blank draft; ON_STOP then Back yields one row; a recreation from
 * the state captured before an ON_STOP insert finished yields one note and one created entity (`draftKey` record); an
 * entity created in a picker for a note that is then deleted is never inserted; an existing note left unchanged, or
 * changed and reverted, writes nothing; the checkbox of a saved note writes at once. Also I-38, the editor's part: the
 * state its entry saves, the `saveable` content (ui.md § 6.3) and the `editorDialog` key included, survives a `Parcel`
 * round trip and recreates equal state. Each editor lives in an [EntryOwner], whose saved state is captured the way
 * Android captures it.
 */
@RunWith(AndroidJUnit4::class)
class EditorViewModelTest {
    @get:Rule
    val databases = TestDatabases()

    @get:Rule
    val applicationScope = ApplicationScopeRule()

    private val time = FakeAppTime()
    private val now = time.nowMillis()
    private val settings = FakeSettingsStore().apply { load() }
    private val messages = DefaultAppMessages()
    private val entries = mutableListOf<EntryOwner>()
    private lateinit var holder: DatabaseHolder
    private lateinit var rows: TestRows
    private lateinit var notes: NoteRepository
    private lateinit var entities: EntityRepository

    @Before
    fun createDatabase() {
        holder = databases.holder()
        rows = TestRows(holder.openDatabase().openHelper.writableDatabase)
        notes = noteRepository(holder, time, applicationScope)
        entities = entityRepository(holder, time, applicationScope)
    }

    @After
    fun clear() = runBlocking {
        onMain { entries.forEach { it.viewModelStore.clear() } }
        settle()
        assertDbInvariants(holder.openDatabase().openHelper.writableDatabase)
    }

    @Test
    fun plusThenBack_insertsNothing() = runBlocking {
        val vm = loaded(EditorKey(null, visit = 1))

        leave(vm)

        assertEquals(0L, count("SELECT COUNT(*) FROM notes"))
    }

    @Test
    fun aPrefillOnlyDraft_andARemovedPrefill_insertNothingOnBack_butSaveInsertsTheBlankRow() = runBlocking {
        val alice = rows.person("Alice", now)
        val prefill = NotePrefill(personId = alice, epochDay = TODAY)
        leave(loaded(EditorKey(null, visit = 1, prefill = prefill)))
        assertEquals(0L, count("SELECT COUNT(*) FROM notes"))

        val removed = loaded(EditorKey(null, visit = 1, prefill = prefill))
        onMain {
            removed.removeLink(EntityKind.Person, removed.uiState.value.people.single())
            removed.setDate(null)
        }
        leave(removed)
        assertEquals(0L, count("SELECT COUNT(*) FROM notes"))

        onMain { removed.save() }
        settle()
        assertEquals(1L, count("SELECT COUNT(*) FROM notes WHERE content = '' AND date IS NULL"))
        assertEquals(0L, count("SELECT COUNT(*) FROM note_persons"))
    }

    @Test
    fun saveOnABlankDraft_insertsARowWithEmptyContent() = runBlocking {
        val vm = loaded(EditorKey(null, visit = 1))

        onMain { vm.save() }
        settle()

        assertEquals(1L, count("SELECT COUNT(*) FROM notes WHERE content = ''"))
        assertTrue(vm.uiState.first { it.closed }.closed)
    }

    @Test
    fun onStopThenBack_onANewNote_yieldsExactlyOneRow() = runBlocking {
        val vm = loaded(EditorKey(null, visit = 1))
        onMain { vm.content.setTextAndPlaceCursorAtEnd("Call Bob") }

        leave(vm) // ON_STOP
        leave(vm) // Back

        assertEquals(1L, count("SELECT COUNT(*) FROM notes WHERE content = 'Call Bob'"))
    }

    @Test
    fun recreatedFromStateCapturedBeforeTheInsert_yieldsOneNoteAndOneCreatedEntity() = runBlocking {
        val entry = newEntry()
        val vm = loaded(EditorKey(null, visit = 1), entry)
        onMain {
            vm.onPickerResult(PickerResult.Links(EntityKind.Person, emptySet(), listOf(NewEntity("k1", "Dora"))))
            vm.setDate(TODAY)
        }
        val captured = onMain { entry.saveState() }.parcelled() // what Android saved right after onStop

        leave(vm) // the ON_STOP insert, finishing after the capture
        val restored = loaded(EditorKey(null, visit = 1), newEntry(captured))
        leave(restored) // Back in the new process

        assertEquals(1L, count("SELECT COUNT(*) FROM notes"))
        assertEquals(1L, count("SELECT COUNT(*) FROM persons WHERE name = 'Dora'"))
        assertEquals(1L, count("SELECT COUNT(*) FROM note_persons"))
    }

    @Test
    fun aPersonCreatedInThePicker_thenTheNoteDeleted_isNeverInserted() = runBlocking {
        val vm = loaded(EditorKey(null, visit = 1))
        onMain {
            vm.onPickerResult(PickerResult.Links(EntityKind.Person, emptySet(), listOf(NewEntity("k1", "Dora"))))
            vm.deleteNote()
        }
        settle()

        leave(vm)

        assertEquals(0L, count("SELECT COUNT(*) FROM persons"))
        assertEquals(0L, count("SELECT COUNT(*) FROM notes"))
    }

    @Test
    fun anExistingNote_leftUnchanged_orChangedAndReverted_writesNothing() = runBlocking {
        val id = rows.note("kept", createdAt = now - 10, updatedAt = now - 10)
        val vm = loaded(EditorKey(id, visit = 1))
        leave(vm)

        onMain {
            vm.content.setTextAndPlaceCursorAtEnd("kept!")
            vm.setDate(TODAY)
            vm.content.setTextAndPlaceCursorAtEnd("kept")
            vm.setDate(null)
        }
        leave(vm)

        assertEquals(1L, count("SELECT COUNT(*) FROM notes WHERE id = ? AND updated_at = ?", id, now - 10))
    }

    @Test
    fun theCheckboxOfASavedNote_writesAtOnce() = runBlocking {
        val id = rows.note("open", createdAt = now - 10, updatedAt = now - 10)
        val vm = loaded(EditorKey(id, visit = 1))
        val changed = CompletableDeferred<Boolean>()

        onMain { vm.setChecked(true) { changed.complete(it) } }

        assertEquals(true, withTimeout(TIMEOUT) { changed.await() })
        assertTrue(vm.uiState.value.checked)
        assertEquals(1L, count("SELECT COUNT(*) FROM notes WHERE id = ? AND checked = 1 AND updated_at = ?", id, now - 10))
    }

    @Test
    fun savedState_survivesAParcelRoundTrip_andRecreatesEqualState() = runBlocking<Unit> {
        val alice = rows.person("Alice", now)
        val entry = newEntry()
        val vm = loaded(EditorKey(null, visit = 1), entry)
        onMain {
            vm.content.setTextAndPlaceCursorAtEnd(DRAFT)
            vm.onPickerResult(PickerResult.Links(EntityKind.Person, setOf(alice), listOf(NewEntity("k1", "Dora"))))
            vm.setDate(TODAY)
            vm.openDialog(EditorDialog.DeleteNote)
        }
        val expected = withTimeout(TIMEOUT) { vm.uiState.first { it.dialog != null && it.people.size == 2 } }

        val restoredEntry = newEntry(onMain { entry.saveState() }.parcelled())
        val restored = loaded(EditorKey(null, visit = 1), restoredEntry)

        val state = withTimeout(TIMEOUT) { restored.uiState.first { it.dialog != null } }
        assertEquals(DRAFT, onMain { restored.content.text.toString() })
        assertEquals(expected.people, state.people)
        assertEquals(expected.date, state.date)
        assertEquals(EditorDialog.DeleteNote, state.dialog)
        val handle = restoredEntry.handle
        assertEquals(entry.handle.get<String>(EditorSavedState.KEY_DRAFT_KEY), handle.get<String>(EditorSavedState.KEY_DRAFT_KEY))
        assertArrayEquals(longArrayOf(alice), handle.get<LongArray>(EditorSavedState.KEY_PERSON_IDS))
        assertEquals(listOf("Dora"), handle.get<ArrayList<String>>(EditorSavedState.KEY_NEW_PERSON_NAMES))
        assertEquals("DeleteNote", handle.get<String>(EditorSavedState.KEY_DIALOG))
        assertEquals(0, handle.get<Int>(EditorSavedState.KEY_EPOCH))
    }

    /** A new entry, or one recreated from the [restored] state of an earlier one; cleared after the test. */
    private suspend fun newEntry(restored: Bundle? = null): EntryOwner = onMain { EntryOwner(restored).also(entries::add) }

    /** An editor of [key] in [entry], built through `createSavedStateHandle()` as the app builds it, once loaded. */
    private suspend fun loaded(key: EditorKey, entry: EntryOwner? = null): EditorViewModel {
        val owner = entry ?: newEntry()
        val factory = viewModelFactory {
            initializer {
                val handle = createSavedStateHandle().also { owner.handle = it }
                EditorViewModel(
                    key, handle, notes, entities, settings, messages, time, applicationScope.scope, AppDispatchers.production(),
                )
            }
        }
        val vm = onMain { ViewModelProvider.create(owner.viewModelStore, factory, owner.extras())[EditorViewModel::class] }
        withTimeout(TIMEOUT) { vm.uiState.first { !it.loading } }
        return vm
    }

    /** Back, leaving by navigation or `ON_STOP`: the save on leave, run to its end. */
    private suspend fun leave(vm: EditorViewModel) {
        onMain { vm.saveOnLeave() }
        settle()
    }

    /** Waits for every detached write and save in the application scope. */
    private suspend fun settle() = withTimeout(TIMEOUT) {
        var running: List<Job>
        while (applicationScope.scope.coroutineContext.job.children.toList().also { running = it }.isNotEmpty()) {
            running.forEach { it.join() }
        }
    }

    private fun count(sql: String, vararg args: Any): Long =
        holder.openDatabase().openHelper.readableDatabase.query(sql, args).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    /** This state as a process death keeps it: written to a `Parcel` and read back. */
    private fun Bundle.parcelled(): Bundle {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(this)
            parcel.setDataPosition(0)
            return checkNotNull(parcel.readBundle(javaClass.classLoader))
        } finally {
            parcel.recycle()
        }
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    /**
     * A stand-in for the editor's NavEntry: a `SavedStateRegistryOwner` and `ViewModelStoreOwner` whose registry feeds
     * `createSavedStateHandle()`, so [saveState] holds what Android saves for the entry, the providers registered with
     * `SavedStateHandle.saveable` included. [handle] is the editor's handle. Main thread only.
     */
    private class EntryOwner(restored: Bundle?) : SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        lateinit var handle: SavedStateHandle

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        override val viewModelStore = ViewModelStore()

        init {
            // The order of ComponentActivity: attach, enable the handles, restore, then CREATED.
            controller.performAttach()
            enableSavedStateHandles()
            controller.performRestore(restored)
            registry.currentState = Lifecycle.State.CREATED
        }

        fun saveState(): Bundle = Bundle().also(controller::performSave)

        fun extras(): CreationExtras = MutableCreationExtras().also {
            it[SAVED_STATE_REGISTRY_OWNER_KEY] = this
            it[VIEW_MODEL_STORE_OWNER_KEY] = this
        }
    }

    private companion object {
        val TIMEOUT = 10.seconds

        /** A draft with an accented letter and a surrogate pair, so the round trip keeps more than ASCII. */
        const val DRAFT = "Call Bob é 😀"

        /** 2026-11-17, the day `FakeAppTime` starts on. */
        const val TODAY = 20_774L
    }
}
