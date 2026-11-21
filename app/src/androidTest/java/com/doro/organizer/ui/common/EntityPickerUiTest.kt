package com.doro.organizer.ui.common

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.testing.EditorUi
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.ListComposeTest
import com.doro.organizer.testing.pickerRowTag
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-23, Compose half (DEC-32, DEC-33; ui.md § 6.4, § 7.3, § 7.6): the editor's "Add person" picker lists
 * every person with "N notes · added <date>", zero-note ones included; the create row reads `Create another "X"` on a
 * case-insensitive match; a person created in a cancelled picker, or removed before the save, is never inserted,
 * while one kept is inserted once, trimmed, with the note. The repository half is `EntityPickerTest`.
 */
@RunWith(AndroidJUnit4::class)
class EntityPickerUiTest : ListComposeTest() {
    @Test
    fun thePickerListsZeroNotePeople_andOffersCreateAnotherOnAMatch() {
        var anna = 0L
        var added = 0L
        startWith {
            added = now
            anna = person("Anna", added)
        }
        editor.createNote()
        compose.onNodeWithText(EditorUi.ADD_PERSON).performClick()

        ui.awaitText("Add people")
        ui.awaitTag(pickerRowTag(anna))
        ui.awaitText("0 notes · added ${mediumDate(time.dateOf(added))}")
        compose.onNodeWithTag(EditorUi.PICKER_SEARCH).performTextInput("ANNA")
        ui.awaitText("Create another \"ANNA\"")
        compose.onNodeWithTag(EditorUi.PICKER_SEARCH).performTextReplacement("Zed")
        ui.awaitText("Create \"Zed\"")
    }

    @Test
    fun aPersonCreatedInACancelledPicker_orRemovedBeforeTheSave_isNeverInserted() {
        startWith()
        editor.createNote()
        compose.onNodeWithText(EditorUi.ADD_PERSON).performClick()
        compose.onNodeWithTag(EditorUi.PICKER_SEARCH).performTextInput("Zed")
        compose.onNodeWithTag(EditorUi.PICKER_CREATE).performClick()
        ui.awaitText("New")
        compose.onNodeWithTag(EditorUi.PICKER_CREATE).assertDoesNotExist() // the field was cleared

        // Cancel: the created entity goes with the picker.
        compose.onNode(cancelIcon()).performClick()
        ui.awaitTag(EditorUi.CONTENT)
        compose.onNodeWithText("Zed").assertDoesNotExist()
        compose.onNodeWithContentDescription(REMOVE_ZED).assertDoesNotExist()

        // Done, then the chip removed before leaving: nothing is inserted either.
        compose.onNodeWithText(EditorUi.ADD_PERSON).performClick()
        compose.onNodeWithTag(EditorUi.PICKER_SEARCH).performTextInput("Zed")
        compose.onNodeWithTag(EditorUi.PICKER_CREATE).performClick()
        editor.button(DONE).performClick()
        ui.awaitDescription(REMOVE_ZED)
        compose.onNodeWithContentDescription(REMOVE_ZED).performClick()
        awaitDetachedWork { leaveEditor() }

        assertEquals(0L, count("SELECT COUNT(*) FROM persons"))
        assertEquals(0L, count("SELECT COUNT(*) FROM notes"))
    }

    @Test
    fun aKeptNewPerson_isInsertedOnceAndTrimmed_withTheNote() {
        startWith()
        editor.createNote()
        compose.onNodeWithText(EditorUi.ADD_PERSON).performClick()
        compose.onNodeWithTag(EditorUi.PICKER_SEARCH).performTextInput("  Dora  ")
        compose.onNodeWithTag(EditorUi.PICKER_CREATE).performClick()
        editor.button(DONE).performClick()
        ui.awaitDescription("Remove Dora") // exact, so it also proves the chip carries the trimmed name

        awaitDetachedWork { leaveEditor() }

        assertEquals(1L, count("SELECT COUNT(*) FROM note_persons"))
        assertEquals(1L, count("SELECT COUNT(*) FROM persons WHERE name = 'Dora'"))
        assertEquals(1L, count("SELECT COUNT(*) FROM notes"))
    }

    private fun cancelIcon() = describedWith(EditorUi.CANCEL)

    /**
     * [date] in the MEDIUM style of the locale the app formats with, `LocalConfiguration`'s first locale
     * (ui.md § 11.14, DEC-26), built with java.time rather than the app's `DateFormats`. The per-locale literals are
     * pinned by `DateDisplayTest` (I-31); this test runs in whatever locale the phone has.
     */
    private fun mediumDate(date: LocalDate): String {
        var locale = Locale.ROOT
        scene.onActivity { locale = it.resources.configuration.locales[0] }
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)
    }

    /** Back from the editor, until its entry has left the composition and started its save on leave. */
    private fun leaveEditor() {
        ui.pressBack()
        ui.awaitTag("fab_create")
        compose.waitUntil(GateUi.TIMEOUT_MS) {
            compose.onAllNodesWithTag(EditorUi.CONTENT).fetchSemanticsNodes().isEmpty()
        }
    }

    private companion object {
        const val DONE = "Done"

        /** The editor link chip of the created person: `a11y_remove_link`, ui.md § 6.4 and § 11; its only handle. */
        const val REMOVE_ZED = "Remove Zed"
    }
}
