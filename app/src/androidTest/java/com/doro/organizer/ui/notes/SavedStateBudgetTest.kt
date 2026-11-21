package com.doro.organizer.ui.notes

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.Perspective
import com.doro.organizer.testing.EditorUi
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.ListComposeTest
import com.doro.organizer.testing.SeedGenerator
import com.doro.organizer.testing.captureSavedState
import com.doro.organizer.testing.holdsText
import com.doro.organizer.testing.marshalled
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-38, saved-state budget (ui.md § 3.10, § 6.14): the tabs in selection mode after Select all over the
 * 10,000-note seed, plus an open editor whose draft holds 200,000 characters, save an activity state below 500 KB
 * that crosses Binder in `onStop` without `TransactionTooLargeException`. The editor can only be opened at a tab root
 * outside selection mode, so All, whose Select all of 10,001 ids is above the 1,000 ids a selection saves (§ 3.10)
 * and adds no bytes, holds the editor instead of a selection. The draft ends with a marker that must be in the saved
 * bytes, so the budget is measured with the draft in it.
 */
@RunWith(AndroidJUnit4::class)
class SavedStateBudgetTest : ListComposeTest() {
    @Test
    fun selectAllOverTheSeedInEveryTab_andAFullEditorDraft_stayBelow500KB() {
        val model = SeedGenerator().generate()
        val person = model.persons.maxBy { p -> model.notes.count { p.id in it.personIds } }.id
        val project = model.projects.maxBy { p -> model.notes.count { p.id in it.projectIds } }.id
        // The Calendar opens on today: one unlinked note of today, so it and Orphans list at least one row.
        val expected = mapOf(
            Perspective.All to model.notes.size + 1,
            Perspective.Individual to model.notes.count { person in it.personIds },
            Perspective.Project to model.notes.count { project in it.projectIds },
            Perspective.Date to model.notes.count { it.date == today } + 1,
            Perspective.Orphan to model.notes.count { it.personIds.isEmpty() && it.projectIds.isEmpty() } + 1,
        )
        startWith(
            settings = {
                setRemembered(EntityKind.Person, person)
                setRemembered(EntityKind.Project, project)
            },
        ) {
            seed(model)
            note("today", createdAt = now, date = today)
        }

        expected.filterKeys { it != Perspective.All }.forEach { (perspective, count) ->
            openTab(perspective)
            longPressFirstRow()
            ui.awaitText(selectedTitle(1))
            tapAction(SELECT_ALL)
            ui.awaitText(selectedTitle(count))
        }
        assertTrue("All's Select all saves no ids", expected.getValue(Perspective.All) > MAX_SAVED_SELECTION)
        openTab(Perspective.All)
        editor.createNote()
        compose.onNodeWithTag(EditorUi.CONTENT).performTextReplacement("d".repeat(DRAFT_CHARS - MARKER.length) + MARKER)
        compose.waitUntil(GateUi.TIMEOUT_MS) { editor.contentText().length == DRAFT_CHARS }

        val saved = captureSavedState { scene.moveActivityTo(Lifecycle.State.CREATED) }

        val bytes = saved.marshalled()
        assertTrue("the editor's draft is in the saved state", bytes.holdsText(MARKER))
        assertTrue("saved state of ${bytes.size} bytes", bytes.size < BUDGET_BYTES)
    }

    private fun longPressFirstRow() {
        val rows = SemanticsMatcher("a note row") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("note_row_") == true
        }
        compose.waitUntil(GateUi.TIMEOUT_MS) { compose.onAllNodes(rows).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(rows).onFirst().performTouchInput { longClick() }
        compose.waitForIdle()
    }

    private companion object {
        const val DRAFT_CHARS = 200_000

        /** The draft's last characters, found in the saved bytes only if the whole draft was saved. */
        const val MARKER = "<end of the 200,000-character draft>"

        /** The most ids a saved selection holds (ui.md § 3.10). */
        const val MAX_SAVED_SELECTION = 1_000
        /** "Below 500 KB" (DEC-57, ui.md § 3.10) read as 500,000 bytes, the stricter of the two readings. */
        const val BUDGET_BYTES = 500_000
    }
}
