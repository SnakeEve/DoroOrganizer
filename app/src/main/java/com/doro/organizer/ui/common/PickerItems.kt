package com.doro.organizer.ui.common

import androidx.annotation.StringRes
import com.doro.organizer.R
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.EntityOption
import com.doro.organizer.model.NewEntity
import com.doro.organizer.model.SearchText
import com.doro.organizer.model.TextWhitespace
import com.doro.organizer.ui.navigation.PickerPurpose
import java.time.LocalDate

/** One row of the `EntityPicker` list (ui.md § 7.3); [key] is its `LazyColumn` key (ui.md § 7.2). */
sealed interface PickerItem {
    val key: Any

    /** "Any person" / "Any project" of `SecondaryFilter`, [selected] while no filter is set. */
    data class AnyEntity(val selected: Boolean) : PickerItem {
        override val key: Any get() = "any"

        override fun toString() = "AnyEntity(selected=$selected)"
    }

    /**
     * "All people" / "All projects" of `PerspectivePrimary` (DEC-112): returns no entity, so the list shows its picker
     * body. Never checked, shown whatever the name search holds.
     */
    data object AllEntities : PickerItem {
        override val key: Any get() = "all"
    }

    /** `Create "X"`, or `Create another "X"` when [another]: a case-insensitive exact match exists (DEC-32). */
    data class Create(val name: String, val another: Boolean) : PickerItem {
        override val key: Any get() = "create"

        override fun toString() = "Create(another=$another)" // DEC-70
    }

    /** An entity created in this visit: checked, inserted only by the save or batch confirm that uses it (DEC-33). */
    data class New(val entity: NewEntity) : PickerItem {
        override val key: Any get() = "new:${entity.key}"

        override fun toString() = "New(${entity.key})" // DEC-70
    }

    /** A section header (ui.md § 7.4); [index] keeps the two headers of one list apart. */
    data class Header(@param:StringRes val text: Int, val index: Int) : PickerItem {
        override val key: Any get() = "header:$index"

        override fun toString() = "Header($index)"
    }

    /**
     * A stored entity. [checked] is its checkbox in the multi-select forms and the trailing check of the chosen
     * entity in the single-select ones.
     */
    data class Entity(val row: EntityRowUi, val checked: Boolean) : PickerItem {
        override val key: Any get() = row.id

        override fun toString() = "Entity(id=${row.id}, checked=$checked)" // DEC-70
    }
}

/** The purposes whose list offers the create row and checkboxes (ui.md § 7.1, DEC-33). */
val PickerPurpose?.isMultiSelect: Boolean
    get() = this is PickerPurpose.EditorLinks || this is PickerPurpose.BatchAssociate

/**
 * The list of a picker tab of [kind] for [purpose] (null: the embedded body of Individual and Project), top to bottom
 * (ui.md § 7.4): the "Any …", "All …" or create row, the entities [created] in this visit, then the [options] in
 * repository order. Where the picker forms groups, [firstGroup] holds the ids of the first one (DEC-28, DEC-111): the
 * options split by id into that group and the rest, each in repository order and under its header; a tick moves
 * nobody. Every section is filtered by a case-insensitive substring of the trimmed [search] (`SearchText.fold`, ui.md
 * § 7.2), the "Any …" and "All …" rows excepted; a header shows only when both of its groups have rows after that
 * filter.
 */
