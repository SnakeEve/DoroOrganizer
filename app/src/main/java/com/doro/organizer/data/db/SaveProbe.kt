package com.doro.organizer.data.db

/** The columns `NoteRepository.save` compares with the draft ([SAVE_PROBE_SQL], data-layer.md § Note save). */
data class SaveProbe(val content: String, val date: Long?) {
    override fun toString() = "SaveProbe" // DEC-70
}
