package com.doro.organizer.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.data.EntityRepository
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.EntityOption
import com.doro.organizer.model.MAX_NAME_CHARS
import com.doro.organizer.model.SearchText
import com.doro.organizer.model.TextWhitespace
import com.doro.organizer.model.capChars
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
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
 * What [EntityPicker] shows below its search field: [rows] are the entities whose name matches the search, in
 * repository order (DEC-32); [anyEntity] tells "nothing exists yet" from "nothing matches" (ui.md § 7.8).
 */
data class EntityPickerUiState(
    val loading: Boolean,
    val anyEntity: Boolean,
    val rows: List<EntityRowUi>,
) {
    override fun toString() = "EntityPickerUiState(loading=$loading, rows=${rows.size})" // DEC-70
}

/**
 * The state holder of [EntityPicker] (ui.md § 7) in its single-select embedded form, the body of Individual and
 * Project while nothing is chosen (DEC-52): every entity of [kind], zero-note ones included (DEC-33), filtered in
 * memory by a case-insensitive substring of the name (`SearchText.fold`). The search text is saved (ui.md § 7.7).
 */
class EntityPickerViewModel(
    kind: EntityKind,
    private val savedStateHandle: SavedStateHandle,
    entityRepository: EntityRepository,
    private val appTime: AppTime,
) : ViewModel() {
    /**
     * The search field's text: snapshot state written inside the field's `onValueChange`, so the field shows every
     * edit in its own frame; [searchFlow] carries the same value to the filter.
     */
    var search by mutableStateOf(savedStateHandle[KEY_SEARCH] ?: "")
        private set
    private val searchFlow = MutableStateFlow(search)

    val uiState: StateFlow<EntityPickerUiState> =
        // Null until the first emission, so the field follows typing while the options are still loading.
        combine(entityRepository.observeOptions(kind, coLinkedTo = null).loadingFirst(), searchFlow) { options, text ->
            EntityPickerUiState(
                loading = options == null,
                anyEntity = !options.isNullOrEmpty(),
                rows = options?.let { rowsOf(it, text) }.orEmpty(),
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            EntityPickerUiState(loading = true, anyEntity = false, rows = emptyList()),
        )

    /** The field keeps at most `MAX_NAME_CHARS`, without a message (ui.md § 7.2). */
    fun onSearchChange(text: String) {
        val capped = capChars(text, MAX_NAME_CHARS)
        search = capped
        savedStateHandle[KEY_SEARCH] = capped
        searchFlow.value = capped
    }

    private fun rowsOf(options: List<EntityOption>, search: String): List<EntityRowUi> {
        val term = SearchText.fold(TextWhitespace.trim(search))
        return options
            .filter { term.isEmpty() || SearchText.fold(it.name).contains(term) }
            .map { EntityRowUi(it.id, it.name, it.noteCount, appTime.dateOf(it.createdAt)) }
    }

    private companion object {
        const val KEY_SEARCH = "search"
    }
}

/** Null, then this flow's values: null stands for "not loaded yet". */
private fun <T : Any> Flow<T>.loadingFirst(): Flow<T?> = onStart<T?> { emit(null) }
