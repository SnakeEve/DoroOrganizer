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
 * The arrangement of the entity picker per purpose (ui.md § 7.3, § 7.4, § 7.6; DEC-28, DEC-32–DEC-34): the "Any"
 * and create rows, the entities created in this visit, the headed sections and the name search over all of them.
 * The options arrive in repository order, which the picker never changes.
 */
class PickerItemsTest {
    private val anna = option(1, "Anna")
    private val bob = option(2, "Bob", coLinked = true)
    private val carla = option(3, "Carla")
    private val options = listOf(bob, anna, carla)

    @Test
    fun editorLinks_listsTheCreateRow_thenCreated_thenEveryEntityChecked() {
        val zed = NewEntity("k1", "Zed")

        val items = items(PickerPurpose.EditorLinks(listOf(1)), search = " ", checked = setOf(1), created = listOf(zed))

        assertEquals(
            listOf(PickerItem.New(zed), entity(bob, false), entity(anna, true), entity(carla, false)),
            items,
        )
    }

    @Test
    fun theCreateRow_trimsTheText_andSaysAnotherOnACaseInsensitiveMatch() {
        val plain = items(PickerPurpose.BatchAssociate, search = "  Dora ").first()
        val listed = items(PickerPurpose.BatchAssociate, search = "anna").first()
        val createdBefore = items(PickerPurpose.BatchAssociate, search = "ZED", created = listOf(NewEntity("k", "zed")))

        assertEquals(PickerItem.Create("Dora", another = false), plain)
        assertEquals(PickerItem.Create("anna", another = true), listed)
        assertEquals(PickerItem.Create("ZED", another = true), createdBefore.first())
    }

    @Test
    fun theSearch_filtersEverySection_byACaseInsensitiveSubstring() {
        val items = items(PickerPurpose.EditorLinks(emptyList()), search = "AR", created = listOf(NewEntity("k", "Marc")))

        assertEquals(
            listOf(PickerItem.Create("AR", another = false), PickerItem.New(NewEntity("k", "Marc")), entity(carla, false)),
            items,
        )
    }

    @Test
    fun secondaryFilter_putsAnyFirst_andHeadsCoLinkedAndOther_whenBothHaveRows() {
        val items = items(PickerPurpose.SecondaryFilter(selectedId = 3, coLinkedTo = 9))

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
        val items = items(PickerPurpose.SecondaryFilter(selectedId = null, coLinkedTo = 9), search = "car")

        assertEquals(listOf(PickerItem.AnyEntity(selected = true), entity(carla, false)), items)
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
    fun perspectivePrimary_marksTheCurrentEntity_andTheEmbeddedBodyMarksNone() {
        assertEquals(
            listOf(entity(bob, false), entity(anna, true), entity(carla, false)),
            items(PickerPurpose.PerspectivePrimary(selectedId = 1)),
        )
        assertEquals(listOf(entity(bob, false), entity(anna, false), entity(carla, false)), items(null))
    }

    private fun items(
        purpose: PickerPurpose?,
        kind: EntityKind = EntityKind.Person,
        search: String = "",
        checked: Set<Long> = emptySet(),
        created: List<NewEntity> = emptyList(),
    ) = pickerItems(purpose, kind, options, search, checked, created) { DAY }

    private fun entity(option: EntityOption, checked: Boolean) =
        PickerItem.Entity(EntityRowUi(option.id, option.name, option.noteCount, DAY), checked)

    private fun option(id: Long, name: String, coLinked: Boolean = false) =
        EntityOption(id, name, createdAt = 0, noteCount = id.toInt(), coLinked = coLinked)

    private companion object {
        val DAY: LocalDate = LocalDate.of(2026, 11, 17)
    }
}
