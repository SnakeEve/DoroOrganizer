package com.doro.organizer.security

import java.security.MessageDigest

/**
 * SHA-256 of [bytes]. Lives here because only `security` may import `java.security` (architecture.md § Import rules);
 * a restore from `Ready` compares the live key with the key its export used by digest (db-lifecycle.md § Restore,
 * steps 11 and 13), so no second copy of the key outlives the export.
 */
fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
