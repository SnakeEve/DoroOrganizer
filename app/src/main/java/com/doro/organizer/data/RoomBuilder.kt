package com.doro.organizer.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.doro.organizer.data.db.AppDatabase
import java.io.File
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * The single Room builder (db-lifecycle.md § Room builder): `DatabaseHolder` builds the live instance with it, and
 * `DbMaintenance` builds temporary instances on staged copies. SQLCipher is the only engine and an empty [rawKey] is
 * plain mode (DEC-08). The factory keeps a reference to [rawKey], so the caller zeroes it only after
 * `RoomDatabase.close()` (security.md § 6.4). Building opens nothing; `openHelper.writableDatabase` does.
 *
 * Never on this builder (DEC-08, DEC-88): destructive migration, `minimumSupportedVersion`, an auto-close timeout,
 * prepackaged databases, multi-instance invalidation, main-thread queries, a driver or a framework helper.
 */
internal fun buildRoom(context: Context, file: File, rawKey: ByteArray, temporary: Boolean): AppDatabase {
    SqlcipherEngine.init()
    val hook = if (rawKey.isEmpty()) null else CipherCompatibilityHook
    // The application context: the live and temporary instances outlive any Activity that might call this.
    return Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, file.absolutePath)
        .openHelperFactory(SupportOpenHelperFactory(rawKey, hook, false))
        .addCallback(AppDatabase.CALLBACK)
        .addMigrations(*AppDatabase.MIGRATIONS)
        // A temporary instance opens a single non-WAL connection: one PBKDF2 run for a passphrase-keyed candidate.
        // The live instance keeps Room's AUTOMATIC journal mode (DEC-89).
        .apply { if (temporary) setJournalMode(RoomDatabase.JournalMode.TRUNCATE) }
        .apply { DebugRoomHooks.install(this) } // the query callback in src/debug, a no-op in src/release
        .build()
}
