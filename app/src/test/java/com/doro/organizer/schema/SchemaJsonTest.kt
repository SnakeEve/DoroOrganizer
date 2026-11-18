package com.doro.organizer.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * testing.md I-47: `content` and `content_fold` are the last columns of `notes`, so the small columns that `WHERE`
 * and `ORDER BY` read sit before any overflow page (data-layer.md § Size and performance). Checked in every
 * exported schema version.
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

    private fun endsWithContentColumns(json: String): Boolean =
        SchemaFiles.createSqlColumns(json, "notes").takeLast(2) == listOf("content", "content_fold")

    private fun schema(createSql: String, table: String = "notes") =
        """{"formatVersion":1,"database":{"version":1,"entities":[{"tableName":"$table","createSql":"$createSql"}]}}"""

    private companion object {
        const val NOTES_SQL = "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `date` INTEGER, `checked` INTEGER NOT NULL DEFAULT 0, " +
            "`checked_at` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
            "`content` TEXT NOT NULL, `content_fold` TEXT NOT NULL)"
        const val JUNCTION_SQL = "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`note_id` INTEGER NOT NULL, " +
            "`person_id` INTEGER NOT NULL, PRIMARY KEY(`note_id`, `person_id`), FOREIGN KEY(`note_id`) " +
            "REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
    }
}
