package com.doro.organizer.data.maintenance

import com.doro.organizer.core.result.BackupRejection
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.data.db.RestoreChecks

// The checks of db-lifecycle.md § Restore validation that run on an open candidate connection, in the doc's order.
// Each failure throws CandidateRejected; an SQLiteException from the candidate is the caller's to map (Damaged).

/** A restore candidate failed a check of db-lifecycle.md § Restore validation; never escapes the restore pipeline. */
internal class CandidateRejected(val reason: BackupRejection, cause: Throwable? = null) : Exception("backup rejected", cause)

internal fun reject(reason: BackupRejection): Nothing = throw CandidateRejected(reason)

/** Check 15: the tables every schema version so far has (a renaming version turns this into a per-version list). */
private val EXPECTED_TABLES = listOf("notes", "persons", "projects", "note_persons", "note_projects", "room_master_table")

private const val IDENTITY_HASH = "SELECT identity_hash FROM room_master_table WHERE id = 42 LIMIT 1"

/** Check 7: at least one schema object besides `android_metadata`. */
internal fun QueryConnection.checkNotEmpty() {
    if (long("SELECT count(*) FROM sqlite_master WHERE name <> 'android_metadata'") == 0L) reject(BackupRejection.NotABackup)
}

/** Checks 8 and 9: this app's file of a version this build can read; returns its `user_version`. */
internal fun QueryConnection.checkIdentity(): Int {
    if (long("PRAGMA application_id") != AppDatabase.APPLICATION_ID.toLong()) reject(BackupRejection.NotABackup)
    val version = long("PRAGMA user_version")
    if (version > AppDatabase.VERSION) reject(BackupRejection.NewerVersion)
    if (version < 1) reject(BackupRejection.Incompatible)
    return version.toInt()
}

/** Checks 11–16 on a candidate of schema [version]; [encrypted] adds `cipher_integrity_check`. */
internal fun QueryConnection.checkContent(version: Int, encrypted: Boolean, reference: ReferenceSchema) {
    if (version == AppDatabase.VERSION && identityHash() != reference.identityHash) reject(BackupRejection.Incompatible)
    if (rows("PRAGMA integrity_check") != listOf(listOf("ok"))) reject(BackupRejection.Damaged)
    if (encrypted && rows("PRAGMA cipher_integrity_check").isNotEmpty()) reject(BackupRejection.Damaged)
    if (rows("PRAGMA foreign_key_check").isNotEmpty()) reject(BackupRejection.Damaged)
    if (long("SELECT count(*) FROM sqlite_master WHERE type IN ('trigger', 'view')") != 0L) {
        reject(BackupRejection.Incompatible)
    }
    if (EXPECTED_TABLES.any { long("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ?", it) == 0L }) {
        reject(BackupRejection.Incompatible)
    }
    if (RestoreChecks.forVersion(version).any { long(it) != 0L }) reject(BackupRejection.Damaged)
}

/**
 * Check 17 after `MIGRATE_INPUT` (or without it): the candidate's schema object set and identity hash equal the
 * reference's. A hash is only a stored value and Room's open skips its structure check when it matches, so the
 * structure is compared too. After a migration Room's own validation has run and the `sql` texts may differ.
 */
internal fun QueryConnection.checkSchema(reference: ReferenceSchema, migrated: Boolean) {
    if (identityHash() != reference.identityHash) reject(BackupRejection.Incompatible)
    val facts = schemaFacts(reference.tables, reference.indexes)
    val equal = if (migrated) facts.withoutSql() == reference.facts.withoutSql() else facts == reference.facts
    if (!equal) reject(BackupRejection.Incompatible)
}

/** Room's identity hash; null when the table or its row is missing (checks 11 and 17). */
private fun QueryConnection.identityHash(): String? {
    if (long("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'room_master_table'") == 0L) return null
    return rows(IDENTITY_HASH).firstOrNull()?.firstOrNull()
}

/** The expected schema, read from an empty database this build created (check 10). */
internal class ReferenceSchema(
    val identityHash: String,
    val tables: List<String>,
    val indexes: List<String>,
    val facts: SchemaFacts,
) {
    override fun toString() = "ReferenceSchema"
}

internal fun QueryConnection.referenceSchema(): ReferenceSchema {
    val tables = rows(OBJECTS).filter { it[0] == "table" }.map { checkNotNull(it[1]) }
    // (b) covers every index, the autoindexes of PRIMARY KEY and UNIQUE constraints included: their names follow
    // from the table definitions, so identical schemas have identical names.
    val indexes = rows(INDEXES).map { checkNotNull(it[0]) }
    return ReferenceSchema(checkNotNull(identityHash()) { "reference identity" }, tables, indexes, schemaFacts(tables, indexes))
}

/**
 * The schema object set of check 17: (a) the `(type, name, tbl_name)` rows of `sqlite_master`; (b) per table of the
 * reference its columns, foreign keys and indexes, and per index of the reference (autoindexes included) its
 * columns, without column positions (a migrated file may legitimately order columns differently); (c) the
 * whitespace-normalized `sql` of every table and index, which also catches CHECK constraints, generated columns and
 * column order.
 */
internal data class SchemaFacts(
    val objects: Set<List<String?>>,
    val structure: Map<String, List<List<String?>>>,
    val sql: Map<String?, String?>,
) {
    fun withoutSql() = copy(sql = emptyMap())

    override fun toString() = "SchemaFacts"
}

/** Excludes autoindexes and `android_metadata` on both sides (check 17 (a) and (c)). */
private const val OBJECT_FILTER = "name NOT LIKE 'sqlite\\_autoindex\\_%' ESCAPE '\\' AND name <> 'android_metadata'"
private const val OBJECTS = "SELECT type, name, tbl_name FROM sqlite_master WHERE $OBJECT_FILTER"
private const val INDEXES = "SELECT name FROM sqlite_master WHERE type = 'index' ORDER BY name"
private const val SQL_TEXTS = "SELECT name, sql FROM sqlite_master WHERE type IN ('table', 'index') AND $OBJECT_FILTER"
private const val TABLE_COLUMNS = "SELECT name, type, \"notnull\", dflt_value, pk, hidden FROM pragma_table_xinfo(?) ORDER BY name"
private const val TABLE_FOREIGN_KEYS =
    "SELECT \"table\", \"from\", \"to\", on_update, on_delete, \"match\" FROM pragma_foreign_key_list(?) ORDER BY \"table\", \"from\""
private const val TABLE_INDEXES = "SELECT name, \"unique\", origin, partial FROM pragma_index_list(?) ORDER BY name"
private const val INDEX_COLUMNS = "SELECT seqno, name, \"desc\", coll, key FROM pragma_index_xinfo(?) ORDER BY seqno"
private val WHITESPACE = Regex("\\s+")

/** Reads [SchemaFacts] for the reference's [tables] and [indexes]; the names are bound, never interpolated. */
private fun QueryConnection.schemaFacts(tables: List<String>, indexes: List<String>): SchemaFacts {
    val structure = buildMap {
        for (table in tables) {
            put("columns:$table", rows(TABLE_COLUMNS, table))
            put("foreign keys:$table", rows(TABLE_FOREIGN_KEYS, table))
            put("indexes:$table", rows(TABLE_INDEXES, table))
        }
        for (index in indexes) put("index columns:$index", rows(INDEX_COLUMNS, index))
    }
    val sql = rows(SQL_TEXTS).associate { (name, text) -> name to text?.trim()?.replace(WHITESPACE, " ") }
    return SchemaFacts(rows(OBJECTS).toSet(), structure, sql)
}
