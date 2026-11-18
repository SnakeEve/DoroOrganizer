package com.doro.organizer.testing

import androidx.sqlite.db.SupportSQLiteDatabase

/** The first column of the single row that [statement] returns, as a `Long`. */
fun SupportSQLiteDatabase.longOf(statement: String): Long =
    query(statement).use { cursor ->
        check(cursor.moveToFirst()) { "no row" }
        cursor.getLong(0)
    }

/** The first column of every row that [statement] returns, as `Long`s. */
fun SupportSQLiteDatabase.longsOf(statement: String): List<Long> =
    query(statement).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }

/** The first column of every row that [statement] returns, as strings. */
fun SupportSQLiteDatabase.stringsOf(statement: String): List<String?> =
    query(statement).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
