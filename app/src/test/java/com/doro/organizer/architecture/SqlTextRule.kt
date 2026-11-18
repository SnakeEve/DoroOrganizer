package com.doro.organizer.architecture

/**
 * Rule 6 of architecture.md § Boundary test, read from the string literals of `data.db`: no `SELECT *` and no `.*`
 * projection over `notes`, so no query loads every note's full content by accident (data-layer.md § Editor loads,
 * pickers and existence probes).
 */
object SqlTextRule {
    private val SELECT_STAR = Regex("""\bSELECT\s+(?:DISTINCT\s+)?\*""", RegexOption.IGNORE_CASE)
    private val QUALIFIED_STAR = Regex("""\b(\w+)\.\*""")

    fun check(source: KotlinSource): List<Violation> {
        if (source.packageName?.let(Layer::of) != Layer.DATA_DB) return emptyList()
        return source.literals.flatMap { literal ->
            val selectStar = SELECT_STAR.findAll(literal.text).map { "SELECT *" }
            val notesStar = QUALIFIED_STAR.findAll(literal.text)
                .map { it.groupValues[1] }
                .filter { it.equals("notes", ignoreCase = true) || aliasesNotes(literal.text, it) }
                .map { "$it.* over notes" }
            (selectStar + notesStar).map { Violation(source.path, literal.line, "banned SQL: $it") }.toList()
        }
    }

    private fun aliasesNotes(sql: String, alias: String): Boolean =
        Regex("""\bnotes\s+(?:AS\s+)?${Regex.escape(alias)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(sql)
}
