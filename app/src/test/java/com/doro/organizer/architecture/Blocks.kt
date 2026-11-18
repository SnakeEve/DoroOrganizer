package com.doro.organizer.architecture

/**
 * Brace structure of [KotlinSource.code]. A block is a declaration body when the last of `class`, `object`,
 * `interface` and `fun` in the text before its `{` (back to the previous brace or `;`) is not `fun`; every other
 * block (function body, lambda, `init`, `when`, accessor) is local.
 */
class Blocks(private val code: String) {
    private val opens = IntArray(code.length) // depth after each character
    private val isDeclarationBody = mutableMapOf<Int, Boolean>() // keyed by the offset of the opening brace
    private val enclosing = IntArray(code.length) { -1 } // offset of the innermost open brace, -1 at top level

    init {
        val stack = ArrayDeque<Int>()
        var statementStart = 0
        code.forEachIndexed { i, c ->
            when (c) {
                '{' -> {
                    val keyword = HEADER_KEYWORD.findAll(code.substring(statementStart, i)).lastOrNull()?.value
                    isDeclarationBody[i] = keyword != null && keyword != "fun"
                    stack.addLast(i)
                    statementStart = i + 1
                }
                '}' -> {
                    stack.removeLastOrNull()
                    statementStart = i + 1
                }
                ';' -> statementStart = i + 1
            }
            enclosing[i] = stack.lastOrNull() ?: -1
            opens[i] = stack.size
        }
    }

    /** True when [offset] lies at file level or directly in a class, object or interface body, not in local code. */
    fun isMemberLevel(offset: Int): Boolean {
        val brace = enclosing[offset]
        return brace < 0 || isDeclarationBody.getValue(brace)
    }

    /** The offset just past the brace that closes the block opened at [open]. */
    fun closeOf(open: Int): Int {
        val depth = opens[open]
        var i = open + 1
        while (i < code.length && opens[i] >= depth) i++
        return minOf(i + 1, code.length)
    }

    /** The body of the block opened at [open], with every nested block blanked: what remains is its own level. */
    fun ownLevel(open: Int): String {
        val depth = opens[open]
        return buildString {
            for (i in open + 1 until closeOf(open) - 1) {
                val nested = opens[i] > depth || code[i] == '}' // a '}' at this depth closes a nested block
                append(if (nested) ' ' else code[i])
            }
        }
    }

    private companion object {
        val HEADER_KEYWORD = Regex("""(?<!::)\b(class|object|interface|fun)\b""")
    }
}
