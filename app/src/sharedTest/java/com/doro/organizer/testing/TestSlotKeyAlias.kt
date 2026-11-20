package com.doro.organizer.testing

/**
 * The `bioSlot.keyAlias` of every planted and every fake biometric slot: the fresh-alias form that the key-state accepts
 * (DEC-98, security.md § 3.5). No Keystore key has it, so a planted slot that names it reads `Dead` on the device.
 */
const val TEST_SLOT_KEY_ALIAS = "doro_bio_v1_0123456789abcdef0123456789abcdef"
