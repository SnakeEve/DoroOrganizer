package com.doro.organizer.ui

import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.data.maintenance.RestorePreview
import com.doro.organizer.model.KeyMode
import com.doro.organizer.testing.ListComposeTest
import com.doro.organizer.testing.TAB_LABELS
import com.doro.organizer.testing.show
import com.doro.organizer.ui.common.RestoreConfirmDialog
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-34: the spec strings appear verbatim where the spec puts them (DEC-55, ui.md § 11, § 12), the tab
 * labels are the owner's (Q10), every dialog text is the one of the strings table, and the restore confirmation states
 * either that the security settings stay or that encryption turns off, never both (DEC-84, DEC-86).
 */
@RunWith(AndroidJUnit4::class)
class SpecStringsUiTest : ListComposeTest() {
    @Test
    fun rows_sayNoDataOverdueAndOrphaned_andAnEmptyListSaysNoData() {
        var blank = 0L
        var overdue = 0L
        startWith {
            val alice = person("Alice", now)
            blank = note(content = "", createdAt = now)
            linkPerson(blank, alice)
            overdue = note(content = "Call the bank", createdAt = now, date = today - 1)
        }
        awaitRow(blank)
        awaitRow(overdue)

        assertEquals("$NO_DATA, People: Alice", rowDescription(blank))
        assertTrue(rowDescription(overdue), rowDescription(overdue).endsWith(", $OVERDUE, $ORPHANED"))

        compose.onNodeWithContentDescription(SEARCH).performClick()
        compose.onNodeWithTag(SEARCH_FIELD).performTextInput("no such text")
        ui.awaitText(NO_DATA)
        compose.onNodeWithText(NO_DATA).assertIsDisplayed()
    }

    @Test
    fun theDateFilter_offersUndated() {
        startWith()

        compose.onNodeWithTag(DATE_CHIP).performClick()

        ui.awaitText(UNDATED)
        compose.onNodeWithText(UNDATED).assertIsDisplayed()
    }

    @Test
    fun theCreateButton_andTheCheckboxes_sayWhatTheyDo() {
        var open = 0L
        var done = 0L
        startWith {
            open = note(content = "Open", createdAt = now)
            done = note(content = "Done", createdAt = now, checkedAt = now)
        }
        awaitRow(open)
        awaitRow(done)

        compose.onNode(hasContentDescription(CREATE_NOTE) and hasClickAction()).assertIsDisplayed()
        assertEquals(MARK_DONE, checkboxDescription(open))
        assertEquals(NOT_DONE, checkboxState(open))
        assertEquals(MARK_NOT_DONE, checkboxDescription(done))
        assertEquals(DONE, checkboxState(done))
    }

    @Test
    fun theEditor_namesItsAssociationArea_andItsDateField() {
        var id = 0L
        startWith { id = note(content = "Weekly sync", createdAt = now) }
        awaitRow(id)

        compose.onNodeWithTag(rowTag(id)).performClick()
        ui.awaitTag(EDITOR_CONTENT)

        compose.onNodeWithContentDescription(ASSOCIATE).assertExists()
        compose.onNodeWithTag(EDITOR_DATE).assert(hasContentDescription(SET_DATE))
    }

    @Test
    fun settings_namesTheBackupAndRestoreRows() {
        startWith()

        compose.onNodeWithTag(SETTINGS_ACTION).performClick()
        ui.awaitTag(SETTINGS_LIST)

        compose.onNode(hasText(BACKUP_ALL_DATA)).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(RESTORE_FROM_BACKUP)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tabLabels() {
        startWith()

        for ((tag, label) in TAB_LABELS) compose.onNodeWithTag(tag).assert(hasText(label))
    }

    @Test
    fun dialogStrings_areTheTextsOfTheStringsTable() {
        val resources = englishResources()
        val wrong = DIALOG_STRINGS.flatMap { entry ->
            when (entry) {
                is TableString.Single -> listOfNotNull(mismatch(resources, entry.id, entry.text, resources.getString(entry.id)))
                is TableString.Plural -> listOfNotNull(
                    mismatch(resources, entry.id, entry.one, resources.getQuantityText(entry.id, 1).toString()),
                    mismatch(resources, entry.id, entry.other, resources.getQuantityText(entry.id, 2).toString()),
                )
            }
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun restoreConfirmation_saysSettingsStay_orEncryptionTurnsOff_neverBoth() {
        startWith()

        for (turnsOff in listOf(false, true)) {
            val preview = RestorePreview(3, 1, 1, KeyMode.PLAIN, encryptionTurnsOff = turnsOff)
            scene.onActivity { it.show { RestoreConfirmDialog(preview, onAnswer = {}) } }
            ui.awaitText(RESTORE_TITLE)

            assertEquals(if (turnsOff) 0 else 1, ui.countText(SETTINGS_KEPT, substring = true))
            assertEquals(if (turnsOff) 1 else 0, ui.countText(TURNS_OFF, substring = true))
        }
    }

    private fun checkboxState(id: Long): String? =
        compose.onNodeWithTag(checkboxTag(id), useUnmergedTree = true).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.StateDescription)

    /** The app's resources with English plural rules, whatever the phone's language: v1 ships English only. */
    private fun englishResources(): Resources {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val english = Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        return context.createConfigurationContext(english).resources
    }

    private fun mismatch(resources: Resources, id: Int, expected: String, actual: String): String? =
        if (expected == actual) null else "${resources.getResourceEntryName(id)}: \"$actual\", expected \"$expected\""

    private companion object {
        const val SEARCH_FIELD = "search_field"
        const val DATE_CHIP = "chip_Date"
        const val EDITOR_CONTENT = "editor_content"
        const val EDITOR_DATE = "editor_date"
        const val SETTINGS_ACTION = "action_settings"
        const val SETTINGS_LIST = "settings_list"

        // Literals copied from ui.md § 11 and § 12.
        const val UNDATED = "Undated"
        const val CREATE_NOTE = "Create new note"
        const val NOT_DONE = "Not done"
        const val DONE = "Done"
        const val ASSOCIATE = "Associate persons and projects"
        const val SET_DATE = "Set or change date"
        const val BACKUP_ALL_DATA = "Backup all data"
        const val RESTORE_FROM_BACKUP = "Restore from backup"
        const val RESTORE_TITLE = "Restore this backup?"
        const val SETTINGS_KEPT = "Your security settings stay as they are."
        const val TURNS_OFF = "Database encryption will be turned off. You can turn it on again in Settings afterwards."
    }
}
