package com.doro.organizer.gates.pagesize

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Builds a minimal ELF file: the identification, the program-header fields of the header and the program headers. */
internal object TestElf {
    /** [segments] are `p_type to p_align` pairs. */
    fun build(is64: Boolean, littleEndian: Boolean, segments: List<Pair<Int, Long>>): ByteArray {
        val headerSize = if (is64) 64 else 52
        val entrySize = if (is64) 56 else 32
        val buffer = ByteBuffer.allocate(headerSize + entrySize * segments.size)
            .order(if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        buffer.put(0, 0x7F.toByte()).put(1, 'E'.code.toByte()).put(2, 'L'.code.toByte()).put(3, 'F'.code.toByte())
        buffer.put(4, if (is64) 2 else 1).put(5, if (littleEndian) 1 else 2)
        if (is64) {
            buffer.putLong(0x20, headerSize.toLong()).putShort(0x36, entrySize.toShort()).putShort(0x38, segments.size.toShort())
        } else {
            buffer.putInt(0x1C, headerSize).putShort(0x2A, entrySize.toShort()).putShort(0x2C, segments.size.toShort())
        }
        segments.forEachIndexed { index, (type, align) ->
            val entry = headerSize + index * entrySize
            buffer.putInt(entry, type)
            if (is64) buffer.putLong(entry + 0x30, align) else buffer.putInt(entry + 0x1C, align.toInt())
        }
        return buffer.array()
    }
}
