package com.doro.organizer.ui.editor

import androidx.annotation.StringRes
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import com.doro.organizer.R
import com.doro.organizer.model.MAX_CONTENT_CHARS
import com.doro.organizer.model.MAX_CONTENT_LINES
import com.doro.organizer.model.capCharsLength
import com.doro.organizer.model.capLinesLength

/**
 * The input transformation of the content field (ui.md § 6.9; DEC-24, Q14): a change that would make the text longer
 * than `MAX_CONTENT_CHARS` UTF-16 units or give it more than `MAX_CONTENT_LINES` lines is cut back to the limit
 * ([cut]), and [onTruncated] is called once for that change with the limit that cut it. Typing, paste and IME commits
 * all pass through it. A text shorter than 100,000 units cannot exceed the line limit, so typing in such a note never
 * scans its text for line breaks.
 */
class ContentCap(private val onTruncated: (ContentLimit) -> Unit) : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val cut = cut(asCharSequence()) ?: return
        replace(cut.keptLength, length, "")
        onTruncated(cut.limit)
    }

    companion object {
        /**
         * Where the cap cuts [text], and which limit cuts it; null when [text] is within both limits. The character
         * limit keeps the first 200,000 units, or 199,999 when the cut would split a surrogate pair; the line limit keeps
         * the first 100,000 lines, cutting just before the 100,000th line break. When both limits cut, the shorter text
         * is kept and the limit that cut it is named; on an equal cut, the character limit. The same rule cuts a stored
         * note on load (ui.md § 6.9).
         */
        fun cut(text: CharSequence): ContentCut? {
            val byChars = capCharsLength(text, MAX_CONTENT_CHARS)
            val byLines = capLinesLength(text, MAX_CONTENT_LINES)
            return when {
                byChars < text.length && byChars <= byLines -> ContentCut(byChars, ContentLimit.Chars)
                byLines < text.length -> ContentCut(byLines, ContentLimit.Lines)
                else -> null
            }
        }
    }
}

/** A cut of [ContentCap]: the leading UTF-16 units kept, and the limit that cut the rest. */
data class ContentCut(val keptLength: Int, val limit: ContentLimit) {
    override fun toString() = "ContentCut(keptLength=$keptLength, limit=$limit)" // DEC-70: a count and a limit only
}

/** The two limits of the content (ui.md § 6.9), each with the notice that a cut by it shows. */
enum class ContentLimit(@StringRes val notice: Int) {
    /** `MAX_CONTENT_CHARS` UTF-16 units (DEC-24). */
    Chars(R.string.content_truncated),

    /** `MAX_CONTENT_LINES` lines (Q14). */
    Lines(R.string.content_lines_truncated),
}
