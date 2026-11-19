package com.doro.organizer.security

/** The AndroidKeyStore alias of the KEK (security.md § 3.1); the key-state file accepts no other. */
internal const val KEK_ALIAS = "doro_kek_v1"

/** The AndroidKeyStore alias of the biometric key (security.md § 3.7); a `bioSlot` with another alias is dead. */
internal const val BIO_KEY_ALIAS = "doro_bio_v1"
