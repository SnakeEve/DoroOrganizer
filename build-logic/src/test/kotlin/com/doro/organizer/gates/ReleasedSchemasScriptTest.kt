package com.doro.organizer.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Runs config/check-released-schemas.sh in a throwaway git repository. Needs `git` on the path; `sh` comes from the
 * path or, on Windows, from the Git installation. A missing tool fails the tests instead of skipping them.
 */
class ReleasedSchemasScriptTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var repo: File
    private lateinit var gitConfig: File
    private lateinit var sh: String

    private val versions get() = File(repo, "config/released-schema-versions.txt")

    private fun schema(version: Int) = File(repo, "app/schemas/com.doro.organizer.data.db.AppDatabase/$version.json")

    @Before
    fun setUp() {
        repo = temp.newFolder("repo")
        gitConfig = temp.newFile("gitconfig").apply {
            writeText("[user]\n\tname = Test\n\temail = test@example.invalid\n[commit]\n\tgpgsign = false\n[core]\n\tautocrlf = false\n")
        }
        sh = shell()
        git("init", "--quiet")
        File(ConfigFiles.directory, "check-released-schemas.sh").copyTo(File(repo, "config/check-released-schemas.sh"))
        versions.writeText("# released versions\n")
        schema(1).apply { parentFile.mkdirs() }.writeText("""{"version":1}""")
        commit("schema 1")
    }

    @Test
    fun noReleasedVersion_passesWhateverChanges() {
        schema(1).writeText("""{"version":1,"changed":true}""")
        assertEquals(0, runScript().exitCode)
    }

    @Test
    fun releasedSchemaUnchanged_passes() {
        release(1)
        schema(2).writeText("""{"version":2}""")
        commit("schema 2")
        schema(2).writeText("""{"version":2,"unreleased":"may change"}""")
        assertEquals(0, runScript().exitCode)
    }

    @Test
    fun releasedSchemaChangedInWorkingTree_fails() {
        release(1)
        schema(1).writeText("""{"version":1,"changed":true}""")
        assertFails("released schema changed: app/schemas/com.doro.organizer.data.db.AppDatabase/1.json")
    }

    @Test
    fun releasedSchemaChangedInALaterCommit_fails() {
        release(1)
        schema(1).writeText("""{"version":1,"changed":true}""")
        commit("edit released schema")
        assertFails("released schema changed")
    }

    @Test
    fun releasedSchemaDeleted_fails() {
        release(1)
        git("rm", "--quiet", schema(1).relativeTo(repo).invariantSeparatorsPath)
        commit("delete released schema")
        assertFails("released schema changed")
    }

    @Test
    fun versionListedBeforeCommitting_comparesWithHead() {
        versions.appendText("1\n")
        assertEquals(0, runScript().exitCode)
        schema(1).writeText("""{"version":1,"changed":true}""")
        assertFails("released schema changed")
    }

    @Test
    fun releasedVersionWithoutCommittedSchema_fails() {
        versions.appendText("3\n")
        assertFails("released schema not committed: app/schemas/com.doro.organizer.data.db.AppDatabase/3.json")
    }

    @Test
    fun releasedVersionRemovedFromTheList_fails() {
        release(1)
        versions.writeText("# released versions\n")
        schema(1).writeText("""{"version":1,"changed":true}""")
        commit("drop the released version and edit its schema")
        assertFails("released version removed from config/released-schema-versions.txt: 1")
    }

    @Test
    fun nonIntegerLine_fails() {
        versions.appendText("v1\n")
        assertFails("not a schema version")
    }

    private fun release(version: Int) {
        versions.appendText("$version\n")
        commit("release schema $version")
    }

    private fun assertFails(expectedOutput: String) {
        val result = runScript()
        assertEquals(result.output, 1, result.exitCode)
        assertTrue(result.output, result.output.contains(expectedOutput))
    }

    private fun commit(message: String) {
        git("add", "--all")
        git("commit", "--quiet", "--allow-empty", "-m", message)
    }

    private fun git(vararg args: String) {
        val result = run(listOf("git", *args))
        check(result.exitCode == 0) { "git ${args.first()} failed: ${result.output}" }
    }

    private fun runScript() = run(listOf(sh, "config/check-released-schemas.sh"))

    private class Result(val exitCode: Int, val output: String)

    /**
     * Runs [command] in the throwaway repository. Inherited `GIT_*` variables (set inside a git hook, for example)
     * would point git at the real repository, so they are removed, and git never searches above the temp folder.
     */
    private fun run(command: List<String>, directory: File = repo): Result {
        val process = ProcessBuilder(command).directory(directory).redirectErrorStream(true).apply {
            environment().keys.removeIf { it.startsWith("GIT_") }
            environment()["GIT_CEILING_DIRECTORIES"] = temp.root.path
            environment()["GIT_CONFIG_GLOBAL"] = gitConfig.path
            environment()["GIT_CONFIG_NOSYSTEM"] = "1"
        }.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(1, TimeUnit.MINUTES)) { "${command.first()} timed out" }
        return Result(process.exitValue(), output)
    }

    /** `sh` from the path, else the one Git for Windows ships next to `git --exec-path` (`<git>/mingw64/libexec/git-core`). */
    private fun shell(): String {
        if (works(listOf("sh", "-c", "true"))) return "sh"
        val execPath = tryRun(listOf("git", "--exec-path"))?.takeIf { it.exitCode == 0 }?.output?.trim()
            ?: error("git is required on the path")
        val gitRoot = File(execPath).parentFile?.parentFile?.parentFile
        return listOf("bin/sh.exe", "usr/bin/sh.exe").map { File(gitRoot, it) }.firstOrNull(File::isFile)?.path
            ?: error("sh is required: it is neither on the path nor in the Git installation at $gitRoot")
    }

    private fun works(command: List<String>): Boolean = tryRun(command)?.exitCode == 0

    /** Null when [command] cannot be started at all. */
    private fun tryRun(command: List<String>): Result? = try {
        run(command, temp.root)
    } catch (e: IOException) {
        null
    }
}
