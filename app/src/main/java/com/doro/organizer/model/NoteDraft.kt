package com.doro.organizer.model

/**
 * A person or project created in a picker, inserted only by the save or batch confirm that uses it (DEC-33).
 * [key] is a draft-local UUID string that maps the entity to its new id in the result.
 */
data class NewEntity(val key: String, val name: String) {
    override fun toString() = "NewEntity(key=$key)" // DEC-70
}

/**
 * The editor's note as `NoteRepository.save` receives it (data-layer.md § Note save, DEC-37). [noteId] is null until
 * the first insert; [checked] is read only when inserting, because the checkbox of a saved note writes through
 * `setChecked` (DEC-22).
 */
data class NoteDraft(
    val draftKey: String,
    val noteId: Long?,
    val content: String,
    val date: Long?,
    val checked: Boolean,
    val personIds: Set<Long>,
    val projectIds: Set<Long>,
    val newPersons: List<NewEntity>,
    val newProjects: List<NewEntity>,
) {
    override fun toString() = "NoteDraft(noteId=$noteId)" // DEC-70
}
