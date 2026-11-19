package com.doro.organizer.data.db

/**
 * A picker row of [PERSON_OPTIONS_SQL] or [PROJECT_OPTIONS_SQL], filled through the SQL aliases `createdAt`,
 * `noteCount` and `coLinked`. Unsorted: `EntityRepository` sorts with a `Collator` (DEC-32).
 */
data class EntityOptionRow(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val noteCount: Int,
    val coLinked: Boolean,
) {
    override fun toString() = "EntityOptionRow(id=$id)" // DEC-70
}
