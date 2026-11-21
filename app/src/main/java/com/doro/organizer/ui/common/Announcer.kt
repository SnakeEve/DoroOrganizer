package com.doro.organizer.ui.common

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.doro.organizer.ui.GateGraph

/** One spoken announcement for accessibility services (ui.md § 12.3). */
fun interface Announcer {
    fun announce(text: String)

    companion object {
        /**
         * `View.announceForAccessibility` on [view]. It is deprecated from API 36; with targetSdk 35 it still speaks,
         * and a later switch to a polite live region keeps the call sites (ui.md § 3.9, DEC-01).
         */
        @Suppress("DEPRECATION")
        fun of(view: View): Announcer = Announcer { view.announceForAccessibility(it) }
    }
}

/** The announcer of the current view, as [graph] makes it (architecture.md § Dependency injection). */
@Composable
fun rememberAnnouncer(graph: GateGraph): Announcer {
    val view = LocalView.current
    return remember(view, graph) { graph.announcer(view) }
}
