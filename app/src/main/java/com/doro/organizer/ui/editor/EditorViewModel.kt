package com.doro.organizer.ui.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable
import com.doro.organizer.R
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.coroutines.runDetached
import com.doro.organizer.core.messages.AppMessages
import com.doro.organizer.core.messages.UiText
import com.doro.organizer.core.messages.UserMessage
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.AppResult
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.data.EntityRepository
import com.doro.organizer.data.NoteRepository
import com.doro.organizer.model.DateRules
import com.doro.organizer.model.DraftInsert
import com.doro.organizer.model.NewEntity
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.NoteDetail
import com.doro.organizer.model.NoteDraft
import com.doro.organizer.model.SaveResult
import com.doro.organizer.settings.SettingsStore
import com.doro.organizer.ui.common.PickerResult
import com.doro.organizer.ui.navigation.EditorKey
import com.doro.organizer.ui.navigation.PickerKey
import com.doro.organizer.ui.navigation.PickerPurpose
import java.io.IOException
import java.text.Collator
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The state holder of the note editor (ui.md § 6, DEC-37) for [key]. The draft lives here and in the entry's
 * `SavedStateHandle` ([EditorSavedState]); the content in [content]. Saves run in [applicationScope] on the main
 * dispatcher, one at a time (data-layer.md § Note save, caller rules), so a save on leave outlives the editor and
 * never inserts a note or an entity twice: after the first insert the draft holds the new ids, and across a process
 * death the `draftKey` record of `SettingsStore` does (ui.md § 6.8). A draft of an older `dataEpoch` is never saved
 * (architecture.md § Data reset). The writes a tap starts (the checkbox, the deletes) run detached as well, so leaving
 * right after the tap never cancels them. Chip names come from `loadNote` and the existence probes (ui.md § 6.7).
 */
