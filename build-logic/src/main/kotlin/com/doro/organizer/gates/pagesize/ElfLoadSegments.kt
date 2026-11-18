package com.doro.organizer.gates.pagesize

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the `p_align` of every `PT_LOAD` program header of an ELF file: the `Align` column that
 * `readelf -lW` prints for `LOAD` rows (testing.md § 9 `page-size`).
 */
internal object ElfLoadSegments {
    private const val PT_LOAD = 1

    fun alignments(elf: ByteArray): List<Long> {
        require(elf.size >= 16 && elf[0] == 0x7F.toByte() && String(elf, 1, 3, Charsets.US_ASCII) == "ELF") { "not an ELF file" }
        val is64 = when (elf[4].toInt()) {
            1 -> false
            2 -> true
            else -> throw IllegalArgumentException("unknown ELF class ${elf[4]}")
        }
        val order = when (elf[5].toInt()) {
            1 -> ByteOrder.LITTLE_ENDIAN
            2 -> ByteOrder.BIG_ENDIAN
            else -> throw IllegalArgumentException("unknown ELF data encoding ${elf[5]}")
        }
        val buffer = ByteBuffer.wrap(elf).order(order)
        return try {
            val headerOffset = if (is64) buffer.getLong(0x20) else buffer.getInt(0x1C).toUInt().toLong()
            val entrySize = buffer.getShort(if (is64) 0x36 else 0x2A).toUShort().toInt()
            val entryCount = buffer.getShort(if (is64) 0x38 else 0x2C).toUShort().toInt()
            (0 until entryCount).mapNotNull { index ->
                val entry = Math.toIntExact(headerOffset + index.toLong() * entrySize)
                if (buffer.getInt(entry) != PT_LOAD) {
                    null
                } else if (is64) {
                    buffer.getLong(entry + 0x30)
                } else {
                    buffer.getInt(entry + 0x1C).toUInt().toLong()
                }
            }
        } catch (e: IndexOutOfBoundsException) {
            throw IllegalArgumentException("truncated ELF program headers", e)
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("ELF program header offset out of range", e)
        }
    }
}
