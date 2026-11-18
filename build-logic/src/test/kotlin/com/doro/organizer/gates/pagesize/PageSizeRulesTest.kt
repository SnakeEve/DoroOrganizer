package com.doro.organizer.gates.pagesize

import org.junit.Assert.assertEquals
import org.junit.Test

class PageSizeRulesTest {
    private val aligned = TestElf.build(is64 = true, littleEndian = true, segments = listOf(1 to 0x4000L, 1 to 0x4000L))
    private val allowed = setOf("libsqlcipher.so")

    @Test
    fun allowlistedLibraryWith16KbSegments_passes() {
        assertEquals(emptyList<String>(), PageSizeRules.violations(mapOf("lib/arm64-v8a/libsqlcipher.so" to aligned), allowed))
    }

    @Test
    fun largerPowerOfTwoAlignment_passes() {
        val elf = TestElf.build(is64 = true, littleEndian = true, segments = listOf(1 to 0x10000L))
        assertEquals(emptyList<String>(), PageSizeRules.violations(mapOf("lib/x86_64/libsqlcipher.so" to elf), allowed))
    }

    @Test
    fun segmentBelow16Kb_fails() {
        val elf = TestElf.build(is64 = false, littleEndian = true, segments = listOf(1 to 0x4000L, 1 to 0x1000L))
        assertEquals(
            listOf("lib/armeabi-v7a/libsqlcipher.so has a LOAD segment aligned to 0x1000, expected a power of two of at least 0x4000"),
            PageSizeRules.violations(mapOf("lib/armeabi-v7a/libsqlcipher.so" to elf), allowed),
        )
    }

    @Test
    fun multipleOf16KbThatIsNoPowerOfTwo_fails() {
        val elf = TestElf.build(is64 = true, littleEndian = true, segments = listOf(1 to 0xC000L))
        assertEquals(
            listOf("lib/arm64-v8a/libsqlcipher.so has a LOAD segment aligned to 0xc000, expected a power of two of at least 0x4000"),
            PageSizeRules.violations(mapOf("lib/arm64-v8a/libsqlcipher.so" to elf), allowed),
        )
    }

    @Test
    fun unlistedLibrary_failsEvenWhenAligned() {
        assertEquals(
            listOf("lib/x86/libnew.so is not in config/native-library-allowlist.txt"),
            PageSizeRules.violations(mapOf("lib/x86/libnew.so" to aligned), allowed),
        )
    }

    @Test
    fun unreadableOrSegmentlessLibrary_fails() {
        val noLoad = TestElf.build(is64 = true, littleEndian = true, segments = listOf(6 to 8L))
        assertEquals(
            listOf("lib/x86/libsqlcipher.so: not an ELF file", "lib/x86_64/libsqlcipher.so has no LOAD segment"),
            PageSizeRules.violations(
                mapOf("lib/x86_64/libsqlcipher.so" to noLoad, "lib/x86/libsqlcipher.so" to "junk".toByteArray()),
                allowed,
            ),
        )
    }
}
