package com.doro.organizer.architecture

/**
 * Rule 1 of architecture.md § Boundary test: app references the import table forbids (imports and fully qualified
 * names alike), third-party packages outside their only layers, non-Kotlin imports and platform names in `model`,
 * the repository and settings tokens in `ui.gate`, and `debug` outside the debug source set.
 */
object ImportRule {
    private val APP_REFERENCE = Regex("""\b${Regex.escape(Layer.APP_PACKAGE)}((?:\.(?:\w+|\*))*)""")
    private val IMPORT = Regex("""^[ \t]*import\s+([\w.]+(?:\.\*)?)""", RegexOption.MULTILINE)
    private val PACKAGE_LINE = Regex("""^[ \t]*package\s+[\w.]+""", RegexOption.MULTILINE)
    private val GATE_TOKENS = Regex("""\b(noteRepository|entityRepository|settingsStore)\b""")

    fun check(source: KotlinSource): List<Violation> {
        val packageName = source.packageName ?: return listOf(source.violation(0, "file has no package"))
        val layer = Layer.of(packageName)
            ?: return listOf(source.violation(0, "package $packageName is not a row of the import table"))
        // The package line names the file's own package, which is no reference.
        val code = PACKAGE_LINE.replace(source.code) { " ".repeat(it.value.length) }
        return appReferences(source, code, layer) + thirdParty(source, code, layer) +
            modelImports(source, code, layer) + gateTokens(source, code, layer) + debugOnly(source, layer)
    }

    private fun appReferences(source: KotlinSource, code: String, layer: Layer): List<Violation> =
        APP_REFERENCE.findAll(code).mapNotNull { match ->
            val segments = match.groupValues[1].split('.').filter { it.isNotEmpty() }
            val packageSegments = segments.takeWhile { it != "*" && it.first().isLowerCase() }
            val type = segments.getOrNull(packageSegments.size) // a type name, "*" or null (a package or function)
            val target = Layer.ofRelative(packageSegments.joinToString("."))
                ?: return@mapNotNull source.violation(match.range.first, "${match.value} is in no import-table row")
            val access = ImportTable.access(layer, target)
            val allowed = when {
                // R holds the generated resources: it lives in the root package but is no root-package component.
                target == Layer.ROOT && type == "R" -> true
                access == null -> false
                access is Access.Only -> type != null && type in access.types
                access is Access.Except -> type != null && type != "*" && !access.type.matches(type)
                else -> true
            }
            val message = "${layer.label} must not reference ${match.value}"
            if (allowed) null else source.violation(match.range.first, message)
        }.toList()

    private fun thirdParty(source: KotlinSource, code: String, layer: Layer): List<Violation> {
        if (layer.isRootLike) return emptyList()
        return ImportTable.onlyIn.filterValues { layer !in it }.keys.flatMap { prefix ->
            Regex("""\b${Regex.escape(prefix)}\.""").findAll(code).map {
                source.violation(it.range.first, "${layer.label} must not use $prefix")
            }.toList()
        }
    }

    private fun modelImports(source: KotlinSource, code: String, layer: Layer): List<Violation> {
        if (layer != Layer.MODEL) return emptyList()
        val imports = IMPORT.findAll(code)
            .filter { match -> ImportTable.modelImports.none { match.groupValues[1].startsWith(it) } }
            .map { source.violation(it.range.first, "model must not import ${it.groupValues[1]}") }
        // Imports are reported above, so only the names written out in the code remain.
        val body = IMPORT.replace(code) { " ".repeat(it.value.length) }
        val qualified = ImportTable.modelForbiddenQualified.findAll(body)
            .map { source.violation(it.range.first, "model must not use ${it.value.dropLast(2)}") }
        return (imports + qualified).toList()
    }

    private fun gateTokens(source: KotlinSource, code: String, layer: Layer): List<Violation> {
        if (layer != Layer.UI_GATE) return emptyList()
        return GATE_TOKENS.findAll(code).map {
            source.violation(it.range.first, "ui.gate must not use ${it.value}")
        }.toList()
    }

    private fun debugOnly(source: KotlinSource, layer: Layer): List<Violation> =
        if (layer == Layer.DEBUG && source.sourceSet != "debug") {
            listOf(source.violation(0, "package debug belongs in src/debug only"))
        } else {
            emptyList()
        }

    private val Layer.label get() = if (this == Layer.ROOT) "root package" else pkg
}
