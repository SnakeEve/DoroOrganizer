package com.doro.organizer.testing

import com.doro.organizer.data.db.WHITESPACE_CHARS

/** The code points that [WHITESPACE_CHARS] lists, in order. */
val WHITESPACE_CODE_POINTS: List<Int> = Regex("""\d+""").findAll(WHITESPACE_CHARS).map { it.value.toInt() }.toList()

/** The BMP code points for which `Char.isWhitespace()` is true on the running runtime (JVM or ART). */
fun kotlinWhitespaceCodePoints(): List<Int> = (0..0xFFFF).filter { it.toChar().isWhitespace() }
