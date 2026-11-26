package com.doro.organizer.ui.common

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.NewEntity
import com.doro.organizer.model.Perspective
import com.doro.organizer.testing.EditorUi
import com.doro.organizer.testing.ListComposeTest
import com.doro.organizer.testing.OptionsProbe
import com.doro.organizer.testing.TestRows
import com.doro.organizer.testing.draft
import com.doro.organizer.testing.pickerRowTag
import com.doro.organizer.testing.saved
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * testing.md I-63 (DEC-112, Q18; ui.md § 4.2, § 7.4, § 7.5): "All people" / "All projects", the first row of the
 * People and Projects title's picker, returns the tab to its picker body and forgets the remembered choice of that tab
 * only. Planted: people Alice and Bob, projects Apollo and Gemini, and the notes Alice + Apollo, Alice, Bob + Apollo
 * and Bob + Gemini; Alice is remembered for People and Apollo for Projects in the scene's `FakeSettingsStore`, which a
 * restart keeps, as the DataStore file would. The entity repository's options collections are counted and can be held
 * ([OptionsProbe]).
 */
@RunWith(AndroidJUnit4::class)
class AllEntitiesUiTest : ListComposeTest() {
    private lateinit var options: OptionsProbe

    private var alice = 0L
    private var bob = 0L
    private var apollo = 0L
    private var gemini = 0L
    private var aliceApollo = 0L
    private var aliceOnly = 0L
    private var bobApollo = 0L
    private var bobGemini = 0L

    init {
        scene.wrapEntityRepository = { real -> OptionsProbe(real).also { options = it } }
    }

    @Test
    fun a_allPeople_isAButtonAboveThePeople_andShowsTheBody_forgettingThePersonOnly() {
        startPlanted()
        openTab(Perspective.Individual)
        openTitlePicker("Alice", CHOOSE_PERSON)

        compose.onNodeWithTag(PICKER_ALL).assertTextEquals(ALL_PEOPLE).assert(hasRole(Role.Button))
        val all = ui.boundsOf(PICKER_ALL)
        assertTrue("above Alice", all.bottom <= ui.boundsOf(pickerRowTag(alice)).top + 1)
        assertTrue("above Bob", all.bottom <= ui.boundsOf(pickerRowTag(bob)).top + 1)
        compose.onNodeWithTag(PICKER_ALL).performClick()

        awaitPickerBody(alice, bob)
        assertPlainTitle(PEOPLE, Perspective.Individual)
        val settings = runBlocking { scene.settings.snapshot() }
        assertNull("no person remembered", settings.rememberedPersonId)
        assertEquals("the project stays", apollo, settings.rememberedProjectId)
    }

    @Test
    fun b_afterAllPeople_aRestartStillShowsTheBody() {
        startPlanted()
        openTab(Perspective.Individual)
        chooseAll("Alice", CHOOSE_PERSON)
        awaitPickerBody(alice, bob)

        scene.restart()
        scene.coldStart()
        ui.awaitShell()
        openTab(Perspective.Individual)

        awaitPickerBody(alice, bob)
    }

    @Test
    fun c_aNoteCreatedOnTheBody_hasNoLink_theBodyStays_andAllShowsItOrphaned() {
        startPlanted()
        openTab(Perspective.Individual)
        chooseAll("Alice", CHOOSE_PERSON)
        awaitPickerBody(alice, bob)

        editor.createNote()
        compose.onNodeWithTag(EditorUi.CONTENT).performTextInput(WORD)
        awaitDetachedWork { compose.onNodeWithContentDescription(BACK).performClick() }

        awaitPickerBody(alice, bob)
        val created = count("SELECT MAX(id) FROM notes")
        assertEquals("person links", 0L, count("SELECT COUNT(*) FROM note_persons WHERE note_id = ?", created))
        assertEquals("project links", 0L, count("SELECT COUNT(*) FROM note_projects WHERE note_id = ?", created))
        openTab(Perspective.All)
        awaitRow(created)
        assertTrue(rowDescription(created), rowDescription(created).contains(ORPHANED))
    }

