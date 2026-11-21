package com.doro.organizer.ui

import android.view.View
import androidx.compose.runtime.staticCompositionLocalOf
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.external.ExternalActivities
import com.doro.organizer.core.messages.AppMessages
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.data.EntityRepository
import com.doro.organizer.data.NoteRepository
import com.doro.organizer.data.maintenance.DbMaintenance
import com.doro.organizer.security.AppLockController
import com.doro.organizer.session.SessionGate
import com.doro.organizer.settings.SettingsStore
import com.doro.organizer.ui.common.Announcer
import kotlinx.coroutines.CoroutineScope

/**
 * All that `ui.gate` may use (architecture.md § Dependency injection): no repository and no settings, so a gate
 * screen cannot show note-derived data (DEC-66).
 */
interface GateGraph {
    val sessionGate: SessionGate
    val dbMaintenance: DbMaintenance
    val appLockController: AppLockController
    val externalActivities: ExternalActivities
    val appTime: AppTime
    val appMessages: AppMessages

    /** The [Announcer] of [view] (ui.md § 12.3); the instrumented tests' container records its calls (I-54). */
    fun announcer(view: View): Announcer = Announcer.of(view)
}

/** All that `ui` may use; `AppContainer` implements it and `MainActivity` provides it as [LocalUiGraph]. */
interface UiGraph : GateGraph {
    val noteRepository: NoteRepository
    val entityRepository: EntityRepository
    val settingsStore: SettingsStore

    /** The editor's save-on-leave (architecture.md § Threading). */
    val applicationScope: CoroutineScope

    /** The list ViewModels map rows on `default` (architecture.md § Threading: heavy list mapping). */
    val dispatchers: AppDispatchers
}

val LocalUiGraph = staticCompositionLocalOf<UiGraph> { error("MainActivity provides the UiGraph") }
