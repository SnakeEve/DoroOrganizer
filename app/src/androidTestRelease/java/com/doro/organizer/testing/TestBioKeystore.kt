package com.doro.organizer.testing

import com.doro.organizer.security.AndroidBioKeystore
import com.doro.organizer.security.BioKeystore

/**
 * The biometric seam of the `qa` and `benchmark` test runs: the production key spec, because the spec without user
 * authentication exists only in the debug APK (architecture.md, ArchitectureTest rule 7). Their tests list and delete
 * slot keys but never create one; a test that does fails at the Keystore instead of running without a fingerprint.
 */
fun testBioKeystore(): BioKeystore = AndroidBioKeystore()
