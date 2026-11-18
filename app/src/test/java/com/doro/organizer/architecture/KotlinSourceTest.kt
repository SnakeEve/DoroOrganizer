package com.doro.organizer.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinSourceTest {
    @Test
    fun comments_areBlankedAndLinesKept() {
        val source = KotlinSource("A.kt", "main", "val a = 1 // println\n/* outer /* inner */ println */ val b = 2\n")

        assertFalse("println" in source.code)
        assertEquals(source.text.length, source.code.length)
        assertEquals(2, source.lineAt(source.code.indexOf("val b")))
    }

    @Test
    fun stringText_isBlankedButTemplateCodeKept() {
        val source = KotlinSource("A.kt", "main", "val s = \"SELECT * \\\" \${Instant.now()} \$name\"\n")

        assertFalse("SELECT" in source.code)
        assertTrue("Instant.now()" in source.code)
        assertFalse('{' in source.code || '}' in source.code || '$' in source.code)
        assertEquals(listOf("SELECT * \\\" \${} \$name"), source.literals.map { it.text })
    }

    @Test
    fun nestedTemplateBraces_areKeptAndBalanced() {
        val source = KotlinSource("A.kt", "main", "val s = \"\${xs.map { \"\${it}\" }}\"\nval t = 1\n")

        val firstLine = source.code.lines().first()
        assertEquals("{}", firstLine.filter { it in "{}" }) // only the lambda's own braces remain
        assertTrue("xs.map" in firstLine && "it" in firstLine)
        assertTrue("val t = 1" in source.code)
    }

    @Test
    fun rawString_endsAtLastQuoteOfRun() {
        val source = KotlinSource("A.kt", "main", "val s = \"\"\"\n  SELECT \"x\"\"\"\"\nval t = 1\n")

        assertEquals(listOf("\n  SELECT \"x\""), source.literals.map { it.text })
        assertEquals(1, source.literals.single().line)
        assertTrue("val t = 1" in source.code)
    }

    @Test
    fun charLiteral_quoteDoesNotOpenString() {
        val source = KotlinSource("A.kt", "main", "val q = '\"'\nval e = '\\''\nfun f() = println()\n")

        assertTrue(source.literals.isEmpty())
        assertEquals(3, source.lineAt(source.code.indexOf("println")))
    }

    @Test
    fun packageName_isRead() {
        val source = KotlinSource("A.kt", "main", "// header\npackage com.doro.organizer.data\n")

        assertEquals("com.doro.organizer.data", source.packageName)
    }
}
