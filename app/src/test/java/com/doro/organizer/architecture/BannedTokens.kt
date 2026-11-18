package com.doro.organizer.architecture

/** Rule 2 of architecture.md § Boundary test: banned tokens, grouped as the rule lists them. */
enum class TokenGroup(val tokens: List<BannedToken>) {
    CONCURRENCY(
        listOf(
            BannedToken("runBlocking", """\brunBlocking\b"""),
            BannedToken("GlobalScope", """\bGlobalScope\b"""),
            BannedToken("Thread(", """\bThread\("""),
            BannedToken("Executors.", """\bExecutors\."""),
            BannedToken("newSingleThreadContext", """\bnewSingleThreadContext\b"""),
            BannedToken("newFixedThreadPoolContext", """\bnewFixedThreadPoolContext\b"""),
            BannedToken("Dispatchers.", """\bDispatchers\.""", allowedPackage = "core.coroutines"),
        ),
    ),
    CLOCKS(
        listOf(
            "System.currentTimeMillis", "System.nanoTime", "SystemClock", "Instant.now", "LocalDate.now",
            "LocalDateTime.now", "ZonedDateTime.now", "ZoneId.systemDefault", "TimeZone.getDefault",
            "Calendar.getInstance",
        ).map { BannedToken(it, """\b${Regex.escape(it)}\b""", allowedPackage = "core.time") } +
            BannedToken("Clock.system", """\bClock\.system""", allowedPackage = "core.time") +
            BannedToken("Date()", """\bDate\(\)""", allowedPackage = "core.time"),
    ),
    LOGGING(
        listOf(
            BannedToken("println", """\bprintln\b"""),
            BannedToken("printStackTrace", """\bprintStackTrace\b"""),
            BannedToken("System.out", """\bSystem\.out\b"""),
            BannedToken("System.err", """\bSystem\.err\b"""),
            BannedToken("timber.", """\btimber\."""),
            BannedToken("android.util.Log", """\bandroid\.util\.Log\b""", allowedPackage = "core.log"),
            // The wildcard import and the bare calls would otherwise reach Log without naming android.util.Log.
            BannedToken("android.util.*", """\bandroid\.util\.\*""", allowedPackage = "core.log"),
            BannedToken(
                "Log.<level>(",
                """\bLog\.(?:v|d|i|w|e|wtf|println|isLoggable)\(""",
                allowedPackage = "core.log",
                sample = "Log.d(TAG, message)",
            ),
            BannedToken("setQueryCallback", """\bsetQueryCallback\b""", allowedSourceSet = "debug"),
        ),
    ),
    API_PITFALLS(
        listOf(
            BannedToken(".removeFirst()", """\.removeFirst\(\)"""),
            BannedToken(".removeLast()", """\.removeLast\(\)"""),
        ),
    ),
    DATABASE(
        listOf(
            "allowMainThreadQueries", "allowDataLossOnRecovery", "FrameworkSQLiteOpenHelperFactory",
            "AndroidSQLiteDriver", "minimumSupportedVersion", "rawExecSQL", "SimpleSQLiteQuery",
            "androidx.sqlite.db.framework", "android.database.sqlite.SQLiteDatabase",
            "android.database.sqlite.SQLiteOpenHelper",
        ).map { BannedToken(it, """\b${Regex.escape(it)}\b""") } +
            // Forces explicit imports of the framework exception classes that sqlcipher-android reuses (DEC-08).
            BannedToken("android.database.sqlite.*", """\bandroid\.database\.sqlite\.\*""") +
            BannedToken("fallbackToDestructiveMigration", """\bfallbackToDestructiveMigration\w*""") +
            BannedToken("@RawQuery", """@RawQuery\b"""),
    ),
    UI(listOf(BannedToken("SecureFlagPolicy.SecureOff", """\bSecureFlagPolicy\.SecureOff\b"""))),
    ;

    fun check(source: KotlinSource): List<Violation> = tokens
        .filterNot { it.allowedIn(source) }
        .flatMap { token ->
            token.pattern.findAll(source.code).map { source.violation(it.range.first, "banned: ${token.display}") }
        }
        .sortedBy { it.line }
}

/**
 * @param allowedPackage the package below the app package, with its subpackages, where the token is allowed
 * @param allowedSourceSet the source set where the token is allowed
 * @param sample source text that uses the token, for the planted test
 */
class BannedToken(
    val display: String,
    pattern: String,
    val allowedPackage: String? = null,
    val allowedSourceSet: String? = null,
    val sample: String = display,
) {
    val pattern = Regex(pattern)

    fun allowedIn(source: KotlinSource): Boolean {
        val pkg = allowedPackage?.let { "${Layer.APP_PACKAGE}.$it" }
        val inPackage = pkg != null &&
            (source.packageName == pkg || source.packageName?.startsWith("$pkg.") == true)
        return inPackage || source.sourceSet == allowedSourceSet
    }
}
