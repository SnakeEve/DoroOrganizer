package com.doro.organizer.architecture

import java.io.File

/**
 * Every instrumented test class is one JUnit 4 accepts (testing.md § 1): its `@Test`, `@Before` and `@After` methods
 * are public, not static, without parameters and return `Unit`; its `@BeforeClass` and `@AfterClass` methods the same
 * but static; its `@Rule` members are public and not static, its `@ClassRule` members public and static. JUnit rejects
 * a whole class over one such member with `initializationError`, AGP's result XML then leaves the class out and the
 * device run still passes, so the rule runs on the compiled classes before any device run. It checks what JUnit
 * validates: each class with a `@Test` method and its superclasses. The usual causes are an expression body whose last
 * expression has a value, such as `fun setUp() = runBlocking { … }` (write `runBlocking<Unit>` or `runTest`), and a
 * Kotlin `@Rule val`, which annotates the private field (write `@get:Rule`).
 */
object TestMethodRule {
    private const val TEST = "Lorg/junit/Test;"
    private const val RULE = "Lorg/junit/Rule;"
    private const val CLASS_RULE = "Lorg/junit/ClassRule;"
    private val INSTANCE_METHODS = setOf(TEST, "Lorg/junit/Before;", "Lorg/junit/After;")
    private val CLASS_METHODS = setOf("Lorg/junit/BeforeClass;", "Lorg/junit/AfterClass;")

    /** The compiled instrumented tests (shared fakes included), passed by `app/build.gradle.kts`. */
    val androidTestClassRoots: List<File> by lazy {
        val paths = checkNotNull(System.getProperty("doro.androidTestClasses")) {
            "doro.androidTestClasses is not set; run through Gradle"
        }
        paths.split(File.pathSeparator).filter(String::isNotEmpty).map(::File)
    }

    /** The members of [classes] that JUnit would reject, one line each. */
    fun check(classes: List<ClassFile>): List<String> {
        val byName = classes.associateBy { it.name }
        val testClasses = classes.filter { type -> type.methods.any { TEST in it.annotations } }
        val checked = testClasses.flatMapTo(linkedSetOf()) { type ->
            generateSequence(type) { it.superName?.let(byName::get) }.toList()
        }
        return checked.flatMap { type ->
            type.methods.flatMap { method -> method.annotations.mapNotNull { report(type, method, it, methodProblems(method, it)) } } +
                type.fields.flatMap { field -> field.annotations.mapNotNull { report(type, field, it, ruleProblems(field, it)) } }
        }
    }

    private fun methodProblems(method: ClassFile.Member, annotation: String): List<String> = when (annotation) {
        in INSTANCE_METHODS -> lifecycleProblems(method, static = false)
        in CLASS_METHODS -> lifecycleProblems(method, static = true)
        else -> ruleProblems(method, annotation)
    }

    private fun lifecycleProblems(method: ClassFile.Member, static: Boolean) = listOfNotNull(
        "does not return Unit".takeUnless { method.descriptor.endsWith(")V") },
        "takes parameters".takeUnless { method.descriptor.startsWith("()") },
        "is not public".takeUnless { method.isPublic },
        (if (static) "is not static" else "is static").takeIf { method.isStatic != static },
    )

    /** A `@Rule` that is also a `@ClassRule` is static, as JUnit allows. */
    private fun ruleProblems(member: ClassFile.Member, annotation: String): List<String> = when (annotation) {
        RULE -> listOfNotNull(
            "is not public".takeUnless { member.isPublic },
            "is static".takeIf { member.isStatic && CLASS_RULE !in member.annotations },
        )
        CLASS_RULE -> listOfNotNull("is not public".takeUnless { member.isPublic }, "is not static".takeUnless { member.isStatic })
        else -> emptyList()
    }

    private fun report(type: ClassFile, member: ClassFile.Member, annotation: String, problems: List<String>): String? {
        if (problems.isEmpty()) return null
        val name = annotation.removeSuffix(";").substringAfterLast('/')
        return "${type.name}.${member.name}: @$name ${problems.joinToString()}"
    }

    private val ClassFile.Member.isPublic get() = access and ClassFile.ACC_PUBLIC != 0

    private val ClassFile.Member.isStatic get() = access and ClassFile.ACC_STATIC != 0
}
