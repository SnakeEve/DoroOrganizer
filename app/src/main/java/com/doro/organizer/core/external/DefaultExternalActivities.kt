package com.doro.organizer.core.external

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch

/**
 * The production [ExternalActivities]. [callbacks] is a provider (the lock controller), resolved at each launch and
 * result. Everything runs on the main thread.
 */
class DefaultExternalActivities(private val callbacks: () -> ExternalCallbacks) : ExternalActivities {
    private val inboxState = MutableStateFlow<ExternalResult?>(null)
    private val failureEvents =
        MutableSharedFlow<ExternalKind>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val queued = MutableStateFlow<ExternalRequest?>(null)

    override val inbox: StateFlow<ExternalResult?> = inboxState.asStateFlow()
    override val failures: SharedFlow<ExternalKind> = failureEvents.asSharedFlow()

    override fun request(request: ExternalRequest) {
        queued.getAndUpdate { request }?.let { callbacks().notLaunched(it) }
    }

    override fun take(kind: ExternalKind): ExternalResult? {
        val result = inboxState.value?.takeIf { it.kind == kind } ?: return null
        return result.takeIf { inboxState.compareAndSet(it, null) }
    }

    override fun bind(activity: ComponentActivity) {
        // One launcher per kind, so every callback knows which request it answers without a saved "pending kind".
        val bound = Launchers(
            backup = activity.registerForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME)) { uri ->
                deliver(ExternalKind.Backup, uri, resultCodeOf(uri))
            },
            restore = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                deliver(ExternalKind.Restore, uri, resultCodeOf(uri))
            },
            intents = INTENT_KINDS.associateWith { kind ->
                activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    deliver(kind, null, it.resultCode)
                }
            },
        )
        // Only this collector holds the launchers; it ends with the activity, so no destroyed activity is kept.
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                queued.filterNotNull().collect { request ->
                    if (queued.compareAndSet(request, null)) launch(bound, request)
                }
            }
        }
    }

    private fun launch(bound: Launchers, request: ExternalRequest) {
        val kind = request.kind
        callbacks().beforeLaunch(kind)
        val fallback = request.fallbackIntent
        val launched = tryLaunch { bound.launch(request) } ||
            (fallback != null && tryLaunch { bound.intent(kind).launch(fallback) })
        if (!launched) {
            // A launch that never paused the activity must not leave the exemption armed (security.md § 7.7).
            callbacks().afterResult(kind)
            callbacks().notLaunched(request)
            failureEvents.tryEmit(kind)
        }
    }

    private fun deliver(kind: ExternalKind, uri: Uri?, resultCode: Int) {
        callbacks().afterResult(kind)
        when (kind) {
            ExternalKind.CredentialConfirm -> callbacks().onCredentialResult(resultCode)
            ExternalKind.Backup, ExternalKind.Restore -> inboxState.value = ExternalResult(kind, uri, resultCode)
            ExternalKind.Share, ExternalKind.Enroll -> Unit // Settings re-checks availability on resume
        }
    }

    private class Launchers(
        val backup: ActivityResultLauncher<String>,
        val restore: ActivityResultLauncher<Array<String>>,
        val intents: Map<ExternalKind, ActivityResultLauncher<Intent>>,
    ) {
        fun intent(kind: ExternalKind): ActivityResultLauncher<Intent> = intents.getValue(kind)

        fun launch(request: ExternalRequest) = when (request.kind) {
            ExternalKind.Backup -> backup.launch(checkNotNull(request.suggestedName) { "a backup needs a name" })
            ExternalKind.Restore -> restore.launch(arrayOf(RESTORE_MIME)) // DEC-84: any file, validation decides
            else -> intent(request.kind).launch(checkNotNull(request.intent) { "${request.kind} needs an intent" })
        }
    }

    private companion object {
        const val BACKUP_MIME = "application/octet-stream" // DEC-83
        const val RESTORE_MIME = "*/*"
        val INTENT_KINDS = listOf(ExternalKind.Share, ExternalKind.Enroll, ExternalKind.CredentialConfirm)

        fun resultCodeOf(uri: Uri?) = if (uri != null) Activity.RESULT_OK else Activity.RESULT_CANCELED

        /** False when the platform has no handler for the intent or refuses it. */
        inline fun tryLaunch(block: () -> Unit): Boolean = try {
            block()
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
