package com.doro.organizer.testapk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ClassHeaderTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun read_givesTheNameSuperclassAndInterfaces_pastLongAndDoubleConstants() {
        val header = ClassHeader.read(classBytes(Sample::class.java).inputStream())
        assertEquals(ClassHeader(binaryName(Sample::class.java), "java/util/AbstractList", listOf("java/io/Serializable", "java/lang/Runnable")), header)
        assertEquals(listOf("java/util/AbstractList", "java/io/Serializable", "java/lang/Runnable"), header.supertypes)
    }

    @Test
    fun read_anInterfaceHasObjectAsSuperclass_andObjectHasNone() {
        val marker = ClassHeader.read(classBytes(Marker::class.java).inputStream())
        assertEquals("java/lang/Object", marker.superName)
        assertTrue(marker.isInterface)
        assertFalse(ClassHeader.read(classBytes(Sample::class.java).inputStream()).isInterface)
        assertNull(ClassHeader.read(classBytes(Any::class.java).inputStream()).superName)
    }

    @Test
    fun read_givesTheTypesThatInvokedynamicSitesReturn_theLambdasFunctionalInterfaces() {
        val header = ClassHeader.read(classBytes(IndyLambdaSample::class.java).inputStream())
        assertTrue(header.lambdaTypes.toString(), header.lambdaTypes.containsAll(listOf("java/util/function/Supplier", "java/lang/Runnable")))
        assertEquals(header.lambdaTypes.sorted().distinct(), header.lambdaTypes)
        assertEquals(emptyList<String>(), ClassHeader.read(classBytes(Marker::class.java).inputStream()).lambdaTypes)
    }

    @Test
    fun serviceTypes_areTheServiceFileNames_ofJarsAndDirectories() {
        val directory = temporary.newFolder("resources")
        File(directory, "META-INF/services/a.b.Directory\$Nested").apply { parentFile.mkdirs() }.writeText("a.b.Impl\n")
        val jar = temporary.newFile("services.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            fun put(name: String) { zip.putNextEntry(ZipEntry(name)); zip.write(byteArrayOf(1)); zip.closeEntry() }
            zip.putNextEntry(ZipEntry("META-INF/services/")); zip.closeEntry()
            put("META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory")
            put("META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler")
            put("META-INF/services/nested/not.a.Service")
            put("kotlinx/coroutines/test/TestDispatcher.class")
        }
        assertEquals(
            listOf("a/b/Directory\$Nested", "kotlinx/coroutines/CoroutineExceptionHandler", "kotlinx/coroutines/internal/MainDispatcherFactory"),
            ClassHeader.serviceTypes(listOf(directory, jar)),
        )
    }

    @Test
    fun readAll_readsJarsAndDirectories_skipsMultiReleaseAndModuleInfo_firstDefinitionWins() {
        val directory = temporary.newFolder("classes")
        File(directory, "a/Sample.class").apply { parentFile.mkdirs() }.writeBytes(classBytes(Sample::class.java))
        val jar = temporary.newFile("lib.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            put("b/Marker.class", classBytes(Marker::class.java))
            put("a/SampleAgain.class", classBytes(Sample::class.java))
            put("META-INF/versions/9/b/Hidden.class", classBytes(Runnable::class.java))
            put("module-info.class", classBytes(Runnable::class.java))
            put("b/readme.txt", byteArrayOf(1, 2))
        }
        val headers = ClassHeader.readAll(listOf(directory, jar))
        assertEquals(setOf(binaryName(Sample::class.java), binaryName(Marker::class.java)), headers.keys)
    }

    @Test
    fun isProgramClass() {
        assertTrue(ClassHeader.isProgramClass("kotlin/LazyKt.class"))
        assertFalse(ClassHeader.isProgramClass("META-INF/versions/9/kotlin/LazyKt.class"))
        assertFalse(ClassHeader.isProgramClass("module-info.class"))
        assertFalse(ClassHeader.isProgramClass("META-INF/versions/11/module-info.class"))
        assertFalse(ClassHeader.isProgramClass("kotlin/kotlin.kotlin_builtins"))
    }

    @Suppress("unused")
    private class Sample : java.util.AbstractList<String>(), java.io.Serializable, Runnable {
        val long = 1234567890123L
        val double = 0.123456789
        val afterLong = "x"
        override val size: Int get() = (long + double).toInt()
        override fun get(index: Int) = afterLong
        override fun run() = Unit

        companion object {
            const val LONG = 9_876_543_210L
            const val DOUBLE = 2.5e300
        }
    }

    private interface Marker

    private fun binaryName(type: Class<*>) = type.name.replace('.', '/')

    private fun classBytes(type: Class<*>): ByteArray =
        checkNotNull(type.getResourceAsStream("/${binaryName(type)}.class")) { "no class file for $type" }.use { it.readBytes() }
}
