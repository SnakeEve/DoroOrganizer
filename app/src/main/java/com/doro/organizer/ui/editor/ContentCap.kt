package com.doro.organizer.ui.editor

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import com.doro.organizer.model.MAX_CONTENT_CHARS
import com.doro.organizer.model.capChars

/**
 * The input transformation of the content field (ui.md § 6.9, DEC-24): a change that would make the text longer than
 * `MAX_CONTENT_CHARS` keeps only the first 200,000 UTF-16 units, or 199,999 when the cut would split a surrogate pair,
 * and calls [onTruncated] once for that change. Typing, paste and IME commits all pass through it.
 */
class ContentCap(private val onTruncated: () -> Unit) : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        if (length <= MAX_CONTENT_CHARS) return
        replace(keptLength(asCharSequence()), length, "")
        onTruncated()
    }

    companion object {
        /** How many leading units of [text] the cap keeps; the same rule truncates a stored note on load. */
        fun keptLength(text: CharSequence): Int =
            if (text.length <= MAX_CONTENT_CHARS) text.length else capChars(text.toString(), MAX_CONTENT_CHARS).length

        /** [text] within the cap: the editor's load of a note longer than the limit (ui.md § 6.9). */
        fun capped(text: String): String = text.substring(0, keptLength(text))
    }
}
