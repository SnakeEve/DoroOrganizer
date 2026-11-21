package com.doro.organizer.ui.navigation

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.Perspective
import com.doro.organizer.testing.ListComposeTest
import com.doro.organizer.testing.pickerRowTag
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-38, the pickers' part (DEC-28, DEC-52; ui.md § 3.4, § 4.2, § 7.5): after a pick the Individual title
 * shows the entity and reopens the picker, Back from it keeps the entity, and a chip's pick sets its filter. Over the
 * real database, so it is a list test; the shell's start, actions and Back are `NavigationTest`.
 */
@RunWith(AndroidJUnit4::class)
class PickerNavigationTest : ListComposeTest() {
    @Test
    fun afterAPick_theTitleShowsTheEntity_andReopensThePicker() {
        var alice = 0L
        var bob = 0L
        var bobsNote = 0L
        startWith(settings = { setRemembered(EntityKind.Person, alice) }) {
            alice = person("Alice", now)
            bob = person("Bob", now)
            note("with Alice", createdAt = now).also { linkPerson(it, alice) }
            bobsNote = note("with Bob", createdAt = now - 1).also { linkPerson(it, bob) }
        }
        openTab(Perspective.Individual)
        ui.awaitText("Alice")
        assertEquals("Change person", clickLabelOf(titleButton("Alice")))

        compose.onNode(titleButton("Alice")).performClick()
        ui.awaitText("Choose a person")
        compose.onNodeWithTag(pickerRowTag(bob)).performClick()

        awaitRow(bobsNote)
        compose.onNode(titleButton("Bob")).performClick()
        ui.awaitText("Choose a person")
        ui.pressBack()
        awaitRow(bobsNote)
        compose.onNode(titleButton("Bob")).assertExists()
    }

    @Test
    fun aChipsPick_setsItsFilter_andAnyClearsIt() {
        var alpha = 0L
        var inAlpha = 0L
        var other = 0L
        startWith {
            alpha = project("Alpha", now)
            inAlpha = note("in Alpha", createdAt = now).also { linkProject(it, alpha) }
            other = note("other", createdAt = now - 1)
        }
        awaitRow(other)

        compose.onNodeWithTag("chip_Project").performClick()
        ui.awaitText("Filter by project")
        compose.onNodeWithTag(pickerRowTag(alpha)).performClick()
        awaitNoRow(other)
        awaitRow(inAlpha)

        compose.onNodeWithTag("chip_Project").performClick()
        editor.button("Any project").performClick()
        awaitRow(other)
        assertEquals("Project filter: Any", descriptionOf("chip_Project"))
    }

    private fun titleButton(name: String) = SemanticsMatcher("title button $name") { node ->
        node.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == name }
    }

    private fun clickLabelOf(matcher: SemanticsMatcher): String? =
        compose.onNode(matcher).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label
}
