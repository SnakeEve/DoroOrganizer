package com.doro.organizer.ui.common

import androidx.lifecycle.SavedStateHandle
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
 * `EntityPickerViewModel` per purpose (ui.md § 7.5–§ 7.7; DEC-33, DEC-36): initial checks applied once, the create
 * row adding a checked new entity and clearing the field, the results with vanished ids dropped, the batch confirm
 * rule, the saved keys, and a loading state that ends once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntityPickerViewModelTest {
    private val people = MutableStateFlow(listOf(option(1, "Anna"), option(2, "Bob")))
    private val projects = MutableStateFlow(listOf(option(7, "Alpha")))
    private val entities = object : EntityRepository by unused() {
        override fun observeOptions(kind: EntityKind, coLinkedTo: Long?): Flow<List<EntityOption>> =
            if (kind == EntityKind.Person) people else projects
    }

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun editorLinks_startsWithTheDraftsLinksChecked_onlyWhenFirstCreated() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val vm = picker(PickerPurpose.EditorLinks(listOf(1)), handle)
        collect(vm)
        vm.toggle(1)
        vm.toggle(2)

        val restored = picker(PickerPurpose.EditorLinks(listOf(1)), handle)
        collect(restored)

        assertEquals(PickerResult.Links(EntityKind.Person, setOf(2), emptyList()), restored.confirm())
        assertArrayEquals(longArrayOf(2), handle.get<LongArray>(EntityPickerViewModel.KEY_CHECKED))
    }

    @Test
    fun theCreateRow_addsACheckedNewEntity_andClearsTheField() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val vm = picker(PickerPurpose.EditorLinks(emptyList()), handle)
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
        val vm = picker(PickerPurpose.EditorLinks(emptyList()))
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
        val vm = picker(PickerPurpose.EditorLinks(listOf(1, 2)))
        collect(vm)

        people.value = listOf(option(2, "Bob"))

        assertEquals(PickerResult.Links(EntityKind.Person, setOf(2), emptyList()), vm.confirm())
    }

    @Test
    fun batchAssociate_startsEmpty_confirmsOnceAnythingIsChosen_andReturnsBothKinds() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val vm = picker(PickerPurpose.BatchAssociate, handle)
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
        val vm = picker(PickerPurpose.EditorLinks(emptyList()))
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

    private fun TestScope.collect(vm: EntityPickerViewModel) {
        backgroundScope.launch { vm.uiState.collect {} }
    }

    private fun picker(purpose: PickerPurpose?, handle: SavedStateHandle = SavedStateHandle()) =
        EntityPickerViewModel(EntityKind.Person, handle, entities, FakeAppTime(), purpose)

    private fun option(id: Long, name: String) = EntityOption(id, name, createdAt = 0, noteCount = 0, coLinked = false)
}
