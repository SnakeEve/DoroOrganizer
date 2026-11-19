package com.doro.organizer.core.messages

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * Messages that must outlive the screen that caused them, shown by the shell (ui.md § 2.4), plus the in-memory
 * fingerprint-reset marker (DEC-63). State, not a channel, so a message survives configuration changes. Thread-safe:
 * `DatabaseHolder` posts flow problems from any thread.
 */
interface AppMessages {
    val pending: StateFlow<List<UserMessage>>

    fun post(message: UserMessage)

    /** Removes the message with [id] after the shell has shown it. */
    fun consume(id: Long)

    /** Set by the Forgot-password flow before it starts the fingerprint reset (ui.md § 8.15). */
    fun markFingerprintReset()

    fun clearFingerprintReset()

    /** True once after [markFingerprintReset]; clears the marker. */
    fun consumeFingerprintReset(): Boolean
}

class DefaultAppMessages : AppMessages {
    private val messages = MutableStateFlow<List<UserMessage>>(emptyList())
    private val fingerprintReset = MutableStateFlow(false)

    override val pending: StateFlow<List<UserMessage>> = messages.asStateFlow()

    override fun post(message: UserMessage) = messages.update { it + message }

    override fun consume(id: Long) = messages.update { list -> list.filterNot { it.id == id } }

    override fun markFingerprintReset() {
        fingerprintReset.value = true
    }

    override fun clearFingerprintReset() {
        fingerprintReset.value = false
    }

    override fun consumeFingerprintReset(): Boolean = fingerprintReset.getAndUpdate { false }
}

