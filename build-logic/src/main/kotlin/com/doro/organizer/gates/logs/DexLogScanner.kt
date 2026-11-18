package com.doro.organizer.gates.logs

/**
 * Scans `dexdump -d` output for the calls the `release-logs` gate bans (testing.md § 9, DEC-70, security.md § 10):
 * any `android.util.Log` v/d/i/w/e/wtf/println invoke anywhere (`isLoggable` and `getStackTraceString` stay allowed),
 * and, in app classes, `printStackTrace` or a read of `System.out`/`System.err` (`kotlin.io.println` inlines to it).
 *
 * [originalClassName] maps a dex class name (`a.b`) back to its source name through the R8 mapping.
 */
internal class DexLogScanner(private val originalClassName: (String) -> String) {
    fun violations(dexdump: Sequence<String>): List<String> {
        val found = LinkedHashSet<String>()
        var className = "<no class>"
        for (line in dexdump) {
            val descriptor = CLASS_DESCRIPTOR.find(line)
            if (descriptor != null) {
                className = originalClassName(descriptor.groupValues[1].replace('/', '.'))
                continue
            }
            LOG_CALL.find(line)?.let { found += "$className calls android.util.Log.${it.groupValues[1]}" }
            if (className.startsWith(APP_PACKAGE)) {
                if (PRINT_STACK_TRACE.containsMatchIn(line)) found += "$className calls printStackTrace"
                STANDARD_STREAM.find(line)?.let { found += "$className reads System.${it.groupValues[1]}" }
            }
        }
        return found.toList()
    }

    // dexdump prints member references as `Lpkg/Cls;.name:(…)` (build-tools 36) or `Lpkg/Cls;->name(…)` (older).
    private companion object {
        const val APP_PACKAGE = "com.doro.organizer."
        val CLASS_DESCRIPTOR = Regex("""^\s*Class descriptor\s*:\s*'L([^;]+);'""")
        val LOG_CALL = Regex("""invoke-[\w/-]+ \{[^}]*}, Landroid/util/Log;(?:->|\.)(v|d|i|w|e|wtf|println)[:(]""")
        val PRINT_STACK_TRACE = Regex("""invoke-[\w/-]+ \{[^}]*}, L[^;]+;(?:->|\.)printStackTrace[:(]""")
        val STANDARD_STREAM = Regex("""sget-object \w+, Ljava/lang/System;(?:->|\.)(out|err):""")
    }
}