    @Test
    fun d_backFromTheTitlesPicker_keepsThePerson_andBackOnTheBody_goesToAll() {
        startPlanted()
        openTab(Perspective.Individual)
        openTitlePicker("Alice", CHOOSE_PERSON)

        ui.pressBack()
        awaitRow(aliceOnly)
        compose.onNode(titleButton("Alice")).assertExists()
        assertEquals(alice, runBlocking { scene.settings.snapshot() }.rememberedPersonId)

        chooseAll("Alice", CHOOSE_PERSON)
        awaitPickerBody(alice, bob)
        ui.pressBack()
        ui.awaitText(ALL_NOTES)
        awaitRow(bobGemini)
        openTab(Perspective.Individual)
        awaitPickerBody(alice, bob)
    }

    @Test
    fun e_allProjects_forgetsTheProjectOnly() {
        startPlanted()
        openTab(Perspective.Project)
        awaitRow(bobApollo)

        openTitlePicker("Apollo", CHOOSE_PROJECT)
        compose.onNodeWithTag(PICKER_ALL).assertTextEquals(ALL_PROJECTS).assert(hasRole(Role.Button))
        compose.onNodeWithTag(PICKER_ALL).performClick()

        awaitPickerBody(apollo, gemini)
        assertPlainTitle(PROJECTS, Perspective.Project)
        val settings = runBlocking { scene.settings.snapshot() }
        assertNull("no project remembered", settings.rememberedProjectId)
        assertEquals("the person stays", alice, settings.rememberedPersonId)
        openTab(Perspective.Individual)
        awaitRow(aliceOnly)
        compose.onNode(titleButton("Alice")).assertExists()
    }

    @Test
    fun f_theSecondaryFilter_staysForTheNextPerson() {
        startPlanted()
        openTab(Perspective.Individual)
        awaitRow(aliceOnly)
        compose.onNodeWithTag(PROJECT_CHIP).performClick()
        ui.awaitText(FILTER_BY_PROJECT)
        compose.onNodeWithTag(pickerRowTag(apollo)).performClick()
        awaitNoRow(aliceOnly)

        chooseAll("Alice", CHOOSE_PERSON)
        awaitPickerBody(alice, bob)
        compose.onNodeWithTag(pickerRowTag(bob)).performClick()

        awaitRow(bobApollo)
        assertEquals("Bob's Gemini note", 0, compose.onAllNodesWithTag(rowTag(bobGemini)).fetchSemanticsNodes().size)
        assertEquals("Project filter: Apollo", descriptionOf(PROJECT_CHIP))
    }

    @Test
    fun g_theBodysNameSearch_isEmptyAfterAllPeople() {
        startPlanted(rememberAlice = false)
        openTab(Perspective.Individual)
        awaitPickerBody(alice, bob)
        compose.onNodeWithTag(PICKER_SEARCH).performTextInput("Al")
        awaitNoPickerRow(bob)
        compose.onNodeWithTag(pickerRowTag(alice)).performClick()
        awaitRow(aliceOnly)

        chooseAll("Alice", CHOOSE_PERSON)

        awaitPickerBody(alice, bob)
        assertEquals("", pickerSearchText())
        compose.onNodeWithTag(pickerRowTag(bob)).assertIsDisplayed()
    }

    @Test
    fun h_aBodyScrolledToItsLastName_comesBackFromItsFirstName() {
        var last = 0L
        startPlanted(rememberAlice = false) {
            last = (1..MORE_PEOPLE).map { person("Person %02d".format(it), createdAt = now - MINUTE) }.last()
        }
        openTab(Perspective.Individual)
        awaitPickerBody(alice, bob)
        compose.onNode(hasScrollToKeyAction()).performScrollToKey(last)
        compose.onNodeWithTag(pickerRowTag(last)).performClick()
        ui.awaitText(NO_DATA)

        chooseAll("Person %02d".format(MORE_PEOPLE), CHOOSE_PERSON)

        awaitPickerBody()
        assertTopPickerRow(alice)
    }

