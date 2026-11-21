package com.doro.organizer.testing

/**
 * A performance test of testing.md § 8. The `device-tests` job excludes it with `notAnnotation`; the `perf` job runs
 * only these, on the `benchmark` build (testing.md § 9). Every annotated class measures through a [PerfRecorder].
 * P-5 is no instrumented test: it is the `am start -W` procedure of testing.md § 8 over `seed-10k.db`.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class PerfTest
