package com.doro.organizer.architecture

/**
 * Production code syncs a file only through `core.io.syncFile` (`core/io/SyncFile.kt`), which calls `Os.fsync` and
 * keeps the errno of a failed sync as the cause of its `SyncFailedException` (DEC-107 (4)). On ART
 * `FileDescriptor.sync()` throws one without a cause, and `FileChannel.force` and `MappedByteBuffer.force` throw an
 * `IOException` that carries no errno either, so a sync that fails on a full storage would be classified `Unexpected`
 * (`AuthFailed` for a biometric slot creation) instead of `StorageFull`. The rule reports every `sync()` without
 * arguments (a call such as `fd.sync()`, `fd?.sync()` or a bare `sync()` inside a scope function, and a function
 * declared with that name), every `force(` (a call such as `channel.force(true)` or `buffer.force()`, and a function
 * declared with that name) and every reference `::sync` or `::force`, in every production file; `syncFile` itself has
 * none.
 */
object FileSyncRule {
    private val CALLS = Regex("""\bsync\s*\(\s*\)|\bforce\s*\(|::\s*(?:sync|force)\b""")

    fun check(source: KotlinSource): List<Violation> = CALLS.findAll(source.code)
        .map { source.violation(it.range.first, "${it.value}: $MESSAGE") }
        .toList()

    private const val MESSAGE = "FileDescriptor.sync() and FileChannel.force() lose the errno; sync with syncFile"
}
