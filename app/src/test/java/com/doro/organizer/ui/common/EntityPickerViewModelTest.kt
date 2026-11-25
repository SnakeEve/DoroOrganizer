package com.doro.organizer.ui.common

import androidx.lifecycle.SavedStateHandle
import com.doro.organizer.R
import com.doro.organizer.data.EntityRepository
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.EntityOption
import com.doro.organizer.model.NewEntity
import com.doro.organizer.testing.FakeAppTime
import com.doro.organizer.testing.unused
import com.doro.organizer.ui.navigation.PickerPurpose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `EntityPickerViewModel` per purpose (ui.md § 7.4–§ 7.7; DEC-28, DEC-33, DEC-36, DEC-111): initial checks applied
 * once, the create row adding a checked new entity and clearing the field, the results with vanished ids dropped, the
 * batch confirm rule, the saved keys, a loading state that ends once, what each purpose asks the repository to flag,
 * and each id's group fixed when the id is first listed (testing.md I-61 (c), I-62 (d)).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntityPickerViewModelTest {
    private val people = MutableStateFlow(listOf(option(1, "Anna"), option(2, "Bob")))
    private val projects = MutableStateFlow(listOf(option(7, "Alpha")))

    /** Every `observeOptions` call; an empty `coLinkedTo` flags nothing, as the repository's statement does. */
    private val asked = mutableListOf<Asked>()
    private var optionCollections = 0
    private val entities = object : EntityRepository by unused() {
        override fun observeOptions(kind: EntityKind, coLinkedTo: List<Long>, exceptNoteId: Long?): Flow<List<EntityOption>> {
            asked += Asked(kind, coLinkedTo, exceptNoteId)
            val options = if (kind == EntityKind.Person) people else projects
            val flagged = if (coLinkedTo.isEmpty()) options.map { list -> list.map { it.copy(coLinked = false) } } else options
            return flagged.onStart { optionCollections++ }
        }
    }

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun editorLinks_startsWithTheDraftsLinksChecked_onlyWhenFirstCreated() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val vm = picker(PickerPurpose.EditorLinks(listOf(1), emptyList(), null, 0), handle)
        collect(vm)
        vm.toggle(1)
        vm.toggle(2)

        val restored = picker(PickerPurpose.EditorLinks(listOf(1), emptyList(), null, 0), handle)
        collect(restored)

        assertEquals(PickerResult.Links(EntityKind.Person, setOf(2), emptyList()), restored.confirm())
        assertArrayEquals(longArrayOf(2), handle.get<LongArray>(EntityPickerViewModel.KEY_CHECKED))
    }

    @Test
    fun theCreateRow_addsACheckedNewEntity_andClearsTheField() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val vm = picker(PickerPurpose.EditorLinks(emptyList(), emptyList(), null, 0), handle)
        collect(vm)

        vm.onSearchChange("  Carla ")
        vm.create()

        assertEquals("", vm.search)
        val created = (vm.uiState.value.items.single { it is PickerItem.New } as PickerItem.New).entity
        assertEquals("Carla", created.name)
        assertEquals(1, vm.uiState.value.chosen[EntityKind.Person])
        assertEquals(arrayListOf("Carla"), handle.get<ArrayList<String>>(EntityPickerViewModel.KEY_NEW_NAMES))
        assertEquals(PickerResult.Links(EntityKind.Person, emptySet(), listOf(created)), vm.confirm())
    }

    @Test
    fun blankText_createsNothing_andUncheckingANewEntityDiscardsIt() = runTest(dispatcher) {
        val vm = picker(PickerPurpose.EditorLinks(emptyList(), emptyList(), null, 0))
        collect(vm)
        vm.onSearchChange("   ")
        vm.create()
        assertEquals("   ", vm.search)

        vm.onSearchChange("Dora")
        vm.create()
        val key = (vm.uiState.value.items.single { it is PickerItem.New } as PickerItem.New).entity.key
        vm.discard(key)

        assertTrue(vm.uiState.value.items.none { it is PickerItem.New })
        assertEquals(PickerResult.Links(EntityKind.Person, emptySet(), emptyList()), vm.confirm())
    }

    @Test
    fun theSearchField_keepsAtMost200Characters() = runTest(dispatcher) {
        val vm = picker(null)

        vm.onSearchChange("x".repeat(250))

        assertEquals(200, vm.search.length)
    }

    @Test
    fun aCheckedEntityThatVanished_isDroppedFromTheResult() = runTest(dispatcher) {
        val vm = picker(PickerPurpose.EditorLinks(listOf(1, 2), emptyList(), null, 0))
        collect(vm)

        people.value = listOf(option(2, "Bob"))

        assertEquals(PickerResult.Links(EntityKind.Person, setOf(2), emptyList()), vm.confirm())
    }

    @Test
    fun batchAssociate_startsEmpty_confirmsOnceAnythingIsChosen_andReturnsBothKinds() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val vm = picker(PickerPurpose.BatchAssociate(selectionSaved = true, coLinkedTo = null), handle)
        collect(vm)
        assertFalse(vm.uiState.value.canConfirm)

        vm.toggle(1)
        vm.selectTab(EntityKind.Project)
        vm.onSearchChange("Beta")
        vm.create()

        val state = vm.uiState.value
        assertTrue(state.canConfirm)
        assertEquals(EntityKind.Project, state.tab)
        assertEquals(mapOf(EntityKind.Person to 1, EntityKind.Project to 1), state.chosen)
        val result = vm.confirm() as PickerResult.Associate
        assertEquals(setOf(1L), result.personIds)
        assertEquals(emptySet<Long>(), result.projectIds)
        assertEquals(emptyList<NewEntity>(), result.newPersons)
        assertEquals(listOf("Beta"), result.newProjects.map { it.name })
        assertEquals("Project", handle.get<String>(EntityPickerViewModel.KEY_TAB))
        assertEquals(arrayListOf("Beta"), handle.get<ArrayList<String>>("${EntityPickerViewModel.KEY_NEW_NAMES}_Project"))
    }

    @Test
    fun aPauseLongerThanTheSharingTimeout_neverBringsBackTheLoadingState() = runTest(dispatcher) {
        val vm = picker(PickerPurpose.EditorLinks(emptyList(), emptyList(), null, 0))
        val seen = mutableListOf<Boolean>()

        backgroundScope.launch { vm.uiState.collect { seen += it.loading } }.cancel()
        advanceTimeBy(6_000)
        backgroundScope.launch { vm.uiState.collect { seen += it.loading } }

        assertEquals("loading after it ended", emptyList<Boolean>(), seen.dropWhile { it }.filter { it })
        assertEquals(2, vm.uiState.value.items.size)
    }

    @Test
    fun singleSelectTaps_returnTheirPurposesResult() = runTest(dispatcher) {
        assertEquals(PickerResult.Primary(EntityKind.Person, 2), picker(PickerPurpose.PerspectivePrimary(1)).pick(2))
        assertEquals(PickerResult.Filter(EntityKind.Person, null), picker(PickerPurpose.SecondaryFilter(1, null)).pick(null))
        assertEquals(PickerResult.Delete(EntityKind.Person, 1), picker(PickerPurpose.DeleteEntity(emptyList())).pick(1))
    }

    @Test
    fun theEmbeddedBody_savesOnlyItsSearch() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val vm = picker(null, handle)
        collect(vm)

        vm.onSearchChange("an")

        assertEquals(setOf(EntityPickerViewModel.KEY_SEARCH), handle.keys())
        assertEquals(listOf(1L), vm.uiState.value.items.map { (it as PickerItem.Entity).row.id })
        assertNull(handle.get<String>(EntityPickerViewModel.KEY_TAB))
    }

    // The groups (ui.md § 7.4, DEC-111).

    @Test
    fun eachPurpose_asksForItsCoLinks_andEveryOtherAsksForNone() = runTest(dispatcher) {
        fun askedBy(purpose: PickerPurpose?, kind: EntityKind = EntityKind.Person): List<Asked> {
            asked.clear()
            EntityPickerViewModel(kind, SavedStateHandle(), entities, FakeAppTime(), purpose)
            return asked.toList()
        }
        val person = EntityKind.Person
        val project = EntityKind.Project

        val none = listOf(Asked(person, emptyList(), null))
        assertEquals(listOf(Asked(person, listOf(7, 9), 4)), askedBy(PickerPurpose.EditorLinks(listOf(1), listOf(7, 9), 4, 2)))
        assertEquals("no stored project", none, askedBy(PickerPurpose.EditorLinks(listOf(1), emptyList(), null, 1)))
        assertEquals("no project", none, askedBy(PickerPurpose.EditorLinks(listOf(1), listOf(7), 4, 0)))
        assertEquals(
            "Add project, whatever the key holds (Q20)",
            listOf(Asked(project, emptyList(), null)),
            askedBy(PickerPurpose.EditorLinks(listOf(1), listOf(7), 4, 1), project),
        )
        assertEquals(
            "People tab only (Q20)",
            listOf(Asked(person, listOf(7), null), Asked(project, emptyList(), null)),
            askedBy(PickerPurpose.BatchAssociate(selectionSaved = true, coLinkedTo = 7)),
        )
        assertEquals(listOf(Asked(project, listOf(2), null)), askedBy(PickerPurpose.SecondaryFilter(null, coLinkedTo = 2), project))
        val others = listOf(
            PickerPurpose.PerspectivePrimary(1),
            PickerPurpose.DeleteEntity(listOf(1)),
            PickerPurpose.SecondaryFilter(1, null),
            PickerPurpose.BatchAssociate(selectionSaved = true, coLinkedTo = null),
            null,
        )
        others.forEach { purpose ->
            val expected = if (purpose is PickerPurpose.BatchAssociate) none + Asked(project, emptyList(), null) else none
            assertEquals("$purpose", expected, askedBy(purpose))
        }
    }

    @Test
    fun thePurposesWithoutAGroup_listInRepositoryOrder_withoutAHeader_whateverTheirKeysHold() = runTest(dispatcher) {
        // Flagged and selected entities that sort last: a first group would move them up.
        people.value = listOf(option(1, "Anna"), option(2, "Bob", coLinked = true))
        projects.value = listOf(option(7, "Alpha"), option(8, "Beta", coLinked = true))
        val noProject = picker(PickerPurpose.EditorLinks(listOf(2), listOf(7), 4, projectCount = 0))
        val addProject = EntityPickerViewModel(
            EntityKind.Project,
            SavedStateHandle(),
            entities,
            FakeAppTime(),
            PickerPurpose.EditorLinks(listOf(8), listOf(1), 4, projectCount = 1),
        )
        val associate = picker(PickerPurpose.BatchAssociate(selectionSaved = true, coLinkedTo = 7))
        listOf(noProject, addProject, associate).forEach { collect(it) }
        associate.selectTab(EntityKind.Project)

        assertEquals("Add person, projectCount 0", listOf(listOf(1L, 2L)), groups(noProject))
        assertEquals("Add project (Q20)", listOf(listOf(7L, 8L)), groups(addProject))
        assertEquals("Associate's Projects tab (Q20)", listOf(listOf(7L, 8L)), groups(associate))
        listOf(noProject, addProject, associate).forEach { assertEquals(emptyList<Int>(), headers(it)) }
    }

    @Test
    fun addPerson_putsTheFlaggedAndTheSelectedInTheFirstGroup() = runTest(dispatcher) {
        people.value = listOf(option(1, "Anna"), option(2, "Bob", coLinked = true), option(3, "Carla"))
        val vm = picker(addPerson(selected = listOf(1)))
        collect(vm)

        assertEquals(listOf(listOf(1L, 2L), listOf(3L)), groups(vm))
        assertEquals(R.string.picker_section_note_project, headers(vm).first())
    }

    @Test
    fun aGroup_isFixedWhenTheIdIsFirstListed_soLaterFlagsAndTicksMoveNobody() = runTest(dispatcher) {
        people.value = listOf(option(1, "Anna"), option(2, "Bob", coLinked = true), option(3, "Carla"))
        val vm = picker(addPerson(selected = emptyList()))
        collect(vm)

        // Another write while the picker is open: Bob loses his flag and Carla gains one; a tick changes nothing either.
        people.value = listOf(option(1, "Anna"), option(2, "Bob"), option(3, "Carla", coLinked = true))
        vm.toggle(1)

        assertEquals(listOf(listOf(2L), listOf(1L, 3L)), groups(vm))
    }

    @Test
    fun aPersonFirstListedLater_isGroupedByItsFlagThen_whetherAFirstListCameBefore() = runTest(dispatcher) {
        val before = listOf(option(1, "Anna"), option(2, "Bob", coLinked = true))
        val after = before + listOf(option(3, "Dora", coLinked = true), option(4, "Eve"))
        people.value = before
        val late = picker(addPerson(selected = emptyList()))
        collect(late)
        people.value = after
        val atOnce = picker(addPerson(selected = emptyList()))
        collect(atOnce)

        assertEquals(listOf(listOf(2L, 3L), listOf(1L, 4L)), groups(late))
        assertEquals(groups(late), groups(atOnce))
    }

    @Test
    fun theGroups_surviveAPauseLongerThanTheSharingTimeout() = runTest(dispatcher) {
        people.value = listOf(option(1, "Anna"), option(2, "Bob", coLinked = true))
        val vm = picker(addPerson(selected = emptyList()))
        backgroundScope.launch { vm.uiState.collect {} }.cancel()

        advanceTimeBy(6_000) // the upstream stops, then restarts with the next collector
        people.value = listOf(option(1, "Anna", coLinked = true), option(2, "Bob"))
        collect(vm)

        assertEquals("the upstream restarted", 2, optionCollections)
        assertEquals(listOf(listOf(2L), listOf(1L)), groups(vm))
    }

    @Test
    fun aPickerRebuiltFromTheSameSavedState_formsItsGroupsAgain_fromItsOwnLists() = runTest(dispatcher) {
        people.value = listOf(option(1, "Anna"), option(2, "Bob", coLinked = true))
        val handle = SavedStateHandle()
        val first = picker(addPerson(selected = emptyList()), handle)
        collect(first)
        first.toggle(1)
        assertEquals("the groups are no saved key", setOf(EntityPickerViewModel.KEY_CHECKED), handle.keys())

        // Android closed the app; another write changed Anna's and Bob's links meanwhile.
        people.value = listOf(option(1, "Anna", coLinked = true), option(2, "Bob"))
        val rebuiltHandle = SavedStateHandle(handle.toMap())
        val rebuilt = picker(addPerson(selected = emptyList()), rebuiltHandle)
        collect(rebuilt)

        assertEquals(listOf(listOf(1L), listOf(2L)), groups(rebuilt))
        assertEquals(setOf(EntityPickerViewModel.KEY_CHECKED), rebuiltHandle.keys())
    }

    @Test
    fun anIdOfSelectedIds_startsCheckedInTheFirstGroup_andUntickedLeavesTheLinksResult() = runTest(dispatcher) {
        // Dora, saved before the picker opened (DEC-111 (7)), is on this note only: not flagged, but selected.
        people.value = listOf(option(1, "Anna"), option(2, "Bob", coLinked = true), option(4, "Dora"))
        val vm = picker(addPerson(selected = listOf(4)))
        collect(vm)

        assertEquals(listOf(listOf(2L, 4L), listOf(1L)), groups(vm))
        assertTrue(vm.uiState.value.items.filterIsInstance<PickerItem.Entity>().single { it.row.id == 4L }.checked)
        vm.toggle(4)
        assertEquals(listOf(listOf(2L, 4L), listOf(1L)), groups(vm))
        assertEquals(PickerResult.Links(EntityKind.Person, emptySet(), emptyList()), vm.confirm())
    }

    @Test
    fun associateInProject_groupsThePeopleTabOnly() = runTest(dispatcher) {
        people.value = listOf(option(1, "Anna"), option(2, "Bob", coLinked = true))
        projects.value = listOf(option(7, "Alpha", coLinked = true), option(8, "Beta"))
        val vm = picker(PickerPurpose.BatchAssociate(selectionSaved = true, coLinkedTo = 7))
        collect(vm)

        assertEquals(listOf(listOf(2L), listOf(1L)), groups(vm))
        assertEquals(R.string.picker_section_co_people, headers(vm).first())
        vm.selectTab(EntityKind.Project)
        assertEquals("no group (Q20)", listOf(listOf(7L, 8L)), groups(vm))
    }

    @Test
    fun theSecondaryFilters_fixTheirGroupsTheSameWay() = runTest(dispatcher) {
        people.value = listOf(option(1, "Anna"), option(2, "Bob", coLinked = true))
        val vm = picker(PickerPurpose.SecondaryFilter(selectedId = null, coLinkedTo = 7))
        collect(vm)

        people.value = listOf(option(1, "Anna", coLinked = true), option(2, "Bob"))

        assertEquals(listOf(listOf(2L), listOf(1L)), groups(vm))
        assertEquals(R.string.picker_section_co_people, headers(vm).first())
    }

    // DEC-57: an Associate picker that outlived the list's selection.

    @Test
    fun batchAssociate_overAnUnsavedSelection_isLostOnlyWhenRebuiltFromItsSavedState() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val opened = picker(PickerPurpose.BatchAssociate(selectionSaved = false, coLinkedTo = null), handle)
        assertFalse("a new picker has its selection", opened.selectionLost)

        // Android closed the app: a new ViewModel over the same saved state, as the entry restores it.
        val rebuilt = picker(PickerPurpose.BatchAssociate(selectionSaved = false, coLinkedTo = null), SavedStateHandle(handle.toMap()))

        assertTrue(rebuilt.selectionLost)
    }

    @Test
    fun batchAssociate_overASavedSelection_isNeverLost_andSavesNoMarker() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        picker(PickerPurpose.BatchAssociate(selectionSaved = true, coLinkedTo = null), handle)

        val rebuilt = picker(PickerPurpose.BatchAssociate(selectionSaved = true, coLinkedTo = null), SavedStateHandle(handle.toMap()))

        assertFalse(rebuilt.selectionLost)
        assertFalse(handle.contains(EntityPickerViewModel.KEY_OPENED))
    }

    private fun SavedStateHandle.toMap(): Map<String, Any?> = keys().associateWith { get<Any?>(it) }

    private fun TestScope.collect(vm: EntityPickerViewModel) {
        backgroundScope.launch { vm.uiState.collect {} }
    }

    private fun picker(purpose: PickerPurpose?, handle: SavedStateHandle = SavedStateHandle()) =
        EntityPickerViewModel(EntityKind.Person, handle, entities, FakeAppTime(), purpose)

    /** Add person on a stored note 4 with project 7 (DEC-111 (1)). */
    private fun addPerson(selected: List<Long>) = PickerPurpose.EditorLinks(selected, listOf(7), 4, 1)

    /** The entity ids of the current tab, per group between the headers. */
    private fun groups(vm: EntityPickerViewModel): List<List<Long>> =
        vm.uiState.value.items.fold(listOf<List<Long>>()) { groups, item ->
            when (item) {
                is PickerItem.Header -> groups + listOf(emptyList())
                is PickerItem.Entity -> groups.dropLast(1) + listOf(groups.lastOrNull().orEmpty() + item.row.id)
                else -> groups
            }
        }.filter { it.isNotEmpty() }

    private fun headers(vm: EntityPickerViewModel): List<Int> =
        vm.uiState.value.items.filterIsInstance<PickerItem.Header>().map { it.text }

    private data class Asked(val kind: EntityKind, val coLinkedTo: List<Long>, val exceptNoteId: Long?)

    private fun option(id: Long, name: String, coLinked: Boolean = false) =
        EntityOption(id, name, createdAt = 0, noteCount = 0, coLinked = coLinked)
}
