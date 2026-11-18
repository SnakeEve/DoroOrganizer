package com.doro.organizer.gates.pagesize

/** ELF part of the `page-size` gate (testing.md § 9, architecture.md § ABI and 16 KB pages, DEC-14). */
internal object PageSizeRules {
    private const val PAGE = 0x4000L

    /**
     * [libraries] maps each `lib/<abi>/<name>.so` entry of the release APK to its bytes. Each library must be
     * listed in [allowedNames] (a new one is reviewed before it ships) and every LOAD segment aligned to a power of two
     * of at least 16 KB.
     */
    fun violations(libraries: Map<String, ByteArray>, allowedNames: Set<String>): List<String> = buildList {
        libraries.toSortedMap().forEach { (entry, bytes) ->
            if (entry.substringAfterLast('/') !in allowedNames) add("$entry is not in config/native-library-allowlist.txt")
            val alignments = try {
                ElfLoadSegments.alignments(bytes)
            } catch (e: IllegalArgumentException) {
                add("$entry: ${e.message}")
                return@forEach
            }
            if (alignments.isEmpty()) add("$entry has no LOAD segment")
            // ELF p_align is a power of two; anything else (for example 0xC000) marks a malformed library.
            alignments.filter { it < PAGE || (it and (it - 1)) != 0L }
                .forEach { add("$entry has a LOAD segment aligned to 0x${it.toString(16)}, expected a power of two of at least 0x4000") }
        }
    }
}
