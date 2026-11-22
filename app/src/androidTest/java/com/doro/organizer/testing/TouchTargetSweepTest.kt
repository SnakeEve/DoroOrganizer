package com.doro.organizer.testing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What [GateUi.assertEveryClickableHasATouchTarget] measures (testing.md I-35): the touch bounds a finger gets. It
 * measures a clickable that a scroll container hides wholly once it is scrolled into view, where Compose reports 0 × 0
 * touch bounds for it while it is hidden; it still fails a clickable that a non-scrolling clip hides wholly; and it
 * passes a visible clickable laid out smaller than 48 dp, whose touch bounds Compose pads to 48 dp (Q13).
 */
@RunWith(AndroidJUnit4::class)
class TouchTargetSweepTest : ScriptedGateComposeTest() {
    @Test
    fun buttonsAScrollingColumnHidesWholly_areMeasuredInView_andPass() {
        showAlone {
            Column(Modifier.height(120.dp).verticalScroll(rememberScrollState())) {
                repeat(6) { Button(onClick = {}) { Text("$it") } }
            }
        }
        val hidden = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { it.touchBoundsInRoot.isEmpty }
        assertTrue("a button lies wholly below the column's visible part", hidden.isNotEmpty())

        ui.assertEveryClickableHasATouchTarget("scrolling column")
    }

    @Test
    fun aButtonANonScrollingClipHidesWholly_fails() {
        showAlone {
            Box(Modifier.size(100.dp).clipToBounds()) {
                Button(onClick = {}, Modifier.offset(y = 150.dp)) { Text("x") }
            }
        }

        val failure = assertThrows(AssertionError::class.java) { ui.assertEveryClickableHasATouchTarget("clipped box") }
        assertTrue(failure.message, failure.message.orEmpty().startsWith("clipped box: under 48 dp"))
    }

    /**
     * Q13, DEC-55: a clickable laid out at 24 dp, with room around it, gets 48 dp touch bounds from Compose and passes;
     * the touch area counts, and its own layout size need not be 48 dp.
     */
    @Test
    fun aVisibleClickableLaidOutSmallerThan48dp_passes_onItsPaddedTouchBounds() {
        showAlone {
            Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(24.dp).testTag(SMALL).clickable {})
            }
        }
        val node = compose.onNodeWithTag(SMALL).fetchSemanticsNode()
        val minimum = with(node.layoutInfo.density) { 48.dp.toPx() }
        assertTrue("laid out under 48 dp: ${node.size}", node.size.width < minimum && node.size.height < minimum)

        ui.assertEveryClickableHasATouchTarget("small clickable")
        compose.onNodeWithTag(SMALL).assertTouchTarget()
    }

    private companion object {
        const val SMALL = "small_clickable"
    }
}
