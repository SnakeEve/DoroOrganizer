package com.doro.organizer.architecture

/**
 * Rule 3 of architecture.md § Boundary test (DEC-81): no stored property, constructor `val`s included, of a DAO or
 * Room database type, or initialized from a DAO getter or a Room builder, outside `DatabaseHolder`. Locals inside
 * function bodies and lambdas are per-call and allowed.
 */
object StoredDatabaseRule {
    private val TYPED = Regex("""\b(?:val|var)\s+(\w+)\s*:\s*([^=\n,)]+)""")
    private val INITIALIZED = Regex("""\b(?:val|var)\s+(\w+)\s*(?::([^=\n]*?))?(?:=|\bby\b)""")
    private val DATABASE_TYPE = Regex("""\b(\w*Dao|RoomDatabase|AppDatabase)\b""")
    private val DATABASE_CALL =
        Regex("""\b(?:\w+Dao\(\)|buildRoom\(|Room\.(?:databaseBuilder|inMemoryDatabaseBuilder)\b)""")
    private val GETTER = Regex("""\s*get\s*\(""")
    private val CONTINUATION = Regex("""\s*\??\.""")
    private val HOLDER_FILES = setOf("DatabaseHolder.kt", "DefaultDatabaseHolder.kt")

    fun check(source: KotlinSource): List<Violation> {
        val fileName = source.path.substringAfterLast('/')
        if (source.packageName == "${Layer.APP_PACKAGE}.data" && fileName in HOLDER_FILES) return emptyList()
        val code = source.code
        val blocks = Blocks(code)
        val typed = TYPED.findAll(code).filter { match ->
            val type = match.groupValues[2]
            // A property with a getter computes its value on every read and stores nothing.
            val computed = GETTER.containsMatchIn(type) || GETTER.matchesAt(code, match.range.last + 1)
            DATABASE_TYPE.containsMatchIn(type) && !computed
        }
        val initialized = INITIALIZED.findAll(code).filter { match ->
            !GETTER.containsMatchIn(match.groupValues[2]) &&
                DATABASE_CALL.containsMatchIn(initializer(code, match.range.last + 1))
        }
        return (typed + initialized)
            .filter { blocks.isMemberLevel(it.range.first) }
            .map { source.violation(it.range.first, "stored ${it.groupValues[1]} outside DatabaseHolder (DEC-81)") }
            .distinctBy { it.line to it.message }
            .toList()
    }

    /**
     * The initializer starting at [from]: up to the end of its statement, across bracketed blocks such as a
     * `by lazy { … }` body, a first line that holds nothing yet (`=` at the end of a line) and continuation lines
     * that start with `.` or `?.`.
     */
    private fun initializer(code: String, from: Int): String {
        var depth = 0
        var i = from
        while (i < code.length) {
            val c = code[i]
            when {
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> if (--depth < 0) break
                depth == 0 && (c == ';' || c == ',') -> break
                depth == 0 && c == '\n' -> {
                    val continues = code.substring(from, i).isBlank() || CONTINUATION.matchesAt(code, i + 1)
                    if (!continues) break
                }
            }
            i++
        }
        return code.substring(from, i)
    }
}
