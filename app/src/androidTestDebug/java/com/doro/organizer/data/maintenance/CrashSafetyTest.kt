package com.doro.organizer.data.maintenance

import com.doro.organizer.testing.CrashCase
import com.doro.organizer.testing.CrashMatrix
import com.doro.organizer.testing.CrashScene
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * L-15 (DEC-87): for every (operation, step) of db-lifecycle.md's step table, the `:crashtest` process is killed at
 * the step's hook; the next start reaches the state of the crash-safety matrix row the case is keyed by, opens with
 * the matching key, with the fingerprint exactly when a slot was committed, and keeps the matrix's four invariants.
 * `CrashSafetyCoverageTest` is the meta-test; `CrashSafetyMoreCasesTest` holds the in-process cases of L-15.
 */
@RunWith(Parameterized::class)
class CrashSafetyTest(private val case: CrashCase) {
    private val scene = CrashScene()

    @Before
    fun wipe() = scene.wipe()

    @After
    fun wipeAfter() = scene.wipe()

    @Test
    fun theNextStartReachesTheMatrixRow() = runBlocking {
        val planted = scene.plant(case.flow)
        scene.runUntilKilled(CrashScene.requestFor(case.flow, case.step))
        scene.nextStart(case, planted)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<CrashCase> = CrashMatrix.cases
    }
}
