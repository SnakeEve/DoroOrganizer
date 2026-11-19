package com.doro.organizer.data

import com.doro.organizer.core.result.AppResult
import com.doro.organizer.model.DeleteImpact
import com.doro.organizer.model.EntityDeleteResult
import com.doro.organizer.model.EntityKind
import com.doro.organizer.model.EntityOption
import com.doro.organizer.model.EntityRef
import kotlinx.coroutines.flow.Flow

/**
 * Person and project reads and deletes (data-layer.md § Editor loads, pickers and existence probes, § Deletes).
 * Entities are created only inside a note save or a batch confirm (DEC-33), by `EntityRepository.insertNew` in that
 * caller's transaction. A vanished entity is a value, never an error.
 */
interface EntityRepository {
    /**
     * Every entity of [kind], zero-note ones included (DEC-33): co-linked ones first, then by `Collator` on the name,
     * then by id (DEC-32). [coLinkedTo] is an id of the other kind, set only for the secondary filters of DEC-28.
     */
    fun observeOptions(kind: EntityKind, coLinkedTo: Long?): Flow<List<EntityOption>>

    /** The entity, or null once it is gone, which resets every reference to it (data-layer.md § Reactivity). */
    fun observe(kind: EntityKind, id: Long): Flow<EntityRef?>

    /** The counts of the delete dialog, read in one transaction (DEC-34); 0 and 0 for a person that is gone. */
    suspend fun personDeleteImpact(id: Long): AppResult<DeleteImpact>

    /** The mirror of [personDeleteImpact]. */
    suspend fun projectDeleteImpact(id: Long): AppResult<DeleteImpact>

    /**
     * Hard-deletes the person and its links in one transaction that recomputes the counts, because the dialog may be
     * stale (DEC-34, spec §1). No note's `updated_at` changes (DEC-21).
     */
    suspend fun deletePerson(id: Long): AppResult<EntityDeleteResult>

    /** The mirror of [deletePerson]. */
    suspend fun deleteProject(id: Long): AppResult<EntityDeleteResult>

    /**
     * Holds the package-internal `insertNew` of data-layer.md § Transactions and results. It runs in the caller's
     * transaction and needs no repository instance.
     */
    companion object
}
