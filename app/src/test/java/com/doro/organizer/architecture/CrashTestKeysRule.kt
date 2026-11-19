package com.doro.organizer.architecture

/**
 * The biometric-key spec without user authentication ships in the debug APK, because the `:crashtest` process cannot
 * load test classes (testing.md 5, technique "Biometric slot"). Only the crash-test service may reach it: a reference
 * from any other production file could put a slot key that needs no fingerprint into the running app.
 */
object CrashTestKeysRule {
    private val NAMES = Regex("""\b(?:testBioKeySpec|crashTestVault)\b""")
    private val ALLOWED = setOf("CrashTestKeys.kt", "CrashRuns.kt", "CrashTestService.kt")

    fun check(source: KotlinSource): List<Violation> {
        val allowed = source.sourceSet == "debug" && source.packageName == "${Layer.APP_PACKAGE}.debug" &&
            source.path.substringAfterLast('/') in ALLOWED
        if (allowed) return emptyList()
        return NAMES.findAll(source.code)
            .map { source.violation(it.range.first, "${it.value} outside the crash-test service") }
            .toList()
    }
}
