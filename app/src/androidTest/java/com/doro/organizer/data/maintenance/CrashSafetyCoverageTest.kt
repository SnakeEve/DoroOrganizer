package com.doro.organizer.data.maintenance

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doro.organizer.debug.CrashOp
import com.doro.organizer.testing.CrashMatrix
import com.doro.organizer.testing.Plant
import com.doro.organizer.testing.CrashScene
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The meta-test of L-15: every flow of [CrashMatrix] runs once in this process with a recording fault hook, and the
 * steps the pipeline really passes are exactly the steps the matrix has an expectation for, in order. A step without
 * an expectation, or an expectation for a step the pipeline never reaches (which would only time out in
 * `CrashSafetyTest`), fails here. The vault-injected faults die at no step: the read-back faults of CS-14 and CS-38
 * after their operation, the kill of CS-30 right after its slot commit.
 */
@RunWith(AndroidJUnit4::class)
class CrashSafetyCoverageTest {
    private val scene = CrashScene()

    @After
    fun wipe() = scene.wipe()

    @Test
    fun everyStepOfEveryFlow_hasAnExpectation() = runBlocking {
        for (flow in CrashMatrix.killedAtAStep) {
            scene.wipe()
            scene.plant(flow)
            val seen = mutableListOf<PipelineStep>()
            // The step is irrelevant here: the recording hook never kills.
            scene.runInProcess(CrashScene.requestFor(flow, PipelineStep.PRECHECK)) { op, step ->
                if (op == flow.op.hookOp) synchronized(seen) { seen += step }
            }
            val expected = CrashMatrix.cases.filter { it.flow == flow }.map { it.step }
            assertEquals("$flow", expected, synchronized(seen) { seen.toList() })
        }
    }

    @Test
    fun everyCase_isKeyedByAMatrixRow() {
        val rows = (1..38).map { "CS-%02d".format(it) }.toSet()
        assertTrue(CrashMatrix.cases.all { it.row in rows })
        // The rows the testing.md L-15 entry names as needing their own cases.
        val named = setOf(
            "CS-05", "CS-06", "CS-14", "CS-17", "CS-22", "CS-24", "CS-25", "CS-26", "CS-37", "CS-29", "CS-30", "CS-31",
            "CS-38",
        )
        assertEquals(emptySet<String>(), named - CrashMatrix.cases.map { it.row }.toSet())
        // The flows it names: re-creating a slot over a dead one, and encrypted backups.
        assertTrue(CrashMatrix.flows.any { it.op == CrashOp.CreateBioSlot && it.plant == Plant.PasswordWithDeadSlot })
        for (op in listOf(CrashOp.BackupToDocument, CrashOp.BackupForShare, CrashOp.Restore)) {
            assertTrue("$op encrypted", CrashMatrix.flows.any { it.op == op && it.encryptedBackup })
        }
    }
}
