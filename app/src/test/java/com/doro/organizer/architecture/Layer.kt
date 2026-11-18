package com.doro.organizer.architecture

/** The rows of the import table (architecture.md § Import rules), keyed by package below `com.doro.organizer`. */
enum class Layer(val pkg: String) {
    MODEL("model"),
    CORE("core"),
    SETTINGS("settings"),
    SECURITY("security"),
    DATA_DB("data.db"),
    DATA_MAINTENANCE("data.maintenance"),
    DATA("data"),
    SESSION("session"),
    UI_GATE("ui.gate"),
    UI("ui"),

    /**
     * Debug-only tools (architecture.md § Per-build-type files: `debug/CrashTestService.kt` builds an `AppContainer`
     * and runs `DbMaintenance`, testing.md § 5). The import table has no row for it, so it is treated as the root
     * package and is allowed only in the debug source set.
     */
    DEBUG("debug"),
    ROOT(""),
    ;

    /** The root package and `debug` may reference every app package and third-party library. */
    val isRootLike: Boolean get() = this == ROOT || this == DEBUG

    companion object {
        const val APP_PACKAGE = "com.doro.organizer"

        /**
         * The layer of a package below [APP_PACKAGE] given without that prefix (`data.db.x` → [DATA_DB]), the most
         * specific row winning; null for a package the table does not list.
         */
        fun ofRelative(relative: String): Layer? {
            if (relative.isEmpty()) return ROOT
            return entries.filter { it != ROOT && (relative == it.pkg || relative.startsWith(it.pkg + ".")) }
                .maxByOrNull { it.pkg.length }
        }

        /** The layer of a full package name; null outside [APP_PACKAGE] or for an unlisted package. */
        fun of(packageName: String): Layer? = when {
            packageName == APP_PACKAGE -> ROOT
            packageName.startsWith("$APP_PACKAGE.") -> ofRelative(packageName.removePrefix("$APP_PACKAGE."))
            else -> null
        }
    }
}

/** What a layer may reference from another app layer: every type, or only the listed or unlisted type names. */
sealed interface Access {
    data object All : Access
    class Only(val types: Set<String>) : Access
    class Except(val type: Regex) : Access
}

object ImportTable {
    private fun all(vararg layers: Layer): Map<Layer, Access> = layers.associateWith { Access.All }

    /** `KeyVault` and its key-state types (security.md § 3.6), except `SlotCipher`, which ui may hold. */
    private val KEY_STATE_TYPES = listOf(
        "KeyVault", "Kdf", "KeySlot", "BioSlot", "KeyState", "PreparedKey", "SlotUse", "SlotCipherResult",
        "WrapResult", "BioSlotStatus", "UnlockResult", "KeyFailure", "KeyVaultException",
    )

    // The import table lists the security types session may use.
    private val sessionSecurity = Access.Only(
        KEY_STATE_TYPES.toSet() + setOf("SlotCipher", "AppLockController", "LockListener", "LockError", "LockScreen"),
    )

    // ui may use AppLockController and the types of its public signatures, an open set (LockAvailability, SlotCipher,
    // …). What it must never reach is closed: the key-state types (V33, security.md § 10), the seams beneath
    // AppLockController and every production implementation. Prefix matches cover KeyVaultException and the like.
    private val uiSecurity = Access.Except(
        Regex(
            (KEY_STATE_TYPES.map { "$it\\w*" } + listOf(
                "DefaultKeyVault", "BiometricGateway", "BiometricSlotWriter", "UnlockPolicy", "BioKeystore",
                "KekKeystore", "PromptRequest", "PromptResult", "Default\\w+",
            )).joinToString("|"),
        ),
    )

    // ui may use DbMaintenance and the status, result, operation and preview types of its signatures.
    private val uiMaintenance = Access.Except(Regex("MaintenanceListener|MaintenanceFaultHook|Default\\w+"))
    private val repositories = Access.Only(setOf("NoteRepository", "EntityRepository"))
    private val sessionData =
        Access.Only(setOf("DatabaseHolder", "OpenResult", "OpenFailureKind", "CorruptionListener", "SqlcipherEngine"))

    private val allowed: Map<Layer, Map<Layer, Access>> = mapOf(
        Layer.MODEL to emptyMap(),
        Layer.CORE to all(Layer.MODEL),
        Layer.SETTINGS to all(Layer.CORE, Layer.MODEL),
        Layer.SECURITY to all(Layer.CORE, Layer.MODEL, Layer.SETTINGS),
        Layer.DATA_DB to all(Layer.CORE, Layer.MODEL),
        Layer.DATA to all(Layer.CORE, Layer.MODEL, Layer.DATA_DB),
        Layer.DATA_MAINTENANCE to
            all(Layer.CORE, Layer.MODEL, Layer.SETTINGS, Layer.SECURITY, Layer.DATA, Layer.DATA_DB),
        Layer.SESSION to all(Layer.CORE, Layer.MODEL, Layer.SETTINGS, Layer.DATA_MAINTENANCE) +
            mapOf(Layer.SECURITY to sessionSecurity, Layer.DATA to sessionData),
        Layer.UI to all(Layer.CORE, Layer.MODEL, Layer.SETTINGS, Layer.SESSION, Layer.UI_GATE) +
            mapOf(Layer.DATA to repositories, Layer.SECURITY to uiSecurity, Layer.DATA_MAINTENANCE to uiMaintenance),
        // ui.gate: as ui, minus settings and the repositories.
        Layer.UI_GATE to all(Layer.CORE, Layer.MODEL, Layer.SESSION, Layer.UI) +
            mapOf(Layer.SECURITY to uiSecurity, Layer.DATA_MAINTENANCE to uiMaintenance),
    )

    /** How [from] may reference [to]; null when it may not. */
    fun access(from: Layer, to: Layer): Access? = when {
        from.isRootLike || from == to -> Access.All
        else -> allowed.getValue(from)[to]
    }

    /** Third-party packages and the only layers that may use them; root-like layers are exempt. */
    val onlyIn: Map<String, Set<Layer>> = mapOf(
        "androidx.datastore" to setOf(Layer.SETTINGS),
        "androidx.biometric" to setOf(Layer.SECURITY),
        "android.security.keystore" to setOf(Layer.SECURITY),
        "javax.crypto" to setOf(Layer.SECURITY),
        "java.security" to setOf(Layer.SECURITY),
        "androidx.room" to setOf(Layer.DATA, Layer.DATA_DB, Layer.DATA_MAINTENANCE),
        "net.zetetic" to setOf(Layer.DATA, Layer.DATA_DB, Layer.DATA_MAINTENANCE),
    )

    /** `model` is pure Kotlin: the only imports it may have. */
    val modelImports: List<String> =
        listOf("kotlin.", "java.time.", "java.util.Locale", "kotlinx.serialization.", "${Layer.APP_PACKAGE}.model.")

    /** Platform and library packages `model` must not name even fully qualified, outside its imports. */
    val modelForbiddenQualified = Regex("""\b(?:android|androidx|javax|net\.zetetic|kotlinx\.coroutines)\.\w""")
}