internal fun pickerItems(
    purpose: PickerPurpose?,
    kind: EntityKind,
    options: List<EntityOption>,
    search: String,
    checked: Set<Long>,
    created: List<NewEntity>,
    dateOf: (epochMillis: Long) -> LocalDate,
    firstGroup: Set<Long>?,
): List<PickerItem> {
    val term = SearchText.fold(TextWhitespace.trim(search))
    val matches = { name: String -> term.isEmpty() || SearchText.fold(name).contains(term) }
    val shown = options.filter { matches(it.name) }
    val entity = { option: EntityOption, isChecked: Boolean ->
        PickerItem.Entity(EntityRowUi(option.id, option.name, option.noteCount, dateOf(option.createdAt)), isChecked)
    }
    return buildList {
        when (purpose) {
            is PickerPurpose.EditorLinks, is PickerPurpose.BatchAssociate -> {
                createItem(search, options, created)?.let(::add)
                created.filter { matches(it.name) }.forEach { add(PickerItem.New(it)) }
                val header = when (purpose) {
                    is PickerPurpose.EditorLinks -> noteHeader(purpose.projectCount)
                    else -> coLinkedHeader(kind)
                }
                addGroups(firstGroup, shown, header, otherHeader(kind)) { entity(it, it.id in checked) }
            }
            is PickerPurpose.SecondaryFilter -> {
                add(PickerItem.AnyEntity(selected = purpose.selectedId == null))
                addGroups(firstGroup, shown, coLinkedHeader(kind), otherHeader(kind)) {
                    entity(it, it.id == purpose.selectedId)
                }
            }
            is PickerPurpose.DeleteEntity -> {
                val (linked, others) = shown.partition { it.id in purpose.linkedIds }
                addSections(R.string.picker_section_linked, linked, otherHeader(kind), others) { entity(it, false) }
            }
            is PickerPurpose.PerspectivePrimary -> {
                add(PickerItem.AllEntities)
                shown.forEach { add(entity(it, it.id == purpose.selectedId)) }
            }
            null -> shown.forEach { add(entity(it, false)) }
        }
    }
}

/** [shown] in two headed groups split by the ids of [firstGroup], or in one list without a header when it is null. */
private fun MutableList<PickerItem>.addGroups(
    firstGroup: Set<Long>?,
    shown: List<EntityOption>,
    @StringRes firstHeader: Int,
    @StringRes secondHeader: Int,
    item: (EntityOption) -> PickerItem,
) {
    if (firstGroup == null) {
        shown.forEach { add(item(it)) }
    } else {
        val (first, others) = shown.partition { it.id in firstGroup }
        addSections(firstHeader, first, secondHeader, others, item)
    }
}

/**
 * The create row for [search] (ui.md § 7.6): none while the trimmed text is blank, `Create another "X"` when the
 * folded name equals that of a listed entity or of one created in this visit. Duplicates stay allowed (§1).
 */
private fun createItem(search: String, options: List<EntityOption>, created: List<NewEntity>): PickerItem.Create? {
    if (TextWhitespace.isBlank(search)) return null
    val name = TextWhitespace.trim(search)
    val folded = SearchText.fold(name)
    val another = options.any { SearchText.fold(it.name) == folded } || created.any { SearchText.fold(it.name) == folded }
    return PickerItem.Create(name, another)
}

/** Two groups, each under its header only when both are non-empty (ui.md § 7.4). */
private fun MutableList<PickerItem>.addSections(
    @StringRes firstHeader: Int,
    first: List<EntityOption>,
    @StringRes secondHeader: Int,
    second: List<EntityOption>,
    item: (EntityOption) -> PickerItem,
) {
    val headed = first.isNotEmpty() && second.isNotEmpty()
    if (headed) add(PickerItem.Header(firstHeader, 0))
    first.forEach { add(item(it)) }
    if (headed) add(PickerItem.Header(secondHeader, 1))
    second.forEach { add(item(it)) }
}

/** The header of Add person's first group: it follows the number of project chips the editor shows (DEC-111 (4)). */
@StringRes
private fun noteHeader(projectCount: Int): Int =
    if (projectCount == 1) R.string.picker_section_note_project else R.string.picker_section_note_projects

@StringRes
private fun coLinkedHeader(kind: EntityKind): Int = when (kind) {
    EntityKind.Person -> R.string.picker_section_co_people
    EntityKind.Project -> R.string.picker_section_co_projects
}

@StringRes
private fun otherHeader(kind: EntityKind): Int = when (kind) {
    EntityKind.Person -> R.string.picker_section_other_people
    EntityKind.Project -> R.string.picker_section_other_projects
}
