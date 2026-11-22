package com.doro.organizer.model

/** The most UTF-16 units a note `content` holds (DEC-24, data-layer.md INV-1). */
const val MAX_CONTENT_CHARS = 200_000

/**
 * The most lines a note `content` holds (Q14, DEC-24, data-layer.md INV-1): at most 99,999 line breaks. The editor
 * lays out the whole text, and Compose cannot lay out anything taller than 16,777,215 px; at 200 % font on the
 * owner's phone 100,000 lines are about 13.7 M px (ui.md § 6.9).
 */
const val MAX_CONTENT_LINES = 100_000

/**
 * The one character at which the editor's text layout starts a new line (Q14, ui.md § 6.9): U+000A. Compose lays out
 * text on Android with the platform's `StaticLayout`, which starts a paragraph only after U+000A
 * (`PrecomputedText.createMeasuredParagraphs`), and its line breaker (minikin) breaks inside a paragraph only where
 * the line is full. So U+000D, U+2028 and U+2029 start no line and are not line breaks; a CR LF pair is one.
 */
const val LINE_BREAK = '\n'

/** The most UTF-16 units a person or project name holds after `TextWhitespace.trim` (DEC-32, data-layer.md INV-9). */
const val MAX_NAME_CHARS = 200

/**
 * [text] cut to at most [maxChars] UTF-16 units, one less when the cut would split a surrogate pair, so input fields
 * that cap what the user types never keep half a character (ui.md § 3.3, § 7.2).
 */
fun capChars(text: String, maxChars: Int): String {
    val end = capCharsLength(text, maxChars)
    return if (end == text.length) text else text.substring(0, end)
}

/** How many leading UTF-16 units of [text] [capChars] keeps. */
fun capCharsLength(text: CharSequence, maxChars: Int): Int = when {
    text.length <= maxChars -> text.length
    text[maxChars - 1].isHighSurrogate() -> maxChars - 1
    else -> maxChars
}

/** How many line breaks ([LINE_BREAK]) [text] holds; it has one line more (Q14). */
fun lineBreakCount(text: CharSequence): Int {
    var breaks = 0
    for (i in 0 until text.length) {
        if (text[i] == LINE_BREAK) breaks++
    }
    return breaks
}

/**
 * How many leading UTF-16 units of [text] hold its first [maxLines] lines (Q14): the index of its [maxLines]-th line
 * break, so a cut there keeps those lines and drops that line break and everything after it; [text]'s length when it
 * has at most [maxLines] lines. A line break is never half of a surrogate pair, so the cut never splits one. A text
 * shorter than [maxLines] units cannot hold that many line breaks and is not scanned.
 */
fun capLinesLength(text: CharSequence, maxLines: Int): Int {
    if (text.length < maxLines) return text.length
    var breaks = 0
    for (i in 0 until text.length) {
        if (text[i] == LINE_BREAK && ++breaks == maxLines) return i
    }
    return text.length
}
