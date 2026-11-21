package com.doro.organizer.ui.notes

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.Perspective
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.ListComposeTest
import com.doro.organizer.testing.SeedGenerator
import com.doro.organizer.testing.captureSavedState
import com.doro.organizer.testing.marshalled
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-38, saved-state budget (ui.md § 3.10, § 6.14): five tabs, each in selection mode after Select all over
 * the 10,000-note seed, plus an editor draft of 200,000 characters, save an activity state below 500 KB that crosses
 * Binder in `onStop` without `TransactionTooLargeException`. The editor comes with SPEC T18, so the draft is a stand-in
 * string of its size added to the activity's state before it leaves the process.
 */
@RunWith(AndroidJUnit4::class)
class SavedStateBudgetTest : ListComposeTest() {
    @Test
    fun fiveTabsWithSelectAllOverTheSeed_andAFullDraft_stayBelow500KB() {
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

        expected.forEach { (perspective, count) ->
            openTab(perspective)
            longPressFirstRow()
            ui.awaitText(selectedTitle(1))
            tapAction(SELECT_ALL)
            ui.awaitText(selectedTitle(count))
        }

        val saved = captureSavedState(addToState = { it.putString(DRAFT_KEY, "d".repeat(DRAFT_CHARS)) }) {
            scene.moveActivityTo(Lifecycle.State.CREATED)
        }

        val size = saved.marshalled().size
        assertTrue("saved state of $size bytes", size < BUDGET_BYTES)
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
        const val DRAFT_KEY = "editorDraftStandIn"
        const val DRAFT_CHARS = 200_000
        /** "Below 500 KB" (DEC-57, ui.md § 3.10) read as 500,000 bytes, the stricter of the two readings. */
        const val BUDGET_BYTES = 500_000
    }
}
