package com.doro.organizer.gates.pagesize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ElfLoadSegmentsTest {
    @Test
    fun elf64LittleEndian_returnsTheAlignOfLoadSegmentsOnly() {
        val elf = TestElf.build(is64 = true, littleEndian = true, segments = listOf(1 to 0x4000L, 6 to 8L, 1 to 0x1000L))
        assertEquals(listOf(0x4000L, 0x1000L), ElfLoadSegments.alignments(elf))
    }

    @Test
    fun elf32_returnsTheAlignOfLoadSegments() {
        val elf = TestElf.build(is64 = false, littleEndian = true, segments = listOf(1 to 0x4000L, 1 to 0x10000L))
        assertEquals(listOf(0x4000L, 0x10000L), ElfLoadSegments.alignments(elf))
    }

    @Test
    fun bigEndian_isHonoured() {
        val elf = TestElf.build(is64 = true, littleEndian = false, segments = listOf(1 to 0x4000L))
        assertEquals(listOf(0x4000L), ElfLoadSegments.alignments(elf))
    }

    @Test
    fun nonElfOrTruncatedInput_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { ElfLoadSegments.alignments("not an elf file".toByteArray()) }
        val truncated = TestElf.build(is64 = true, littleEndian = true, segments = listOf(1 to 0x4000L)).copyOf(70)
        assertThrows(IllegalArgumentException::class.java) { ElfLoadSegments.alignments(truncated) }
    }
}
