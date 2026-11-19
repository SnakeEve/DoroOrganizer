package com.doro.organizer.data

/**
 * The most ids one `IN (:ids)` statement binds (data-layer.md § Write rules): longer lists are split into chunks
 * inside the same transaction, below any `SQLITE_MAX_VARIABLE_NUMBER`.
 */
internal const val SQL_CHUNK = 900
