package com.doro.organizer.testing

import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.data.CorruptionListener
import com.doro.organizer.data.DatabaseHolder
import com.doro.organizer.data.DefaultEntityRepository
import com.doro.organizer.data.DefaultNoteRepository
import com.doro.organizer.data.EntityRepository
import com.doro.organizer.data.FlowProblemListener
import com.doro.organizer.data.HolderState
import com.doro.organizer.data.NoteRepository
import com.doro.organizer.data.db.AppDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Records what a `DatabaseHolder` reports to the gate and to the shell (db-lifecycle.md § Interface). */
class HolderEvents : CorruptionListener, FlowProblemListener {
    /** How often runtime corruption was reported; a test waits with `corruptions.first { it > 0 }`. */
    val corruptions = MutableStateFlow(0)

    /** Every flow problem, in order. */
    val flowProblems = MutableStateFlow(emptyList<AppError>())

    override fun onRuntimeCorruption() {
        corruptions.update { it + 1 }
    }

    override fun onFlowProblem(error: AppError) {
        flowProblems.update { it + error }
    }
}

/** The Room instance of an open holder, for direct SQL in tests; production code never keeps it (DEC-81). */
fun DatabaseHolder.openDatabase(): AppDatabase =
    (state.value as? HolderState.Open ?: error("holder not open: ${state.value}")).db

/** The production [NoteRepository] on [holder] with [time]; its detached writes run in [applicationScope]. */
fun noteRepository(holder: DatabaseHolder, time: AppTime, applicationScope: ApplicationScopeRule): NoteRepository =
    DefaultNoteRepository(holder, time, applicationScope.scope, AppDispatchers.production())

/** The production [EntityRepository] on [holder] with [time]; its detached writes run in [applicationScope]. */
fun entityRepository(holder: DatabaseHolder, time: AppTime, applicationScope: ApplicationScopeRule): EntityRepository =
    DefaultEntityRepository(holder, time, applicationScope.scope, AppDispatchers.production())
