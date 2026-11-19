package com.doro.organizer.security

/**
 * `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)`, mapped (security.md § 7.1). `Unavailable` covers
 * HW_UNAVAILABLE, SECURITY_UPDATE_REQUIRED, NO_HARDWARE and UNSUPPORTED.
 */
enum class BiometricAvailability { Success, StatusUnknown, NoneEnrolled, Unavailable }
