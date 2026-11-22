package com.doro.organizer.testing

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick

/**
 * The editor and the full-screen picker as a list test drives them (ui.md § 6, § 7): found by their tags (ui.md § 12.8)
 * and by the literal texts of ui.md § 11.
 */
class EditorUi(private val compose: ComposeTestRule, private val ui: GateUi) {
    /** Taps the create button and waits for the new note's editor. */
    fun createNote() {
        compose.onNodeWithTag("fab_create").performClick()
        ui.awaitTag(CONTENT)
    }

    /** Opens the editor's overflow menu and taps [item]. */
    fun menu(item: String) {
        compose.onNodeWithContentDescription(MORE_OPTIONS).performClick()
        compose.onNodeWithText(item).performClick()
        compose.waitForIdle()
    }

    /** The node of a button whose text is exactly [text], when a title shows the same text. */
    fun button(text: String): SemanticsNodeInteraction = compose.onNode(hasText(text) and hasClickAction())

    /** The tab [label] of the batch associate picker. */
    fun pickerTab(label: String): SemanticsNodeInteraction =
        compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))

    /** The text the content field holds. */
    fun contentText(): String =
        compose.onNodeWithTag(CONTENT).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    companion object {
        const val CONTENT = "editor_content"
        const val PICKER_SEARCH = "picker_search"
        const val PICKER_CREATE = "picker_create"

        // Literals copied from ui.md § 11.
        const val MORE_OPTIONS = "More options"
        const val CANCEL = "Cancel"
        const val ADD_PERSON = "Add person"
        const val ASSOCIATE = "Associate"
        const val PROJECTS = "Projects"
        const val CONTENT_HINT = "Title / content"
        const val EDIT_NOTE = "Edit note"
        const val DELETE_NOTE = "Delete note"
        const val DELETE_PERSON = "Delete person…"
        const val PERSON_DELETED = "Person deleted"
        const val TRUNCATED = "Notes are limited to 200,000 characters. The text was cut at that limit."
        const val LINES_TRUNCATED = "Notes are limited to 100,000 lines. The text was cut at that limit."
    }
}

fun pickerRowTag(id: Long) = "picker_row_$id"
