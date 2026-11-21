package com.doro.organizer.testing

import com.doro.organizer.debug.testBioKeySpec
import com.doro.organizer.security.AndroidBioKeystore
import com.doro.organizer.security.BioKeystore

/**
 * The biometric seam in its test configuration (testing.md § 5, technique "Biometric slot"): the debug
 * `testBioKeySpec`, so a slot key works without a touch.
 */
fun testBioKeystore(): BioKeystore = AndroidBioKeystore(::testBioKeySpec)
