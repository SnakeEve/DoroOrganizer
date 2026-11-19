package com.doro.organizer.data

import com.doro.organizer.core.result.AppError
import com.doro.organizer.core.result.InputRule
import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.data.db.PersonEntity
import com.doro.organizer.data.db.ProjectEntity
import com.doro.organizer.model.MAX_NAME_CHARS
import com.doro.organizer.model.NewEntity

/**
 * The INV-9 check that runs before any write (data-layer.md § Note save, step 1): [InputRule.BlankName] for a name
 * that is blank after `trim()`, null when every name is valid. A name longer than [MAX_NAME_CHARS] is a programming
 * error, because the pickers cut their input there; so is a key used twice within [persons] or [projects], because
 * [CreatedIds] maps each key to one new id and the entity behind the other would be created but never linked (DEC-33).
 */
internal fun newEntityError(persons: List<NewEntity>, projects: List<NewEntity>): AppError? {
    require(persons.hasDistinctKeys() && projects.hasDistinctKeys()) { "new-entity key used twice" }
    val blank = (persons + projects).any { it.storedName() == null }
    return if (blank) AppError.InvalidInput(InputRule.BlankName) else null
}

/**
 * Inserts the entities a save or a batch confirm created (DEC-33) with `created_at = updated_at = now`, inside the
 * caller's transaction (data-layer.md § Transactions and results). Names are stored trimmed (INV-9).
 */
internal suspend fun insertNew(
    db: AppDatabase,
    persons: List<NewEntity>,
    projects: List<NewEntity>,
    now: Long,
): CreatedIds {
    val personDao = db.personDao()
    val projectDao = db.projectDao()
    return CreatedIds(
        persons = persons.associate { entity ->
            val person = PersonEntity(name = entity.requireName(), createdAt = now, updatedAt = now)
            entity.key to personDao.insert(person)
        },
        projects = projects.associate { entity ->
            val project = ProjectEntity(name = entity.requireName(), createdAt = now, updatedAt = now)
            entity.key to projectDao.insert(project)
        },
    )
}

private fun List<NewEntity>.hasDistinctKeys(): Boolean = mapTo(HashSet()) { it.key }.size == size

/** The trimmed name, or null when it is blank. */
private fun NewEntity.storedName(): String? {
    val trimmed = name.trim()
    require(trimmed.length <= MAX_NAME_CHARS) { "name longer than $MAX_NAME_CHARS chars" }
    return trimmed.ifEmpty { null }
}

private fun NewEntity.requireName(): String = requireNotNull(storedName()) { "blank name" }
