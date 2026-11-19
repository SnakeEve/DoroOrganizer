package com.doro.organizer.model

/**
 * The search rule of DEC-29. Both the stored `content_fold` and the query are folded here, in Kotlin: SQLCipher's
 * SQLite has no ICU and folds ASCII only, so SQL never calls `lower()` or `upper()` (data-layer.md § Search).
 */
object SearchText {
    /**
     * The longest folded term that is bound, far below SQLite's 50,000-byte LIKE pattern limit. A longer query
     * matches on its first [MAX_TERM_CHARS] characters (testing.md I-27).
     */
    const val MAX_TERM_CHARS = 1_000

    /** The only folding rule: `lowercase()` is `toLowerCase(Locale.ROOT)`, never the device locale. */
    fun fold(text: String): String = text.lowercase()

    /**
     * The bind value for `:searchPattern`: the folded [input] trimmed by [TextWhitespace], cut to [MAX_TERM_CHARS] without splitting a
     * surrogate pair, with `\`, `%` and `_` escaped for `LIKE … ESCAPE '\'`. Null for blank input: no search filter.
     */
    fun toLikeTerm(input: String): String? {
        val folded = fold(TextWhitespace.trim(input))
        var end = minOf(folded.length, MAX_TERM_CHARS)
        if (end < folded.length && folded[end - 1].isHighSurrogate()) end--
        if (end == 0) return null
        return buildString(end + end / 4) {
            for (i in 0 until end) {
                val c = folded[i]
                if (c == '\\' || c == '%' || c == '_') append('\\')
                append(c)
            }
        }
    }
}
