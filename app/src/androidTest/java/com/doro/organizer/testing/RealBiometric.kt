package com.doro.organizer.testing

/**
 * A test that needs an operator's touch on the phone's fingerprint sensor (testing.md § 2.1). The `device-tests` job
 * excludes it with `notAnnotation`; the `real-biometric` job runs only these, before every release (testing.md § 9,
 * DEC-15, DEC-65).
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class RealBiometric
