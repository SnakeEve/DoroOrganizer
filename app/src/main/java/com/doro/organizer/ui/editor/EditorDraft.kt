package com.doro.organizer.ui.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.NewEntity

/**
 * The editor's draft without its content (data-layer.md § Note save, DEC-37): [noteId] is null until the first insert;
 * [newPersons] and [newProjects] were created in a picker during this edit and are inserted only with a save
 * (DEC-33).
 */
internal data class DraftFields(
    val noteId: Long?,
    val date: Long?,
    val checked: Boolean,
    val personIds: Set<Long>,
    val projectIds: Set<Long>,
    val newPersons: List<NewEntity>,
    val newProjects: List<NewEntity>,
) {
    override fun toString() = "DraftFields(noteId=$noteId)" // DEC-70

    fun ids(kind: EntityKind): Set<Long> = if (kind == EntityKind.Person) personIds else projectIds

    fun created(kind: EntityKind): List<NewEntity> = if (kind == EntityKind.Person) newPersons else newProjects

    /** These fields with the links of [kind] replaced. */
    fun withLinks(kind: EntityKind, ids: Set<Long>, created: List<NewEntity>): DraftFields = when (kind) {
        EntityKind.Person -> copy(personIds = ids, newPersons = created)
        EntityKind.Project -> copy(projectIds = ids, newProjects = created)
    }

    /** The baseline these fields stand for with [content]: what a save of them leaves stored. */
    fun baseline(content: String) = Baseline(content, date, checked, personIds, projectIds)
}

/**
 * What a draft is compared with before a save on leave (ui.md § 6.8): for a new note its initial value, the prefill
 * included (DEC-37); for a stored note what is stored, where [checked] is never compared because the checkbox of a
 * saved note writes at once (DEC-22).
 */
internal data class Baseline(
    val content: String,
    val date: Long?,
    val checked: Boolean,
    val personIds: Set<Long>,
    val projectIds: Set<Long>,
) {
    override fun toString() = "Baseline(date=$date)" // DEC-70
}

/**
 * Whether leaving with [fields] and [content] writes (ui.md § 6.8): a new note only when it differs from its initial
 * [baseline] and is not blank (no content, no date, unchecked, no links, nothing created), so "+ then Back" and a
 * removed prefill insert nothing; a stored note when content (exact `String` equality), date or links differ.
 */
internal fun savesOnLeave(fields: DraftFields, content: String, baseline: Baseline): Boolean {
    val linksDiffer = fields.personIds != baseline.personIds || fields.projectIds != baseline.projectIds ||
        fields.newPersons.isNotEmpty() || fields.newProjects.isNotEmpty()
    val differs = content != baseline.content || fields.date != baseline.date || linksDiffer
    if (fields.noteId != null) return differs
    val blank = content.isEmpty() && fields.date == null && !fields.checked && fields.personIds.isEmpty() &&
        fields.projectIds.isEmpty() && fields.newPersons.isEmpty() && fields.newProjects.isEmpty()
    return (differs || fields.checked != baseline.checked) && !blank
}

/**
 * The editor's keys in its entry's `SavedStateHandle` (ui.md § 6.14): the draft fields and `draftKey`, the initial
 * value of a new note, the `dataEpoch` the draft belongs to (architecture.md § Data reset), `focusRequested`,
 * `editorDialog`, whether the draft was loaded or discarded, and the ids each links picker opened with. The content
 * is saved by [ContentStateSaver] under [KEY_CONTENT]; the stored state of a saved note is not saved, it is reloaded.
 */
internal class EditorSavedState(private val handle: SavedStateHandle) {
    /** The entry existed before: its draft is restored, not created. */
    val restored: Boolean = handle.contains(KEY_DRAFT_KEY)

    var draftKey: String
        get() = checkNotNull(handle[KEY_DRAFT_KEY])
        set(value) = handle.set(KEY_DRAFT_KEY, value)

    var epoch: Int
        get() = checkNotNull(handle[KEY_EPOCH])
        set(value) = handle.set(KEY_EPOCH, value)

    /** The draft holds the stored note's values (or is new): a restored entry that is not loaded loads them again. */
    var loaded: Boolean
        get() = handle[KEY_LOADED] ?: false
        set(value) = handle.set(KEY_LOADED, value)

    var discarded: Boolean
        get() = handle[KEY_DISCARDED] ?: false
        set(value) = handle.set(KEY_DISCARDED, value)

    var focusRequested: Boolean
        get() = handle[KEY_FOCUS_REQUESTED] ?: false
        set(value) = handle.set(KEY_FOCUS_REQUESTED, value)

    var dialog: String?
        get() = handle[KEY_DIALOG]
        set(value) = handle.set(KEY_DIALOG, value)

    var fields: DraftFields
        get() = DraftFields(
            noteId = handle[KEY_NOTE_ID],
            date = handle[KEY_DATE],
            checked = handle[KEY_CHECKED] ?: false,
            personIds = ids(KEY_PERSON_IDS),
            projectIds = ids(KEY_PROJECT_IDS),
            newPersons = created(KEY_NEW_PERSON_KEYS, KEY_NEW_PERSON_NAMES),
            newProjects = created(KEY_NEW_PROJECT_KEYS, KEY_NEW_PROJECT_NAMES),
        )
        set(value) {
            handle[KEY_NOTE_ID] = value.noteId
            handle[KEY_DATE] = value.date
            handle[KEY_CHECKED] = value.checked
            handle[KEY_PERSON_IDS] = value.personIds.toLongArray()
            handle[KEY_PROJECT_IDS] = value.projectIds.toLongArray()
            putCreated(KEY_NEW_PERSON_KEYS, KEY_NEW_PERSON_NAMES, value.newPersons)
            putCreated(KEY_NEW_PROJECT_KEYS, KEY_NEW_PROJECT_NAMES, value.newProjects)
        }

