package com.doro.organizer.architecture

/**
 * The one whitespace rule for user text, `model/TextWhitespace.kt` (DEC-24, DEC-29, DEC-32, DEC-39): Kotlin's own
 * trim and blank checks follow `Char.isWhitespace()`, which differs between the JVM and ART (ART also counts U+180E).
 * Production code calls none of them without a predicate, except in the [ALLOWED] places, which never see user text.
 */
object WhitespaceRule {
    private const val NAMES = "trim|trimStart|trimEnd|isBlank|isNotBlank|isNullOrBlank|isWhitespace"
    // A call without a predicate, a reference to Kotlin's own (`String::isBlank`; a bare `::isWhitespace` names a
    // function of the file), and `ifBlank`.
    private val CALLS =
        Regex("""\b(?:$NAMES)\s*\(\s*\)|\b(?:Char|String|CharSequence)\s*::\s*(?:$NAMES)\b|\bifBlank\b""")

    /** Exact source snippets, per file, that may use Kotlin's whitespace, each with the reason it is not user text. */
    val ALLOWED: Map<String, Map<String, String>> = mapOf(
        "src/main/java/com/doro/organizer/data/maintenance/RestoreValidation.kt" to
            mapOf("text?.trim()" to "the schema SQL of restore check 17"),
        "src/main/java/com/doro/organizer/data/maintenance/StartupRules.kt" to
            mapOf("marker.trim()" to "the digits of the app's own marker file"),
    )

    fun check(source: KotlinSource): List<Violation> = check(source, ALLOWED)

    fun check(source: KotlinSource, allowed: Map<String, Map<String, String>>): List<Violation> {
        val code = source.code
        val allowedRanges = allowed[source.path].orEmpty().keys.flatMap { snippet ->
            Regex(Regex.escape(snippet)).findAll(code).map { it.range }.toList()
        }
        return CALLS.findAll(code)
            .filterNot { match -> allowedRanges.any { match.range.first in it && match.range.last in it } }
            .map { source.violation(it.range.first, "Kotlin whitespace on user text: ${it.value} (use TextWhitespace)") }
            .toList()
    }
}
