package com.doro.organizer.testing

import com.doro.organizer.data.db.PREVIEW_EXPR

/**
 * A note row written with raw SQL, independently of the DAOs, for fixtures that plant data. SQLite computes `preview`
 * from the bound content with the production [PREVIEW_EXPR] in the same statement, as the single write path does
 * (data-layer.md INV-13, DEC-106); no test computes a preview in Kotlin. The numbered parameters are
 * `?1` id (null for the next id), `?2` date, `?3` checked, `?4` checked_at, `?5` created_at, `?6` updated_at,
 * `?7` content and `?8` content_fold, so a bind array lists them in this order.
 */
const val RAW_NOTE_INSERT =
    "INSERT INTO notes (id, date, checked, checked_at, created_at, updated_at, preview, content, content_fold) " +
        "SELECT ?1, ?2, ?3, ?4, ?5, ?6, $PREVIEW_EXPR, content, ?8 FROM (SELECT ?7 AS content)"
