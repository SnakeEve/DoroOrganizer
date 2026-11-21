package com.doro.organizer.ui.notes

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.Perspective
import com.doro.organizer.testing.GateUi
import com.doro.organizer.testing.ListComposeTest
import com.doro.organizer.testing.ok
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-42 (g) and the end of I-38 (DEC-28, DEC-52; data-layer.md § Reactivity): a remembered person that is
 * gone, at a cold start or while the perspective shows it, brings back the picker and is forgotten.
 */
@RunWith(AndroidJUnit4::class)
class StaleIdUiTest : ListComposeTest() {
    @Test
    fun aRememberedPersonGoneAtColdStart_showsThePicker_andIsForgotten() {
        var alice = 0L
        startWith(settings = { setRemembered(EntityKind.Person, alice + 1_000) }) {
            alice = person("Alice", now)
        }

        openTab(Perspective.Individual)

        ui.awaitTag("picker_row_$alice")
        awaitForgotten()
    }

    @Test
    fun deletingTheRememberedPerson_returnsThePerspectiveToItsPicker() {
        var alice = 0L
        var note = 0L
        startWith(settings = { setRemembered(EntityKind.Person, alice) }) {
            alice = person("Alice", now)
            note = note("with Alice", createdAt = now).also { linkPerson(it, alice) }
        }
        openTab(Perspective.Individual)
        awaitRow(note)
        compose.onAllNodes(hasPaneTitle("Alice")).fetchSemanticsNodes().single()

        runBlocking { app.container.entityRepository.deletePerson(alice).ok() }

        ui.awaitTag("picker_search")
        awaitNoRow(note)
        awaitForgotten()
    }

    private fun awaitForgotten() {
        compose.waitUntil(GateUi.TIMEOUT_MS) { runBlocking { scene.settings.snapshot().rememberedPersonId == null } }
        assertNull(runBlocking { scene.settings.snapshot().rememberedPersonId })
    }

    private fun hasPaneTitle(title: String) = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)
}
