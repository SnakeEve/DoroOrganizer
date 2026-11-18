package com.doro.organizer.gates.manifest

import java.io.File

/**
 * `res/xml/file_paths.xml` of the backup FileProvider must be exactly
 * `<paths><cache-path name="share" path="share/" /></paths>` (security.md § 9.1): never `files-path`, `root-path` or `path="."`.
 */
internal object FilePaths {
    const val RESOURCE_NAME = "file_paths"
    private const val FILE_NAME = "$RESOURCE_NAME.xml"

    fun isExpected(xml: String): Boolean {
        val root = parseXml(xml)
        if (root.tagName != "paths" || root.plainAttributes().isNotEmpty()) return false
        val entry = root.children().singleOrNull() ?: return false
        return entry.tagName == "cache-path" &&
            entry.children().isEmpty() &&
            entry.plainAttributes() == mapOf("name" to "share", "path" to "share/")
    }

    /**
     * Checks every `file_paths.xml` in the `xml` and qualified `xml-*` directories of [resourceDirectories]. Android
     * picks a qualified variant (for example `xml-v31`) over `xml` at runtime, so only the unqualified file may exist,
     * and it must match. [referenced] is whether the release manifest names the resource; it must then be in these sources.
     */
    fun resourceViolations(resourceDirectories: Collection<File>, referenced: Boolean): List<String> {
        val files = resourceDirectories.sorted().flatMap { directory ->
            directory.listFiles().orEmpty().sorted()
                .filter { it.isDirectory && (it.name == "xml" || it.name.startsWith("xml-")) }
                .map { File(it, FILE_NAME) }
                .filter(File::isFile)
        }
        val unqualified = files.filter { it.parentFile.name == "xml" }
        return buildList {
            (files - unqualified.toSet()).forEach { add("${it.invariantSeparatorsPath}: only an unqualified xml/$FILE_NAME is allowed") }
            unqualified.filterNot { isExpected(it.readText()) }
                .forEach { add("${it.invariantSeparatorsPath} is not exactly the security.md § 9.1 content") }
            if (referenced && unqualified.isEmpty()) add("@xml/$RESOURCE_NAME is referenced, but no xml/$FILE_NAME is in the release sources")
        }
    }
}
