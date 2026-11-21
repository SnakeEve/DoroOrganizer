package com.doro.organizer.testing

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.doro.organizer.data.HolderState
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.Perspective
import kotlinx.coroutines.runBlocking

/**
 * A list test over the real app (ui.md § 3, § 4): plain data planted with [startWith], the gate opened by a cold
 * start, and the shell on All. Rows are found by their tags (ui.md § 12.8) and read in their merged semantics.
 */
abstract class ListComposeTest : GateComposeTest() {
    val time get() = scene.time

    /** Today as an epoch day: 2026-11-17 on [FakeAppTime]'s clock. */
    val today: Long get() = time.today().toEpochDay()

    /** Now on the planting clock, for `created_at` and the like. */
    val now: Long get() = time.nowMillis()

    /**
     * Plants the rows that [rows] writes into a `PLAIN` database, then the [settings] (remembered selections), starts
     * the app and waits for the shell.
     */
    fun startWith(settings: suspend FakeSettingsStore.() -> Unit = {}, rows: TestRows.() -> Unit = {}) {
        runBlocking {
            scene.plant(KeyMode.PLAIN, rows = rows)
            scene.settings.settings()
        }
        scene.coldStart()
        ui.awaitShell()
    }

    fun openTab(p: Perspective) {
        compose.onNodeWithTag("tab_${p.name}").performClick()
        compose.waitForIdle()
    }

    fun awaitRow(id: Long) = ui.awaitTag(rowTag(id))

    fun awaitNoRow(id: Long) =
        compose.waitUntil(GateUi.TIMEOUT_MS) { compose.onAllNodesWithTag(rowTag(id)).fetchSemanticsNodes().isEmpty() }

    /** The ids of [ids] sorted by where their rows are on screen, top first. */
    fun onScreenOrder(vararg ids: Long): List<Long> = ids.sortedBy { ui.boundsOf(rowTag(it)).top }

    /** What TalkBack reads for the row of [id] (ui.md § 12.1). */
    fun rowDescription(id: Long): String = descriptionOf(rowTag(id))

    fun checkboxDescription(id: Long): String = descriptionOf(checkboxTag(id), unmerged = true)

    /** The row's state description (ui.md § 12.1); null when it has none. */
    fun rowState(id: Long): String? =
        compose.onNodeWithTag(rowTag(id)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    fun longPress(id: Long) {
        compose.onNodeWithTag(rowTag(id)).performTouchInput { longClick() }
        compose.waitForIdle()
    }

    /** Taps an icon button or menu item of the contextual bar by its description, then lets the app settle. */
    fun tapAction(description: String) {
        compose.onNodeWithContentDescription(description).performClick()
        compose.waitForIdle()
    }

    /** Waits until selection mode has ended: the contextual bar is gone (ui.md § 5.5). */
    fun awaitNoSelection() = compose.waitUntil(GateUi.TIMEOUT_MS) {
        compose.onAllNodesWithContentDescription(EXIT_SELECTION).fetchSemanticsNodes().isEmpty()
    }

    /** The single number that [sql], with its bound [args], reads from the app's open database. */
    fun count(sql: String, vararg args: Any): Long {
        val db = (scene.holderState() as HolderState.Open).db.openHelper.readableDatabase
        return db.query(sql, args).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
    }

    fun descriptionOf(tag: String, unmerged: Boolean = false): String =
        compose.onNodeWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()

    companion object {
        fun rowTag(id: Long) = "note_row_$id"

        fun checkboxTag(id: Long) = "note_checkbox_$id"

        fun dayTag(epochDay: Long) = "day_$epochDay"

        /** The contextual bar's title for [count] selected notes (`selected_count`, ui.md § 11.5). */
        fun selectedTitle(count: Int) = "$count selected"

        /** A node whose content description contains [part]. */
        fun describedWith(part: String) = SemanticsMatcher("description contains '$part'") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.contains(part) }
        }

        // Literals copied from ui.md § 11.
        const val NO_DATA = "(No data)"
        const val OVERDUE = "Overdue"
        const val ORPHANED = "Orphaned"
        const val CLEAR_FILTERS = "Clear filters"
        const val MARK_DONE = "Mark note as done"
        const val MARK_NOT_DONE = "Mark note as not done"
        const val MARKED_DONE = "Marked as done"
        const val MARKED_NOT_DONE = "Marked as not done"
        const val SEARCH = "Search"
        const val HAS_NOTES = "has notes"
        const val SELECTED = "Selected"
        const val NOT_SELECTED = "Not selected"
        const val EXIT_SELECTION = "Exit selection"
        const val SELECT_ALL = "Select all"
        const val MORE_OPTIONS = "More options"
        const val SET_DATE = "Set date…"
        const val DELETE = "Delete"
        const val CANCEL = "Cancel"
    }
}
