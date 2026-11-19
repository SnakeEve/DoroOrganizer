package com.doro.organizer.model

/**
 * The outcome of a person or project delete (DEC-34): [deleted] is false when the entity was already gone, with
 * both counts 0; otherwise [n] and [m] are the counts recomputed inside the deleting transaction.
 */
data class EntityDeleteResult(val deleted: Boolean, val n: Int, val m: Int) {
    override fun toString() = "EntityDeleteResult(deleted=$deleted, n=$n, m=$m)"
}
