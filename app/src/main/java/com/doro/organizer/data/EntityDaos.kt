package com.doro.organizer.data

import com.doro.organizer.data.db.AppDatabase
import com.doro.organizer.data.db.EntityDao
import com.doro.organizer.model.EntityKind

/** The DAO of [kind]; both share the [EntityDao] shape, so entity code serves persons and projects alike. */
internal fun AppDatabase.entities(kind: EntityKind): EntityDao = when (kind) {
    EntityKind.Person -> personDao()
    EntityKind.Project -> projectDao()
}
