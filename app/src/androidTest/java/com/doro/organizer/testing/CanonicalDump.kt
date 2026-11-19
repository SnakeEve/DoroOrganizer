package com.doro.organizer.testing

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * testing.md § 7: every table ordered by its primary key (by all columns when it has none, as `sqlite_sequence`),
 * plus `user_version` and `application_id`, as text. Two equal dumps mean the content did not change in between.
 */
fun canonicalDump(db: SupportSQLiteDatabase): Map<String, List<List<String?>>> = buildMap {
    put("user_version", db.rows("PRAGMA user_version"))
    put("application_id", db.rows("PRAGMA application_id"))
    db.rows("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").forEach { (table) ->
        put(table!!, db.rows("SELECT * FROM \"$table\" ORDER BY ${db.orderTerms(table)}"))
    }
}

private fun SupportSQLiteDatabase.orderTerms(table: String): String {
    val columns = rows("PRAGMA table_info(\"$table\")") // cid, name, type, notnull, dflt_value, pk
    val keys = columns.filter { it[5] != "0" }.sortedBy { it[5]!!.toInt() }.map { "\"${it[1]}\"" }
    return keys.ifEmpty { columns.indices.map { "${it + 1}" } }.joinToString()
}

private fun SupportSQLiteDatabase.rows(sql: String): List<List<String?>> = query(sql).use { cursor ->
    buildList {
        while (cursor.moveToNext()) add(List(cursor.columnCount) { cursor.getString(it) })
    }
}
