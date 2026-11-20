package com.doro.organizer.architecture

import java.io.File
import java.nio.file.Files
import javax.tools.ToolProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * testing.md I-41: one method per rule and banned-token group of architecture.md § Boundary test, run over the
 * production sources, each with a twin that plants violating source text and expects it reported at `file:line`.
 * Planted sources start with their package line and a blank line, so the planted body starts on line 3. One more rule
 * reads the compiled instrumented tests instead: every `@Test` is a method JUnit accepts (testing.md § 1).
 */
class ArchitectureTest {
    @Test
    fun productionSourcesAreScanned() {
        assertTrue(sources.any { it.path == "src/main/java/com/doro/organizer/MainActivity.kt" })
        assertTrue(sources.any { it.sourceSet == "debug" } && sources.any { it.sourceSet == "release" })
    }

    @Test
    fun rule1_imports() = assertClean(ImportRule::check)

    @Test
    fun rule1_imports_planted() {
        val ui = planted(
            "ui/notes/List.kt",
            """
            import com.doro.organizer.R
            import com.doro.organizer.data.NoteRepository
            import com.doro.organizer.security.AppLockController
            import com.doro.organizer.data.db.NoteDao
            import com.doro.organizer.data.DatabaseHolder
            import com.doro.organizer.security.KeyVault
            import com.doro.organizer.AppContainer
            import com.doro.organizer.data.*
            import androidx.room.Room
            val x = com.doro.organizer.data.db.AppDatabase.VERSION
            import com.doro.organizer.security.SlotCipher
            import com.doro.organizer.security.LockAvailability
            import com.doro.organizer.security.KeyState
            import com.doro.organizer.security.UnlockResult
            import com.doro.organizer.security.BiometricGateway
            import com.doro.organizer.data.maintenance.DbMaintenance
            import com.doro.organizer.data.maintenance.MaintenanceListener
            """,
        )
        assertEquals(listOf(6, 7, 8, 9, 10, 11, 12, 15, 16, 17, 19), ImportRule.check(ui).map { it.line }.sorted())

        val session = planted(
            "session/Gate.kt",
            """
            import com.doro.organizer.data.DatabaseHolder
            import com.doro.organizer.data.NoteRepository
            import com.doro.organizer.security.KeyState
            import com.doro.organizer.security.AppLockController
            import com.doro.organizer.security.BiometricGateway
            import com.doro.organizer.security.UnlockPolicy
            import com.doro.organizer.data.maintenance.MaintenanceListener
            """,
        )
        assertEquals(listOf(4, 7, 8), ImportRule.check(session).map { it.line })

        val model = planted(
            "model/Note.kt",
            """
            import android.net.Uri
            import java.time.LocalDate
            class Link(val uri: android.net.Uri)
            val min = java.time.LocalDate.MIN
            """,
        )
        assertEquals(listOf(3, 5), ImportRule.check(model).map { it.line })
        assertReported(planted("ui/gate/Gate.kt", "fun f(g: G) = g.settingsStore"), 3, ImportRule::check)
        assertReported(planted("misc/Thing.kt", ""), 1, ImportRule::check)

        val debugBody = """
            import com.doro.organizer.AppContainer
            import com.doro.organizer.data.maintenance.DefaultDbMaintenance
            import androidx.room.Room
            """
        assertNoneReported(planted("debug/CrashTestService.kt", debugBody, sourceSet = "debug"), ImportRule::check)
        assertReported(planted("debug/CrashTestService.kt", debugBody), 1, ImportRule::check)
    }

    @Test
    fun rule2_concurrency() = assertClean(TokenGroup.CONCURRENCY::check)

    @Test
    fun rule2_concurrency_planted() {
        assertReported(planted("data/A.kt", "fun f() = runBlocking { }"), 3, TokenGroup.CONCURRENCY::check)
        assertReported(planted("data/A.kt", "val d = Dispatchers.IO"), 3, TokenGroup.CONCURRENCY::check)
        assertNoneReported(planted("core/coroutines/D.kt", "val d = Dispatchers.IO"), TokenGroup.CONCURRENCY::check)
    }

    @Test
    fun rule2_clocks() = assertClean(TokenGroup.CLOCKS::check)

    @Test
    fun rule2_clocks_planted() {
        val source = planted("model/A.kt", "val a = java.time.Instant.now()\nval b = Date()")
        assertEquals(listOf(3, 4), TokenGroup.CLOCKS.check(source).map { it.line })
        assertNoneReported(planted("core/time/T.kt", "val a = System.currentTimeMillis()"), TokenGroup.CLOCKS::check)
    }

