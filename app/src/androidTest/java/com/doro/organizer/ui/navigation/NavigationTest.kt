package com.doro.organizer.ui.navigation

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.doro.organizer.model.Perspective
import com.doro.organizer.session.GateState
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.ScriptedGateComposeTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-38, the shell's and the lists' part (DEC-50, DEC-51, architecture.md § Back behavior): the shell starts on All;
 * every perspective has the Search action; each tab keeps its own search; Back closes an open search first, then from
 * the root of another tab returns to All and keeps that tab; Back at the root of All is left to the system. The saved
 * list state is `NoteListViewModelTest`, the remembered entity `StaleIdUiTest`; the Settings action, the editor and
 * the pickers come with SPEC T18 and T19.
 */
@RunWith(AndroidJUnit4::class)
class NavigationTest : ScriptedGateComposeTest() {
    @Test
    fun theShell_startsOnAll() {
        scene.launch(GateState.Ready)
        ui.awaitShell()
        compose.onNodeWithTag(GateUi.TAB_ALL).assertIsSelected()
        compose.onNodeWithText(ALL_NOTES).assertExists()
    }

    @Test
    fun back_fromAnotherTabsRoot_returnsToAll_andKeepsThatTab() {
        scene.launch(GateState.Ready)
        ui.awaitShell()
        compose.onNodeWithTag(TAB_PROJECT).performClick()
        compose.onNode(hasPaneTitle(PROJECTS)).assertExists()
        assertTrue("NavDisplay takes Back from the Project root", backIsIntercepted())

        ui.pressBack()

        compose.onNodeWithTag(GateUi.TAB_ALL).assertIsSelected()
        compose.onNodeWithTag(TAB_PROJECT).assertIsNotSelected()
        compose.onNodeWithText(ALL_NOTES).assertExists()
        compose.onNodeWithTag(TAB_PROJECT).performClick()
        compose.onNode(hasPaneTitle(PROJECTS)).assertExists()
    }

    @Test
    fun back_atTheRootOfAll_isLeftToTheSystem() {
        scene.launch(GateState.Ready)
        ui.awaitShell()
        assertFalse("NavDisplay holds one entry and takes no Back", backIsIntercepted())

        val paused = CountDownLatch(1)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(activity: Activity) = paused.countDown()
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        try {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            assertTrue("the system closed or moved back the task", paused.await(GateUi.TIMEOUT_MS, TimeUnit.MILLISECONDS))
        } finally {
            app.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }

    @Test
    fun theSearchAction_isOnEveryPerspective() {
        scene.launch(GateState.Ready)
        ui.awaitShell()

        for (p in Perspective.entries) {
            compose.onNodeWithTag("tab_${p.name}").performClick()
            compose.onNodeWithContentDescription(SEARCH).performClick()
            if (p == Perspective.Individual || p == Perspective.Project) {
                // Nothing is chosen, so Search goes to the picker's own name field (ui.md § 2.2, § 4.2).
                compose.onNodeWithTag(PICKER_SEARCH).assertIsFocused()
            } else {
                compose.onNodeWithTag(SEARCH_FIELD).assertIsFocused()
                ui.pressBack()
                compose.onNodeWithTag(SEARCH_FIELD).assertDoesNotExist()
                compose.onNodeWithTag("tab_${p.name}").assertIsSelected()
            }
        }
    }

    @Test
    fun eachTab_keepsItsOwnSearch() {
        scene.launch(GateState.Ready)
        ui.awaitShell()
        search(Perspective.All, "first")
        search(Perspective.Orphan, "second")

        compose.onNodeWithTag(GateUi.TAB_ALL).performClick()
        compose.onNodeWithTag(SEARCH_FIELD).assert(hasText("first"))
        compose.onNodeWithTag(TAB_ORPHAN).performClick()
        compose.onNodeWithTag(SEARCH_FIELD).assert(hasText("second"))
    }

    private fun search(p: Perspective, text: String) {
        compose.onNodeWithTag("tab_${p.name}").performClick()
        compose.onNodeWithContentDescription(SEARCH).performClick()
        compose.onNodeWithTag(SEARCH_FIELD).performTextInput(text)
    }

    /** The tab root whose screen is titled [title] (its `paneTitle`, ui.md § 2.2); its label is on the tab too. */
    private fun hasPaneTitle(title: String) = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)

    /** Whether a callback of the activity's `OnBackPressedDispatcher` takes the next Back. */
    private fun backIsIntercepted(): Boolean {
        compose.waitForIdle()
        var enabled = false
        scene.onActivity { enabled = it.onBackPressedDispatcher.hasEnabledCallbacks() }
        return enabled
    }

    private companion object {
        const val TAB_PROJECT = "tab_Project"
        const val TAB_ORPHAN = "tab_Orphan"
        const val SEARCH_FIELD = "search_field"
        const val PICKER_SEARCH = "picker_search"

        // Literals copied from ui.md § 11.
        const val ALL_NOTES = "All notes"
        const val PROJECTS = "Projects"
        const val SEARCH = "Search"
    }
}
