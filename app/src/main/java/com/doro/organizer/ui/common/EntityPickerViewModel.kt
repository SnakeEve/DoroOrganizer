package com.doro.organizer.ui.common

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.data.EntityRepository
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.EntityOption
import com.doro.organizer.model.MAX_NAME_CHARS
import com.doro.organizer.model.NewEntity
import com.doro.organizer.model.TextWhitespace
import com.doro.organizer.model.capChars
import com.doro.organizer.ui.navigation.PickerPurpose
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/** An entity row of [EntityPicker]: the name and the duplicate display "N notes · added <date>" of DEC-32. */
data class EntityRowUi(val id: Long, val name: String, val noteCount: Int, val added: LocalDate) {
    override fun toString() = "EntityRowUi(id=$id)" // DEC-70
}

/**
 * What [EntityPicker] shows for the current [tab]: [items] in the arrangement of ui.md § 7.4; [anyEntity] tells
 * "nothing exists yet" from "nothing matches" (ui.md § 7.8). [chosen] counts the checked and created entities per
 * kind for the tab labels, and [canConfirm] enables the confirm button of the multi-select forms (ui.md § 7.6).
 */
data class EntityPickerUiState(
    val loading: Boolean,
    val anyEntity: Boolean,
    val items: List<PickerItem>,
    val tab: EntityKind,
    val chosen: Map<EntityKind, Int>,
    val canConfirm: Boolean,
) {
    override fun toString() = "EntityPickerUiState(loading=$loading, items=${items.size}, tab=$tab)" // DEC-70
}

/**
 * The state holder of [EntityPicker] (ui.md § 7) for [purpose], or with [purpose] null for the embedded body of
 * Individual and Project (DEC-52), whose only saved key is `search`. Every entity of a kind is listed, zero-note ones
 * included (DEC-33). `BatchAssociate` holds both kinds on two tabs with [kind] first; every other purpose holds [kind]
 * only. Per kind the search text, the checked ids and the entities created in this visit are saved, and so is the
 * current tab (ui.md § 7.7); the initial checks of `EditorLinks` apply only when the entry is first created.
 */
