package com.doro.organizer.data.db

// The rename statements of PersonDao and ProjectDao (data-layer.md § Rename, DEC-113). Guarded updates: they write
// only when the entity exists and its stored name differs from the new one in some code unit (`<>` compares with
// BINARY), so an equal name or a vanished id updates no row, fires no table trigger and re-runs no observer. They
// write only `name` and the entity's own `updated_at`: no link, no note (DEC-21).

const val RENAME_PERSON_SQL = "UPDATE persons SET name = :name, updated_at = :now WHERE id = :id AND name <> :name"

/** The mirror of [RENAME_PERSON_SQL]. */
const val RENAME_PROJECT_SQL = "UPDATE projects SET name = :name, updated_at = :now WHERE id = :id AND name <> :name"
