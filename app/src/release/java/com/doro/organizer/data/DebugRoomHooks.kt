package com.doro.organizer.data

import androidx.room.RoomDatabase
import com.doro.organizer.data.db.AppDatabase

/** Release: no query callback (security.md § 10, DEC-70). */
internal object DebugRoomHooks {
    fun install(builder: RoomDatabase.Builder<AppDatabase>) = Unit
}
