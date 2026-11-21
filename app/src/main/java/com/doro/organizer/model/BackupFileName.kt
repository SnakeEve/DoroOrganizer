package com.doro.organizer.model

import java.time.LocalDate

/**
 * The suggested SAF name and the share display name of a backup made on [today], the local date (DEC-83):
 * `LocalDate.toString()` is ISO `YYYY-MM-DD`. Restore never depends on it.
 */
fun backupFileName(today: LocalDate): String = "doro-organizer-backup-$today.db"
