package com.doro.organizer.data

import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.model.EntityKind

// Ids may name rows deleted since the UI read them; writes drop those first (data-layer.md § Batch operations,
// § Reactivity). Before inserting links this is required: a link to a vanished row aborts on its foreign key even
// with IGNORE (INV-7).

internal suspend fun AppDatabase.survivingNotes(ids: Collection<Long>): List<Long> =
    ids.chunked(SQL_CHUNK).flatMap { noteDao().existingNoteIds(it) }

internal suspend fun AppDatabase.survivingEntities(kind: EntityKind, ids: Collection<Long>): List<Long> =
    ids.chunked(SQL_CHUNK).flatMap { entities(kind).existingIds(it) }
