package com.doro.organizer.testing

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import com.doro.organizer.data.HolderState
import com.doro.organizer.model.KeyMode
import com.doro.organizer.model.Perspective
import kotlin.math.abs
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue

/**
 * A list test over the real app (ui.md § 3, § 4): plain data planted with [startWith], the gate opened by a cold
 * start, and the shell on All. Rows are found by their tags (ui.md § 12.8) and read in their merged semantics.
 */
abstract class ListComposeTest : GateComposeTest() {
    val time get() = scene.time

    /** The editor and the full-screen picker (ui.md § 6, § 7). */
    val editor by lazy { EditorUi(compose, ui) }

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

    /**
     * The row at the top of the list (testing.md I-58): among the displayed rows whose bottom is below the top of
     * `note_list`, the one with the smallest top.
     */
    fun topRow(): TopRow {
        val list = ui.boundsOf(NOTE_LIST)
        return compose.onAllNodes(isNoteRow).fetchSemanticsNodes()
            .map { TopRow(it.rowId(), it.boundsInRoot.top) to it.boundsInRoot.bottom }
            .filter { (row, bottom) -> bottom > list.top && row.top < list.bottom }
            .minByOrNull { (row, _) -> row.top }
            ?.first
            ?: error("no row is displayed")
    }

    /** The note rows displayed in `note_list`, top first: each shows at least one pixel of the list. */
    fun displayedRows(): List<Long> {
        val list = ui.boundsOf(NOTE_LIST)
        return compose.onAllNodes(isNoteRow).fetchSemanticsNodes()
            .filter { it.boundsInRoot.bottom > list.top && it.boundsInRoot.top < list.bottom }
            .sortedBy { it.boundsInRoot.top }
            .map { it.rowId() }
    }

    /** The top of the row of note [id] in the root, as laid out: a row partly scrolled off keeps its real top. */
    fun rowTop(id: Long): Float = compose.onNodeWithTag(rowTag(id)).fetchSemanticsNode().positionInRoot.y

    /**
     * The row of entity [id] is the first of the picker list on screen, at its top (1 px tolerance): its top as laid
     * out, which the list does not clip, so a row partly scrolled off does not pass.
     */
    fun assertTopPickerRow(id: Long) {
        val list = compose.onNode(hasScrollToKeyAction()).fetchSemanticsNode().boundsInRoot
        val node = compose.onAllNodesWithTag(pickerRowTag(id)).fetchSemanticsNodes().singleOrNull()
        assertTrue("the row of $id is not composed: the list shows other names", node != null)
        val row = checkNotNull(node).positionInRoot.y
        assertTrue("$row at the top of $list", abs(row - list.top) <= 1f)
    }

    /** Scrolls `note_list` to the item at [index] (`performScrollToIndex`), then lets the app settle. */
    fun scrollTo(index: Int) {
        compose.onNodeWithTag(NOTE_LIST).performScrollToIndex(index)
        compose.waitForIdle()
    }

    /**
     * Taps the title of the entity [name] in Individual or Project and waits for its full-screen picker titled [title],
     * with its "All …" row (ui.md § 4.2, § 7).
     */
    fun openTitlePicker(name: String, title: String = CHOOSE_PERSON) {
        compose.waitUntil(PICKER_WAIT_MS) { compose.onAllNodes(titleButton(name)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(titleButton(name)).performClick()
        ui.awaitText(title)
        ui.awaitTag(PICKER_ALL)
    }

    /**
     * Waits until the picker body of Individual or Project shows (DEC-52), with the rows of the entities [ids]: its
     * search field, and no full-screen picker.
     */
    fun awaitPickerBody(vararg ids: Long) {
        compose.waitUntil("the picker body", PICKER_WAIT_MS) {
            compose.onAllNodesWithTag(PICKER_ALL).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText(CHOOSE_PERSON).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText(CHOOSE_PROJECT).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag(EditorUi.PICKER_SEARCH).fetchSemanticsNodes().isNotEmpty() &&
                ids.all { compose.onAllNodesWithTag(pickerRowTag(it)).fetchSemanticsNodes().isNotEmpty() }
        }
        compose.waitForIdle()
    }

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

    /**
     * Runs [block] and then waits for the work it started in the app's `applicationScope`, such as the editor's save on
     * leave (architecture.md § Threading), so a count read afterwards also proves that nothing was written. The scope's
     * long-lived children (shared flows, the clock) were there before and are not waited for.
     */
    fun awaitDetachedWork(block: () -> Unit) {
        val scope = scene.app.container.applicationScope.coroutineContext.job
        val before = scope.children.toSet()
        block()
        compose.waitForIdle()
        runBlocking { withTimeout(GateUi.TIMEOUT_MS) { (scope.children.toSet() - before).forEach { it.join() } } }
    }

    fun descriptionOf(tag: String, unmerged: Boolean = false): String =
        compose.onNodeWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()

    /** The row of note [id] whose top is at [top] px in the root; equal within 1 px (testing.md I-58). */
    class TopRow(val id: Long, val top: Float) {
        fun samePlaceAs(other: TopRow) = id == other.id && abs(top - other.top) <= 1f

        override fun toString() = "TopRow(id=$id, top=$top)"
    }

    private fun SemanticsNode.rowId(): Long =
        config.getOrNull(SemanticsProperties.TestTag).orEmpty().removePrefix(ROW_TAG_PREFIX).toLong()

    companion object {
        const val NOTE_LIST = "note_list"

        /** The overflow button of the shown person or project and the rename dialog's field (DEC-113, ui.md § 12.8). */
        const val ENTITY_MENU = "entity_menu"
        const val RENAME_FIELD = "rename_field"

        /** The "All people" / "All projects" row of the title's picker (DEC-112, ui.md § 12.8). */
        const val PICKER_ALL = "picker_all"
        private const val ROW_TAG_PREFIX = "note_row_"
        private const val PICKER_WAIT_MS = 20_000L
        private val isNoteRow = SemanticsMatcher("is a note row") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(ROW_TAG_PREFIX) == true
        }

        fun rowTag(id: Long) = "$ROW_TAG_PREFIX$id"

        fun checkboxTag(id: Long) = "note_checkbox_$id"

        fun dayTag(epochDay: Long) = "day_$epochDay"

        /** The contextual bar's title for [count] selected notes (`selected_count`, ui.md § 11.5). */
        fun selectedTitle(count: Int) = "$count selected"

        /** The title of Individual or Project as a button (ui.md § 4.2): the name of the shown entity. */
        fun titleButton(name: String) = SemanticsMatcher("title button $name") { node ->
            node.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == name }
        }

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
        const val CHOOSE_PERSON = "Choose a person"
        const val CHOOSE_PROJECT = "Choose a project"
    }
}
