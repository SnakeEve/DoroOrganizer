package com.doro.organizer.core.external

import androidx.activity.ComponentActivity
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns every activity-result launcher and delivers their results (architecture.md § Result launchers and process
 * death, DEC-90). Application-scoped: a lock removes the composition, never a pending result.
 */
interface ExternalActivities {
    /** One slot: a `Backup` or `Restore` result waiting for the screen that asked for it. */
    val inbox: StateFlow<ExternalResult?>

    /** A launch that threw `ActivityNotFoundException` or `SecurityException` (after its fallback, if any). */
    val failures: SharedFlow<ExternalKind>

    /** Main thread. Launched once `MainActivity` is STARTED; a request still queued is replaced and reported not launched. */
    fun request(request: ExternalRequest)

    /** Removes the inbox entry when it has [kind]. */
    fun take(kind: ExternalKind): ExternalResult?

    /** `MainActivity.onCreate`, before STARTED: registers the five launchers. */
    fun bind(activity: ComponentActivity)
}
