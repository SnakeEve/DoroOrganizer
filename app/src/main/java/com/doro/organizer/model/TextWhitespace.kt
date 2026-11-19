package com.doro.organizer.model

/**
 * The app's one whitespace rule for user text: the trimming of names (DEC-32, INV-9) and of the search input
 * (DEC-29), and when a note's content or preview is blank (DEC-24, DEC-25) and which line is its title (DEC-39). The
 * SQL list `WHITESPACE_CHARS` that cuts the list preview names exactly these code points, so the preview is blank
 * exactly when the content is (WhitespaceCharsTest on the JVM, NoteRowTest on the device).
 *
 * The set is explicit because Kotlin's `Char.isWhitespace()` depends on the runtime: on the JVM it is the JDK's
 * Unicode data, on ART a hand-written table in `java.lang.Character` that also counts U+180E MONGOLIAN VOWEL
 * SEPARATOR, which Unicode 6.3 moved from the space separators (Zs) to the format characters (Cf). The set below is
 * the JDK 21 one, Unicode's current answer, on every runtime; no user-text rule calls `Char.isWhitespace()`,
 * `trim()` or `isBlank()` without a predicate (the architecture test's `WhitespaceRule` holds production code to it).
 */
object TextWhitespace {
    private val CODE_POINTS = intArrayOf(
        9, 10, 11, 12, 13, 28, 29, 30, 31, 32, 160, 5760,
        8192, 8193, 8194, 8195, 8196, 8197, 8198, 8199, 8200, 8201, 8202,
        8232, 8233, 8239, 8287, 12288,
    )

    /** The whitespace code points in ascending order; all in the BMP, so a `Char` is whitespace or not by itself. */
    val codePoints: List<Int> get() = CODE_POINTS.asList()

    fun isWhitespace(c: Char): Boolean = CODE_POINTS.binarySearch(c.code) >= 0

    /** [text] without leading and trailing whitespace. */
    fun trim(text: String): String = text.trim(::isWhitespace)

    /** Whether [text] is empty or holds whitespace only. */
    fun isBlank(text: CharSequence): Boolean = text.all(::isWhitespace)
}
