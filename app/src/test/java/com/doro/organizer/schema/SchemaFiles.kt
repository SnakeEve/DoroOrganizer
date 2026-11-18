package com.doro.organizer.schema

import com.doro.organizer.architecture.SourceTree
import java.io.File
import org.json.JSONObject

/** The Room schema JSON files that the Room Gradle plugin exports into `app/schemas` (DEC-07, DEC-14). */
object SchemaFiles {
    val directory: File = SourceTree.srcRoot.resolveSibling("schemas/com.doro.organizer.data.db.AppDatabase")

    private val APP_DATABASE = Regex("""\bclass\s+AppDatabase\b""")

    /**
     * Whether a production source declares `AppDatabase` in `data.db`, wherever the file lives; once it does, its
     * exported schema must exist too.
     */
    fun databaseDeclared(): Boolean = SourceTree.production()
        .any { it.packageName == "com.doro.organizer.data.db" && APP_DATABASE.containsMatchIn(it.code) }

    /** Schema version to file, for every `<version>.json` in [directory]. */
    fun versions(): Map<Int, File> =
        directory.listFiles { file -> file.isFile && file.name.matches(Regex("""\d+\.json""")) }
            .orEmpty()
            .associateBy { it.nameWithoutExtension.toInt() }

    /** The column names of [table] in the order of its exported `createSql`. */
    fun createSqlColumns(schemaJson: String, table: String): List<String> {
        val entities = JSONObject(schemaJson).getJSONObject("database").getJSONArray("entities")
        val entity = (0 until entities.length()).map(entities::getJSONObject)
            .single { it.getString("tableName") == table }
        val createSql = entity.getString("createSql")
        val definitions = createSql.substring(createSql.indexOf('(') + 1, createSql.lastIndexOf(')'))
        return splitTopLevel(definitions)
            .map(String::trim)
            .filter { it.startsWith("`") } // column definitions; table constraints start with a keyword
            .map { it.substring(1, it.indexOf('`', 1)) }
    }

    private fun splitTopLevel(definitions: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        definitions.forEachIndexed { i, c ->
            when {
                c == '(' -> depth++
                c == ')' -> depth--
                c == ',' && depth == 0 -> { parts += definitions.substring(start, i); start = i + 1 }
            }
        }
        return parts + definitions.substring(start)
    }
}
