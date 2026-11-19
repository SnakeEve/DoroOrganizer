package com.doro.organizer.architecture

/**
 * The app opens a transaction only through `AppDatabase.transaction` in `data/WriteTransaction.kt`, which keeps a full
 * disk a classified failure when SQLite rolls the transaction back by itself (db-lifecycle.md § Classification table,
 * Runtime). Room's and SQLite's own transaction calls, and SQL that opens one, appear nowhere else in production code.
 */
object TransactionRule {
    private val CALLS = Regex("""\b(?:withTransaction|runInTransaction|beginTransaction\w*|withWriteTransaction|useWriterConnection)\b""")
    private val SQL = Regex("""^\s*(?:BEGIN|SAVEPOINT)\b""", RegexOption.IGNORE_CASE)
    private const val FRAME = "src/main/java/com/doro/organizer/data/WriteTransaction.kt"

    fun check(source: KotlinSource): List<Violation> {
        if (source.path == FRAME) return emptyList()
        val calls = CALLS.findAll(source.code).map { source.violation(it.range.first, "transaction outside AppDatabase.transaction: ${it.value}") }
        val sql = source.literals.filter { SQL.containsMatchIn(it.text) }
            .map { Violation(source.path, it.line, "transaction SQL outside AppDatabase.transaction") }
        return (calls + sql).sortedBy { it.line }.toList()
    }
}