    @Test
    fun rule2_clocks_methodReferences_planted() {
        val references = "val a = Instant::now\nval b = ZoneId :: systemDefault\nval c = Clock::systemUTC"
        assertEquals(listOf(3, 4, 5), TokenGroup.CLOCKS.check(planted("model/A.kt", references)).map { it.line })
        assertNoneReported(planted("core/time/T.kt", references), TokenGroup.CLOCKS::check)
    }

    @Test
    fun rule2_logging() = assertClean(TokenGroup.LOGGING::check)

    @Test
    fun rule2_logging_planted() {
        val source = planted(
            "data/A.kt",
            """
            import android.util.Log
            fun f(e: Exception) { e.printStackTrace(); println("println(e)") } // System.out
            import android.util.*
            fun g(m: String) = Log.d(TAG, m)
            fun h(m: String) = AppLog.d(TAG, m)
            """,
        )
        assertEquals(listOf(3, 4, 4, 5, 6), TokenGroup.LOGGING.check(source).map { it.line })
        val callback = "fun f(b: B) = b.setQueryCallback()"
        assertReported(planted("data/A.kt", callback), 3, TokenGroup.LOGGING::check)
        assertNoneReported(planted("data/A.kt", callback, sourceSet = "debug"), TokenGroup.LOGGING::check)
        val appLog = "import android.util.*\nimport android.util.Log\nfun d(m: String) = Log.d(TAG, m)"
        assertNoneReported(planted("core/log/AppLog.kt", appLog), TokenGroup.LOGGING::check)
    }

    @Test
    fun rule2_apiPitfalls() = assertClean(TokenGroup.API_PITFALLS::check)

    @Test
    fun rule2_apiPitfalls_planted() {
        val source = planted("model/A.kt", "fun f(l: MutableList<Int>) = l.removeFirst()")
        assertReported(source, 3, TokenGroup.API_PITFALLS::check)
    }

    @Test
    fun rule2_database() = assertClean(TokenGroup.DATABASE::check)

    @Test
    fun rule2_database_planted() {
        val source = planted(
            "data/db/A.kt",
            """
            import android.database.sqlite.SQLiteDatabaseCorruptException
            import android.database.sqlite.SQLiteOpenHelper
            fun f(b: B) = b.fallbackToDestructiveMigrationOnDowngrade()
            @RawQuery fun g(): Int
            import android.database.sqlite.*
            """,
        )
        assertEquals(listOf(4, 5, 6, 7), TokenGroup.DATABASE.check(source).map { it.line })
    }

    @Test
    fun rule2_ui() = assertClean(TokenGroup.UI::check)

    @Test
    fun rule2_ui_planted() {
        assertReported(planted("ui/A.kt", "val p = SecureFlagPolicy.SecureOff"), 3, TokenGroup.UI::check)
    }

    /** Every token of every group is reported where it is banned and not where it is allowed. */
    @Test
    fun rule2_everyToken_planted() {
        TokenGroup.entries.forEach { group ->
            group.tokens.forEach { token ->
                val body = "fun f() { ${token.sample} }"
                val message = "banned: ${token.display}"
                val banned = planted("data/A.kt", body)
                assertTrue("$message not reported", group.check(banned).any { it.line == 3 && it.message == message })
                if (token.allowedPackage != null || token.allowedSourceSet != null) {
                    val directory = (token.allowedPackage ?: "data").replace('.', '/')
                    val allowed = planted("$directory/A.kt", body, sourceSet = token.allowedSourceSet ?: "main")
                    assertFalse("$message where allowed", group.check(allowed).any { it.message == message })
                }
            }
        }
    }

    @Test
    fun rule3_noCachedDatabaseObjects() = assertClean(StoredDatabaseRule::check)

