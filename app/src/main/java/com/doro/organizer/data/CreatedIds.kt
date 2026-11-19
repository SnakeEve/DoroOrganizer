package com.doro.organizer.data

/**
 * The ids of the entities `EntityRepository.insertNew` created, keyed by `NewEntity.key` (data-layer.md
 * § Transactions and results).
 */
data class CreatedIds(val persons: Map<String, Long>, val projects: Map<String, Long>) {
    override fun toString() = "CreatedIds(persons=${persons.size}, projects=${projects.size})" // DEC-70
}