@OptIn(SavedStateHandleSaveableApi::class)
class EditorViewModel(
    private val key: EditorKey,
    savedStateHandle: SavedStateHandle,
    private val noteRepository: NoteRepository,
    private val entityRepository: EntityRepository,
    private val settingsStore: SettingsStore,
    private val appMessages: AppMessages,
    private val appTime: AppTime,
    private val applicationScope: CoroutineScope,
    private val dispatchers: AppDispatchers,
) : ViewModel() {
    private val saved = EditorSavedState(savedStateHandle)

    /** The content field's text (ui.md § 6.3); not part of [uiState]. */
    val content: TextFieldState =
        savedStateHandle.saveable(EditorSavedState.KEY_CONTENT, saver = TextFieldState.Saver) { TextFieldState() }

    /** The content field's cap (ui.md § 6.9): each truncating change shows the notice. */
    val contentCap = ContentCap { showMessage(UiText.Res(R.string.content_truncated), isError = true) }

    private val fields: MutableStateFlow<DraftFields>
    private val screen: MutableStateFlow<Screen>

    /** The names of the entities the draft links, per kind (ui.md § 6.4); an id without a name shows no chip yet. */
    private val names = MutableStateFlow(EntityKind.entries.associateWith { emptyMap<Long, String>() })

    /** A restored impact dialog, re-read once the draft has loaded (ui.md § 6.14). */
    private val restoredImpact: SavedDialog.Impact?
    private val saveMutex = Mutex()

    /** What a save on leave compares with (ui.md § 6.8); null until a saved note is loaded. Main thread only. */
    private var baseline: Baseline? = null
    private var cleared = false
    private var saveRunning = false
    private var deleteRunning = false

    init {
        if (!saved.restored) {
            val prefill = key.prefill
            val start = DraftFields(
                noteId = key.noteId,
                date = prefill.epochDay,
                checked = false,
                personIds = setOfNotNull(prefill.personId),
                projectIds = setOfNotNull(prefill.projectId),
                newPersons = emptyList(),
                newProjects = emptyList(),
            )
            saved.draftKey = UUID.randomUUID().toString()
            saved.epoch = currentEpoch()
            saved.fields = start
            saved.initial = start.baseline("")
            saved.loaded = key.noteId == null
        }
        fields = MutableStateFlow(saved.fields)
        val restoredDialog = savedDialogOf(saved.dialog)
        restoredImpact = restoredDialog as? SavedDialog.Impact
        screen = MutableStateFlow(Screen(loaded = false, dialog = (restoredDialog as? SavedDialog.Plain)?.dialog))
        viewModelScope.launch { load() }
    }

    // Every input is a state held here, so an upstream restarted after a pause re-emits the loaded draft: `loading`
    // ends once and never returns (ui.md § 6.14).
    val uiState: StateFlow<EditorUiState> =
        combine(fields, screen, names, appTime.todayFlow) { draft, screen, names, today ->
            EditorUiState(
                loading = !screen.loaded,
                isNew = key.noteId == null,
                noteId = draft.noteId,
                date = draft.date?.let(LocalDate::ofEpochDay),
                overdue = DateRules.isOverdue(draft.checked, draft.date, today),
                checked = draft.checked,
                people = chips(draft, EntityKind.Person, names.getValue(EntityKind.Person)),
                projects = chips(draft, EntityKind.Project, names.getValue(EntityKind.Project)),
                dialog = screen.dialog,
                message = screen.message,
                closed = screen.closed,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialState())

    /** A new note asks for focus once, on the first composition after loading (ui.md § 6.3). */
    val wantsFocus: Boolean get() = key.noteId == null && !saved.focusRequested

    fun onFocusRequested() {
        saved.focusRequested = true
    }

    // Draft changes (ui.md § 6.4–§ 6.6, § 6.10).

    /** The date picker's OK, or with null "Remove date" (ui.md § 6.5). */
    fun setDate(epochDay: Long?) {
        updateFields { it.copy(date = epochDay) }
        closeDialog()
    }

    /** A chip's remove action or the unlink sheet's "Unlink": the link leaves the draft; a created entity is dropped. */
    fun removeLink(kind: EntityKind, chip: LinkChip) = updateFields { draft ->
        draft.withLinks(
            kind,
            draft.ids(kind) - setOfNotNull(chip.id),
            draft.created(kind).filterNot { chip.newKey != null && it.key == chip.newKey },
        )
    }

    /**
     * The done checkbox (ui.md § 6.6): a saved note is written at once with the target state (DEC-22), and [onDone]
     * reports whether the row changed, for the announcement; a note not yet inserted only changes its draft. Under the
     * save lock, so a save that is inserting the note meanwhile decides which of the two applies.
     */
    fun setChecked(checked: Boolean, onDone: (changed: Boolean) -> Unit) {
        viewModelScope.launch {
            val result = applicationScope.runDetached {
                withContext(dispatchers.main) {
                    saveMutex.withLock {
                        val written = fields.value.noteId?.let { noteRepository.setChecked(it, checked) }
                        if (written !is AppResult.Err) updateFields { it.copy(checked = checked) }
                        written
                    }
                }
            }
            when (result) {
                null -> Unit
                is AppResult.Ok -> onDone(result.value)
                is AppResult.Err -> showError(result.error)
            }
        }
    }

    /**
     * The "Add person" / "Add project" picker (ui.md § 6.4): the draft's ids of [kind] start checked, and they are
     * what its `Links` result replaces. [visit] comes from `Navigator.nextVisit()` (DEC-100).
     */
    fun linksPicker(kind: EntityKind, visit: Int): PickerKey {
        val ids = fields.value.ids(kind)
        saved.setPickerBase(kind, ids)
        return PickerKey(kind, PickerPurpose.EditorLinks(ids.sorted()), visit)
    }

    /** The "Delete person…" / "Delete project…" picker (ui.md § 6.11): this note's links of [kind] first. */
    fun deletePicker(kind: EntityKind, visit: Int) =
        PickerKey(kind, PickerPurpose.DeleteEntity(fields.value.ids(kind).sorted()), visit)

    /** The result of a picker opened from this editor (ui.md § 6.4, § 6.11, § 7.7). */
    fun onPickerResult(result: PickerResult) {
        when (result) {
            is PickerResult.Links -> {
                val base = saved.pickerBase(result.kind)
                saved.setPickerBase(result.kind, null)
                updateFields { draft ->
                    // Opening the picker covered the editor, and that save may have inserted entities created earlier
                    // in this edit: they joined the ids after the picker opened, so they stay chips (ui.md § 7.6).
                    val kept = draft.ids(result.kind) - (base ?: draft.ids(result.kind))
                    draft.withLinks(result.kind, kept + result.ids, draft.created(result.kind) + result.created)
                }
                viewModelScope.launch { resolveNames() }
            }
            is PickerResult.Delete -> viewModelScope.launch { onDeletePicked(result.kind, result.id) }
            else -> Unit
        }
    }

    fun openDialog(dialog: EditorDialog) = setDialog(dialog)

    fun closeDialog() = setDialog(null)

    fun onMessageShown(id: Long) = screen.update { if (it.message?.id == id) it.copy(message = null) else it }

    // Saving (ui.md § 6.8).

    /** The Save action: saves now, a new note even when blank, and closes; a failure keeps the editor open. */
    fun save() {
        if (saveRunning) return
        saveRunning = true
        viewModelScope.launch {
            val outcome = applicationScope.runDetached { withContext(dispatchers.main) { saveDraft(explicit = true) } }
            saveRunning = false
            when (outcome) {
                is Outcome.Failed -> showError(outcome.error)
                Outcome.Gone -> close(UiText.Res(R.string.note_gone), isError = false)
                Outcome.Done -> close(null, isError = false)
            }
        }
    }

    /**
     * Back, leaving by navigation and the app going to the background (the entry's `ON_STOP`): saves when the draft
     * differs from its baseline, in [applicationScope] because the editor may be gone by then. A failure or a vanished
     * note reaches the user through `AppMessages` (ui.md § 6.8, architecture.md § Threading).
     */
    fun saveOnLeave() {
        applicationScope.launch(dispatchers.main) {
            when (val outcome = saveDraft(explicit = false)) {
                is Outcome.Failed -> appMessages.post(
                    UserMessage.of(UiText.Res(R.string.editor_save_failed, listOf(UiText.Error(outcome.error))), true),
                )
                Outcome.Gone -> appMessages.post(UserMessage.of(UiText.Res(R.string.note_gone), isError = false))
                Outcome.Done -> Unit
            }
        }
    }

    // Deletes (ui.md § 6.11, § 6.12).

    /**
     * "Delete" of the delete note dialog: a hard delete of the inserted note (DEC-35), which then closes the editor
     * with "1 note deleted" on the list, or with "This note no longer exists." when it was already gone; a draft never
     * inserted, or already discarded, closes without a write or a message. Either way the draft is discarded and never
     * saved. The dialog closes at once and a second call while one runs is ignored, so one tap deletes once (ui.md
     * § 8.1).
     */
    fun deleteNote() {
        closeDialog()
        if (deleteRunning) return
        deleteRunning = true
        viewModelScope.launch {
            val result = applicationScope.runDetached {
                withContext(dispatchers.main) {
                    saveMutex.withLock {
                        val noteId = fields.value.noteId.takeUnless { saved.discarded }
                        val deleted = noteId?.let { noteRepository.deleteNotes(listOf(it)) }
                        if (deleted !is AppResult.Err) discard()
                        deleted
                    }
                }
            }
            deleteRunning = false
            when (result) {
                null -> close(null, isError = false)
                is AppResult.Err -> showError(result.error)
                is AppResult.Ok -> {
                    val gone = result.value.changed == 0 // deleted elsewhere meanwhile (ui.md § 6.8)
                    val text = if (gone) UiText.Res(R.string.note_gone) else UiText.Plural(R.plurals.batch_deleted, 1)
                    close(text, isError = false)
                }
            }
        }
    }

    /**
     * "Delete" of the impact dialog: one transaction that recomputes the counts (DEC-34). The entity leaves the draft;
     * "Person deleted" / "Project deleted" shows unless it was already gone.
     */
    fun deleteEntity(kind: EntityKind, id: Long) {
        closeDialog()
        viewModelScope.launch {
            val result = applicationScope.runDetached {
                when (kind) {
                    EntityKind.Person -> entityRepository.deletePerson(id)
                    EntityKind.Project -> entityRepository.deleteProject(id)
                }
            }
            when (result) {
                is AppResult.Ok -> {
                    forgetEntity(kind, id)
                    if (result.value.deleted) {
                        val text = if (kind == EntityKind.Person) R.string.person_deleted else R.string.project_deleted
                        showMessage(UiText.Res(text), isError = false)
                    }
                }
                is AppResult.Err -> showError(result.error)
            }
        }
    }

    override fun onCleared() {
        // The entry was popped (or its data epoch ended): the draft is finished once the saves before this are done.
        cleared = true
        applicationScope.launch(dispatchers.main) { saveMutex.withLock { dropDraftInsert() } }
    }

    // Loading (ui.md § 6.7, § 6.8, § 6.9).

    /** Loads the draft once: `loading` ends here (ui.md § 6.14). */
    private suspend fun load() {
        if (saved.discarded) return close(null, isError = false)
        val noteId = adoptDraftInsert() ?: fields.value.noteId
        if (noteId == null) {
            baseline = saved.initial
        } else {
            val detail = when (val result = noteRepository.loadNote(noteId)) {
                is AppResult.Err -> return close(UiText.Error(result.error), isError = true)
                is AppResult.Ok -> result.value ?: return close(UiText.Res(R.string.note_gone), isError = false)
            }
            applyLoaded(detail)
        }
        resolveNames()
        screen.update { it.copy(loaded = true) }
        restoreImpact()
    }

    /**
     * Probes every linked id the draft has no name for (ui.md § 6.7): the prefill, a picker result and the links of a
     * restored draft. An id that no longer exists leaves the draft without a message.
     */
    private suspend fun resolveNames() {
        for (kind in EntityKind.entries) {
            for (id in fields.value.ids(kind) - names.value.getValue(kind).keys) {
                val ref = entityRepository.observe(kind, id).first()
                if (ref == null) forgetEntity(kind, id) else putNames(kind, mapOf(id to ref.name))
            }
        }
    }

    /**
     * A restored draft that was never known to be inserted looks up its `draftKey` record first (ui.md § 6.8): on a
     * hit the insert happened before the process died, and the draft adopts its ids exactly as after `Saved`.
     */
    private suspend fun adoptDraftInsert(): Long? {
        if (!saved.restored || fields.value.noteId != null) return null
        val record = try {
            settingsStore.draftInsert(saved.draftKey)
        } catch (_: IOException) {
            null // An unreadable record is a lost one: the residual risk ui.md § 6.8 accepts.
        } ?: return null
        adopt(record)
        return record.noteId
    }

    /**
     * The stored note becomes the baseline. A fresh draft takes its values; a restored one keeps the user's edits and
     * only takes `checked`, which the checkbox writes at once. A note above the cap (only a foreign backup holds one)
     * is cut like typed input, with the notice, and the cut text is the baseline, so leaving writes nothing.
     */
    private fun applyLoaded(detail: NoteDetail) {
        putNames(EntityKind.Person, detail.persons.associate { it.id to it.name })
        putNames(EntityKind.Project, detail.projects.associate { it.id to it.name })
        val text = ContentCap.capped(detail.content)
        baseline = Baseline(
            text,
            detail.date,
            detail.checked,
            detail.persons.mapTo(HashSet()) { it.id },
            detail.projects.mapTo(HashSet()) { it.id },
        )
        if (saved.loaded) {
            updateFields { it.copy(checked = detail.checked) }
            return
        }
        updateFields {
            it.copy(
                date = detail.date,
                checked = detail.checked,
                personIds = detail.persons.mapTo(HashSet()) { ref -> ref.id },
                projectIds = detail.projects.mapTo(HashSet()) { ref -> ref.id },
            )
        }
        content.setTextAndPlaceCursorAtEnd(text)
        saved.loaded = true
        if (text.length < detail.content.length) showMessage(UiText.Res(R.string.content_truncated), isError = true)
    }

    /** A restored impact dialog re-reads its name and counts (ui.md § 6.14). */
    private suspend fun restoreImpact() {
        val impact = restoredImpact ?: return
        setDialog(null)
        showImpact(impact.kind, impact.id)
    }

    /**
     * The delete picker returned an entity (ui.md § 6.11): the draft is saved first when leaving would save it, so the
     * counts include this note's link edits; then the impact dialog opens.
     */
    private suspend fun onDeletePicked(kind: EntityKind, id: Long) {
        when (val outcome = applicationScope.runDetached { withContext(dispatchers.main) { saveDraft(explicit = false) } }) {
            is Outcome.Failed -> showError(outcome.error)
            Outcome.Gone -> close(UiText.Res(R.string.note_gone), isError = false)
            Outcome.Done -> showImpact(kind, id)
        }
    }

    private suspend fun showImpact(kind: EntityKind, id: Long) {
        // An entity already gone has nothing to delete: no dialog (data-layer.md § Reactivity).
        val ref = entityRepository.observe(kind, id).first() ?: return forgetEntity(kind, id)
        val impact = when (kind) {
            EntityKind.Person -> entityRepository.personDeleteImpact(id)
            EntityKind.Project -> entityRepository.projectDeleteImpact(id)
        }
        when (impact) {
            is AppResult.Ok -> setDialog(EditorDialog.DeleteImpact(kind, id, ref.name, impact.value.n, impact.value.m))
            is AppResult.Err -> showError(impact.error)
        }
    }

    // The save itself.

    /**
     * One save of the current draft under the lock (data-layer.md § Note save): skipped once the draft is discarded,
     * before it is loaded and for an older data epoch; on leave also when [savesOnLeave] says nothing changed.
     */
    private suspend fun saveDraft(explicit: Boolean): Outcome = saveMutex.withLock {
        if (saved.discarded) return@withLock Outcome.Done
        val base = baseline ?: return@withLock Outcome.Done
        if (saved.epoch != currentEpochOrNull()) return@withLock Outcome.Done
        val draft = fields.value
        val text = content.text.toString()
        val wanted = if (explicit && draft.noteId == null) true else savesOnLeave(draft, text, base)
        if (!wanted) return@withLock Outcome.Done
        val result = noteRepository.save(
            NoteDraft(
                saved.draftKey, draft.noteId, text, draft.date, draft.checked,
                draft.personIds, draft.projectIds, draft.newPersons, draft.newProjects,
            ),
        )
        when (result) {
            is AppResult.Err -> Outcome.Failed(result.error)
            is AppResult.Ok -> when (val value = result.value) {
                SaveResult.NoteGone -> {
                    discard()
                    Outcome.Gone
                }
                is SaveResult.Saved -> {
                    val record = DraftInsert(value.noteId, value.createdPersons, value.createdProjects)
                    // The record exists before the saved state can say "inserted" (ui.md § 6.8); an editor already
                    // closed can no longer be restored, so it needs none.
                    if (draft.noteId == null && !cleared) putDraftInsert(record)
                    adopt(record)
                    baseline = draft.copy(
                        personIds = draft.personIds + value.createdPersons.values,
                        projectIds = draft.projectIds + value.createdProjects.values,
                    ).baseline(text)
                    if (cleared) dropDraftInsert()
                    Outcome.Done
                }
            }
        }
    }

    /**
     * After an insert: the draft keeps the note id, and the entities it created move from the new lists into the id
     * sets, so no later save inserts them again; their chips keep their names. Entities removed from the draft
     * meanwhile stay out.
     */
    private fun adopt(record: DraftInsert) {
        val draft = fields.value
        putNames(EntityKind.Person, createdNames(draft.newPersons, record.createdPersons))
        putNames(EntityKind.Project, createdNames(draft.newProjects, record.createdProjects))
        updateFields {
            it.copy(
                noteId = record.noteId,
                personIds = it.personIds + it.newPersons.mapNotNull { new -> record.createdPersons[new.key] },
                projectIds = it.projectIds + it.newProjects.mapNotNull { new -> record.createdProjects[new.key] },
                newPersons = it.newPersons.filterNot { new -> new.key in record.createdPersons },
                newProjects = it.newProjects.filterNot { new -> new.key in record.createdProjects },
            )
        }
    }

    private fun createdNames(created: List<NewEntity>, ids: Map<String, Long>): Map<Long, String> =
        created.mapNotNull { new -> ids[new.key]?.let { it to new.name } }.toMap()

    /** The draft is finished without saving: deleted, or its note is gone (ui.md § 6.8). */
    private suspend fun discard() {
        saved.discarded = true
        dropDraftInsert()
    }

    private suspend fun putDraftInsert(record: DraftInsert) {
        try {
            settingsStore.putDraftInsert(saved.draftKey, record)
        } catch (_: IOException) {
            // A full disk: the note is saved; only the guard against a second insert after a process death is lost.
        }
    }

    private suspend fun dropDraftInsert() {
        try {
            settingsStore.dropDraftInsert(saved.draftKey)
        } catch (_: IOException) {
            // A record that stays is dropped with the oldest of MAX_DRAFT_INSERTS or by the next data reset.
        }
    }

    // State helpers.

    /**
     * A deleted entity leaves the draft and its baseline, so the delete alone never makes the draft "changed" (DEC-21:
     * an entity delete bumps no note).
     */
    private fun forgetEntity(kind: EntityKind, id: Long) {
        updateFields { it.withLinks(kind, it.ids(kind) - id, it.created(kind)) }
        baseline = baseline?.without(kind, id)
        if (fields.value.noteId == null) saved.initial = saved.initial.without(kind, id)
    }

    private fun putNames(kind: EntityKind, entries: Map<Long, String>) {
        if (entries.isNotEmpty()) names.update { it + (kind to it.getValue(kind) + entries) }
    }

    private fun updateFields(change: (DraftFields) -> DraftFields) {
        val next = change(fields.value)
        saved.fields = next
        fields.value = next
    }

    private fun setDialog(dialog: EditorDialog?) {
        saved.dialog = dialog?.toKey()
        screen.update { it.copy(dialog = dialog) }
    }

    /** Asks the screen to leave; [text], when given, shows on the list through `AppMessages` (ui.md § 2.4). */
    private fun close(text: UiText?, isError: Boolean) {
        text?.let { appMessages.post(UserMessage.of(it, isError)) }
        setDialog(null)
        screen.update { it.copy(closed = true) }
    }

    private fun showError(error: AppError) = screen.update { it.copy(message = UserMessage.error(error)) }

    private fun showMessage(text: UiText, isError: Boolean) =
        screen.update { it.copy(message = UserMessage.of(text, isError)) }

    private fun currentEpochOrNull(): Int? = settingsStore.settings.value?.dataEpoch

    private fun currentEpoch(): Int =
        checkNotNull(currentEpochOrNull()) { "the editor opens only after the settings were read" }

    private fun initialState() = EditorUiState(
        loading = true,
        isNew = key.noteId == null,
        noteId = fields.value.noteId,
        date = null,
        overdue = false,
        checked = false,
        people = emptyList(),
        projects = emptyList(),
        dialog = null,
        message = null,
        closed = false,
    )

    /**
     * The chips of [kind] (ui.md § 6.4): the draft's stored links by `Collator` on the name, then id (DEC-32), then
     * the entities created during this edit in creation order.
     */
    private fun chips(draft: DraftFields, kind: EntityKind, names: Map<Long, String>): List<LinkChip> {
        val collator = Collator.getInstance() // per pass: not thread-safe, and the locale may change
        val stored = draft.ids(kind).mapNotNull { id -> names[id]?.let { LinkChip(id, null, it) } }
            .sortedWith(compareBy<LinkChip, String>(collator) { it.name }.thenBy { it.id })
        return stored + draft.created(kind).map { LinkChip(null, it.key, it.name) }
    }

    /** What a save returned to its caller. */
    private sealed interface Outcome {
        data object Done : Outcome

        data object Gone : Outcome

        data class Failed(val error: AppError) : Outcome {
            override fun toString() = "Failed($error)"
        }
    }

    /** The part of the state that is not the draft. */
    private data class Screen(
        val loaded: Boolean,
        val dialog: EditorDialog?,
        val message: UserMessage? = null,
        val closed: Boolean = false,
    ) {
        override fun toString() = "Screen(loaded=$loaded, closed=$closed)"
    }
}
