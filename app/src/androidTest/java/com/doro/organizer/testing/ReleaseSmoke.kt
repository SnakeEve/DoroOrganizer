package com.doro.organizer.testing

/**
 * A black-box smoke test of the `release-smoke` job (testing.md § 9, DEC-13): it runs only on the R8-minified `qa`
 * build, through `-Pandroid.testInstrumentationRunnerArguments.annotation=com.doro.organizer.testing.ReleaseSmoke`.
 * An annotated class touches no app class but `MainActivity`, because R8 renames the others in that build.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class ReleaseSmoke
