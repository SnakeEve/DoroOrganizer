package com.doro.organizer.model

/**
 * The impact dialog of a person or project delete (DEC-34): the entity is linked to [n] notes, and [m] of them
 * (a subset of [n]) keep no person or project and move to Orphans.
 */
data class DeleteImpact(val n: Int, val m: Int) {
    override fun toString() = "DeleteImpact(n=$n, m=$m)"
}
