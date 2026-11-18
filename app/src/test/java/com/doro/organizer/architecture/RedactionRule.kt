package com.doro.organizer.architecture

/**
 * Rule 4 of architecture.md § Boundary test and the `ArchitectureTest` part of testing.md I-39 (DEC-70).
 * - Every `data class` in `model`, `data`, `data.db`, `data.maintenance`, `session` and `ui` overrides `toString()`.
 * - Every other `data class` under the app package does so when a constructor property may carry user text or a
 *   secret (`String`, `CharSequence`, `CharArray`, `ByteArray`, `Uri`, `File`, also as a type argument).
 * - `ui.navigation` is the exception both ways: its keys keep the generated `toString()`, so they must hold ids
 *   only (architecture.md § Keys and stacks, CLAUDE.md rule 12): a text or secret property there fails, and so
 *   does any `toString` override.
 */
object RedactionRule {
    /**
     * Data classes exempt from the text-property rule, as fully qualified name to reason: constant app text only
     * (testing.md I-39). Empty: no class needs it yet.
     */
    val ALLOWLIST: Map<String, String> = emptyMap()

    private val REDACTED_LAYERS =
        setOf(Layer.MODEL, Layer.DATA, Layer.DATA_DB, Layer.DATA_MAINTENANCE, Layer.SESSION, Layer.UI, Layer.UI_GATE)
    private val NAVIGATION = "${Layer.APP_PACKAGE}.ui.navigation"
    private val DATA_CLASS = Regex("""\bdata\s+class\s+(\w+)""")
    private val TO_STRING = Regex("""\boverride\s+fun\s+toString\s*\(""")
    private val PROPERTY = Regex("""\b(?:val|var)\s+\w+\s*:([^=]*)""")
    private val TEXT_TYPE = Regex("""\b(String|CharSequence|CharArray|ByteArray|Uri|File)\b""")

    fun check(source: KotlinSource, allowlist: Map<String, String> = ALLOWLIST): List<Violation> {
        val packageName = source.packageName ?: return emptyList()
        val blocks = Blocks(source.code)
        if (packageName == NAVIGATION || packageName.startsWith("$NAVIGATION.")) {
            val overrides = TO_STRING.findAll(source.code).map {
                source.violation(it.range.first, "ui.navigation keys keep the generated toString")
            }
            val textKeys = DATA_CLASS.findAll(source.code)
                .filter { DataClass.parse(source.code, blocks, it.range.last + 1).hasTextProperty }
                .map {
                    source.violation(it.range.first, "navigation key ${it.groupValues[1]} carries text; keys hold ids only")
                }
            return (overrides + textKeys).sortedBy { it.line }.toList()
        }
        val layer = Layer.of(packageName) ?: return emptyList()
        return DATA_CLASS.findAll(source.code).mapNotNull { match ->
            val name = match.groupValues[1]
            val declaration = DataClass.parse(source.code, blocks, match.range.last + 1)
            val reason = when {
                declaration.overridesToString -> null
                layer in REDACTED_LAYERS -> "must override toString"
                "$packageName.$name" in allowlist -> null
                declaration.hasTextProperty -> "has a text or secret property and must override toString"
                else -> null
            }
            reason?.let { source.violation(match.range.first, "data class $name $it") }
        }.toList()
    }

    /** The parts of one `data class` declaration that the rule reads. */
    private class DataClass(val hasTextProperty: Boolean, val overridesToString: Boolean) {
        companion object {
            /** Parses the declaration whose name ends just before [from]. */
            fun parse(code: String, blocks: Blocks, from: Int): DataClass {
                val paramsOpen = code.indexOf('(', from)
                val paramsClose = matching(code, paramsOpen)
                val hasTextProperty = topLevelParts(code.substring(paramsOpen + 1, paramsClose - 1))
                    .any { part -> PROPERTY.find(part)?.let { TEXT_TYPE.containsMatchIn(it.groupValues[1]) } == true }
                val bodyOpen = bodyStart(code, paramsClose)
                val overridesToString = bodyOpen >= 0 && TO_STRING.containsMatchIn(blocks.ownLevel(bodyOpen))
                return DataClass(hasTextProperty, overridesToString)
            }

            /** The offset just past the bracket that closes the one at [open]. */
            private fun matching(code: String, open: Int): Int {
                var depth = 0
                for (i in open until code.length) {
                    when (code[i]) {
                        '(', '[', '{' -> depth++
                        ')', ']', '}' -> if (--depth == 0) return i + 1
                    }
                }
                return code.length
            }

            /** The constructor's parameters, split at the commas outside nested brackets. */
            private fun topLevelParts(params: String): List<String> {
                val parts = mutableListOf<String>()
                var depth = 0
                var start = 0
                params.forEachIndexed { i, c ->
                    val arrow = c == '>' && params.getOrNull(i - 1) == '-' // of a function type, not a type argument
                    when {
                        c in "([{<" -> depth++
                        c in ")]}>" && !arrow -> depth--
                        c == ',' && depth == 0 -> { parts += params.substring(start, i); start = i + 1 }
                    }
                }
                return parts + params.substring(start)
            }

            /**
             * The offset of the body's `{` after the constructor ending at [after], or -1 without a body. Supertypes
             * may follow a `:` and continue over lines that end or start with `,` or `:`.
             */
            private fun bodyStart(code: String, after: Int): Int {
                var i = after
                var depth = 0
                var lastSignificant = ')'
                while (i < code.length) {
                    val c = code[i]
                    when {
                        depth == 0 && c == '{' -> return i
                        depth == 0 && (c == '}' || c == ';') -> return -1
                        c == '(' || c == '<' -> depth++
                        c == ')' || c == '>' -> depth--
                        depth == 0 && c == '\n' -> {
                            val next = code.substring(i).trimStart().firstOrNull()
                            val continues = lastSignificant == ',' || lastSignificant == ':' || next == ':' ||
                                next == ',' || next == '{'
                            if (!continues) return -1
                        }
                    }
                    if (!c.isWhitespace()) lastSignificant = c
                    i++
                }
                return -1
            }
        }
    }
}
