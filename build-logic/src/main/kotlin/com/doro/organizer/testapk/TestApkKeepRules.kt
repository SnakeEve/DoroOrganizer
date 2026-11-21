package com.doro.organizer.testapk

import java.io.File

/**
 * The keep rules of a minified app whose test APK runs in the app's process and calls into it (DEC-103 (5)).
 *
 * AGP leaves every library of the tested app's runtime classpath out of the test APK, so at run time the test APK uses
 * the app's minified copies of those libraries and of the app's own classes. R8 minifies the app without seeing the
 * test APK. Four rule sets close that gap:
 *  1. R8 TraceReferences lists every class, field and method of the app that the test APK references, as plain `-keep`
 *     rules (see [GenerateTestApkKeepRulesTask]);
 *  2. [supertypeChains]: every app type a test-APK class extends or implements, or that a test-APK lambda implements,
 *     with all of that type's own supertypes that the app defines, is kept whole by [wholeTypeRule]. A test class
 *     overrides members that no reference names (`TestScopeImpl.toString()` over `JobSupport`), which R8 would otherwise
 *     finalize, and it inherits members that only the platform's types name (`Iterator.next()` of an app
 *     `AbstractIterator`), which R8 removes when no app subclass is instantiated (R8 full mode);
 *  3. [serviceRules]: every app type the test APK provides a `META-INF/services` implementation of keeps its name and
 *     stays pinned, so R8 does not rewrite the app's `ServiceLoader.load` of it into the app's own providers
 *     (kotlinx-coroutines-test's `TestMainDispatcherFactory` and `ExceptionCollectorAsService`, which `runTest` needs);
 *  4. [packageRules], for an obfuscating variant only ([obfuscates]): every app subtype of a non-public type that rule
 *     sets 1 to 3 keep keeps its name, and so its package; R8 may still remove, optimize, merge or publicize it.
 *     Observed with R8 9.5.29 (in the `qa` mapping and dex and in a minimal reproduction, `SubtypeRuleR8Test`; not
 *     read from R8's sources): a kept class keeps its access flags, while a renamed subtype may be moved to the root
 *     package, where the runtime refuses to load it (`IllegalAccessError`). In `qa`,
 *     `kotlin.collections.CollectionsKt__MutableCollectionsJVMKt` became `h10` in the root package, extending the
 *     package-private multifile part `CollectionsKt__IterablesKt`, which TraceReferences keeps because it defines a
 *     facade method the test APK calls; the unused parts between them were gone from the mapping.
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
     * Whether R8 obfuscates with these configuration files' texts: none holds a `-dontobfuscate` option. A comment
     * (`#` to the end of the line) is no option. Only an obfuscating R8 renames classes, and so moves them to other
     * packages. Fails on an `-include` option, which this check cannot follow.
     */
    fun obfuscates(configurations: List<String>): Boolean {
        val options = configurations.flatMap { text ->
            text.lineSequence().flatMap { line -> line.substringBefore('#').split(WHITESPACE).asSequence() }.toList()
        }
        check("-include" !in options) {
            "An R8 configuration file of the variant uses -include, which TestApkKeepRules.obfuscates cannot follow; " +
                "keep -dontobfuscate in the variant's own proguardFiles or teach obfuscates to read included files (DEC-103 (5))."
        }
        return "-dontobfuscate" !in options
    }

    /**
     * The files of [proguardFiles] (a variant's `proguardFiles`) outside [buildDirectory], in order: the project's own
     * R8 configuration files. AGP extracts its default files into the build directory; they never disable obfuscation.
     */
    fun ownConfigurations(proguardFiles: List<File>, buildDirectory: File): List<File> =
        proguardFiles.filterNot { it.absoluteFile.normalize().startsWith(buildDirectory.absoluteFile.normalize()) }.distinct()

    /**
     * The types (`a/b/C`) that the plain `-keep class|interface|enum|@interface a.b.C` rules of [rules] keep, sorted:
     * every rule TraceReferences writes, and [wholeTypeRule]. R8 neither renames nor moves such a type, and R8 9.5.29
     * was observed to keep its access flags. [unreadableOptions] lists the option lines this skips.
     */
    fun keptTypes(rules: List<String>): List<String> =
        rules.mapNotNull { KEEP_TYPE.find(it)?.groupValues?.get(1)?.replace('.', '/') }.distinct().sorted()

    /**
     * The option lines (starting with `-`) of [rules] that are neither a plain type `-keep` that [keptTypes] reads nor a
     * `-keeppackagenames`, which keeps no type. [render] fails on any: a kept non-public type that [keptTypes] cannot read
     * would silently get no rule of set 4.
     */
    fun unreadableOptions(rules: List<String>): List<String> = rules.filter { line ->
        line.trimStart().startsWith("-") && KEEP_TYPE.find(line) == null && !line.startsWith("-keeppackagenames ")
    }

    /**
     * For each kept type of [kept] that [app] defines and that is not public ([ClassHeader.isPublic]), sorted: a rule
     * that keeps the names of all its app subtypes, direct and indirect ([subtypeRule]). A subtype that keeps its name
     * keeps its package, so it can still extend or implement the type.
     */
    fun packageRules(kept: Collection<String>, app: Map<String, ClassHeader>): List<String> =
        kept.distinct().sorted().mapNotNull { type -> app[type]?.takeUnless(ClassHeader::isPublic)?.let(::subtypeRule) }

    /**
     * `-keep,allowshrinking,allowoptimization,allowaccessmodification class * extends a.b.C` (`implements` for an
     * interface): every program class or interface that has the type among its supertypes, at any depth, keeps its
     * name. R8 may still remove, optimize, merge or publicize it.
     */
    fun subtypeRule(type: ClassHeader): String =
        "-keep,allowshrinking,allowoptimization,allowaccessmodification class * " +
            "${if (type.isInterface) "implements" else "extends"} ${type.name.replace('/', '.')}"

    /**
     * The generated file. [traced] is TraceReferences' output, [chains] the [supertypeChains], [services] the
     * [serviceRules]. Rule set 4 ([packageRules]) follows only when [obfuscates]; without it, the file holds rule sets
     * 1 to 3 alone. Fails, in every variant, when rule sets 1 to 3 hold an option line that [keptTypes] cannot read
     * ([unreadableOptions]).
     */
    fun render(
        taskPath: String,
        sourceCount: Int,
        targetCount: Int,
        traced: String,
        chains: List<String>,
        services: List<String>,
        app: Map<String, ClassHeader>,
        obfuscates: Boolean,
    ): String = buildString {
        val tracedRules = traced.replace("\r\n", "\n")
        val chainRules = chains.map(::wholeTypeRule)
        val setsOneToThree = tracedRules.lines() + chainRules + services
        val unreadable = unreadableOptions(setsOneToThree)
        check(unreadable.isEmpty()) {
            "Rule sets 1 to 3 hold option lines that TestApkKeepRules.keptTypes cannot read, so rule set 4 could miss a kept " +
                "type (DEC-103 (5)): $unreadable"
        }
        appendLine("$MARKER: generated by $taskPath (DEC-103 (5)); never edit. Test APK: $sourceCount inputs, app: $targetCount inputs.")
        appendLine("# 1. R8 TraceReferences: every class, field and method of the app that the test APK references.")
        append(tracedRules)
        appendLine()
        appendLine("# 2. The ${chains.size} app types that test-APK classes or lambdas extend or implement, with their app supertypes, kept whole.")
        chainRules.forEach(::appendLine)
        appendLine("# 3. The ${services.size} app types the test APK provides META-INF/services implementations of, pinned: R8 then keeps")
        appendLine("#    the app's ServiceLoader.load of them and their names (kotlinx-coroutines-test's providers, which runTest needs).")
        services.forEach(::appendLine)
        if (!obfuscates) return@buildString
        val packages = packageRules(keptTypes(setsOneToThree), app)
        appendLine("# 4. The ${packages.size} non-public app types that rule sets 1 to 3 keep: R8 keeps their access, so every app subtype")
        appendLine("#    keeps its name and package, or R8 may move it away from them (IllegalAccessError). Obfuscating variants only.")
        packages.forEach(::appendLine)
    }

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

    private val WHITESPACE = Regex("""\s+""")

    /** A plain `-keep` (no modifiers) of a named class, interface, enum or annotation. */
    private val KEEP_TYPE = Regex("""^-keep (?:class|interface|enum|@interface) ([^\s{*!?,]+)(?:\s|$)""")

    /** `a.b.C`; `R a.b.C.m(A, B)`; `T a.b.C.f`: the holder class, or null. */
    private fun holderOf(kind: String, text: String): String? = when (kind) {
        "class" -> text.takeIf { ' ' !in it }
        "method" -> text.substringAfter(' ', "").substringBefore('(').substringBeforeLast('.', "").ifEmpty { null }
        "field" -> text.substringAfter(' ', "").substringBeforeLast('.', "").ifEmpty { null }
        else -> null
    }
}
