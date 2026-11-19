package com.doro.organizer.core.messages

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.doro.organizer.core.result.AppError
import java.util.concurrent.atomic.AtomicLong

/**
 * Resource-derived message text (ui.md § 2.4): it can hold no user data, because every argument is another [UiText]
 * (DEC-70). The text is resolved in the current locale when it is shown.
 */
sealed interface UiText {
    /** [args] fill `%1$s`, `%2$s`, … in order. */
    data class Res(@param:StringRes val id: Int, val args: List<UiText> = emptyList()) : UiText {
        override fun toString() = "Res(id=$id, args=$args)"
    }

    /** The count is the only argument. */
    data class Plural(@param:PluralsRes val id: Int, val count: Int) : UiText

    /** The text of an expected failure (ui.md § 11.13). */
    data class Error(val error: AppError) : UiText
}

/** The only message type of UiStates and of [AppMessages] (ui.md § 2.4). */
data class UserMessage(val id: Long, val text: UiText, val isError: Boolean) {
    override fun toString() = "UserMessage(id=$id)" // DEC-70

    companion object {
        private val nextId = AtomicLong()

        /** A message with a new process-wide id. */
        fun of(text: UiText, isError: Boolean) = UserMessage(nextId.incrementAndGet(), text, isError)

        /** The message of an expected failure: shown as an error (ui.md § 2.4). */
        fun error(error: AppError) = of(UiText.Error(error), isError = true)
    }
}
