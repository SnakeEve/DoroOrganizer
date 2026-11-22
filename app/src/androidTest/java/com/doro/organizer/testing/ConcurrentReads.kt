package com.doro.organizer.testing

import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs [rounds] schema reads on each of [threads] threads that start together, and waits for all of them. A WAL pool
 * of sqlcipher-android serves read-only statements from reader connections beside the primary one and opens them on
 * demand, so afterwards it holds more than one connection; a pool limited to one connection serves every read from
 * its primary connection.
 */
fun SupportSQLiteDatabase.readOnThreads(threads: Int = 4, rounds: Int = 25) {
    val executor = Executors.newFixedThreadPool(threads)
    try {
        val start = CyclicBarrier(threads)
        val runs = List(threads) {
            executor.submit(
                Callable {
                    start.await(10, TimeUnit.SECONDS)
                    repeat(rounds) { longOf("SELECT count(*) FROM sqlite_master") }
                },
            )
        }
        runs.forEach { it.get(30, TimeUnit.SECONDS) }
    } finally {
        executor.shutdownNow()
    }
}
