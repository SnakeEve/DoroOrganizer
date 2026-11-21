package com.doro.organizer.smoke

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.MainActivity
import com.doro.organizer.testing.ReleaseSmoke
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The `release-smoke` job (testing.md § 9, DEC-13) on the R8-minified `qa` build: what R8 can break in a release, seen
 * from outside. Black-box: only `MainActivity`, the test tags of ui.md § 12.8 and literals copied from ui.md § 11,
 * because R8 renames every other app class. Each test starts from cleared data (the Orchestrator's `pm clear`), so its
 * first launch is a cold start that loads SQLCipher's JNI library and creates the database through Room. The class
 * lives in the `qa` test source set: the debug and benchmark runs start no app container without a test rule.
 */
@ReleaseSmoke
@RunWith(AndroidJUnit4::class)
class ReleaseSmokeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun closeActivity() {
        scenario?.close()
    }

    @Test
    fun coldStart_showsTheEmptyAllList() {
        launch()
        awaitText(TITLE_ALL)
        awaitText(NO_DATA)
    }

    @Test
    fun aCreatedNote_isListed_reopens_andOutlivesARecreateAndARelaunch() {
        launch()
        awaitText(TITLE_ALL)
        compose.onNodeWithContentDescription(CREATE_NOTE).performClick()
        awaitTag(CONTENT)
        compose.onNodeWithTag(CONTENT).performTextInput(NOTE)
        compose.onNodeWithContentDescription(SAVE).performClick()
        awaitListWith(NOTE)

        // The editor's navigation key carries the note id through kotlinx.serialization on a saved-state round trip.
        compose.onNodeWithText(NOTE).performClick()
        awaitText(EDIT_NOTE)
        compose.onNodeWithTag(CONTENT).assert(hasText(NOTE))
        checkNotNull(scenario).recreate()
        awaitText(EDIT_NOTE)
        awaitTag(CONTENT)
        compose.onNodeWithTag(CONTENT).assert(hasText(NOTE))
        compose.onNodeWithContentDescription(BACK).performClick()
        awaitListWith(NOTE)

        checkNotNull(scenario).close()
        launch()
        awaitListWith(NOTE)
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    /** The All list once the editor has left it, with a row that shows [text]. */
    private fun awaitListWith(text: String) {
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(CONTENT).fetchSemanticsNodes().isEmpty() }
        awaitText(TITLE_ALL)
        awaitText(text)
    }

    private fun awaitTag(tag: String) =
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun awaitText(text: String) =
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private companion object {
        /** A cold start includes creating and keying the database. */
        const val TIMEOUT_MS = 20_000L
        const val NOTE = "Release smoke note"

        // Test tags of ui.md § 12.8.
        const val CONTENT = "editor_content"

        // Literals copied from ui.md § 11.
        const val TITLE_ALL = "All notes"
        const val NO_DATA = "(No data)"
        const val CREATE_NOTE = "Create new note"
        const val SAVE = "Save"
        const val EDIT_NOTE = "Edit note"
        const val BACK = "Back"
    }
}