    @Test
    fun i_aPersonAddedWhileTheBodyWasAway_isItsTopmostRow_withAnEmptySearch() {
        startPlanted(rememberAlice = false) { person("Albert", createdAt = now - MINUTE) }
        openTab(Perspective.Individual)
        awaitPickerBody(alice, bob)
        compose.onNodeWithTag(PICKER_SEARCH).performTextInput("Al")
        awaitNoPickerRow(bob)
        compose.onNodeWithTag(pickerRowTag(alice)).performClick()
        awaitRow(aliceOnly)
        compose.waitUntil("the body's upstream stopped", LONG_WAIT_MS) { options.active == 0 }
        val aaron = NewEntity("aaron", "Aaron")
        runBlocking { scene.app.container.noteRepository.save(draft(content = WORD, newPersons = listOf(aaron))).saved() }
        val aaronId = count("SELECT id FROM persons WHERE name = ?", "Aaron")
        openTitlePicker("Alice", CHOOSE_PERSON)
        ui.awaitTag(pickerRowTag(alice))
        val freshRead = options.holdNextFirstEmission()

        compose.onNodeWithTag(PICKER_ALL).performClick()

        awaitPickerBody(alice)
        assertEquals("", pickerSearchText())
        // The body has laid out the names it had before it left; only then does its fresh read, with Aaron, come in.
        compose.waitUntil("the body's read started", LONG_WAIT_MS) { freshRead.reached.isCompleted }
        freshRead.release()
        awaitPickerItem(aaronId)
        compose.waitForIdle()
        assertTopPickerRow(aaronId)
    }

    /**
     * Plants the people, projects and notes of I-63, with Apollo remembered for Projects and, with [rememberAlice],
     * Alice for People; [more] writes after them.
     */
    private fun startPlanted(rememberAlice: Boolean = true, more: TestRows.() -> Unit = {}) {
        startWith(
            settings = {
                if (rememberAlice) setRemembered(EntityKind.Person, alice)
                setRemembered(EntityKind.Project, apollo)
            },
        ) {
            val created = now - 10 * MINUTE
            alice = person("Alice", created)
            bob = person("Bob", created)
            apollo = project("Apollo", created)
            gemini = project("Gemini", created)
            aliceApollo = note("Alice and Apollo", createdAt = now - MINUTE).also {
                linkPerson(it, alice)
                linkProject(it, apollo)
            }
            aliceOnly = note("Alice alone", createdAt = now - 2 * MINUTE).also { linkPerson(it, alice) }
            bobApollo = note("Bob and Apollo", createdAt = now - 3 * MINUTE).also {
                linkPerson(it, bob)
                linkProject(it, apollo)
            }
            bobGemini = note("Bob and Gemini", createdAt = now - 4 * MINUTE).also {
                linkPerson(it, bob)
                linkProject(it, gemini)
            }
            more()
        }
    }

    /** "All people" / "All projects" from the title of the entity [name]. */
    private fun chooseAll(name: String, title: String) {
        openTitlePicker(name, title)
        compose.onNodeWithTag(PICKER_ALL).performClick()
    }

    private fun awaitNoPickerRow(id: Long) = compose.waitUntil(LONG_WAIT_MS) {
        compose.onAllNodesWithTag(pickerRowTag(id)).fetchSemanticsNodes().isEmpty()
    }

    /** The app bar's title reads [title] as plain text, not as a button (ui.md § 4.2); [tab]'s own label aside. */
    private fun assertPlainTitle(title: String, tab: Perspective) {
        compose.onNode(hasText(title) and !hasTestTag("tab_${tab.name}")).assertIsDisplayed()
        assertEquals("a title button", 0, compose.onAllNodes(titleButton(title)).fetchSemanticsNodes().size)
    }

    /** Waits until the picker list holds the item of key [key] (its `IndexForKey` semantics), on screen or not. */
    private fun awaitPickerItem(key: Long) = compose.waitUntil("item $key in the picker list", LONG_WAIT_MS) {
        compose.onNode(hasScrollToKeyAction()).fetchSemanticsNode().config[SemanticsProperties.IndexForKey](key) >= 0
    }

    private fun pickerSearchText(): String = compose.onNodeWithTag(PICKER_SEARCH).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private companion object {
        const val PICKER_SEARCH = EditorUi.PICKER_SEARCH
        const val PROJECT_CHIP = "chip_Project"
        const val MORE_PEOPLE = 30
        const val MINUTE = 60_000L
        const val WORD = "word"
        const val LONG_WAIT_MS = 20_000L

        // Literals copied from ui.md § 11.
        const val ALL_PEOPLE = "All people"
        const val ALL_PROJECTS = "All projects"
        const val PEOPLE = "People"
        const val PROJECTS = "Projects"
        const val FILTER_BY_PROJECT = "Filter by project"
        const val ALL_NOTES = "All notes"
        const val BACK = "Back"
    }
}