    @Test
    fun rule3_noCachedDatabaseObjects_planted() {
        val source = planted(
            "data/NoteRepository.kt",
            """
            class NoteRepository(
                private val dao: NoteDao,
                private val holder: DatabaseHolder,
            ) {
                private lateinit var db: AppDatabase
                private val cached = holder.db.noteDao()
                private val computed: NoteDao get() = holder.db.noteDao()
                suspend fun f() = holder.withDb { db -> val local: NoteDao = db.noteDao(); local.count() }
                private val built = buildRoom(c, f, k, false)
                private val later =
                    buildRoom(c, f, k, true)
                private val lazyDao by lazy {
                    holder.db.noteDao()
                }
                private val chained = holder.db
                    .noteDao()
                private val memory = Room.inMemoryDatabaseBuilder(c, AppDatabase::class.java)
                private val name = holder.name
                fun g() {
                    val local by lazy {
                        holder.db.noteDao()
                    }
                }
            }
            """,
        )
        assertEquals(listOf(4, 7, 8, 11, 12, 14, 17, 19), StoredDatabaseRule.check(source).map { it.line })
        val holder = planted("data/DatabaseHolder.kt", "class DefaultDatabaseHolder { var db: AppDatabase? = null }")
        assertNoneReported(holder, StoredDatabaseRule::check)
        // A string template in the constructor must not hide the class body that follows it.
        val template = planted(
            "data/Repo.kt",
            """
            class Repo(val tag: String = "${'$'}{X}") {
                private val dao: NoteDao
            }
            """,
        )
        assertEquals(listOf(4), StoredDatabaseRule.check(template).map { it.line })
        val nested = planted(
            "data/db/AppDatabase.kt",
            """
            abstract class AppDatabase : RoomDatabase() {
                companion object {
                    val CALLBACK: RoomDatabase.Callback = object : RoomDatabase.Callback() {}
                    val INSTANCE: AppDatabase? = null
                }
            }
            """,
        )
        assertEquals(listOf(6), StoredDatabaseRule.check(nested).map { it.line })
    }

    @Test
    fun rule4_redaction() = assertClean(RedactionRule::check)

    @Test
    fun rule4_redaction_planted() {
        val model = planted(
            "model/Rows.kt",
            """
            data class Bare(val id: Long)
            data class Redacted(val id: Long, val text: String) {
                override fun toString() = "Redacted(id=${'$'}id)"
            }
            data class NestedOnly(val id: Long) {
                class Inner { override fun toString() = "Inner" }
            }
            """,
        )
        assertEquals(listOf(3, 7), RedactionRule.check(model).map { it.line })

        // Outside the listed packages only a constructor property that may carry text or a secret requires it.
        val security = planted(
            "security/Keys.kt",
            """
            data class Flags(val on: Boolean, val onDone: (Int) -> Unit)
            data class Wrapped(val ids: List<Long>, val iv: ByteArray)
            """,
        )
        assertEquals(listOf(4), RedactionRule.check(security).map { it.line })

        val navigation = planted(
            "ui/navigation/Keys.kt",
            """
            data class EditorKey(val noteId: Long)
            data class PickerKey(val id: Long) { override fun toString() = "PickerKey" }
            data class SearchKey(val query: String)
            """,
        )
        assertEquals(listOf(4, 5), RedactionRule.check(navigation).map { it.line })
    }

    @Test
    fun rule4_redaction_allowlist() {
        val label = planted("core/Labels.kt", "data class Label(val text: String)")
        assertReported(label, 3, RedactionRule::check)

        val allowlist = mapOf("com.doro.organizer.core.Label" to "constant app text only")
        assertEquals(emptyList<Violation>(), RedactionRule.check(label, allowlist))
        // The allowlist exempts only the text-property rule, never the packages where every data class redacts.
        val model = planted("model/Label.kt", "data class Label(val text: String)")
        val modelAllowlist = mapOf("com.doro.organizer.model.Label" to "constant app text only")
        assertEquals(listOf(3), RedactionRule.check(model, modelAllowlist).map { it.line })
    }

    @Test
    fun crashTestKeys() = assertClean(CrashTestKeysRule::check)

    @Test
    fun crashTestKeys_planted() {
        val body = "val vault = crashTestVault(app, settings, time, dispatchers)"
        assertNoneReported(planted("debug/CrashRuns.kt", body, sourceSet = "debug"), CrashTestKeysRule::check)
        assertReported(planted("AppContainer.kt", body), 3, CrashTestKeysRule::check)
        assertReported(planted("debug/Tools.kt", "val spec = testBioKeySpec()", sourceSet = "debug"), 3, CrashTestKeysRule::check)
        assertReported(planted("debug/CrashRuns.kt", body), 3, CrashTestKeysRule::check) // main source set
    }

    @Test
    fun rule6_sqlText() = assertClean(SqlTextRule::check)

