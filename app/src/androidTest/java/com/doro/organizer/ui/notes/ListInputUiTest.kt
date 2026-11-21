package com.doro.organizer.ui.notes

import android.view.View
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.SearchText
import com.doro.organizer.session.GateState
import com.doro.organizer.testing.ScriptedGateComposeTest
import org.hamcrest.Description
import org.hamcrest.Matchers.allOf
import org.hamcrest.TypeSafeMatcher
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The list's input surfaces over a scripted gate (no database needed): the search fields keep every character of
 * quick typing, because their text is updated in the frame of the edit (ui.md § 3.3, § 7.2), and the list's sheet
 * and date pickers keep autofill out of their own windows through `SecureDialogWindow` (security.md § 11, DEC-71).
 */
@RunWith(AndroidJUnit4::class)
class ListInputUiTest : ScriptedGateComposeTest() {
    @Test
    fun listSearch_keepsEveryCharacter_ofQuickTyping_andItsCap() {
        scene.launch(GateState.Ready)
        ui.awaitShell()
        compose.onNodeWithContentDescription(SEARCH).performClick()
        val field = compose.onNodeWithTag(SEARCH_FIELD)

        field.performTextInput("weekly ")
        "budget".forEach { field.performTextInput(it.toString()) }
        field.assert(hasText("weekly budget"))

        field.performTextInput("x".repeat(SearchText.MAX_TERM_CHARS))
        field.assert(hasText(("weekly budget" + "x".repeat(SearchText.MAX_TERM_CHARS)).take(SearchText.MAX_TERM_CHARS)))
    }

    @Test
    fun pickerSearch_keepsEveryCharacter_ofQuickTyping() {
        scene.launch(GateState.Ready)
        ui.awaitShell()
        compose.onNodeWithTag(TAB_PEOPLE).performClick()
        val field = compose.onNodeWithTag(PICKER_SEARCH)

        field.performTextInput("Al")
        "ice".forEach { field.performTextInput(it.toString()) }

        field.assert(hasText("Alice"))
    }

    @Test
    fun dateFilterSheet_andItsEndPicker_excludeAutofill() {
        scene.launch(GateState.Ready)
        ui.awaitShell()

        compose.onNodeWithTag(DATE_CHIP).performClick()
        compose.onNodeWithText(FILTER_BY_DATE).assertExists()
        assertFocusedDialogExcludesAutofill()

        compose.onNodeWithText(FROM).performClick()
        compose.waitForIdle()
        assertFocusedDialogExcludesAutofill()
    }

    @Test
    fun rangePicker_excludesAutofill() {
        scene.launch(GateState.Ready)
        ui.awaitShell()
        compose.onNodeWithTag(TAB_CALENDAR).performClick()

        compose.onNodeWithText(SELECT_RANGE).performClick()
        compose.waitForIdle()

        assertFocusedDialogExcludesAutofill()
    }

    /** The dialog window that has focus, the topmost one, carries the exclusion on its own decor view. */
    private fun assertFocusedDialogExcludesAutofill() {
        onView(isRoot()).inRoot(allOf(isDialog(), withDecorView(hasWindowFocus()))).check { view, _ ->
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, view.rootView.importantForAutofill)
        }
    }

    private fun hasWindowFocus() = object : TypeSafeMatcher<View>() {
        override fun describeTo(description: Description) {
            description.appendText("has window focus")
        }

        override fun matchesSafely(item: View) = item.hasWindowFocus()
    }

    private companion object {
        const val SEARCH_FIELD = "search_field"
        const val PICKER_SEARCH = "picker_search"
        const val DATE_CHIP = "chip_Date"
        const val TAB_PEOPLE = "tab_Individual"
        const val TAB_CALENDAR = "tab_Date"

        // Literals copied from ui.md § 11.
        const val SEARCH = "Search"
        const val FILTER_BY_DATE = "Filter by date"
        const val FROM = "From"
        const val SELECT_RANGE = "Select range"
    }
}
