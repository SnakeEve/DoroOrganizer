package com.doro.organizer.data

import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook

/**
 * Pins SQLCipher 4 at connection scope (security.md § 3.4). sqlcipher-android calls the hook for every connection its
 * pool opens, so it is passed for every connection with a non-empty key, raw or Room (db-lifecycle.md § Room builder).
 */
internal object CipherCompatibilityHook : SQLiteDatabaseHook {
    override fun preKey(connection: SQLiteConnection) = Unit

    override fun postKey(connection: SQLiteConnection) =
        connection.executeRaw("PRAGMA cipher_compatibility = 4", null, null)
}
