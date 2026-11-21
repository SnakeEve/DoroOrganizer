package com.doro.organizer.ui.editor

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.SavedStateHandle
import com.doro.organizer.R
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.messages.DefaultAppMessages
import com.doro.organizer.core.messages.UiText
import com.doro.organizer.core.result.AppError
import com.doro.organizer.model.DeleteImpact
import com.doro.organizer.model.DraftInsert
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.MAX_CONTENT_CHARS
import com.doro.organizer.model.NewEntity
import com.doro.organizer.settings.SettingsStore
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.FakeSettingsStore
import com.doro.organizer.ui.common.PickerResult
import com.doro.organizer.ui.navigation.EditorKey
import com.doro.organizer.ui.navigation.NotePrefill
import com.doro.organizer.ui.navigation.PickerPurpose
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `EditorViewModel` over in-memory repositories (ui.md § 6.6–§ 6.12; DEC-33, DEC-37): which leaves save, the single
 * insert of a note and of its created entities across saves and across a process death (`draftKey` record), the
 * Save action, the truncation on load with its notice (testing.md I-08, JVM part), a vanished note, an older data
 * epoch, the deletes, the checkbox of a saved note, the chip names and the picker results. The same rules over the
 * real repositories are `EditorViewModelTest` (I-36).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelRulesTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val entities = EditorEntities()
    private val notes = EditorNotes(entities)
    private val settings = FakeSettingsStore().apply { load() }
    private val messages = DefaultAppMessages()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aNewNoteLeftUntouched_insertsNothing() = runTest(dispatcher) {
        val vm = editor(EditorKey(null, visit = 1, prefill = NotePrefill(personId = 1)))
        entities.put(EntityKind.Person, 1, "Alice")

        vm.saveOnLeave()

        assertEquals(0, notes.saves.size)
    }

    @Test
    fun typedContent_isInsertedOnce_andAnUnchangedSecondLeaveWritesNothing() = runTest(dispatcher) {
        val vm = editor(EditorKey(null, visit = 1))

        vm.content.setTextAndPlaceCursorAtEnd("Call Bob")
        vm.saveOnLeave()
        vm.saveOnLeave()

        assertEquals(1, notes.saves.size)
        val noteId = notes.notes.keys.single()
        assertEquals("Call Bob", notes.notes.getValue(noteId).content)
        assertEquals(noteId, settings.draftInsert(notes.saves.single().draftKey)?.noteId)
    }

    @Test
    fun aCreatedEntity_isInsertedWithTheNote_andNeverAgain() = runTest(dispatcher) {
        val vm = editor(EditorKey(null, visit = 1))
        collect(vm)
        vm.onPickerResult(PickerResult.Links(EntityKind.Person, emptySet(), listOf(NewEntity("k1", "Dora"))))

        vm.saveOnLeave()
        vm.content.setTextAndPlaceCursorAtEnd("more")
        vm.saveOnLeave()

        val (first, second) = notes.saves
        assertEquals(listOf("Dora"), first.newPersons.map { it.name })
        assertEquals(emptyList<NewEntity>(), second.newPersons)
        assertNull(first.noteId)
        assertEquals(setOf(500L), second.personIds)
        assertEquals(listOf(LinkChip(500, null, "Dora")), vm.uiState.value.people)
    }

    @Test
    fun loading_endsOnce_andAPauseLongerThanTheSharingTimeout_neverBringsItBack() = runTest(dispatcher) {
        entities.put(EntityKind.Person, 1, "Alice")
        notes.notes[7] = StoredNote("x", persons = setOf(1))
        val vm = editor(EditorKey(7, visit = 1))
        val seen = mutableListOf<Boolean>()

        // Covered by a picker or in the background: nothing collects for longer than WhileSubscribed's 5 s.
        backgroundScope.launch { vm.uiState.collect { seen += it.loading } }.cancel()
        advanceTimeBy(6_000)
        backgroundScope.launch { vm.uiState.collect { seen += it.loading } }

        assertEquals("loading after it ended", emptyList<Boolean>(), seen.dropWhile { it }.filter { it })
        assertFalse(vm.uiState.value.loading)
        assertEquals(listOf(LinkChip(1, null, "Alice")), vm.uiState.value.people)
    }

    @Test
    fun chips_takeTheirNamesFromTheLoadAndTheProbes_inCollatorOrder() = runTest(dispatcher) {
        entities.put(EntityKind.Person, 1, "bob")
        entities.put(EntityKind.Person, 2, "Anna")
        entities.put(EntityKind.Person, 3, "Émile")
        notes.notes[7] = StoredNote("x", persons = setOf(1))
        val vm = editor(EditorKey(7, visit = 1))
        collect(vm)

        vm.onPickerResult(PickerResult.Links(EntityKind.Person, setOf(1, 2, 3, 4), emptyList()))

        assertEquals(listOf("Anna", "bob", "Émile"), vm.uiState.value.people.map { it.name })
        vm.saveOnLeave()
        assertEquals("a vanished id leaves the draft", setOf(1L, 2L, 3L), notes.saves.single().personIds)
    }

    @Test
    fun aLinksResult_replacesTheIdsThePickerOpenedWith() = runTest(dispatcher) {
        entities.put(EntityKind.Project, 3, "Alpha")
        entities.put(EntityKind.Project, 4, "Beta")
        notes.notes[7] = StoredNote("x", projects = setOf(3))
        val vm = editor(EditorKey(7, visit = 1))
        collect(vm)

        val key = vm.linksPicker(EntityKind.Project, visit = 2)
        vm.onPickerResult(PickerResult.Links(EntityKind.Project, setOf(4), emptyList()))

        assertEquals(PickerPurpose.EditorLinks(listOf(3)), key.purpose)
        assertEquals(listOf(LinkChip(4, null, "Beta")), vm.uiState.value.projects)
    }

    @Test
    fun anEntityCreatedEarlier_andInsertedByTheSaveWhenThePickerCoveredTheEditor_staysAChip() = runTest(dispatcher) {
        entities.put(EntityKind.Person, 2, "Bob")
        val vm = editor(EditorKey(null, visit = 1))
        collect(vm)
        vm.content.setTextAndPlaceCursorAtEnd("Call Alice")
        vm.onPickerResult(PickerResult.Links(EntityKind.Person, emptySet(), listOf(NewEntity("k1", "Alice"))))

        val key = vm.linksPicker(EntityKind.Person, visit = 2) // "Add person" again: the picker covers the editor,
        vm.saveOnLeave() // which saves and inserts Alice (architecture.md § Threading)
        vm.onPickerResult(PickerResult.Links(EntityKind.Person, setOf(2), emptyList()))

        assertEquals(PickerPurpose.EditorLinks(emptyList()), key.purpose)
        assertEquals(listOf("Alice", "Bob"), vm.uiState.value.people.map { it.name })
        vm.saveOnLeave()
        assertEquals(setOf(500L, 2L), notes.notes.values.single().persons)
    }

    @Test
    fun theSaveAction_insertsEvenABlankNote_andCloses() = runTest(dispatcher) {
        val vm = editor(EditorKey(null, visit = 1))
        collect(vm)

        vm.save()

        assertEquals("", notes.notes.values.single().content)
        assertTrue(vm.uiState.value.closed)
    }

    @Test
    fun aRestoredDraft_adoptsTheInsertItsLostStateMissed() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val before = editor(EditorKey(null, visit = 1), handle)
        before.setDate(20_774)
        // The state Android captured right after onStop, before the ON_STOP insert below finished.
        val captured = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        before.saveOnLeave()

        val restored = editor(EditorKey(null, visit = 1), captured)
        restored.saveOnLeave()

        assertEquals(1, notes.inserts)
        assertEquals(1, notes.notes.size)
        assertEquals(notes.notes.keys.single(), captured.get<Long>(EditorSavedState.KEY_NOTE_ID))
    }

    @Test
    fun aStoredNoteAboveTheCap_isCutOnLoad_withTheNotice_andLeavingWritesNothing() = runTest(dispatcher) {
        notes.notes[7] = StoredNote("a".repeat(MAX_CONTENT_CHARS - 1) + "😀")
        val vm = editor(EditorKey(7, visit = 1))
        collect(vm)

        assertEquals(199_999, vm.content.text.length)
        assertEquals(UiText.Res(R.string.content_truncated), vm.uiState.value.message?.text)
        assertTrue(vm.uiState.value.message?.isError == true)
        vm.saveOnLeave()
        assertEquals(0, notes.saves.size)
    }

    @Test
    fun aVanishedNote_closesTheEditor_withNoteGone() = runTest(dispatcher) {
        val vm = editor(EditorKey(7, visit = 1))
        collect(vm)

        assertTrue(vm.uiState.value.closed)
        assertEquals(UiText.Res(R.string.note_gone), messages.pending.value.single().text)
        vm.saveOnLeave()
        assertEquals(0, notes.saves.size)
    }

    @Test
    fun aDraftOfAnOlderDataEpoch_isNeverSaved() = runTest(dispatcher) {
        val vm = editor(EditorKey(null, visit = 1))
        vm.content.setTextAndPlaceCursorAtEnd("old data")

        settings.onDataReplaced()
        vm.saveOnLeave()
        vm.save()

        assertEquals(0, notes.saves.size)
    }

    @Test
    fun aFailedSaveOnLeave_reachesTheUserThroughAppMessages() = runTest(dispatcher) {
        val vm = editor(EditorKey(null, visit = 1))
        vm.content.setTextAndPlaceCursorAtEnd("x")
        notes.saveError = AppError.StorageFull

        vm.saveOnLeave()

        val message = messages.pending.value.single()
        assertEquals(UiText.Res(R.string.editor_save_failed, listOf(UiText.Error(AppError.StorageFull))), message.text)
        assertTrue(message.isError)
    }

    @Test
    fun deletingADraftNeverInserted_closesWithoutAWriteOrAMessage() = runTest(dispatcher) {
        val vm = editor(EditorKey(null, visit = 1))
        collect(vm)
        vm.content.setTextAndPlaceCursorAtEnd("never kept")

        vm.deleteNote()
        vm.saveOnLeave()

        assertTrue(vm.uiState.value.closed)
        assertEquals(0, notes.saves.size)
        assertEquals(0, notes.deletes.size)
        assertTrue(messages.pending.value.isEmpty())
    }

    @Test
    fun deletingAnInsertedNote_postsOneNoteDeleted_andDropsTheDraft() = runTest(dispatcher) {
        val vm = editor(EditorKey(null, visit = 1))
        collect(vm)
        vm.content.setTextAndPlaceCursorAtEnd("to delete")
        vm.saveOnLeave()
        val noteId = notes.notes.keys.single()

        vm.deleteNote()
        vm.content.setTextAndPlaceCursorAtEnd("edited after")
        vm.saveOnLeave()

        assertEquals(listOf(setOf(noteId)), notes.deletes)
        assertEquals(1, notes.saves.size)
        assertEquals(UiText.Plural(R.plurals.batch_deleted, 1), messages.pending.value.single().text)
        assertNull(settings.draftInsert(notes.saves.single().draftKey))
        assertTrue(vm.uiState.value.closed)
    }

    @Test
    fun aSecondDeleteTap_whileTheDeleteRuns_orAfterIt_deletesNothingMore_andPostsNoSecondMessage() = runTest(dispatcher) {
        notes.notes[7] = StoredNote("x")
        val vm = editor(EditorKey(7, visit = 1))
        collect(vm)
        val gate = CompletableDeferred<Unit>()
        notes.deleteGate = gate
        vm.openDialog(EditorDialog.DeleteNote)

        vm.deleteNote()
        assertNull("the dialog closes at once", vm.uiState.value.dialog)
        vm.deleteNote()
        gate.complete(Unit)
        vm.deleteNote()

        assertEquals(listOf(setOf(7L)), notes.deletes)
        assertEquals(UiText.Plural(R.plurals.batch_deleted, 1), messages.pending.value.single().text)
        assertTrue(vm.uiState.value.closed)
    }

    @Test
    fun deletingANoteAlreadyDeletedElsewhere_closesWithNoteGone() = runTest(dispatcher) {
        notes.notes[7] = StoredNote("x")
        val vm = editor(EditorKey(7, visit = 1))
        collect(vm)
        notes.notes.remove(7)

        vm.deleteNote()

        assertEquals(UiText.Res(R.string.note_gone), messages.pending.value.single().text)
        assertTrue(vm.uiState.value.closed)
    }

    @Test
    fun theCheckboxOfASavedNote_writesAtOnce_andIsNoDraftChange() = runTest(dispatcher) {
        notes.notes[7] = StoredNote("kept")
        val vm = editor(EditorKey(7, visit = 1))
        collect(vm)
        var changed: Boolean? = null

        vm.setChecked(true) { changed = it }
        vm.saveOnLeave()

        assertEquals(true, changed)
        assertTrue(notes.notes.getValue(7).checked)
        assertTrue(vm.uiState.value.checked)
        assertEquals(0, notes.saves.size)
    }

    @Test
    fun aDeletePick_savesTheDraftFirst_thenShowsTheImpact_andTheDeleteDropsTheLink() = runTest(dispatcher) {
        entities.put(EntityKind.Person, 1, "Alice")
        entities.put(EntityKind.Person, 2, "Bob")
        notes.notes[7] = StoredNote("x", persons = setOf(1))
        entities.impact = DeleteImpact(n = 2, m = 1)
        val vm = editor(EditorKey(7, visit = 1))
        collect(vm)
        vm.linksPicker(EntityKind.Person, visit = 2)
        vm.onPickerResult(PickerResult.Links(EntityKind.Person, setOf(1, 2), emptyList()))

        vm.onPickerResult(PickerResult.Delete(EntityKind.Person, 2))

        assertEquals(setOf(1L, 2L), notes.notes.getValue(7).persons)
        assertEquals(EditorDialog.DeleteImpact(EntityKind.Person, 2, "Bob", 2, 1), vm.uiState.value.dialog)
        vm.deleteEntity(EntityKind.Person, 2)
        assertNull(vm.uiState.value.dialog)
        assertEquals(listOf("Alice"), vm.uiState.value.people.map { it.name })
        assertEquals(UiText.Res(R.string.person_deleted), vm.uiState.value.message?.text)
        vm.saveOnLeave()
        assertEquals("the delete alone changes no note", 1, notes.saves.size)
    }

    @Test
    fun aRestoredImpactDialog_rereadsItsNameAndCounts() = runTest(dispatcher) {
        entities.put(EntityKind.Project, 3, "Alpha")
        entities.impact = DeleteImpact(n = 4, m = 0)
        val handle = SavedStateHandle()
        editor(EditorKey(null, visit = 1), handle)
        handle[EditorSavedState.KEY_DIALOG] = "DeleteImpact:Project:3"

        val restored = editor(EditorKey(null, visit = 1), handle)
        collect(restored)

        assertEquals(EditorDialog.DeleteImpact(EntityKind.Project, 3, "Alpha", 4, 0), restored.uiState.value.dialog)
    }

    @Test
    fun aPrefilledEntityThatNoLongerExists_isDroppedFromTheChips() = runTest(dispatcher) {
        entities.put(EntityKind.Project, 3, "Alpha")
        val vm = editor(EditorKey(null, visit = 1, prefill = NotePrefill(personId = 9, projectId = 3, epochDay = 20_774)))
        collect(vm)

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertEquals(emptyList<LinkChip>(), state.people)
        assertEquals(listOf(LinkChip(3, null, "Alpha")), state.projects)
        assertEquals(20_774L, state.date?.toEpochDay())
    }

    @Test
    fun theFirstInsert_recordsItsDraftKey_beforeTheSavedStateSaysInserted() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        var savedStateAtThePut: Pair<Long?, List<String>?>? = null
        val recording = object : SettingsStore by settings {
            override suspend fun putDraftInsert(draftKey: String, record: DraftInsert) {
                savedStateAtThePut = handle.get<Long>(EditorSavedState.KEY_NOTE_ID) to
                    handle.get<ArrayList<String>>(EditorSavedState.KEY_NEW_PROJECT_KEYS)
                this@EditorViewModelRulesTest.settings.putDraftInsert(draftKey, record)
            }
        }
        val vm = editor(EditorKey(null, visit = 1), handle, recording)
        vm.onPickerResult(PickerResult.Links(EntityKind.Project, emptySet(), listOf(NewEntity("k9", "Beta"))))

        vm.saveOnLeave()

        assertEquals("not inserted yet when the record is put", null to listOf("k9"), savedStateAtThePut)
        val key = handle.get<String>(EditorSavedState.KEY_DRAFT_KEY)!!
        val noteId = handle.get<Long>(EditorSavedState.KEY_NOTE_ID)!!
        assertEquals(DraftInsert(noteId, emptyMap(), mapOf("k9" to 500L)), settings.draftInsert(key))
        assertEquals(emptyList<String>(), handle.get<ArrayList<String>>(EditorSavedState.KEY_NEW_PROJECT_KEYS))
    }

    private fun TestScope.collect(vm: EditorViewModel) {
        backgroundScope.launch { vm.uiState.collect {} }
    }

    private fun TestScope.editor(
        key: EditorKey,
        handle: SavedStateHandle = SavedStateHandle(),
        settingsStore: SettingsStore = settings,
    ) = EditorViewModel(
        key,
        handle,
        notes,
        entities,
        settingsStore,
        messages,
        FakeAppTime(),
        backgroundScope,
        AppDispatchers(dispatcher, dispatcher, dispatcher),
    )
}
