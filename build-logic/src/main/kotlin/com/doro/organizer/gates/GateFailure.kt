package com.doro.organizer.gates

import org.gradle.api.GradleException

/** Fails the gate with every violation listed, so one run shows all of them. */
internal fun failOnViolations(gate: String, violations: List<String>) {
    if (violations.isEmpty()) return
    throw GradleException(
        "Verification gate '$gate' failed (testing.md § 9):\n" + violations.joinToString("\n") { "  - $it" },
    )
}
