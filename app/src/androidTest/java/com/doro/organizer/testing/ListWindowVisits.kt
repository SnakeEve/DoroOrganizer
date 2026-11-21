package com.doro.organizer.testing

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.doro.organizer.model.Perspective

/** What [visitListWindows] stops at: a screen of the activity window, a dialog or sheet window, or a menu popup. */
enum class ListWindow { Screen, Dialog, Menu }

/**
 * Plants a note dated yesterday and linked to a person and a project, a done note and an empty note, then opens one
 * after another every screen, dialog, sheet and menu of the list (ui.md § 3–§ 5) and of the editor (ui.md § 6) and
 * calls [visit] in each while it is shown: All, the date filter sheet and its end picker, Calendar and its range
 * picker, the selection menu, Set date, batch delete, the editor, its date picker, its menu, the unlink sheet, and
 * the delete-note and delete-person dialogs. Each window is closed before the next opens.
 */
fun ListComposeTest.visitListWindows(visit: (name: String, window: ListWindow) -> Unit) {
    var linked = 0L
    var alice = 0L
    startWith {
        linked = note(content = "Weekly sync\nBudget", createdAt = now, date = today - 1)
        alice = person("Alice", now)
        linkPerson(linked, alice)
        linkProject(linked, project("Alpha", now))
        note(content = "Done", createdAt = now, checkedAt = now)
        note(content = "", createdAt = now)
    }
    awaitRow(linked)
    visit("All", ListWindow.Screen)

    compose.onNodeWithTag(DATE_CHIP).performClick()
    ui.awaitText(FILTER_BY_DATE)
    visit("date filter sheet", ListWindow.Dialog)
    compose.onNodeWithText(FROM).performClick()
    awaitButton(CANCEL)
    visit("date filter end picker", ListWindow.Dialog)
    closeWith(CANCEL)
    compose.onNode(hasText(DONE) and hasClickAction()).performClick()
    awaitNoText(FILTER_BY_DATE)

    openTab(Perspective.Date)
    ui.awaitTag(MONTH_GRID)
    visit("Calendar", ListWindow.Screen)
    compose.onNodeWithText(SELECT_RANGE).performClick()
    awaitButton(CANCEL)
    visit("range picker", ListWindow.Dialog)
    closeWith(CANCEL)

    openTab(Perspective.All)
    awaitRow(linked)
    longPress(linked)
    ui.awaitText(ListComposeTest.selectedTitle(1))
    tapAction(ListComposeTest.MORE_OPTIONS)
    ui.awaitText(ListComposeTest.SET_DATE)
    visit("selection menu", ListWindow.Menu)
    compose.onNodeWithText(ListComposeTest.SET_DATE).performClick()
    ui.awaitText(SET_DATE_TITLE)
    visit("Set date", ListWindow.Dialog)
    closeWith(CANCEL)
    tapAction(ListComposeTest.MORE_OPTIONS)
    ui.awaitText(ListComposeTest.DELETE)
    compose.onNodeWithText(ListComposeTest.DELETE).performClick()
    ui.awaitText(BATCH_DELETE_TITLE)
    visit("batch delete", ListWindow.Dialog)
    closeWith(CANCEL)
    tapAction(ListComposeTest.EXIT_SELECTION)
    awaitNoSelection()

    compose.onNodeWithTag(ListComposeTest.rowTag(linked)).performClick()
    ui.awaitTag(EditorUi.CONTENT)
    visit("editor", ListWindow.Screen)
    compose.onNodeWithTag(EDITOR_DATE).performClick()
    awaitButton(CANCEL)
    visit("editor date picker", ListWindow.Dialog)
    closeWith(CANCEL)
    compose.onNodeWithContentDescription(EditorUi.MORE_OPTIONS).performClick()
    ui.awaitText(EditorUi.DELETE_NOTE)
    visit("editor menu", ListWindow.Menu)
    compose.onNodeWithText(UNLINK_ITEM).performClick()
    ui.awaitText(UNLINK_TITLE)
    visit("unlink sheet", ListWindow.Dialog)
    compose.onNode(hasText(DONE) and hasClickAction()).performClick()
    awaitNoText(UNLINK_TITLE)
    editor.menu(EditorUi.DELETE_NOTE)
    ui.awaitText(DELETE_NOTE_TITLE)
    visit("delete note", ListWindow.Dialog)
    closeWith(CANCEL)
    editor.menu(EditorUi.DELETE_PERSON)
    ui.awaitTag(pickerRowTag(alice))
    compose.onNodeWithTag(pickerRowTag(alice)).performClick()
    ui.awaitText(DELETE_ALICE_TITLE)
    visit("delete person", ListWindow.Dialog)
    closeWith(CANCEL)
}

private fun ListComposeTest.awaitButton(text: String) = compose.waitUntil(GateUi.TIMEOUT_MS) {
    compose.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
}

/** Taps the button [text] of the open dialog and waits until it is gone. */
private fun ListComposeTest.closeWith(text: String) {
    compose.onNode(hasText(text) and hasClickAction()).performClick()
    compose.waitUntil(GateUi.TIMEOUT_MS) {
        compose.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isEmpty()
    }
}

private fun ListComposeTest.awaitNoText(text: String) = compose.waitUntil(GateUi.TIMEOUT_MS) {
    compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
}

private const val DATE_CHIP = "chip_Date"
private const val MONTH_GRID = "month_grid"
private const val EDITOR_DATE = "editor_date"

// Literals copied from ui.md § 11.
private const val FILTER_BY_DATE = "Filter by date"
private const val FROM = "From"
private const val DONE = "Done"
private const val CANCEL = "Cancel"
private const val SELECT_RANGE = "Select range"
private const val SET_DATE_TITLE = "Set date"
private const val BATCH_DELETE_TITLE = "Delete 1 note?"
private const val UNLINK_ITEM = "Unlink person or project…"
private const val UNLINK_TITLE = "Unlink person or project"
private const val DELETE_NOTE_TITLE = "Delete this note?"
private const val DELETE_ALICE_TITLE = "Delete person \"Alice\"?"
