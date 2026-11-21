package com.doro.organizer.perf

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.NoteFilter
import com.doro.organizer.model.Perspective
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.ListComposeTest
import com.doro.organizer.testing.NoteOracle
import com.doro.organizer.testing.PerfBudget
import com.doro.organizer.testing.PerfRecorder
import com.doro.organizer.testing.PerfStatistic
import com.doro.organizer.testing.PerfTest
import com.doro.organizer.testing.SeedGenerator
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md P-2 on the 10,000-note seed: from `performClick()` on a tab to the first frame that shows the target
 * list's first row, inside trace section `doro:switch`, within 250 ms median (architecture.md § Performance budgets).
 * All's first note is no orphan, so its row going away proves the Orphan list replaced All's.
 */
@PerfTest
@RunWith(AndroidJUnit4::class)
class PerspectiveSwitchPerfTest : ListComposeTest() {
    @get:Rule(order = -1)
    val perf = PerfRecorder()

    @Test
    fun tabSwitch_showsTheTargetListWithinBudget() {
        val model = SeedGenerator().generate()
        val allFirst = NoteOracle.listIds(model, NoteFilter(Perspective.All)).first()
        val orphans = NoteOracle.listIds(model, NoteFilter(Perspective.Orphan))
        assertFalse(allFirst in orphans)
        startWith { seed(model) }

        perf.measure("All to Orphan", SWITCH_BUDGET, SECTION, prepare = { show(Perspective.All, allFirst) }) {
            tapTab(Perspective.Orphan)
            awaitFrame { has(orphans.first()) && !has(allFirst) }
        }
        perf.measure("Orphan to All", SWITCH_BUDGET, SECTION, prepare = { show(Perspective.Orphan, orphans.first()) }) {
            tapTab(Perspective.All)
            awaitFrame { has(allFirst) }
        }
    }

    private fun show(perspective: Perspective, firstRow: Long) {
        openTab(perspective)
        awaitRow(firstRow)
    }

    private fun tapTab(perspective: Perspective) = compose.onNodeWithTag("tab_${perspective.name}").performClick()

    private fun has(id: Long) = compose.onAllNodesWithTag(rowTag(id)).fetchSemanticsNodes().isNotEmpty()

    private fun awaitFrame(condition: () -> Boolean) = compose.waitUntil(GateUi.TIMEOUT_MS, condition)

    private companion object {
        const val SECTION = "doro:switch"
        val SWITCH_BUDGET = PerfBudget(250, PerfStatistic.Median)
    }
}
