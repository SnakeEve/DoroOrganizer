package com.doro.organizer.testing

import com.doro.organizer.data.db.NoteDao
import com.doro.organizer.data.db.NoteRowRaw
import com.doro.organizer.model.NoteFilter
import com.doro.organizer.model.NoteQuery
import com.doro.organizer.model.Perspective
import com.doro.organizer.model.toQuery
import kotlinx.coroutines.flow.first

/** The query of the All perspective without filters: every note. */
val ALL_NOTES: NoteQuery = checkNotNull(NoteFilter(Perspective.All).toQuery())

/** The current rows of [query], with its fields passed to `observeRows` by name. */
suspend fun NoteDao.rows(query: NoteQuery = ALL_NOTES): List<NoteRowRaw> = observeRows(
    personId = query.personId,
    projectId = query.projectId,
    orphanOnly = query.orphanOnly,
    undatedOnly = query.undatedOnly,
    fromDay = query.fromDay,
    toDay = query.toDay,
    searchPattern = query.searchPattern,
).first()

/** The ids of [rows], in list order. */
suspend fun NoteDao.listedIds(query: NoteQuery = ALL_NOTES): List<Long> = rows(query).map { it.id }

/** The Select-all ids of [query], unordered. */
suspend fun NoteDao.selectedIds(query: NoteQuery): List<Long> = selectIds(
    personId = query.personId,
    projectId = query.projectId,
    orphanOnly = query.orphanOnly,
    undatedOnly = query.undatedOnly,
    fromDay = query.fromDay,
    toDay = query.toDay,
    searchPattern = query.searchPattern,
)

/** The current marked days in [fromDay]..[toDay] for the person, project and search of [query] (DEC-53). */
suspend fun NoteDao.markedDays(fromDay: Long, toDay: Long, query: NoteQuery): Set<Long> = observeMarkedDays(
    fromDay = fromDay,
    toDay = toDay,
    personId = query.personId,
    projectId = query.projectId,
    searchPattern = query.searchPattern,
).first().toSet()
