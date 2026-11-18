package com.doro.organizer.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The Room database (DEC-07, DEC-30). The entities and the exported schema JSON under `app/schemas/` are the source
 * of truth. Built only by `buildRoom`, opened only through `DatabaseHolder` (DEC-81).
 */
@Database(
    entities = [
        NoteEntity::class,
        PersonEntity::class,
        ProjectEntity::class,
        NotePersonCrossRef::class,
        NoteProjectCrossRef::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    companion object {
        /** The schema version; every change raises it by one and ships a migration and its test (DEC-88). */
        const val VERSION = 1

        /** `PRAGMA application_id`, "DORO" in ASCII (DEC-30). */
        const val APPLICATION_ID = 0x444F524F

        /** Manual migrations, in version order (data-layer.md § Schema evolution). Version 1 has none. */
        val MIGRATIONS: Array<Migration> = emptyArray()

        /** Runs for a new file only. `sqlcipher_export` does not copy the id, so every export sets it too (DEC-82). */
        val CALLBACK: RoomDatabase.Callback = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA application_id = $APPLICATION_ID")
            }
        }
    }
}
