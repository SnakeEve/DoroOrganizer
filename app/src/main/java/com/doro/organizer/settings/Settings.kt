package com.doro.organizer.settings

/**
 * The non-secret settings (architecture.md § SettingsStore, security.md § 2); each default is the first-run value.
 * No member is user text, a secret or a URI (DEC-09).
 */
data class Settings(
    val screenshotProtection: Boolean = false,
    val biometricLock: Boolean = false,
    val encryptBackups: Boolean = false,
    /** Consecutive wrong startup passwords (DEC-62); written by `KeyVault` only. */
    val unlockFailures: Int = 0,
    /** On the `AppTime.elapsedMillis()` base; 0 = no delay (DEC-62). Written by `KeyVault` only. */
    val unlockRetryAtElapsedMs: Long = 0,
    val lockAutoOffNotice: Boolean = false,
    /**
     * The last successful startup-password entry, `AppTime.nowMillis()` base; 0 = absent, the reminder is due
     * (DEC-74). Written by `KeyVault` only.
     */
    val passwordVerifiedAt: Long = 0,
    val rememberedPersonId: Long? = null,
    val rememberedProjectId: Long? = null,
    /** Incremented by every data reset; navigation compares it with its tab keys (architecture.md § Data reset). */
    val dataEpoch: Int = 0,
)
