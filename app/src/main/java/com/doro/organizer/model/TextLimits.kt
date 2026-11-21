package com.doro.organizer.model

/** The most UTF-16 units a note `content` holds (DEC-24, data-layer.md INV-1). */
const val MAX_CONTENT_CHARS = 200_000

/** The most UTF-16 units a person or project name holds after `TextWhitespace.trim` (DEC-32, data-layer.md INV-9). */
const val MAX_NAME_CHARS = 200

/**
 * [text] cut to at most [maxChars] UTF-16 units, one less when the cut would split a surrogate pair, so input fields
 * that cap what the user types never keep half a character (ui.md § 3.3, § 7.2).
 */
fun capChars(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val end = if (text[maxChars - 1].isHighSurrogate()) maxChars - 1 else maxChars
    return text.substring(0, end)
}
