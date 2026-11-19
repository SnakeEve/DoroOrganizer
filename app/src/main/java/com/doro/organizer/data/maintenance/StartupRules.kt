package com.doro.organizer.data.maintenance

import com.doro.organizer.security.KeyState

// The decision table and the data-reset marker rule of db-lifecycle.md § Startup reconciliation, without I/O.

/** `KeyVault.readState()` as reconciliation sees it: a state, null (Missing) or `KeyVaultException` (Invalid). */
internal sealed interface KeyStateRead {
    class Valid(val state: KeyState) : KeyStateRead {
        override fun toString() = "Valid($state)"
    }

    data object Missing : KeyStateRead

    data object Invalid : KeyStateRead
}

/**
 * The decision for [read] and the generations that have a main file ([mainFiles]). [isPlaintext] is asked only when
 * the key-state is missing: then only a set of plaintext files may be adopted, the newest one; anything that is not
 * recognizably plaintext is KeyUnavailable (DEC-86).
 */
internal fun startupDecision(read: KeyStateRead, mainFiles: Set<Long>, isPlaintext: (Long) -> Boolean): StartupDecision =
    when (read) {
        is KeyStateRead.Valid -> StartupDecision.Proceed(read.state.generation, read.state.slot.mode)
        KeyStateRead.Invalid -> StartupDecision.KeyUnavailable
        KeyStateRead.Missing -> when {
            mainFiles.isEmpty() -> StartupDecision.FirstRun
            mainFiles.all(isPlaintext) -> StartupDecision.AdoptPlain(mainFiles.max())
            else -> StartupDecision.KeyUnavailable
        }
    }

/**
 * The generation sets reconciliation deletes: only with a Valid key-state whose own file exists and is not empty,
 * every other set. A missing or invalid key-state, or a missing live file, deletes nothing (DEC-86).
 */
internal fun unreferencedAtStartup(read: KeyStateRead, onDisk: Set<Long>, liveFileUsable: Boolean): Set<Long> =
    if (read is KeyStateRead.Valid && liveFileUsable) onDisk - read.state.generation else emptySet()

/**
 * Whether a data-reset marker holding [marker] resets the data (db-lifecycle.md § Data-reset marker): when the Valid
 * key-state names its generation, or when it cannot be parsed (the safe side). Missing or Invalid: nothing was
 * committed.
 */
internal fun markerResets(read: KeyStateRead, marker: String): Boolean {
    val generation = marker.trim().toLongOrNull() ?: return true
    return read is KeyStateRead.Valid && read.state.generation == generation
}
