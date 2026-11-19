package com.doro.organizer.testing

import com.doro.organizer.core.result.AppResult
import com.doro.organizer.model.NewEntity
import com.doro.organizer.model.NoteDraft
import com.doro.organizer.model.SaveResult

/** A [NoteDraft] with empty defaults: a new, blank, unchecked, unlinked note. */
fun draft(
    noteId: Long? = null,
    content: String = "",
    date: Long? = null,
    checked: Boolean = false,
    personIds: Set<Long> = emptySet(),
    projectIds: Set<Long> = emptySet(),
    newPersons: List<NewEntity> = emptyList(),
    newProjects: List<NewEntity> = emptyList(),
) = NoteDraft(
    draftKey = "test-draft",
    noteId = noteId,
    content = content,
    date = date,
    checked = checked,
    personIds = personIds,
    projectIds = projectIds,
    newPersons = newPersons,
    newProjects = newProjects,
)

/** The value of an [AppResult.Ok]; fails the test on an error. */
fun <T> AppResult<T>.ok(): T = when (this) {
    is AppResult.Ok -> value
    is AppResult.Err -> throw AssertionError("expected Ok, was $this")
}

/** The [SaveResult.Saved] of a save; fails the test on an error or `NoteGone`. */
fun AppResult<SaveResult>.saved(): SaveResult.Saved =
    ok() as? SaveResult.Saved ?: throw AssertionError("expected Saved, was $this")

/** A note date for tests: 2026-11-17, the day `FakeAppTime` starts on. */
const val TEST_DAY = 20_774L