    /** The initial value of a new note: its prefill (DEC-37); the content of a new note starts empty. */
    var initial: Baseline
        get() = Baseline(
            content = "",
            date = handle[KEY_INITIAL_DATE],
            checked = handle[KEY_INITIAL_CHECKED] ?: false,
            personIds = ids(KEY_INITIAL_PERSON_IDS),
            projectIds = ids(KEY_INITIAL_PROJECT_IDS),
        )
        set(value) {
            handle[KEY_INITIAL_DATE] = value.date
            handle[KEY_INITIAL_CHECKED] = value.checked
            handle[KEY_INITIAL_PERSON_IDS] = value.personIds.toLongArray()
            handle[KEY_INITIAL_PROJECT_IDS] = value.projectIds.toLongArray()
        }

    /**
     * The draft's ids of [kind] when the editor last opened its links picker, null when none is open: its `Links`
     * result replaces only these, so an id that a save adopted meanwhile stays.
     */
    fun pickerBase(kind: EntityKind): Set<Long>? = handle.get<LongArray>(pickerBaseKey(kind))?.toSet()

    fun setPickerBase(kind: EntityKind, ids: Set<Long>?) {
        handle[pickerBaseKey(kind)] = ids?.toLongArray()
    }

    private fun pickerBaseKey(kind: EntityKind) =
        if (kind == EntityKind.Person) KEY_PICKER_BASE_PERSON_IDS else KEY_PICKER_BASE_PROJECT_IDS

    private fun ids(key: String): Set<Long> = handle.get<LongArray>(key)?.toSet().orEmpty()

    private fun created(keysKey: String, namesKey: String): List<NewEntity> {
        val keys = handle.get<ArrayList<String>>(keysKey).orEmpty()
        val names = handle.get<ArrayList<String>>(namesKey).orEmpty()
        return keys.zip(names, ::NewEntity)
    }

    private fun putCreated(keysKey: String, namesKey: String, list: List<NewEntity>) {
        handle[keysKey] = ArrayList(list.map { it.key })
        handle[namesKey] = ArrayList(list.map { it.name })
    }

    companion object {
        const val KEY_CONTENT = "content"
        const val KEY_DRAFT_KEY = "draftKey"
        const val KEY_NOTE_ID = "noteId"
        const val KEY_DATE = "date"
        const val KEY_CHECKED = "checked"
        const val KEY_PERSON_IDS = "personIds"
        const val KEY_PROJECT_IDS = "projectIds"
        const val KEY_NEW_PERSON_KEYS = "newPersonKeys"
        const val KEY_NEW_PERSON_NAMES = "newPersonNames"
        const val KEY_NEW_PROJECT_KEYS = "newProjectKeys"
        const val KEY_NEW_PROJECT_NAMES = "newProjectNames"
        const val KEY_INITIAL_DATE = "initialDate"
        const val KEY_INITIAL_CHECKED = "initialChecked"
        const val KEY_INITIAL_PERSON_IDS = "initialPersonIds"
        const val KEY_INITIAL_PROJECT_IDS = "initialProjectIds"
        const val KEY_EPOCH = "epoch"
        const val KEY_LOADED = "loaded"
        const val KEY_DISCARDED = "discarded"
        const val KEY_FOCUS_REQUESTED = "focusRequested"
        const val KEY_DIALOG = "editorDialog"
        const val KEY_PICKER_BASE_PERSON_IDS = "pickerBasePersonIds"
        const val KEY_PICKER_BASE_PROJECT_IDS = "pickerBaseProjectIds"
    }
}

/**
 * The saver of the editor's content (DEC-37, ui.md § 6.3): the text and the selection, never the undo history, which
 * the ViewModel keeps across a rotation and a process death ends. `TextFieldState.Saver` of foundation 1.12.1 also
 * saves up to 100 undo steps, each with its whole text before and after, so one paste put the draft into the saved
 * state twice and a 200,000-unit draft crashed `ON_STOP` with `TransactionTooLargeException` (DEC-57). [restore]
 * reads only items 0-2, so a state that saver wrote before an update still restores.
 */
internal object ContentStateSaver : Saver<TextFieldState, Any> {
    override fun SaverScope.save(value: TextFieldState): Any =
        arrayListOf(value.text.toString(), value.selection.start, value.selection.end)

    override fun restore(value: Any): TextFieldState {
        val saved = value as List<*>
        val text = saved[0] as String
        val start = (saved[1] as Int).coerceIn(0, text.length)
        val end = (saved[2] as Int).coerceIn(0, text.length)
        return TextFieldState(text, TextRange(start, end))
    }
}

/** This baseline without the link to entity [id] of [kind]. */
internal fun Baseline.without(kind: EntityKind, id: Long): Baseline = when (kind) {
    EntityKind.Person -> copy(personIds = personIds - id)
    EntityKind.Project -> copy(projectIds = projectIds - id)
}
