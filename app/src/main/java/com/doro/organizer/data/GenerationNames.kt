package com.doro.organizer.data

/** `doro-<n>.db` and its sidecars (db-lifecycle.md § Files on disk); nothing else in `databases/` is ever touched. */
private val GENERATION_FILE = Regex("""^doro-(\d+)\.db(-wal|-shm|-journal|-mj[0-9A-Fa-f]+)?$""")

/** The generation number of a generation file name, or null for any other name (and for a number beyond Long). */
fun generationOf(fileName: String): Long? = GENERATION_FILE.matchEntire(fileName)?.groupValues?.get(1)?.toLongOrNull()

/**
 * The next generation (db-lifecycle.md § Generation numbers): above the committed one ([committed], 0 without a valid
 * key-state) and above every number [inUse] on disk, so a new file never meets a stale sidecar under its name.
 */
fun nextGeneration(committed: Long, inUse: Set<Long>): Long = 1 + maxOf(committed, inUse.maxOrNull() ?: 0L)