    @Test
    fun rule6_sqlText_planted() {
        val source = planted(
            "data/db/NoteDao.kt",
            """
            const val A = "SELECT id FROM notes"
            const val B = "select * FROM persons"
            const val C = "SELECT n.* FROM notes AS n"
            const val D = "SELECT p.* FROM persons p"
            // SELECT * FROM notes
            """,
        )
        assertEquals(listOf(4, 5), SqlTextRule.check(source).map { it.line })
        assertNoneReported(planted("data/A.kt", "const val A = \"SELECT * FROM notes\""), SqlTextRule::check)
    }

    @Test
    fun transactionsOnlyThroughTheFrame() = assertClean(TransactionRule::check)

    @Test
    fun transactionsOnlyThroughTheFrame_planted() {
        val source = planted(
            "data/DefaultNoteRepository.kt",
            """
            suspend fun f(db: AppDatabase) = db.withTransaction { }
            fun g(db: AppDatabase) = db.runInTransaction { }
            fun h(s: SupportSQLiteDatabase) = s.beginTransactionNonExclusive()
            const val OPEN = " begin immediate"
            suspend fun k(db: AppDatabase) = db.transaction { } // withTransaction in a comment
            """,
        )
        assertEquals(listOf(3, 4, 5, 6), TransactionRule.check(source).map { it.line })
        assertNoneReported(planted("data/WriteTransaction.kt", "suspend fun f(db: AppDatabase) = db.withTransaction { }"), TransactionRule::check)
        // The frame is the main source set's file: the same name elsewhere is no exemption.
        assertReported(planted("data/WriteTransaction.kt", "fun f(db: AppDatabase) = db.withTransaction { }", sourceSet = "debug"), 3, TransactionRule::check)
    }

    @Test
    fun whitespaceOnlyThroughTheRule() = assertClean(WhitespaceRule::check)

    @Test
    fun whitespaceOnlyThroughTheRule_planted() {
        val source = planted(
            "data/NewEntities.kt",
            """
            fun a(name: String) = name.trim()
            fun b(text: String) = text.isBlank() || text.isNotBlank()
            fun c(c: Char) = c.isWhitespace()
            fun d(lines: List<String>) = lines.first(String::isNotBlank)
            fun e(name: String?) = name.isNullOrBlank() || name.orEmpty().trimEnd().ifBlank { "x" }.isEmpty()
            fun f(name: String) = with(name) { trimStart() }
            fun g(name: String) = TextWhitespace.trim(name) + name.trim(TextWhitespace::isWhitespace) // name.trim()
            """,
        )
        assertEquals(listOf(3, 4, 4, 5, 6, 7, 7, 7, 8), WhitespaceRule.check(source).map { it.line })
        val allowlist = mapOf(source.path to mapOf("name.trim()" to "planted"))
        assertEquals(listOf(4, 4, 5, 6, 7, 7, 7, 8), WhitespaceRule.check(source, allowlist).map { it.line })
        val rule = planted("model/TextWhitespace.kt", "fun isBlank(text: CharSequence) = text.all(::isWhitespace)")
        assertNoneReported(rule, WhitespaceRule::check) // the rule's own function, with a predicate
    }

    /** Every allowlisted snippet still exists, so a removed one cannot leave a stale exemption behind. */
    @Test
    fun whitespaceAllowlist_namesExistingSnippets() {
        WhitespaceRule.ALLOWED.forEach { (path, snippets) ->
            val source = checkNotNull(sources.find { it.path == path }) { path }
            snippets.keys.forEach { assertTrue("$path: $it", it in source.code) }
        }
    }

    @Test
    fun instrumentedTestMethods_areAllAcceptedByJUnit() {
        val classes = ClassFile.readAll(TestMethodRule.androidTestClassRoots)
        // The scan is real: the compiled instrumented tests, parameterized classes included.
        assertTrue(classes.any { it.name == "com.doro.organizer.data.maintenance.CrashSafetyTest" })
        assertTrue(classes.sumOf { type -> type.methods.count { "Lorg/junit/Test;" in it.annotations } } > 500)
        val violations = TestMethodRule.check(classes)
        assertTrue(violations.joinToString("\n", prefix = "JUnit rejects these (testing.md § 1):\n"), violations.isEmpty())
    }

