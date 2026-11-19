package com.doro.organizer.testing

import org.junit.After
import org.junit.Rule

/**
 * The rules of a gate test in their order: the vault directory and aliases, the test files, the scope of the
 * planting [MaintenanceSetup], then the app container of [scene] (testing.md § 7, Wiring).
 */
abstract class GateTest {
    @get:Rule(order = 0)
    val vaults = KeyVaultRule()

    @get:Rule(order = 1)
    val databases = TestDatabases()

    @get:Rule(order = 2)
    val scope = ApplicationScopeRule()

    val scene = GateScene(databases, vaults, scope)

    @get:Rule(order = 3)
    val app = scene.app

    @After
    fun closeActivity() {
        scene.finish()
    }
}
