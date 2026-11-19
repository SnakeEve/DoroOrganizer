package com.doro.organizer.core.external

import android.content.Intent
import android.net.Uri

/** The external activities the app starts for a result (architecture.md § Result launchers and process death). */
enum class ExternalKind { Backup, Restore, Share, Enroll, CredentialConfirm }

/**
 * One launch. [intent]: `Share`, `Enroll` and `CredentialConfirm`; [suggestedName]: the `Backup` document name;
 * [fallbackIntent]: launched once when [intent] has no handler (enrollment: `ACTION_SECURITY_SETTINGS`, security.md
 * § 7.1).
 */
class ExternalRequest(
    val kind: ExternalKind,
    val intent: Intent? = null,
    val suggestedName: String? = null,
    val fallbackIntent: Intent? = null,
) {
    override fun toString() = "ExternalRequest(kind=$kind)" // no URI, name or intent data (DEC-70)
}

/** A delivered result; a null [uri] from a picker means it was cancelled, which is not an error. */
class ExternalResult(val kind: ExternalKind, val uri: Uri?, val resultCode: Int) {
    override fun toString() = "ExternalResult(kind=$kind)"
}

/** Implemented by `AppLockController`, because `core` cannot import `security`. */
interface ExternalCallbacks {
    /** Arms the one-shot re-lock exemption (security.md § 7.7). */
    fun beforeLaunch(kind: ExternalKind)

    /** Clears it; called first in every result callback and after a launch that failed. */
    fun afterResult(kind: ExternalKind)

    /** [request] ended without a launch: it failed (after [afterResult]) or a newer request replaced it while queued. */
    fun notLaunched(request: ExternalRequest)

    /** `CredentialConfirm`: `RESULT_OK` unlocks (security.md § 7.6). */
    fun onCredentialResult(resultCode: Int)
}
