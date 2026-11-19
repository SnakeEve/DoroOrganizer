package com.doro.organizer.model

/** The outcome of `NoteRepository.save` (data-layer.md § Note save). */
sealed interface SaveResult {
    /**
     * The draft is stored as [noteId]; [wrote] is false when nothing differed. [createdPersons] and [createdProjects]
     * map each `NewEntity.key` to the id it was inserted with.
     */
    data class Saved(
        val noteId: Long,
        val wrote: Boolean,
        val createdPersons: Map<String, Long>,
        val createdProjects: Map<String, Long>,
    ) : SaveResult {
        override fun toString() = "Saved(noteId=$noteId, wrote=$wrote, " +
            "createdPersons=${createdPersons.size}, createdProjects=${createdProjects.size})"
    }

    /** The note was deleted meanwhile; nothing was written, no entity was created (DEC-33). */
    data object NoteGone : SaveResult
}
