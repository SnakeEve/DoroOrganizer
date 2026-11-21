package com.doro.organizer.testapk

/**
 * The keep rules of a minified app whose test APK runs in the app's process and calls into it (DEC-103 (5)).
 *
 * AGP leaves every library of the tested app's runtime classpath out of the test APK, so at run time the test APK uses
 * the app's minified copies of those libraries and of the app's own classes. R8 minifies the app without seeing the
 * test APK. Three rule sets close that gap:
 *  1. R8 TraceReferences lists every class, field and method of the app that the test APK references, as plain `-keep`
 *     rules (see [GenerateTestApkKeepRulesTask]);
 *  2. [supertypeChains]: every app type a test-APK class extends or implements, or that a test-APK lambda implements,
 *     with all of that type's own supertypes that the app defines, is kept whole by [wholeTypeRule]. A test class
 *     overrides members that no reference names (`TestScopeImpl.toString()` over `JobSupport`), which R8 would otherwise
 *     finalize, and it inherits members that only the platform's types name (`Iterator.next()` of an app
 *     `AbstractIterator`), which R8 removes when no app subclass is instantiated (R8 full mode);
 *  3. [serviceRules]: every app type the test APK provides a `META-INF/services` implementation of keeps its name and
 *     stays pinned, so R8 does not rewrite the app's `ServiceLoader.load` of it into the app's own providers
 *     (kotlinx-coroutines-test's `TestMainDispatcherFactory` and `ExceptionCollectorAsService`, which `runTest` needs).
 */
internal object TestApkKeepRules {
    /** The first line of every generated file; the `release-logs` gate fails a release configuration that holds it. */
    const val MARKER = "# doro.test-apk-keep-rules"

    /** Only `qa` and `benchmark`, never `release` (DEC-103 (5)). */
    val BUILD_TYPES = setOf("qa", "benchmark")

    /**
     * Whether a variant gets generated rules: a minified `qa` or `benchmark` variant with a test APK, which AGP builds
     * only for the `-PtestBuildType` build type.
     */
    fun appliesTo(buildType: String?, isMinifyEnabled: Boolean, hasAndroidTest: Boolean): Boolean =
        buildType in BUILD_TYPES && isMinifyEnabled && hasAndroidTest

    /**
     * The app types to keep whole, by binary name (`a/b/C`), sorted: the app-defined direct supertypes and lambda
     * types ([ClassHeader.lambdaTypes], interfaces only) of every [testApk] class, and transitively their app-defined
     * supertypes. A supertype the test APK defines itself is followed no further; one neither defines (the platform's)
     * ends the chain.
     */
    fun supertypeChains(testApk: Map<String, ClassHeader>, app: Map<String, ClassHeader>): List<String> {
        val kept = sortedSetOf<String>()
        val pending = ArrayDeque<String>()
        testApk.values.forEach { header ->
            header.supertypes.filter { it !in testApk && it in app }.forEach(pending::add)
            header.lambdaTypes.filter { it !in testApk && app[it]?.isInterface == true }.forEach(pending::add)
        }
        while (pending.isNotEmpty()) {
            val type = pending.removeFirst()
            if (!kept.add(type)) continue
            app.getValue(type).supertypes.filter { it in app }.forEach(pending::add)
        }
        return kept.toList()
    }

    /** `-keep class a.b.C { *; }`: the type, all its members, no optimization of names, flags or signatures. */
    fun wholeTypeRule(binaryName: String): String = "-keep class ${binaryName.replace('/', '.')} { *; }"

    /** One [wholeTypeRule] for every service type of the test APK ([ClassHeader.serviceTypes]) that the app defines. */
    fun serviceRules(serviceTypes: List<String>, app: Map<String, ClassHeader>): List<String> =
        serviceTypes.filter { it in app }.sorted().map(::wholeTypeRule)

    /**
     * The `Missing class|method|field …` lines of a TraceReferences report whose holder class [allowlist] does not
     * cover, without their `(referenced from: …)` part, sorted. An entry names a class (`a.b.C`) or every class of one
     * package (`a.b.*`, not its subpackages). A `Missing …` line this cannot parse is returned too.
     */
    fun unexpectedMissing(report: List<String>, allowlist: List<String>): List<String> {
        fun allowed(holder: String) = allowlist.any { entry ->
            if (entry.endsWith(".*")) holder.substringBeforeLast('.', "") == entry.removeSuffix(".*") else holder == entry
        }
        return report.mapNotNull { line ->
            val missing = MISSING.find(line) ?: return@mapNotNull null
            val item = missing.value.substringBefore(" (referenced from").trimEnd()
            val holder = holderOf(missing.groupValues[1], item.substringAfter("Missing ${missing.groupValues[1]} "))
            item.takeIf { holder == null || !allowed(holder) }
        }.distinct().sorted()
    }

    private val MISSING = Regex("""Missing (\S+) .*""")

    /** `a.b.C`; `R a.b.C.m(A, B)`; `T a.b.C.f`: the holder class, or null. */
    private fun holderOf(kind: String, text: String): String? = when (kind) {
        "class" -> text.takeIf { ' ' !in it }
        "method" -> text.substringAfter(' ', "").substringBefore('(').substringBeforeLast('.', "").ifEmpty { null }
        "field" -> text.substringAfter(' ', "").substringBeforeLast('.', "").ifEmpty { null }
        else -> null
    }
}
