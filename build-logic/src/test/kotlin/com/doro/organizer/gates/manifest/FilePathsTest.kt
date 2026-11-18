package com.doro.organizer.gates.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FilePathsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val expected = """<paths><cache-path name="share" path="share/" /></paths>"""

    private fun write(resDir: File, qualifiedDir: String, content: String): File =
        File(resDir, "$qualifiedDir/file_paths.xml").apply { parentFile.mkdirs(); writeText(content) }

    @Test
    fun exactShareCachePath_isExpected() {
        assertTrue(FilePaths.isExpected("""<paths><cache-path name="share" path="share/" /></paths>"""))
        assertTrue(
            FilePaths.isExpected(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <!-- security.md § 9.1 -->
                <paths xmlns:tools="http://schemas.android.com/tools">
                    <cache-path path="share/" name="share" />
                </paths>
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun anyOtherShape_isRejected() {
        listOf(
            """<paths><files-path name="share" path="share/" /></paths>""",
            """<paths><root-path name="share" path="share/" /></paths>""",
            """<paths><cache-path name="share" path="." /></paths>""",
            """<paths><cache-path name="other" path="share/" /></paths>""",
            """<paths><cache-path name="share" path="share/" extra="x" /></paths>""",
            """<paths><cache-path name="share" path="share/" /><cache-path name="b" path="b/" /></paths>""",
            """<paths />""",
            """<paths mode="x"><cache-path name="share" path="share/" /></paths>""",
            """<other><cache-path name="share" path="share/" /></other>""",
        ).forEach { assertFalse(it, FilePaths.isExpected(it)) }
    }

    @Test
    fun matchingUnqualifiedResource_passes() {
        val res = temp.newFolder("res")
        write(res, "xml", expected)
        assertEquals(emptyList<String>(), FilePaths.resourceViolations(listOf(res, temp.newFolder("empty")), referenced = true))
    }

    @Test
    fun qualifiedVariant_failsEvenWhenTheUnqualifiedFileMatches() {
        val res = temp.newFolder("res")
        write(res, "xml", expected)
        val v31 = write(res, "xml-v31", """<paths><root-path name="r" path="" /></paths>""")
        assertEquals(
            listOf("${v31.invariantSeparatorsPath}: only an unqualified xml/file_paths.xml is allowed"),
            FilePaths.resourceViolations(listOf(res), referenced = true),
        )
    }

    @Test
    fun unreferencedResourceIsStillChecked() {
        val wrong = write(temp.newFolder("res"), "xml", """<paths><files-path name="share" path="." /></paths>""")
        assertEquals(
            listOf("${wrong.invariantSeparatorsPath} is not exactly the security.md § 9.1 content"),
            FilePaths.resourceViolations(listOf(wrong.parentFile.parentFile), referenced = false),
        )
    }

    @Test
    fun referencedResourceMissingFromTheSources_fails() {
        val res = temp.newFolder("res")
        assertEquals(emptyList<String>(), FilePaths.resourceViolations(listOf(res), referenced = false))
        assertEquals(
            listOf("@xml/file_paths is referenced, but no xml/file_paths.xml is in the release sources"),
            FilePaths.resourceViolations(listOf(res), referenced = true),
        )
    }
}
