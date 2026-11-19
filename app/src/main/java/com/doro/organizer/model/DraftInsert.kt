package com.doro.organizer.model

/**
 * What the first insert of an editor draft created (architecture.md § SettingsStore, DEC-37): the note and the
 * entities created with it, keyed by their draft-local UUIDs (never names), so a draft restored after a process death
 * adopts them instead of inserting them again.
 */
data class DraftInsert(
    val noteId: Long,
    val createdPersons: Map<String, Long>,
    val createdProjects: Map<String, Long>,
) {
    override fun toString() = "DraftInsert(noteId=$noteId)"
}
