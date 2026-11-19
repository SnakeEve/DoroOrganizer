package com.doro.organizer.data.maintenance

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `Running.fraction` of a copy of known length (db-lifecycle.md § Interface and status): whole percents, rising. */
class CopyProgressTest {
    @Test
    fun aCopy_reportsRisingWholePercents_endingAtOne_andCopiesEveryByte() {
        val source = ByteArray(100_000) { it.toByte() }
        val out = ByteArrayOutputStream()
        val reported = mutableListOf<Float>()

        val copied = copyWithProgress(ByteArrayInputStream(source), out, source.size.toLong(), reported::add, bufferSize = 8192)

        assertEquals(source.size.toLong(), copied)
        assertArrayEquals(source, out.toByteArray())
        assertEquals(13, reported.size) // one per 8 KiB chunk, each a new percent
        assertEquals(1f, reported.last())
        assertEquals(reported.sorted().distinct(), reported)
        assertTrue(reported.all { (it * 100).let { p -> p == Math.round(p).toFloat() } })
    }

    @Test
    fun manySmallChunks_reportEachPercentAtMostOnce() {
        val source = ByteArray(1_000_000)
        val reported = mutableListOf<Float>()

        copyWithProgress(ByteArrayInputStream(source), ByteArrayOutputStream(), source.size.toLong(), reported::add, bufferSize = 64)

        assertEquals((0..100).map { it / 100f }, reported)
    }

    @Test
    fun anEmptySource_reportsNothing() {
        val reported = mutableListOf<Float>()

        val copied = copyWithProgress(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), 0, reported::add)

        assertEquals(0L, copied)
        assertEquals(emptyList<Float>(), reported)
    }

    @Test
    fun progress_publishesRunningWithTheFraction_withoutTheFaultHook() {
        val published = mutableListOf<MaintenanceStatus>()
        val hookCalls = mutableListOf<PipelineStep>()
        val run = OperationRun(MaintenanceOp.Backup, { _, step -> hookCalls += step }, published::add) {}

        run.progress(PipelineStep.WRITE_DOCUMENT, 0.25f)

        assertEquals(listOf(MaintenanceStatus.Running(MaintenanceOp.Backup, PipelineStep.WRITE_DOCUMENT, 0.25f)), published)
        assertEquals(emptyList<PipelineStep>(), hookCalls)
    }
}
