package com.doro.organizer.testing

import org.junit.After
import org.junit.Rule

/**
 * The rules of a gate test in their order: the vault directory and aliases, the test files, the scope of the
 * planting [MaintenanceSetup], then the app container of [scene] (testing.md § 7, Wiring). With
 * [realProcessLifecycle] the container keeps the real `ProcessLifecycleOwner` (L-21), with [realBiometrics] the
 * production `BiometricGateway` and biometric key spec (testing.md § 2.1).
 */
abstract class GateTest(realProcessLifecycle: Boolean = false, realBiometrics: Boolean = false) {
    @get:Rule(order = 0)
    val vaults = KeyVaultRule()

    @get:Rule(order = 1)
    val databases = TestDatabases()

    @get:Rule(order = 2)
    val scope = ApplicationScopeRule()

    val scene = GateScene(databases, vaults, scope, realProcessLifecycle, realBiometrics)

    @get:Rule(order = 3)
    val app = scene.app

    @After
    fun tearDownActivity() {
        scene.closeActivity()
    }
}
