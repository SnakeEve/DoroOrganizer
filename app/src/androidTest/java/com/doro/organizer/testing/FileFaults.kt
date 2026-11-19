package com.doro.organizer.testing

import android.system.Os
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random

/** SQLCipher 4's page size (security.md § 3.4); a plaintext file names its own at header offset 16. */
private const val CIPHER_PAGE_SIZE = 4096

private val PLAIN_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

/** The page size of [file]: from the header of a plaintext file (1 means 65536), 4096 for a SQLCipher 4 file. */
fun pageSize(file: File): Int = RandomAccessFile(file, "r").use { f ->
    val header = ByteArray(18).also { f.readFully(it) }
    if (!header.copyOf(PLAIN_MAGIC.size).contentEquals(PLAIN_MAGIC)) return CIPHER_PAGE_SIZE
    val size = ((header[16].toInt() and 0xFF) shl 8) or (header[17].toInt() and 0xFF)
    if (size == 1) 65_536 else size
}

/** The number of pages in [file]; exact only while no `-wal` holds pages of it. */
fun pageCount(file: File): Int = (file.length() / pageSize(file)).toInt()

/** Overwrites page [page] (1-based) of [file] with bytes from [random] (testing.md § 7 `corruptPage`). */
fun corruptPage(file: File, page: Int, random: Random = Random(page)) {
    val size = pageSize(file)
    RandomAccessFile(file, "rw").use { f ->
        f.seek((page - 1L) * size)
        f.write(random.nextBytes(size))
    }
}

/** Inverts one byte in the middle of page [page] (1-based) of [file]: enough to fail a SQLCipher page HMAC. */
fun flipByteInPage(file: File, page: Int) {
    val offset = (page - 1L) * pageSize(file) + pageSize(file) / 2
    RandomAccessFile(file, "rw").use { f ->
        f.seek(offset)
        val byte = f.read()
        f.seek(offset)
        f.write(byte.inv() and 0xFF)
    }
}

/** How many descriptors of this process point at [file] (testing.md L-17: `Os.readlink` over `/proc/self/fd`). */
fun openDescriptors(file: File): Int {
    val target = file.canonicalPath
    return File("/proc/self/fd").listFiles().orEmpty().count { fd ->
        runCatching { Os.readlink(fd.path) }.getOrNull() == target
    }
}
