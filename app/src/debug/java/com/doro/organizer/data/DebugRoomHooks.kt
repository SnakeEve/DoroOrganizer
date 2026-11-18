package com.doro.organizer.data

import androidx.room.RoomDatabase
import com.doro.organizer.core.log.AppLog
import com.doro.organizer.data.db.AppDatabase

/** Debug: logs every Room query (db-lifecycle.md § Room builder). */
internal object DebugRoomHooks {
    fun install(builder: RoomDatabase.Builder<AppDatabase>) {
        // A direct executor: one cheap log call per query, written in query order.
        builder.setQueryCallback({ sql, bindArgs -> AppLog.d(describe(sql, bindArgs)) }, Runnable::run)
    }

    /** The SQL text and the bind-argument count, never the values (security.md § 10, DEC-70). */
    fun describe(sql: String, bindArgs: List<Any?>): String = "query (${bindArgs.size} args): $sql"
}
