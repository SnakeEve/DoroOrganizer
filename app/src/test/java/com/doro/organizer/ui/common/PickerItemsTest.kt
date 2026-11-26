package com.doro.organizer.ui.common

import com.doro.organizer.R
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.EntityOption
import com.doro.organizer.model.NewEntity
import com.doro.organizer.ui.navigation.PickerPurpose
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The arrangement of the entity picker per purpose (ui.md § 7.3, § 7.4, § 7.6; DEC-28, DEC-32–DEC-34, DEC-111;
 * testing.md I-61 (d)): the "Any" and create rows, the entities created in this visit, the headed groups split by the
 * given first group, and the name search over all of them. The options arrive in repository order, which the picker
 * never changes.
 */
class PickerItemsTest {
    private val anna = option(1, "Anna")
    private val bob = option(2, "Bob", coLinked = true)
    private val carla = option(3, "Carla")
    private val options = listOf(bob, anna, carla)

    @Test
    fun editorLinks_listsTheCreateRow_thenCreated_thenEveryEntityChecked() {
        val zed = NewEntity("k1", "Zed")

        val items = items(addPerson(projectCount = 0), search = " ", checked = setOf(1), created = listOf(zed))

        assertEquals(
            listOf(PickerItem.New(zed), entity(bob, false), entity(anna, true), entity(carla, false)),
            items,
        )
    }

    @Test
    fun addPerson_onANoteWithOneProject_headsTheFirstGroup_belowTheCreateRowAndTheCreated() {
        val dora = NewEntity("k1", "Dora")

        val items = items(addPerson(projectCount = 1), search = "a", created = listOf(dora), firstGroup = setOf(3))

        assertEquals(
            listOf(
                PickerItem.Create("a", another = false),
                PickerItem.New(dora),
                PickerItem.Header(R.string.picker_section_note_project, 0),
                entity(carla, false),
                PickerItem.Header(R.string.picker_section_other_people, 1),
                entity(anna, false),
            ),
            items,
        )
    }

    @Test
    fun addPerson_onANoteWithSeveralProjects_saysProjects() {
        val items = items(addPerson(projectCount = 2), firstGroup = setOf(1, 3))

        assertEquals(
            listOf(
                PickerItem.Header(R.string.picker_section_note_projects, 0),
                entity(anna, false),
                entity(carla, false),
                PickerItem.Header(R.string.picker_section_other_people, 1),
                entity(bob, false),
            ),
            items,
        )
    }

    @Test
    fun theGroups_followTheGivenFirstGroup_notTheFlag_andATickMovesNobody() {
        val unticked = items(addPerson(projectCount = 1), firstGroup = setOf(1))
        val ticked = items(addPerson(projectCount = 1), checked = setOf(2, 3), firstGroup = setOf(1))

        // Bob is flagged co-linked but not in the first group; Anna is in it without the flag.
        assertEquals(
            listOf(
                PickerItem.Header(R.string.picker_section_note_project, 0),
                entity(anna, false),
                PickerItem.Header(R.string.picker_section_other_people, 1),
                entity(bob, false),
                entity(carla, false),
            ),
            unticked,
        )
        assertEquals(unticked.map { it.key }, ticked.map { it.key })
        assertEquals(listOf(false, true, true), ticked.filterIsInstance<PickerItem.Entity>().map { it.checked })
    }

    @Test
    fun batchAssociate_headsThePeopleOfTheChosenProject() {
        val items = items(ASSOCIATE, checked = setOf(1), firstGroup = setOf(2))

        assertEquals(
            listOf(
                PickerItem.Header(R.string.picker_section_co_people, 0),
                entity(bob, false),
                PickerItem.Header(R.string.picker_section_other_people, 1),
                entity(anna, true),
                entity(carla, false),
            ),
            items,
        )
    }

    @Test
    fun withoutAFirstGroup_noHeaderShows_whateverTheFlagsOrTheKeySay() {
        val plain = listOf(entity(bob, false), entity(anna, false), entity(carla, false))

        assertEquals(plain, items(PickerPurpose.EditorLinks(emptyList(), listOf(9), 4, 2), kind = EntityKind.Project))
        assertEquals(plain, items(ASSOCIATE))
        assertEquals(listOf(PickerItem.AnyEntity(selected = true)) + plain, items(PickerPurpose.SecondaryFilter(null, null)))
    }

    @Test
    fun theCreateRow_trimsTheText_andSaysAnotherOnACaseInsensitiveMatch() {
        val plain = items(ASSOCIATE, search = "  Dora ").first()
        val listed = items(ASSOCIATE, search = "anna").first()
        val createdBefore = items(ASSOCIATE, search = "ZED", created = listOf(NewEntity("k", "zed")))

        assertEquals(PickerItem.Create("Dora", another = false), plain)
        assertEquals(PickerItem.Create("anna", another = true), listed)
        assertEquals(PickerItem.Create("ZED", another = true), createdBefore.first())
    }

