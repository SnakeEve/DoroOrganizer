package com.doro.organizer.testing

import java.util.Locale

/**
 * The deterministic dataset of [SeedGenerator] as plain Kotlin values, for oracles and performance tests
 * (testing.md § 7). Ids start at 1 and are the ids the seed writer inserts. `toString()` prints ids only: a failing
 * assertion never dumps 200,000 characters of content.
 */
class SeedModel(val notes: List<SeedNote>, val persons: List<SeedEntity>, val projects: List<SeedEntity>) {
    override fun toString() = "SeedModel(notes=${notes.size}, persons=${persons.size}, projects=${projects.size})"
}

/** A note row and its links; `checked` is `checkedAt != null` (DEC-22). */
class SeedNote(
    val id: Long,
    val date: Long?,
    val checkedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val content: String,
    val personIds: Set<Long>,
    val projectIds: Set<Long>,
) {
    val checked: Boolean get() = checkedAt != null

    /** Folded once for the oracles, independently of the production `SearchText.fold` (DEC-29). */
    val foldedContent: String by lazy { content.lowercase(Locale.ROOT) }

    override fun toString() = "SeedNote(id=$id)"
}

/** A person or project row. */
class SeedEntity(val id: Long, val name: String, val createdAt: Long) {
    override fun toString() = "SeedEntity(id=$id)"
}
