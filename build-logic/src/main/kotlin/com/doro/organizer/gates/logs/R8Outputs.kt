package com.doro.organizer.gates.logs

/** Readers for the R8 outputs that the `release-logs` gate inspects. */
internal object R8Outputs {
    /** DEC-70: removes every `android.util.Log` call up to ASSERT, so also `Log.wtf` and `Log.println`. */
    const val LOG_STRIPPING_RULE = "-maximumremovedandroidloglevel 7"

    private val CLASS_MAPPING = Regex("""^(\S+) -> (\S+):$""")

    /** Whether the configuration R8 actually applied contains [LOG_STRIPPING_RULE]. */
    fun hasLogStrippingRule(configuration: Sequence<String>): Boolean = configuration.any { it.trim() == LOG_STRIPPING_RULE }

    /** Obfuscated class name to original class name, from the class lines of `mapping.txt`. */
    fun originalClassNames(mapping: Sequence<String>): Map<String, String> =
        mapping.mapNotNull { CLASS_MAPPING.matchEntire(it.trimEnd()) }.associate { it.groupValues[2] to it.groupValues[1] }
}