    @Test
    fun theSearch_filtersEverySection_byACaseInsensitiveSubstring() {
        val items = items(addPerson(projectCount = 0), search = "AR", created = listOf(NewEntity("k", "Marc")))

        assertEquals(
            listOf(PickerItem.Create("AR", another = false), PickerItem.New(NewEntity("k", "Marc")), entity(carla, false)),
            items,
        )
    }

    @Test
    fun secondaryFilter_putsAnyFirst_andHeadsTheFirstGroupAndOther_whenBothHaveRows() {
        val items = items(PickerPurpose.SecondaryFilter(selectedId = 3, coLinkedTo = 9), firstGroup = setOf(2))

        assertEquals(
            listOf(
                PickerItem.AnyEntity(selected = false),
                PickerItem.Header(R.string.picker_section_co_people, 0),
                entity(bob, false),
                PickerItem.Header(R.string.picker_section_other_people, 1),
                entity(anna, false),
                entity(carla, true),
            ),
            items,
        )
    }

    @Test
    fun aSectionLeftEmptyByTheSearch_dropsBothHeaders() {
        val filterPurpose = PickerPurpose.SecondaryFilter(selectedId = null, coLinkedTo = 9)
        val filter = items(filterPurpose, search = "car", firstGroup = setOf(2))
        val addPerson = items(addPerson(projectCount = 1), search = "bo", firstGroup = setOf(2))

        assertEquals(listOf(PickerItem.AnyEntity(selected = true), entity(carla, false)), filter)
        assertEquals(listOf(PickerItem.Create("bo", another = false), entity(bob, false)), addPerson)
    }

    @Test
    fun deleteEntity_listsTheNotesLinksFirst_underLinkedAndOther() {
        val items = items(PickerPurpose.DeleteEntity(linkedIds = listOf(3)), kind = EntityKind.Project)

        assertEquals(
            listOf(
                PickerItem.Header(R.string.picker_section_linked, 0),
                entity(carla, false),
                PickerItem.Header(R.string.picker_section_other_projects, 1),
                entity(bob, false),
                entity(anna, false),
            ),
            items,
        )
    }

    @Test
    fun perspectivePrimary_putsAllFirst_thenMarksTheCurrentEntity_andTheEmbeddedBodyMarksNone() {
        assertEquals(
            listOf(PickerItem.AllEntities, entity(bob, false), entity(anna, true), entity(carla, false)),
            items(PickerPurpose.PerspectivePrimary(selectedId = 1)),
        )
        assertEquals(listOf(entity(bob, false), entity(anna, false), entity(carla, false)), items(null))
    }

    @Test
    fun theAllRow_staysWhateverTheSearch_andNoOtherPurposeHasIt() {
        val primary = PickerPurpose.PerspectivePrimary(selectedId = 2)

        assertEquals(listOf(PickerItem.AllEntities, entity(carla, false)), items(primary, search = "carl"))
        assertEquals("no match", listOf(PickerItem.AllEntities), items(primary, search = "zzz"))
        assertEquals("all", items(primary, kind = EntityKind.Project).first().key)
        val others = listOf(
            null,
            addPerson(projectCount = 1),
            ASSOCIATE,
            PickerPurpose.SecondaryFilter(selectedId = null, coLinkedTo = null),
            PickerPurpose.DeleteEntity(linkedIds = emptyList()),
        )
        others.forEach { purpose ->
            assertEquals("$purpose", emptyList<PickerItem>(), items(purpose, search = "Bob").filterIsInstance<PickerItem.AllEntities>())
        }
    }

    private fun items(
        purpose: PickerPurpose?,
        kind: EntityKind = EntityKind.Person,
        search: String = "",
        checked: Set<Long> = emptySet(),
        created: List<NewEntity> = emptyList(),
        firstGroup: Set<Long>? = null,
    ) = pickerItems(purpose, kind, options, search, checked, created, { DAY }, firstGroup)

    private fun addPerson(projectCount: Int) = PickerPurpose.EditorLinks(emptyList(), listOf(9), null, projectCount)

    private fun entity(option: EntityOption, checked: Boolean) =
        PickerItem.Entity(EntityRowUi(option.id, option.name, option.noteCount, DAY), checked)

    private fun option(id: Long, name: String, coLinked: Boolean = false) =
        EntityOption(id, name, createdAt = 0, noteCount = id.toInt(), coLinked = coLinked)

    private companion object {
        val DAY: LocalDate = LocalDate.of(2026, 11, 17)
        val ASSOCIATE = PickerPurpose.BatchAssociate(selectionSaved = true, coLinkedTo = null)
    }
}