    @Test
    fun instrumentedTestMethods_planted() {
        val planted = compileJava(
            "Planted.java",
            """
            package planted;

            import org.junit.rules.TestRule;

            public class Planted {
                static final long BIG = 1L << 40;
                static final double HALF = 0.5;

                @org.junit.Rule public TestRule rule = (base, description) -> base;
                @org.junit.Rule TestRule hiddenRule = (base, description) -> base;
                @org.junit.ClassRule public static TestRule classRule = (base, description) -> base;
                @org.junit.ClassRule public TestRule instanceClassRule = (base, description) -> base;
                @org.junit.Rule @org.junit.ClassRule public static TestRule both = (base, description) -> base;

                @org.junit.Rule public TestRule getRuleByGetter() { return rule; }
                @org.junit.Rule TestRule getHiddenRuleByGetter() { return rule; }

                @org.junit.BeforeClass public static void setUpClass() { }
                @org.junit.BeforeClass public void notStaticSetUpClass() { }
                @org.junit.AfterClass static void hiddenTearDownClass() { }
                @org.junit.Before public void setUp() { }
                @org.junit.Before public int setUpWithValue() { return 1; }
                @org.junit.After public static void staticTearDown() { }

                @org.junit.Test public void accepted() { Runnable r = () -> { }; r.run(); }
                @Deprecated @org.junit.Test(timeout = 5) public void acceptedWithArguments() { }
                @org.junit.Test(expected = IllegalStateException.class) public int value() { return 1; }
                @org.junit.Test public Object withParameter(int x) { return null; }
                @org.junit.Test void hidden() { }
                @org.junit.Test public static void shared() { }
                public int notATest() { return 1; }
            }

            class Base {
                @org.junit.Before public Object inheritedSetUp() { return null; }
            }

            class Derived extends Base {
                @org.junit.Test public void test() { }
            }

            /** No test class, so JUnit never validates it: like the companion copy of a Kotlin @JvmStatic method. */
            class NotATestClass {
                @org.junit.BeforeClass public void ignored() { }
            }
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "planted.Base.inheritedSetUp: @Before does not return Unit",
                "planted.Planted.getHiddenRuleByGetter: @Rule is not public",
                "planted.Planted.hidden: @Test is not public",
                "planted.Planted.hiddenRule: @Rule is not public",
                "planted.Planted.hiddenTearDownClass: @AfterClass is not public",
                "planted.Planted.instanceClassRule: @ClassRule is not static",
                "planted.Planted.notStaticSetUpClass: @BeforeClass is not static",
                "planted.Planted.setUpWithValue: @Before does not return Unit",
                "planted.Planted.shared: @Test is static",
                "planted.Planted.staticTearDown: @After is static",
                "planted.Planted.value: @Test does not return Unit",
                "planted.Planted.withParameter: @Test does not return Unit, takes parameters",
            ),
            TestMethodRule.check(planted).sorted(),
        )
    }

    private fun assertClean(rule: (KotlinSource) -> List<Violation>) {
        val violations = sources.flatMap(rule)
        assertTrue(violations.joinToString("\n", prefix = "Boundary test (architecture.md):\n"), violations.isEmpty())
    }

    private fun assertReported(source: KotlinSource, line: Int, rule: (KotlinSource) -> List<Violation>) {
        val reported = rule(source).map(Violation::toString)
        val location = "${source.path}:$line: "
        assertTrue("expected $location in $reported", reported.any { it.startsWith(location) })
    }

    private fun assertNoneReported(source: KotlinSource, rule: (KotlinSource) -> List<Violation>) {
        assertEquals(emptyList<String>(), rule(source).map(Violation::toString))
    }

    /** A source at [relative] below the app package, whose package line follows from its directory. */
    private fun planted(relative: String, body: String, sourceSet: String = "main"): KotlinSource {
        val packageName = (listOf(Layer.APP_PACKAGE) + relative.split('/').dropLast(1)).joinToString(".")
        val text = "package $packageName\n\n${body.trimIndent()}\n"
        return KotlinSource("src/$sourceSet/java/com/doro/organizer/$relative", sourceSet, text)
    }

    /** [source] compiled as [fileName] by the JDK's compiler into a temporary directory; every class it declares. */
    private fun compileJava(fileName: String, source: String): List<ClassFile> {
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "the JVM tests need a JDK, not a JRE" }
        val dir = Files.createTempDirectory("planted").toFile()
        try {
            val file = File(dir, fileName).apply { writeText(source) }
            val classpath = System.getProperty("java.class.path")
            check(compiler.run(null, null, null, "-classpath", classpath, "-d", dir.path, file.path) == 0) { "javac failed" }
            return ClassFile.readAll(listOf(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    private companion object {
        /** Read and lexed once per class: JUnit creates an instance per test method. */
        val sources by lazy { SourceTree.production() }
    }
}
