package com.doro.organizer.testing

import com.doro.organizer.data.db.WHITESPACE_CHARS
import com.doro.organizer.model.TextWhitespace

/** The code points that [WHITESPACE_CHARS] lists, in order. */
val WHITESPACE_CODE_POINTS: List<Int> = Regex("""\d+""").findAll(WHITESPACE_CHARS).map { it.value.toInt() }.toList()

/** The BMP code points that [TextWhitespace] counts as whitespace on the running runtime (JVM or ART). */
fun ruleWhitespaceCodePoints(): List<Int> = (0..0xFFFF).filter { TextWhitespace.isWhitespace(it.toChar()) }

/** The BMP code points for which `Char.isWhitespace()` is true on the running runtime (JVM or ART). */
fun kotlinWhitespaceCodePoints(): List<Int> = (0..0xFFFF).filter { it.toChar().isWhitespace() }

/**
 * The BMP code points where the running runtime's `Char.isWhitespace()` and [TextWhitespace] disagree, plus U+180E,
 * the one known to differ on ART, so a device test of the rule is never empty. Empty on the JVM but for U+180E.
 */
fun runtimeWhitespaceDifferences(): List<Int> =
    ((kotlinWhitespaceCodePoints().toSet() xor TextWhitespace.codePoints.toSet()) + MONGOLIAN_VOWEL_SEPARATOR.code).sorted()

/** U+180E MONGOLIAN VOWEL SEPARATOR: whitespace to ART's `Char.isWhitespace()`, text to the JDK's and the app's. */
const val MONGOLIAN_VOWEL_SEPARATOR = '᠎'

private infix fun <T> Set<T>.xor(other: Set<T>): Set<T> = (this - other) + (other - this)
