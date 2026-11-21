package com.doro.organizer.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * testing.md I-47: `preview`, `content` and `content_fold` are the last columns of `notes`, in this order, so the small
 * columns that `WHERE` and `ORDER BY` read, then the stored preview that lists read (DEC-106), sit before any overflow
 * page of the two large columns (data-layer.md § Size and performance). Checked in every exported schema version.
 */
class SchemaJsonTest {
    /** Keeps the per-version checks below from passing vacuously once the database exists (DEC-14). */
    @Test
    fun schemaExported_exactlyWhenAppDatabaseExists() {
        assertEquals(
            "AppDatabase declared / schema JSON in ${SchemaFiles.directory}",
            SchemaFiles.databaseDeclared(),
            SchemaFiles.versions().isNotEmpty(),
        )
    }

    @Test
    fun everyVersion_notesEndsWithContentColumns() {
        SchemaFiles.versions().forEach { (version, file) ->
            assertTrue("$version.json", endsWithContentColumns(file.readText()))
        }
    }

    @Test
    fun createSqlColumns_readsColumnOrderAndSkipsConstraints() {
        val junction = schema(JUNCTION_SQL, "note_persons")
        assertEquals(listOf("note_id", "person_id"), SchemaFiles.createSqlColumns(junction, "note_persons"))
        assertTrue(endsWithContentColumns(schema(NOTES_SQL)))
    }

    @Test
    fun contentColumnsNotLast_isDetected() {
        val misplaced = NOTES_SQL.replace(", `content` TEXT NOT NULL, `content_fold` TEXT NOT NULL", "")
            .replace("`id` INTEGER", "`content` TEXT NOT NULL, `content_fold` TEXT NOT NULL, `id` INTEGER")
        assertFalse(endsWithContentColumns(schema(misplaced)))
    }

    /** DEC-106: the stored preview must sit right before the two large columns, not behind them or elsewhere. */
    @Test
    fun previewNotRightBeforeTheContentColumns_isDetected() {
        val behind = NOTES_SQL.replace("`preview` TEXT NOT NULL, ", "").replace(
            "`content_fold` TEXT NOT NULL)",
            "`content_fold` TEXT NOT NULL, `preview` TEXT NOT NULL)",
        )
        val early = NOTES_SQL.replace("`preview` TEXT NOT NULL, ", "")
            .replace("`date` INTEGER, ", "`preview` TEXT NOT NULL, `date` INTEGER, ")
        val missing = NOTES_SQL.replace("`preview` TEXT NOT NULL, ", "")

        listOf(behind, early, missing).forEach { assertFalse(it, endsWithContentColumns(schema(it))) }
    }

    private fun endsWithContentColumns(json: String): Boolean =
        SchemaFiles.createSqlColumns(json, "notes").takeLast(3) == listOf("preview", "content", "content_fold")

    private fun schema(createSql: String, table: String = "notes") =
        """{"formatVersion":1,"database":{"version":1,"entities":[{"tableName":"$table","createSql":"$createSql"}]}}"""

    private companion object {
        const val NOTES_SQL = "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `date` INTEGER, `checked` INTEGER NOT NULL DEFAULT 0, " +
            "`checked_at` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
            "`preview` TEXT NOT NULL, `content` TEXT NOT NULL, `content_fold` TEXT NOT NULL)"
        const val JUNCTION_SQL = "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`note_id` INTEGER NOT NULL, " +
            "`person_id` INTEGER NOT NULL, PRIMARY KEY(`note_id`, `person_id`), FOREIGN KEY(`note_id`) " +
            "REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
    }
}