class EntityPickerViewModel(
    private val kind: EntityKind,
    private val savedStateHandle: SavedStateHandle,
    entityRepository: EntityRepository,
    private val appTime: AppTime,
    private val purpose: PickerPurpose? = null,
) : ViewModel() {
    private val kinds = if (purpose == PickerPurpose.BatchAssociate) listOf(kind, kind.other()) else listOf(kind)
    private val tab = MutableStateFlow(savedStateHandle.get<String>(KEY_TAB)?.let(EntityKind::valueOf) ?: kind)

    // Snapshot state written inside the field's onValueChange, so the field shows every edit in its own frame; the
    // flows carry the same values to the list.
    private val searches: Map<EntityKind, MutableState<String>> =
        kinds.associateWith { mutableStateOf(savedStateHandle[savedKey(KEY_SEARCH, it)] ?: "") }
    private val searchFlows = kinds.associateWith { MutableStateFlow(searches.getValue(it).value) }
    private val checked = kinds.associateWith { MutableStateFlow(initialChecked(it)) }
    private val created = kinds.associateWith { MutableStateFlow(savedCreated(it)) }

    /** The latest options per kind, null until loaded: the confirm result drops ids that vanished (ui.md § 7.7). */
    private val latestOptions = kinds.associateWith { MutableStateFlow<List<EntityOption>?>(null) }

    /** The current tab's search field text. */
    val search: String get() = searches.getValue(tab.value).value

    val uiState: StateFlow<EntityPickerUiState> = run {
        val coLinkedTo = (purpose as? PickerPurpose.SecondaryFilter)?.coLinkedTo
        val perKind = kinds.map { k ->
            combine(
                // Null until the first emission, so the field follows typing while the options are still loading; an
                // upstream restarted after a pause starts from the list it had, so the loading state never returns.
                entityRepository.observeOptions(k, coLinkedTo)
                    .onStart<List<EntityOption>?> { emit(latestOptions.getValue(k).value) },
                searchFlows.getValue(k),
                checked.getValue(k),
                created.getValue(k),
            ) { options, text, ids, new ->
                latestOptions.getValue(k).value = options
                TabState(k, options, text, ids, new)
            }
        }
        combine(combine(perKind) { it.toList() }, tab) { tabs, current -> stateOf(tabs, current) }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        EntityPickerUiState(
            loading = true,
            anyEntity = false,
            items = emptyList(),
            tab = tab.value,
            chosen = kinds.associateWith { 0 },
            canConfirm = purpose is PickerPurpose.EditorLinks,
        ),
    )

    /** The field keeps at most `MAX_NAME_CHARS`, surrogate pairs whole, without a message (ui.md § 7.2). */
    fun onSearchChange(text: String) = setSearch(tab.value, capChars(text, MAX_NAME_CHARS))

    /** `BatchAssociate`: shows the tab of [kind]. */
    fun selectTab(kind: EntityKind) {
        tab.value = kind
        savedStateHandle[KEY_TAB] = kind.name
    }

    /** Checks or unchecks a stored entity of the current tab (multi-select forms). */
    fun toggle(id: Long) {
        val k = tab.value
        val ids = checked.getValue(k).value
        setChecked(k, if (id in ids) ids - id else ids + id)
    }

    /**
     * The create row: adds the trimmed search text as a checked new entity of the current tab and clears the field
     * (ui.md § 7.6). Nothing is written to the database (DEC-33); blank text creates nothing (DEC-32).
     */
    fun create() {
        val k = tab.value
        val text = searches.getValue(k).value
        if (TextWhitespace.isBlank(text)) return
        setCreated(k, created.getValue(k).value + NewEntity(UUID.randomUUID().toString(), TextWhitespace.trim(text)))
        setSearch(k, "")
    }

    /** Unchecks an entity created in this visit, which discards it (ui.md § 7.3). */
    fun discard(key: String) {
        val k = tab.value
        setCreated(k, created.getValue(k).value.filterNot { it.key == key })
    }

    /**
     * The confirm button of the multi-select forms (ui.md § 7.6, § 7.7): the checked ids that still exist and the
     * entities created in this visit.
     */
    fun confirm(): PickerResult = when (purpose) {
        is PickerPurpose.EditorLinks -> PickerResult.Links(kind, survivingChecked(kind), created.getValue(kind).value)
        PickerPurpose.BatchAssociate -> PickerResult.Associate(
            personIds = survivingChecked(EntityKind.Person),
            projectIds = survivingChecked(EntityKind.Project),
            newPersons = created.getValue(EntityKind.Person).value,
            newProjects = created.getValue(EntityKind.Project).value,
        )
        else -> error("confirm is for the multi-select forms")
    }

    /** A tap in a single-select form (ui.md § 7.5); [id] null is "Any person" / "Any project". */
    fun pick(id: Long?): PickerResult = when (purpose) {
        is PickerPurpose.PerspectivePrimary -> PickerResult.Primary(kind, checkNotNull(id))
        is PickerPurpose.SecondaryFilter -> PickerResult.Filter(kind, id)
        is PickerPurpose.DeleteEntity -> PickerResult.Delete(kind, checkNotNull(id))
        else -> error("pick is for the single-select forms")
    }

    private fun stateOf(tabs: List<TabState>, current: EntityKind): EntityPickerUiState {
        val shown = tabs.first { it.kind == current }
        val chosen = tabs.associate { it.kind to it.checked.size + it.created.size }
        return EntityPickerUiState(
            loading = shown.options == null,
            anyEntity = !shown.options.isNullOrEmpty(),
            items = shown.options?.let {
                pickerItems(purpose, current, it, shown.search, shown.checked, shown.created, appTime::dateOf)
            }.orEmpty(),
            tab = current,
            chosen = chosen,
            // EditorLinks may confirm with nothing checked (it removes every link); batch associate needs a choice.
            canConfirm = purpose is PickerPurpose.EditorLinks || chosen.values.any { it > 0 },
        )
    }

    private fun survivingChecked(k: EntityKind): Set<Long> {
        val ids = checked.getValue(k).value
        val existing = latestOptions.getValue(k).value ?: return ids
        return existing.mapTo(HashSet()) { it.id }.let { live -> ids.filterTo(LinkedHashSet()) { it in live } }
    }

    private fun setSearch(k: EntityKind, text: String) {
        searches.getValue(k).value = text
        savedStateHandle[savedKey(KEY_SEARCH, k)] = text
        searchFlows.getValue(k).value = text
    }

    private fun setChecked(k: EntityKind, ids: Set<Long>) {
        savedStateHandle[savedKey(KEY_CHECKED, k)] = ids.toLongArray()
        checked.getValue(k).value = ids
    }

    private fun setCreated(k: EntityKind, list: List<NewEntity>) {
        savedStateHandle[savedKey(KEY_NEW_KEYS, k)] = ArrayList(list.map { it.key })
        savedStateHandle[savedKey(KEY_NEW_NAMES, k)] = ArrayList(list.map { it.name })
        created.getValue(k).value = list
    }

    /** The saved checks, else the initial ones of `EditorLinks`: those apply only to a new entry (ui.md § 7.7). */
    private fun initialChecked(k: EntityKind): Set<Long> =
        savedStateHandle.get<LongArray>(savedKey(KEY_CHECKED, k))?.toSet()
            ?: (purpose as? PickerPurpose.EditorLinks)?.selectedIds?.toSet()?.takeIf { k == kind }
            ?: emptySet()

    private fun savedCreated(k: EntityKind): List<NewEntity> {
        val keys = savedStateHandle.get<ArrayList<String>>(savedKey(KEY_NEW_KEYS, k)).orEmpty()
        val names = savedStateHandle.get<ArrayList<String>>(savedKey(KEY_NEW_NAMES, k)).orEmpty()
        return keys.zip(names, ::NewEntity)
    }

    /** The first tab's keys are the plain names, so the embedded body keeps its one key `search` (ui.md § 7.7). */
    private fun savedKey(base: String, k: EntityKind) = if (k == kind) base else "${base}_${k.name}"

    private class TabState(
        val kind: EntityKind,
        val options: List<EntityOption>?,
        val search: String,
        val checked: Set<Long>,
        val created: List<NewEntity>,
    )

    companion object {
        const val KEY_SEARCH = "search"
        const val KEY_CHECKED = "checked"
        const val KEY_NEW_KEYS = "newKeys"
        const val KEY_NEW_NAMES = "newNames"
        const val KEY_TAB = "tab"
    }
}

private fun EntityKind.other(): EntityKind = when (this) {
    EntityKind.Person -> EntityKind.Project
    EntityKind.Project -> EntityKind.Person
}
