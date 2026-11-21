package com.doro.organizer.testapk

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * The type relations of one class file: its binary name (`a/b/C`), its superclass (null for `java/lang/Object` and
 * `module-info`), its direct interfaces, whether it is an interface, and [lambdaTypes]. Only the constant pool and the
 * header are read, never the members.
 */
internal data class ClassHeader(
    val name: String,
    val superName: String?,
    val interfaces: List<String>,
    val isInterface: Boolean = false,
    /**
     * The object types that the class's `invokedynamic` call sites return, sorted. For a `LambdaMetafactory` site that
     * is the functional interface the lambda implements: D8 and R8 turn each such site into a synthetic class that
     * implements it, a class no class file shows.
     */
    val lambdaTypes: List<String> = emptyList(),
) {
    /** Direct supertypes: the superclass, then the interfaces. */
    val supertypes: List<String> get() = listOfNotNull(superName) + interfaces

    companion object {
        private const val MAGIC = 0xCAFEBABE.toInt()
        private const val ACC_INTERFACE = 0x0200

        /** Reads the header of a class file (JVMS § 4.1). */
        fun read(input: InputStream): ClassHeader {
            val data = DataInputStream(input.buffered())
            require(data.readInt() == MAGIC) { "not a class file" }
            data.readUnsignedShort() // minor_version
            data.readUnsignedShort() // major_version
            val count = data.readUnsignedShort()
            val utf8 = arrayOfNulls<String>(count)
            val classNameIndex = IntArray(count)
            val descriptorIndex = IntArray(count) // of a CONSTANT_NameAndType
            val invokeDynamicNameAndTypes = mutableListOf<Int>()
            var index = 1
            while (index < count) {
                when (val tag = data.readUnsignedByte()) {
                    1 -> utf8[index] = data.readUTF()
                    7 -> classNameIndex[index] = data.readUnsignedShort()
                    12 -> { data.readUnsignedShort(); descriptorIndex[index] = data.readUnsignedShort() }
                    18 -> { data.readUnsignedShort(); invokeDynamicNameAndTypes += data.readUnsignedShort() }
                    8, 16, 19, 20 -> data.skipNBytes(2)
                    15 -> data.skipNBytes(3)
                    3, 4, 9, 10, 11, 17 -> data.skipNBytes(4)
                    5, 6 -> { data.skipNBytes(8); index++ } // a long or double takes two entries
                    else -> throw IllegalArgumentException("unknown constant pool tag $tag")
                }
                index++
            }
            fun className(classIndex: Int): String? =
                if (classIndex == 0) null else checkNotNull(utf8[classNameIndex[classIndex]]) { "bad class reference" }
            val access = data.readUnsignedShort()
            val name = checkNotNull(className(data.readUnsignedShort())) { "class file without this_class" }
            val superName = className(data.readUnsignedShort())
            val interfaces = List(data.readUnsignedShort()) { checkNotNull(className(data.readUnsignedShort())) }
            val lambdaTypes = invokeDynamicNameAndTypes.mapNotNull { nameAndType ->
                val descriptor = checkNotNull(utf8[descriptorIndex[nameAndType]]) { "bad invokedynamic descriptor" }
                RETURNED_OBJECT.find(descriptor)?.groupValues?.get(1)
            }.distinct().sorted()
            return ClassHeader(name, superName, interfaces, access and ACC_INTERFACE != 0, lambdaTypes)
        }

        /** The object return type of a method descriptor: `(…)La/b/C;` gives `a/b/C`. */
        private val RETURNED_OBJECT = Regex("""\)L([^;]+);$""")

        /**
         * The headers of every class in [roots] (jars and class directories), by binary name. Multi-release entries
         * (`META-INF/versions/`) and `module-info` are skipped: R8 reads neither. The first definition of a name wins, as
         * on a class path.
         */
        fun readAll(roots: Iterable<File>): Map<String, ClassHeader> {
            val headers = LinkedHashMap<String, ClassHeader>()
            fun add(header: ClassHeader) { headers.putIfAbsent(header.name, header) }
            for (root in roots) {
                when {
                    root.isDirectory -> root.walkTopDown()
                        .filter { it.isFile && isProgramClass(it.relativeTo(root).invariantSeparatorsPath) }
                        .forEach { file -> file.inputStream().use { add(read(it)) } }
                    root.isFile -> ZipFile(root).use { zip ->
                        zip.entries().asSequence().filter { !it.isDirectory && isProgramClass(it.name) }
                            .forEach { entry -> zip.getInputStream(entry).use { add(read(it)) } }
                    }
                }
            }
            return headers
        }

        /** Whether an archive or directory entry is a class R8 reads as program input. */
        fun isProgramClass(path: String): Boolean =
            path.endsWith(".class") && !path.startsWith("META-INF/") && !path.endsWith("module-info.class")

        /**
         * The service types that [roots] (jars and directories) provide implementations of: the names of their
         * `META-INF/services/` files, as binary names (`a/b/C`), sorted.
         */
        fun serviceTypes(roots: Iterable<File>): List<String> {
            val services = sortedSetOf<String>()
            fun add(path: String) {
                if (path.startsWith(SERVICES) && path.length > SERVICES.length && '/' !in path.substring(SERVICES.length)) {
                    services += path.substring(SERVICES.length).replace('.', '/')
                }
            }
            for (root in roots) {
                when {
                    root.isDirectory -> root.walkTopDown().filter(File::isFile).forEach { add(it.relativeTo(root).invariantSeparatorsPath) }
                    root.isFile -> ZipFile(root).use { zip -> zip.entries().asSequence().filter { !it.isDirectory }.forEach { add(it.name) } }
                }
            }
            return services.toList()
        }

        private const val SERVICES = "META-INF/services/"
    }
}
