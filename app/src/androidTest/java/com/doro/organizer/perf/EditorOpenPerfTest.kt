package com.doro.organizer.perf

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.testing.EditorUi
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.ListComposeTest
import com.doro.organizer.testing.PerfBudget
import com.doro.organizer.testing.PerfRecorder
import com.doro.organizer.testing.PerfStatistic
import com.doro.organizer.testing.PerfTest
import com.doro.organizer.testing.SeedGenerator
import com.doro.organizer.testing.SeedModel
import com.doro.organizer.testing.SeedNote
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md P-6 on the 10,000-note seed: from `performClick()` on a row to the editor field holding the note's first
 * 20 characters, inside trace section `doro:editor`, within 300 ms median for a 2,000-character note and 600 ms for a
 * 200,000-character one (architecture.md § Performance budgets, DEC-24). Both notes are made the newest unchecked
 * ones, so their rows are on screen without scrolling.
 */
@PerfTest
@RunWith(AndroidJUnit4::class)
class EditorOpenPerfTest : ListComposeTest() {
    @get:Rule(order = -1)
    val perf = PerfRecorder()

    @Test
    fun editorOpen_showsTheContentWithinBudget() {
        val model = SeedGenerator().generate()
        val long = model.notes.first { it.content.length == SeedGenerator.MAX_CONTENT_CHARS }
        val usual = model.notes.filter { it.content.length <= USUAL_CHARS }.maxBy { it.content.length }
        assertEquals(USUAL_CHARS, usual.content.length)
        startWith { seed(model.onTop(long, usual)) }

        measureOpen("2,000-character note", usual, PerfBudget(300, PerfStatistic.Median))
        measureOpen("200,000-character note", long, PerfBudget(600, PerfStatistic.Median))
    }

    private fun measureOpen(label: String, note: SeedNote, budget: PerfBudget) {
        val start = note.content.take(PREFIX_CHARS)
        perf.measure(label, budget, SECTION, prepare = { backToList(note.id) }) {
            compose.onNodeWithTag(rowTag(note.id)).performClick()
            compose.waitUntil(GateUi.TIMEOUT_MS) { editorOpen() && editor.contentText().startsWith(start) }
        }
        backToList(note.id)
    }

    /** Leaves an open editor (an unchanged note saves nothing) and waits for the row. */
    private fun backToList(id: Long) {
        if (editorOpen()) ui.pressBack()
        awaitRow(id)
    }

    private fun editorOpen() = compose.onAllNodesWithTag(EditorUi.CONTENT).fetchSemanticsNodes().isNotEmpty()

    /** This seed with [notes] unchecked and updated after every other note, in that order: the top rows of All. */
    private fun SeedModel.onTop(vararg notes: SeedNote): SeedModel {
        val newest = this.notes.maxOf { it.updatedAt }
        val raised = notes.withIndex().associate { (index, note) ->
            note.id to SeedNote(
                id = note.id,
                date = note.date,
                checkedAt = null,
                createdAt = note.createdAt,
                updatedAt = newest + notes.size - index,
                content = note.content,
                personIds = note.personIds,
                projectIds = note.projectIds,
            )
        }
        return SeedModel(this.notes.map { raised[it.id] ?: it }, persons, projects)
    }

    private companion object {
        const val SECTION = "doro:editor"
        const val USUAL_CHARS = 2_000
        const val PREFIX_CHARS = 20
    }
}
