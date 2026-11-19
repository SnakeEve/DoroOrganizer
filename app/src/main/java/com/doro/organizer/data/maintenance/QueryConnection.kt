package com.doro.organizer.data.maintenance

import android.os.CancellationSignal
import com.doro.organizer.data.RawConnections
import java.io.Closeable
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * A raw SQLCipher connection that reads a restore candidate or the reference database (db-lifecycle.md § Restore
 * validation). Every statement is fixed; the only arguments are bound, and they are table and index names read from
 * the reference this build created. Statements run with [signal], so `cancelRestore()` also stops a long check.
 */
internal class QueryConnection private constructor(
    private val db: SQLiteDatabase,
    private val signal: CancellationSignal?,
) : Closeable {
    fun rows(sql: String, vararg args: String): List<List<String?>> = db.rawQuery(sql, args, signal).use { c ->
        buildList { while (c.moveToNext()) add(List(c.columnCount) { c.getString(it) }) }
    }

    fun long(sql: String, vararg args: String): Long = db.rawQuery(sql, args, signal).use { c ->
        check(c.moveToFirst()) { "no row" }
        c.getLong(0)
    }

    /** A pragma that sets a value and returns no row. */
    fun exec(sql: String) = db.execSQL(sql)

    override fun close() = db.close()

    override fun toString() = "QueryConnection"

    companion object {
        /** Opens the existing [file] with [key] (empty: plaintext), never creating or deleting it. */
        fun open(file: File, key: ByteArray, signal: CancellationSignal?) =
            QueryConnection(RawConnections.open(file, key), signal)
    }
}
