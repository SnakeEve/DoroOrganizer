package com.doro.organizer.architecture

import java.io.DataInputStream
import java.io.File

/**
 * The parts of a JVM class file that the test-method rule reads: the names of the class and its superclass and, per
 * field and method, its name, descriptor, access flags and runtime-visible annotations (JVMS § 4). Constant-pool
 * entries other than `Utf8` and `Class` are skipped; the rest of the class is read only as far as the methods.
 */
class ClassFile(val name: String, val superName: String?, val fields: List<Member>, val methods: List<Member>) {
    /** [annotations] holds the type descriptor of each runtime-visible annotation, such as `Lorg/junit/Test;`. */
    class Member(val name: String, val descriptor: String, val access: Int, val annotations: List<String>)

    companion object {
        const val ACC_PUBLIC = 0x0001
        const val ACC_STATIC = 0x0008
        private const val MAGIC = 0xCAFEBABE.toInt()

        /** Every `.class` file below [roots], read. */
        fun readAll(roots: List<File>): List<ClassFile> = roots
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "class" }.toList() }
            .map { file -> file.inputStream().buffered().use { read(DataInputStream(it)) } }

        fun read(input: DataInputStream): ClassFile {
            check(input.readInt() == MAGIC) { "not a class file" }
            input.skipBytes(4) // minor and major version
            val pool = ConstantPool.read(input)
            input.readUnsignedShort() // access flags
            val name = pool.className(input.readUnsignedShort())
            val superName = input.readUnsignedShort().takeIf { it != 0 }?.let(pool::className) // 0 only for Object
            input.skipBytes(2 * input.readUnsignedShort()) // interfaces
            val fields = List(input.readUnsignedShort()) { readMember(input, pool) }
            val methods = List(input.readUnsignedShort()) { readMember(input, pool) }
            return ClassFile(name.replace('/', '.'), superName?.replace('/', '.'), fields, methods)
        }

        private fun readMember(input: DataInputStream, pool: ConstantPool): Member {
            val access = input.readUnsignedShort()
            val name = pool.utf8(input.readUnsignedShort())
            val descriptor = pool.utf8(input.readUnsignedShort())
            val annotations = mutableListOf<String>()
            repeat(input.readUnsignedShort()) {
                val attribute = pool.utf8(input.readUnsignedShort())
                val length = input.readInt()
                if (attribute == "RuntimeVisibleAnnotations") {
                    repeat(input.readUnsignedShort()) { annotations += readAnnotation(input, pool) }
                } else {
                    input.skipFully(length)
                }
            }
            return Member(name, descriptor, access, annotations)
        }

        /** One annotation (JVMS § 4.7.16); returns its type descriptor and skips its element values. */
        private fun readAnnotation(input: DataInputStream, pool: ConstantPool): String {
            val type = pool.utf8(input.readUnsignedShort())
            repeat(input.readUnsignedShort()) {
                input.readUnsignedShort() // element name
                skipElementValue(input, pool)
            }
            return type
        }

        private fun skipElementValue(input: DataInputStream, pool: ConstantPool) {
            when (val tag = input.readUnsignedByte().toChar()) {
                'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's', 'c' -> input.readUnsignedShort()
                'e' -> input.skipFully(4)
                '@' -> readAnnotation(input, pool)
                '[' -> repeat(input.readUnsignedShort()) { skipElementValue(input, pool) }
                else -> error("unknown element value tag $tag")
            }
        }

        private fun DataInputStream.skipFully(count: Int) = readFully(ByteArray(count))
    }
}

/** The constant pool, keeping only the entries the reader resolves. */
private class ConstantPool(private val utf8: Map<Int, String>, private val classes: Map<Int, Int>) {
    fun utf8(index: Int): String = checkNotNull(utf8[index]) { "constant $index is not Utf8" }

    fun className(index: Int): String = utf8(checkNotNull(classes[index]) { "constant $index is not a Class" })

    companion object {
        fun read(input: DataInputStream): ConstantPool {
            val utf8 = HashMap<Int, String>()
            val classes = HashMap<Int, Int>()
            val count = input.readUnsignedShort()
            var index = 1
            while (index < count) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> utf8[index] = input.readUTF()
                    7 -> classes[index] = input.readUnsignedShort()
                    8, 16, 19, 20 -> input.skipBytes(2) // String, MethodType, Module, Package
                    15 -> input.skipBytes(3) // MethodHandle
                    3, 4, 9, 10, 11, 12, 17, 18 -> input.skipBytes(4) // Integer, Float, the refs, NameAndType, Dynamic
                    5, 6 -> {
                        input.skipBytes(8) // Long and Double take two entries (JVMS § 4.4.5)
                        index++
                    }
                    else -> error("unknown constant tag $tag")
                }
                index++
            }
            return ConstantPool(utf8, classes)
        }
    }
}
