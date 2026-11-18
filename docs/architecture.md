# Architecture

Purpose: the technical skeleton that all code and the other docs plug into: build, packages and import rules, the dependency graph, navigation, the session gate, threading, time, the error model and performance budgets.

Related: [decisions.md](decisions.md) · [data-layer.md](data-layer.md) · [db-lifecycle.md](db-lifecycle.md) · [security.md](security.md) · [ui.md](ui.md) · [testing.md](testing.md) · [spec](../stakeholder-notepad-spec.md)

## Stack and versions

| Component | Choice | Decision |
|---|---|---|
| SDK levels | minSdk 28, compileSdk 37, targetSdk 35, each set explicitly | DEC-01 |
| Build | AGP 9.5.x, Gradle 9.6, JDK 17, and the SDK Build Tools version AGP 9.5.x requires. Every version and plugin id lives in `gradle/libs.versions.toml`; build files never inline a version | DEC-02 |
| Kotlin | 2.4.21 through AGP built-in Kotlin; Compose compiler and kotlinx-serialization Gradle plugins at the Kotlin version | DEC-02 |
| Code generation | KSP 2.3.14 (never 2.3.13); no kapt | DEC-02, DEC-07 |
| UI | Compose BOM 2026.09.00, Material 3, `material3-adaptive-navigation-suite` (BOM-managed) | DEC-04, DEC-50 |
| Host activity | activity 1.13.0 with activity-compose; `androidx.fragment` latest stable, declared explicitly (biometric 1.1.0 alone pulls fragment 1.2.5) | DEC-04 |
| Splash | `androidx.core:core-splashscreen`, latest stable | [Threading](#threading) |
| Navigation | navigation3-runtime and navigation3-ui 1.2.1, lifecycle-viewmodel-navigation3 2.11.0, kotlinx-serialization-core for keys | DEC-05 |
| Lifecycle | 2.11.0: runtime-compose, viewmodel-compose, viewmodel-savedstate, process (`ProcessLifecycleOwner`) | DEC-02 |
| Core | androidx.core 1.19.1 | DEC-02 |
| Database | Room 2.8.5 (runtime, compiler through KSP, Gradle plugin) on `net.zetetic:sqlcipher-android` 4.20.0 through `SupportOpenHelperFactory` | DEC-07, DEC-08 |
| Settings | Preferences DataStore, latest stable | DEC-09 |
| App lock | androidx.biometric 1.1.0 | DEC-65 |
| Async | kotlinx-coroutines-android, latest stable (version rule below) | — |
| Tracing | The platform `android.os.Trace` for the `doro:*` sections of [Performance budgets](#performance-budgets); no extra artifact | DEC-10 |
| Tests | JUnit4, kotlinx-coroutines-test, Turbine, AndroidX Test (runner, rules, ext-junit, Orchestrator, Espresso-Intents, UiAutomator), Compose UI test (`ui-test-junit4`, `ui-test-manifest`), room-testing, `org.json:json` as `testImplementation` only (Android's `org.json` is a stub on the JVM, [data-layer.md § Read model](data-layer.md#read-model)); hand-written fakes ([testing.md § 7](testing.md#7-fixtures-and-fakes)) | DEC-12 |
| Excluded | Hilt/Koin, Paging, Room 3, `navigation-compose`, Robolectric and mocking libraries, framework SQLite, `androidx.sqlite:sqlite-bundled`, `net.zetetic:android-database-sqlcipher`, security-crypto, Timber, network/analytics/crash SDKs. CI list: `config/banned-dependencies.txt` ([testing.md § 9](testing.md#9-ci-pipeline)) | DEC-05 to DEC-10, DEC-12, DEC-68 |

Version rule for every artifact: DEC-02 pins what it lists. Any other artifact (core-splashscreen, fragment, kotlinx-serialization, coroutines main and test, Turbine, the AndroidX Test artifacts, `org.json:json`) is pinned to its latest stable release at setup, and `libs.versions.toml` records the check date.

Verify at setup: re-check every DEC-02 version against its release page (baseline 2026-11-18), keep the newest patch of each pinned minor and note the check date in `libs.versions.toml`.

### AGP 9 build configuration

- Never apply `org.jetbrains.kotlin.android`: AGP 9 compiles Kotlin itself and bundles KGP 2.2.10. The root build file raises KGP and KSP through the `buildscript` classpath, as the AGP 9 release notes prescribe.
- A plugin that is already on the `buildscript` classpath is applied by id **without a version** (Gradle rejects a versioned plugin request for it): KSP is therefore `id("com.google.devtools.ksp")` and has no `[plugins]` entry in `libs.versions.toml`, only the `[libraries]` entry used for the classpath.
- Kotlin options go in `kotlin { compilerOptions { } }` (`android.kotlinOptions` is gone). `jvmTarget` follows `compileOptions.targetCompatibility` (17).
- `targetSdk = 35` is explicit: AGP 9 otherwise defaults it to compileSdk (37).
- Release builds run R8 with `proguard-android-optimize.txt` (AGP 9 rejects `proguard-android.txt`) and resource shrinking. `proguard-rules.pro` carries the log-stripping rule from [security.md § 10](security.md#10-logging-and-leak-hygiene-dec-70) (DEC-13, DEC-70).
- Room Gradle plugin: `room { schemaDirectory("$projectDir/schemas") }`; `app/schemas/` is committed (DEC-07, DEC-14).
- `buildConfig` and `resValues` stay off (AGP default). Per-build-type code lives in `src/debug` and `src/release` ([Per-build-type files](#per-build-type-files)).
- Lint: `NewApi` stays an error and `abortOnError` stays true (DEC-14).
- Everything that exists only for tests is defined once, in the Gradle sketch of [testing.md § 9](testing.md#9-ci-pipeline): `testInstrumentationRunner` (`com.doro.organizer.testing.DoroTestRunner`), `testBuildType`, the `qa` and `benchmark` build types and their source sets, `targetSdkOverride` (used only by `target36-canary`), the Orchestrator, Gradle Managed Devices and the `src/sharedTest` source set. The excerpt below shows the production configuration and the `doro.srcRoot` property only.

```kotlin
// build.gradle.kts (root)
buildscript { dependencies {
    classpath(libs.kotlin.gradle.plugin)   // org.jetbrains.kotlin:kotlin-gradle-plugin 2.4.21
    classpath(libs.ksp.gradle.plugin)      // com.google.devtools.ksp:symbol-processing-gradle-plugin 2.3.14
} }
// app/build.gradle.kts (excerpt)
plugins {
    alias(libs.plugins.android.application)  // no kotlin-android plugin
    alias(libs.plugins.kotlin.compose); alias(libs.plugins.kotlin.serialization)
    id("com.google.devtools.ksp")            // already on the classpath: no version, no alias
    alias(libs.plugins.room)
}
android {
    namespace = "com.doro.organizer"; compileSdk = 37
    defaultConfig {
        applicationId = "com.doro.organizer"   // placeholder (Q12)
        minSdk = 28; targetSdk = 35           // DEC-01 (testing.md § 9 reads a targetSdkOverride property for the canary job only)
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
    buildTypes {
        release {
            isMinifyEnabled = true; isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    lint { error += "NewApi"; abortOnError = true }
    testOptions { unitTests.all { it.systemProperty("doro.srcRoot", file("src").absolutePath) } }   // read by ArchitectureTest
}
room { schemaDirectory("$projectDir/schemas") }
```

Verify at setup: (a) `./gradlew :app:help` succeeds; if Gradle reports the Compose or serialization plugin as already on the classpath, apply it by id without a version too; (b) whether `buildFeatures.compose` is still required under built-in Kotlin; (c) that built-in Kotlin compiles the `.kt` files of `src/release/java` through the `qa` and `benchmark` source sets of the testing.md § 9 sketch (otherwise use the source-set API AGP 9.5 documents for Kotlin directories).

### ABI and 16 KB pages

- Native code: `libsqlcipher.so` (arm64-v8a, armeabi-v7a, x86, x86_64; 4.20.0 is 16 KB aligned) plus every `.so` that the pinned AndroidX artifacts bring (expected: `libandroidx.graphics.path.so` through Compose, `libdatastore_shared_counter.so` through DataStore). Verify at setup: list `lib/*/*.so` in the first release APK. Every one must have each `LOAD` segment aligned to `0x4000`; a library that is not on that list fails the page-size job until someone reviews and adds it ([testing.md § 9](testing.md#9-ci-pipeline)).
- Keep AGP's default uncompressed, zip-aligned `.so` packaging: never set `useLegacyPackaging = true` or `android:extractNativeLibs="true"`.
- CI runs `zipalign -c -P 16 -v 4` on the release APK (DEC-14); [testing.md § 9](testing.md#9-ci-pipeline) adds the ELF check and a run on a 16 KB page-size emulator image.
- No `abiFilters`. An App Bundle delivers one ABI; a universal APK carries all four. `libsqlcipher.so` alone is about 2.1 MB per ABI (about 7.7 MB for four); Verify at setup: measure the real sizes of the first release build.

## Package layout

One Gradle module `:app` (DEC-03). Production sources live in `app/src/main/java/com/doro/organizer/`:

```
com.doro.organizer
├── DoroApplication, AppContainer, MainActivity, BackupFileProvider
│                      process entry, DI root, single FragmentActivity, empty FileProvider subclass (security.md § 9.1)
├── core/
│   ├── time/         AppTime, DefaultAppTime, TimeChangeReceiver
│   ├── password/     PasswordPolicy: nfc, utf8, passwordBytes, checkNewPassword, PasswordCheck (security.md § 6; ui, session, security and data.maintenance share it)
│   ├── coroutines/   AppDispatchers, runDetached
│   ├── result/       AppResult, AppError, InputRule, BackupRejection
│   ├── messages/     AppMessages, UserMessage (ui.md § 2.4)
│   ├── external/     ExternalActivities, ExternalRequest, ExternalResult, ExternalKind, ExternalCallbacks
│   └── log/          AppLog (debug and release variants; policy: security.md § 10)
├── model/            pure Kotlin, the vocabulary of the repository and UI signatures:
│                     Perspective, EntityKind, DateFilter, NoteFilter, NoteQuery, toQuery(), SearchText,
│                     NoteRow, EntityRef, EntityOption, NoteDetail, NoteDraft, NewEntity, SaveResult, BatchResult,
│                     DeleteImpact, EntityDeleteResult, DraftInsert, KeyMode, WindowPolicy, UnlockBackoff
├── settings/         SettingsStore, Settings (Preferences DataStore)
├── security/         KeyVault and its key-state types (security.md § 3.6), AppLockController, BiometricGateway,
│                     LockListener, LockError, LockAvailability, BiometricAvailability, AuthPurpose
├── data/             DatabaseHolder, CorruptionListener, FlowProblemListener, SqlcipherEngine, DbFiles, NoteRepository, EntityRepository
│   ├── db/           Room database class, entities, DAOs, NoteRowRaw, migrations (data-layer.md)
│   └── maintenance/  DbMaintenance, MaintenanceListener, status and result types (db-lifecycle.md)
├── session/          SessionGate, GateState, GateEvent, GateEffect, reduce()
└── ui/
    ├── UiGraph, GateGraph, DoroRoot   what the UI may reach; gate switch
    ├── navigation/   keys, PickerPurpose, NavigationState, Navigator, ResultEventBus, entry provider
    ├── theme/        Material 3 theme (DEC-56)
    ├── common/       NoteRowItem, EntityPicker, MonthGrid, AppError to string
    ├── gate/         gate screens (ui.md § 10)
    ├── notes/        NoteListScreen, NoteListViewModel, PerspectiveSpec, batch mode
    ├── editor/       EditorScreen, EditorViewModel
    └── settings/     SettingsScreen, SettingsViewModel
```

- `model` imports nothing from Android or Room, so its types are JVM-testable. Room entities and `NoteRowRaw` stay in `data.db` and never reach `ui`; repositories return `model` types (mapping: [data-layer.md § Read model](data-layer.md#read-model)).
- Public signatures of every component that `ui` may use contain only `model` and `core` types, the types that the import table lists for that component, and Kotlin/Android types. `NoteFilter.toQuery()` and `SearchText` live in `model` because `NoteListViewModel` calls them; `SearchText` needs `java.util.Locale` ([data-layer.md § Search](data-layer.md#search)).

### Per-build-type files

| File | Debug | Release |
|---|---|---|
| `core/log/AppLog.kt` | forwards to `android.util.Log` | empty bodies |
| `data/SqlcipherLogging.kt` | no-op | `net.zetetic.database.Logger.setTarget(NoopTarget())` (DEC-08, [security.md § 10](security.md#10-logging-and-leak-hygiene-dec-70)) |
| `data/DebugRoomHooks.kt` | installs the query callback ([db-lifecycle.md § Room builder](db-lifecycle.md#room-builder)) | no-op |
| `DebugTools.kt` | installs StrictMode ([Threading](#threading)) | no-op |
| `debug/CrashTestService.kt` | the `:crashtest` service of [testing.md § 5](testing.md#5-security-and-lifecycle-tests); declared only in the debug manifest | absent |

Each pair is two files with the same fully qualified class name in `src/debug/java` and `src/release/java`. `src/main` never contains `Log` calls.

### Import rules

| Package | May import from the app | Third-party limits |
|---|---|---|
| `model` | nothing | Kotlin stdlib, `java.time`, `java.util.Locale`, kotlinx.serialization annotations only |
| `core` | `model` | no Room, DataStore, biometric, Keystore or SQLCipher; `core.password` uses `android.icu` (`Normalizer2`) and `java.nio.charset` |
| `settings` | `core`, `model` | the only importer of `androidx.datastore` |
| `security` | `core`, `model`, `settings` | the only importer of `androidx.biometric`, `android.security.keystore`, `javax.crypto`, `java.security` |
| `data.db` | `core`, `model` | `androidx.room` and `net.zetetic` only in `data`, `data.db`, `data.maintenance` |
| `data` | `core`, `model`, `data.db` | |
| `data.maintenance` | `core`, `model`, `settings`, `security`, `data`, `data.db` | |
| `session` | `core`, `model`, `settings`, `security` (`KeyVault` with its key-state and `UnlockResult` types, `AppLockController`, `LockListener`, `LockError`), `data` (`DatabaseHolder`, `OpenResult`, `OpenFailureKind`, `CorruptionListener`, `SqlcipherEngine`), `data.maintenance` (all public types) | |
| `ui` | `core`, `model`, `settings`, `session`, `data` (`NoteRepository`, `EntityRepository` only), `data.maintenance` (`DbMaintenance` and the status, result, operation and preview types in its signatures only), `security` (`AppLockController` and the types in its public signatures only) | never `data.db`, `DatabaseHolder`, `KeyVault`, the root package, or the third-party packages above |
| `ui.gate` | as `ui`, minus `settings`, `NoteRepository` and `EntityRepository` | |
| root package | everything | |

`ui` reaches dependencies only through `UiGraph`, and `ui.gate` only through its parent interface `GateGraph` ([Dependency injection](#dependency-injection)); their members are typed with allowed types, so `ui` cannot use a forbidden component even without an import.

### Boundary test

`ArchitectureTest` (JUnit4 in `app/src/test`, DEC-03, [testing.md I-39 and I-41](testing.md#4-invariant-catalog)) reads every `.kt` file under `app/src/{main,debug,release}/{java,kotlin}` (the `qa` and `benchmark` build types reuse `src/release`, so there is nothing more to scan), assigns it to a row by its `package` line, strips comments and string literals (except for rule 6), and fails with `file:line` for any rule below. This section is the single list of its rules; testing.md I-41 and [security.md § 10](security.md#10-logging-and-leak-hygiene-dec-70) link here and never restate them.

1. **Imports.** An import or fully qualified `com.doro.organizer.` reference that the import table forbids, or a forbidden third-party package. Tokens `noteRepository`, `entityRepository` and `settingsStore` in `ui/gate` files.
2. **Banned tokens**, each allowed only where stated:
   - concurrency: `runBlocking`; `GlobalScope`; `Thread(`; `Executors.`; `newSingleThreadContext`; `newFixedThreadPoolContext`; any `Dispatchers.` reference outside `core.coroutines`;
   - clocks and zones outside `core.time` ([Time](#time)): `System.currentTimeMillis`, `System.nanoTime`, `SystemClock`, `Instant.now`, `LocalDate.now`, `LocalDateTime.now`, `ZonedDateTime.now`, `Clock.system`, `ZoneId.systemDefault`, `TimeZone.getDefault`, `Calendar.getInstance`, `Date()`;
   - logging: `println`; `printStackTrace`; `System.out`; `System.err`; `timber.`; `android.util.Log` outside `core.log`; `setQueryCallback` outside `src/debug` (DEC-70);
   - Kotlin and API pitfalls: `.removeFirst()`; `.removeLast()`;
   - database (DEC-08, DEC-81, DEC-82, DEC-88): `allowMainThreadQueries`; `fallbackToDestructiveMigration`; `allowDataLossOnRecovery`; `FrameworkSQLiteOpenHelperFactory`; `AndroidSQLiteDriver`; imports of `androidx.sqlite.db.framework`, `android.database.sqlite.SQLiteDatabase` and `android.database.sqlite.SQLiteOpenHelper`; `minimumSupportedVersion`; `rawExecSQL`; `@RawQuery`; `SimpleSQLiteQuery`;
   - UI: `SecureFlagPolicy.SecureOff` (DEC-67).
3. **No cached database objects.** No stored property (including constructor `val`s) of a `*Dao` or `RoomDatabase` type outside `DatabaseHolder` (DEC-81).
4. **Redaction.** Every `data class` in `model`, `data`, `data.db`, `data.maintenance`, `session` and `ui` overrides `toString()` (DEC-70), except the key classes of `ui.navigation` (the NavKeys, `NotePrefill` and the `PickerPurpose` cases), which keep the generated text ([Keys and stacks](#keys-and-stacks)); an override of `toString` inside `ui.navigation` is itself a failure.
5. **Migrations.** Not checked by this test: `MigrationCoverageTest` ([testing.md I-48](testing.md#4-invariant-catalog), job `jvm-tests` of [testing.md § 9](testing.md#9-ci-pipeline)) fails for a schema version N ≥ 2 without a `MigrationTestHelper` test for N−1 → N. The number is kept so that rule 6 keeps its number.
6. **SQL text.** Read from the string literals of `data.db` (the `const val` fragments and the `@Query` strings), where rules 1 to 5 strip them: no `SELECT *` and no `.*` projection over `notes` ([data-layer.md § Editor loads, pickers and existence probes](data-layer.md#editor-loads-pickers-and-existence-probes)).

The source root reaches the test as the system property `doro.srcRoot`, set in `app/build.gradle.kts` (sketch above). `lint` `NewApi` (an error) catches every other API-level misuse, including the Java 21 `SequencedCollection` members and the API 34 `java.time` methods listed in [Cross-cutting conventions](#cross-cutting-conventions).

## Layering and data flow

```
Composable (stateless)       <- UiState: StateFlow, collectAsStateWithLifecycle()   -> calls ViewModel methods
ViewModel (NavEntry scope)   <- Flow / AppResult from repositories                  -> suspend calls
NoteRepository, EntityRepository: business rules and transactions (data-layer.md)
DatabaseHolder.observe { db -> dao flow } and withDb { } (db-lifecycle.md)          -> Room DAOs -> SQLCipher
```

1. Composables receive UiState and lambdas. They never touch repositories, DatabaseHolder or SettingsStore; only UI-local state (focus, scroll, an open menu) lives in Compose. One stated exception: `MainContent` reads `settings.dataEpoch` from `UiGraph.settingsStore` to reset navigation ([Data reset](#data-reset)).
2. Each ViewModel exposes one `StateFlow<XUiState>` built with `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)`. UiState classes are immutable and carry an explicit loading state, so "(No data)" never flashes (DEC-25).
3. The database is the single source of truth for lists. A write never patches UiState; Room invalidation re-emits the affected queries, which is what moves rows between groups (DEC-54). The editor draft is the only data the UI holds (DEC-37).
4. Repositories own every business rule and transaction: one public write method per user operation, one transaction, one `now` from AppTime (DEC-20). The rules are in [data-layer.md § Write rules](data-layer.md#write-rules). ViewModels never chain two writes into one logical operation.
5. Observed reads are Flows obtained through `DatabaseHolder.observe`, so they pause while the database is closed and resume after a swap without ViewModel code (DEC-81). One-shot reads and writes are suspend functions that run inside `DatabaseHolder.withDb`; called while the holder is `Closed` they return `Err(DatabaseUnavailable)` at once, and while it is opening, draining or exclusive they wait ([db-lifecycle.md § One-shot access: withDb](db-lifecycle.md#one-shot-access-withdb)).
6. A repository write must finish even if its caller is cancelled (a ViewModel cleared mid-save), and a bug inside it must still crash the app. Repositories therefore run every write through the helper below; they map expected exceptions to `AppResult` inside the block, so only bugs escape. A caller that may be gone before the result arrives (the editor's save-on-leave) additionally launches the call in `applicationScope` and posts an `Err` to `AppMessages` itself ([Threading](#threading)).
7. User-facing messages are UiState fields (`message: UserMessage?`) cleared through `onMessageShown()`, never `Channel` events, which can be lost across configuration changes. Messages that must outlive their screen go to `AppMessages` ([ui.md § 2.4](ui.md#24-snackbars-and-notices)).

```kotlin
// core.coroutines: runs block in this scope, detached from the caller
suspend fun <T> CoroutineScope.runDetached(block: suspend () -> T): T {
    val result = CompletableDeferred<T>()
    launch {                                            // a failing launch under the SupervisorJob reaches the uncaught handler: bugs crash
        try { result.complete(block()) }
        catch (e: CancellationException) { result.cancel(e); throw e }
        catch (e: Throwable) { result.completeExceptionally(e); throw e }
    }
    return result.await()                               // a cancelled caller stops waiting; the block keeps running
}
```

### Error model

```kotlin
// com.doro.organizer.core.result
sealed interface AppResult<out T> {
    data class Ok<T>(val value: T) : AppResult<T>
    data class Err(val error: AppError) : AppResult<Nothing>
}
sealed interface AppError {
    data object WrongPassword : AppError            // database password: inner GCM tag failed (DEC-62). Backup password at restore: wrong password or not a Doro Organizer backup (SQLCipher cannot tell them apart, db-lifecycle.md § Restore)
    data class Throttled(val retryAtElapsedMs: Long) : AppError       // AppTime.elapsedMillis() base, as persisted in security.unlock_retry_at (DEC-62, security.md § 5.3)
    data class InvalidInput(val rule: InputRule) : AppError           // password policy (security.md § 6.1); never produced by a repository
    data class InvalidBackup(val reason: BackupRejection) : AppError  // db-lifecycle.md § Restore validation
    data object StorageFull : AppError              // SQLiteFullException, ENOSPC, EDQUOT, failed free-space check
    data object FileAccess : AppError               // SAF or FileProvider I/O error, null stream, revoked grant
    data object KeyUnavailable : AppError           // Keystore failure while obtaining or changing a key (DEC-86)
    data object AuthFailed : AppError               // no successful authentication when toggling the lock (DEC-65)
    data object Busy : AppError                     // DbMaintenance already runs an operation
    data object DatabaseUnavailable : AppError      // holder closed or replaced, or a transient I/O failure; the caller keeps its input
    data object OutcomeUnknown : AppError           // DbMaintenance only: the commit could not be read back; the app restarts and startup decides (db-lifecycle.md § Generation commit)
    data object Unexpected : AppError               // DbMaintenance only, after rollback
}
enum class InputRule { BlankName, PasswordMalformed, PasswordTooShort, PasswordMismatch, AcknowledgementMissing }   // BlankName: data-layer.md § Note save; the four password rules in the order of security.md § 6.1
// BackupRejection { NotABackup, NewerVersion, Incompatible, Damaged }: declared in core.result, values and producers in db-lifecycle.md § Restore validation
```

- Every public suspend method of `NoteRepository`, `EntityRepository`, `DbMaintenance` and the `AppLockController` toggles returns `AppResult<T>`, where `T` is the type the owning doc names for it (data-layer.md, db-lifecycle.md, security.md § 7.3). Flows are plain `Flow<T>`; their failures are handled inside `observe` ([db-lifecycle.md § Observation](db-lifecycle.md#observation)).
- `KeyVault` is the exception: only `session` (E2) and `data.maintenance` call it, and its surface ([security.md § 3.6](security.md#36-keyvault-surface)) returns `UnlockResult` from `unlock` and `openKey` and throws `KeyVaultException(KeyFailure)` for every other failure. `DbMaintenance` maps them to `AppError` (`PasswordRejected` to `WrongPassword`, `Throttled` to `Throttled`, `KeyFailed` and every `KeyVaultException` to `KeyUnavailable`); E2 maps them to gate events ([States, events and effects](#states-events-and-effects)).
- Rows that vanished are success values, not errors: `SaveResult.NoteGone`, `loadNote` returning null, `EntityDeleteResult(deleted = false)` ([data-layer.md](data-layer.md)). There is no `NotFound` error.
- `InvalidInput`: the password rules are checked by `DbMaintenance` before it enters `Maintenance` ([db-lifecycle.md § Operation frame](db-lifecycle.md#operation-frame), step 4). A blank person or project name is prevented by the UI and is an `IllegalArgumentException` in a repository ([data-layer.md § Note save](data-layer.md#note-save)); `BlankName` and the acknowledgement case exist so that `AppError.messageRes()` stays total ([ui.md § 11.13](ui.md#1113-errors)).
- Repositories, KeyVault and DbMaintenance turn expected exceptions into AppError at their boundary. SQLite and SQLCipher classification: [db-lifecycle.md § Failure classification](db-lifecycle.md#failure-classification); Keystore and crypto classification: [security.md § 5.2](security.md#52-error-classes). `CancellationException` is always rethrown.
- A runtime `SQLiteDatabaseCorruptException` makes DatabaseHolder call `CorruptionListener.onRuntimeCorruption()`, which dispatches `CorruptAtRuntime` to SessionGate; the failed call returns `DatabaseUnavailable` and the gate replaces the UI (DEC-85).
- Any other exception is a bug and crashes the app, except inside DbMaintenance, which first restores the previous database ([db-lifecycle.md](db-lifecycle.md)) and returns `Unexpected`. DbMaintenance may also return `DatabaseUnavailable` (a transient failure, or a source found corrupt) and `OutcomeUnknown` (a `Throwable` after the commit started, or a commit that cannot be read back: nothing is deleted and the outcome is `Restart`, so its message must not claim that nothing changed; [ui.md § 9.6](ui.md#96-outcome-messages)).
- No exception message or user data reaches the UI or a log (DEC-70). `ui/common` maps each AppError to a string resource ([ui.md § 11.13](ui.md#1113-errors)). A cancelled SAF picker (null Uri) is not an error.

## Dependency injection

`DoroApplication.onCreate` builds `AppContainer` (DEC-06), installs the process observers and starts the gate. Construction does no I/O: disk, Keystore, DataStore reads and SQLCipher happen only in SessionGate effects or later, off the main thread.

```kotlin
open class DoroApplication : Application() {
    lateinit var container: AppContainer; internal set        // internal so that the instrumented test rule can assign its container (testing.md § 7)
    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName() != packageName) return   // the debug :crashtest process builds its own container (testing.md § 5)
        DebugTools.install(this)                                  // StrictMode in debug, no-op in release
        startApp()
    }
    protected open fun startApp() {                               // TestDoroApplication overrides it with an empty body
        container = createContainer()
        container.installProcessObservers()   // ProcessLifecycleOwner -> AppLockController and AppTime; TimeChangeReceiver
        container.sessionGate.start()         // Starting; Room is never built here (DEC-90)
    }
    protected open fun createContainer(): AppContainer = AppContainer(this)
}
```

How instrumented tests replace the container and start the gate: [testing.md § 7](testing.md#7-fixtures-and-fakes) (Wiring).

`AppContainer` is an open class whose members are `open val ... by lazy`. Every member type except the dispatchers and the scope is an interface named after its component with one production class `Default<Name>`; `Fake<Name>` doubles exist only where [testing.md § 7](testing.md#7-fixtures-and-fakes) lists them: `AppTime`, `SettingsStore`, `KeyVault`, the biometric gateway and the process lifecycle. Repositories, DatabaseHolder, DbMaintenance and AppLockController are never faked: tests use the real ones, on a real SQLCipher file, with the real re-lock rules over the fake gateway. JVM tests construct the `reduce()` function of SessionGate and the pure policies directly; ViewModels are tested instrumented on the real repositories ([testing.md § 1](testing.md#1-test-layers)).

| Member | Notes |
|---|---|
| `dispatchers: AppDispatchers` | `main` = `Dispatchers.Main.immediate`, `io`, `default`; tests inject test dispatchers |
| `applicationScope` | `CoroutineScope(SupervisorJob() + dispatchers.default)`, never cancelled |
| `processLifecycle: LifecycleOwner` | production: `ProcessLifecycleOwner.get()`; tests: a hand-written owner over `LifecycleRegistry`. Overridable. Used by `installProcessObservers`, `AppTime` and `AppLockController` |
| `appTime` | [Time](#time) |
| `settingsStore` | below |
| `files: DbFiles` | the roots of the database, staging, restore and share directories ([db-lifecycle.md § Files on disk](db-lifecycle.md#files-on-disk)); tests redirect them to temporary directories |
| `keyVault` | [security.md § 3.6](security.md#36-keyvault-surface) |
| `databaseHolder` | the only owner of Room (DEC-81); takes a `() -> CorruptionListener` provider (SessionGate) and a `FlowProblemListener` that posts to `appMessages` ([Event wiring](#event-wiring)) |
| `faultHook: MaintenanceFaultHook` | the type is defined in [db-lifecycle.md § Pipeline steps and fault hook](db-lifecycle.md#pipeline-steps-and-fault-hook); the default is a no-op, and test containers override it with a hook that throws, blocks or kills the process |
| `dbMaintenance` | takes the application `Context` (content resolver for SAF and FileProvider), `files`, `keyVault`, `databaseHolder`, `settingsStore`, `appTime` (backup file date, share sweep), `dispatchers`, `applicationScope` (operations run detached, [db-lifecycle.md § Operation frame](db-lifecycle.md#operation-frame)), `faultHook` and a `() -> MaintenanceListener` provider |
| `noteRepository`, `entityRepository` | stateless; resolve DAOs per call or per subscription through the holder |
| `appMessages: AppMessages` | application-scoped queue of `UserMessage`s shown by the shell ([ui.md § 2.4](ui.md#24-snackbars-and-notices)) |
| `externalActivities` | takes an `ExternalCallbacks` provider ([Result launchers](#result-launchers-and-process-death)) |
| `biometricGateway: BiometricGateway` | the platform adapter beneath `AppLockController` ([security.md § 7.3](security.md#73-applockcontroller-and-mainactivity)): availability, the prompt, the credential intent and the enrollment intent. Overridable; tests bind `FakeBiometricGateway` |
| `appLockController` | takes `biometricGateway`, `externalActivities`, `settingsStore`, `appTime`, `processLifecycle` and a `() -> LockListener` provider |
| `sessionGate` | `DefaultSessionGate`; implements `LockListener`, `CorruptionListener` and `MaintenanceListener` ([Event wiring](#event-wiring)) |
| `windowPolicy: StateFlow<WindowPolicy>` | below |

`windowPolicy` is the single source of the window flags and of the keyboard-privacy flag. It combines the settings, the committed key mode and the gate state. While the settings have not been read or the key mode is unknown (null before startup has read it, or while the key-state is invalid) it is the **closed** policy: `FLAG_SECURE` on, recents screenshot off, overlays hidden. A closed policy is still emitted, so the splash hold of [MainActivity startup order](#mainactivity-startup-order) is released (DEC-66, DEC-67; rules: [security.md § 8](security.md#8-screen-protection-dec-66-dec-67)):

```kotlin
// model
data class WindowPolicy(val secure: Boolean, val recentsScreenshot: Boolean, val hideOverlays: Boolean) {
    override fun toString() = "WindowPolicy(secure=$secure, recentsScreenshot=$recentsScreenshot, hideOverlays=$hideOverlays)"   // booleans only; the boundary test requires the override
    companion object { val Closed = WindowPolicy(secure = true, recentsScreenshot = false, hideOverlays = true) }
}
// AppContainer
val windowPolicy: StateFlow<WindowPolicy> =
    combine(settingsStore.settings, dbMaintenance.keyMode, sessionGate.state) { s, mode, gate ->
        if (s == null || mode == null) WindowPolicy.Closed
        else WindowPolicy(
            secure = s.screenshotProtection,
            recentsScreenshot = !(s.biometricLock || mode != KeyMode.PLAIN),
            hideOverlays = !(gate is GateState.Ready || (gate as? GateState.Maintenance)?.origin == Origin.Ready))   // security.md § 9.4
    }.stateIn(applicationScope, SharingStarted.Eagerly, WindowPolicy.Closed)
```

`MainActivity` collects it to apply the window flags and provides `LocalKeyboardPrivacy` (`ui/common`) with `policy.secure`, so composables read neither `SettingsStore` nor the policy directly ([ui.md § 3.3](ui.md#33-search-bar), [security.md § 11](security.md#11-keyboard-and-autofill-dec-71-q11)). When the policy is open `secure` equals `security.screenshot_protection`, which is the DEC-71 condition; when it is closed nothing sensitive is composed yet.

`UiGraph` and `GateGraph` are interfaces in `ui` that AppContainer implements; MainActivity provides the container as `LocalUiGraph`. They limit what UI code can reach:

```kotlin
interface GateGraph {                    // all that ui.gate may use
    val sessionGate: SessionGate; val dbMaintenance: DbMaintenance; val appLockController: AppLockController
    val externalActivities: ExternalActivities; val appTime: AppTime; val appMessages: AppMessages
}
interface UiGraph : GateGraph {
    val noteRepository: NoteRepository; val entityRepository: EntityRepository
    val settingsStore: SettingsStore; val applicationScope: CoroutineScope   // applicationScope: the editor's save-on-leave
}
```

### SettingsStore

`SettingsStore` is the only DataStore user. It exposes `settings: StateFlow<Settings?>` (null until the first read), `suspend fun snapshot(): Settings` (reads DataStore directly, so it is current right after an edit), one setter per toggle, `setBackoff(failures, retryAtElapsedMs)`, `setRemembered(personId, projectId)`, the draft-insert record below, `resetSecurity()` and `onDataReplaced()`. Values are never user text, secrets or URIs.

| Property | DataStore key | Type | Default | Owner of the semantics |
|---|---|---|---|---|
| `screenshotProtection` | `security.screenshot_protection` | Boolean | false | [security.md § 2](security.md#2-security-settings) |
| `biometricLock` | `security.app_lock` | Boolean | false | security.md § 2 |
| `encryptBackups` | `security.encrypt_backups` | Boolean | false | security.md § 2 |
| `unlockFailures` | `security.unlock_failures` | Int | 0 | security.md § 2, § 5.3 |
| `unlockRetryAtElapsedMs` | `security.unlock_retry_at` | Long, `AppTime.elapsedMillis()` base | 0 | security.md § 2, § 5.3 |
| `lockAutoOffNotice` | `security.lock_auto_off_notice` | Boolean | false | security.md § 2, § 7.6 |
| `rememberedPersonId` | `nav.remembered_person_id` | Long? | null | DEC-28 |
| `rememberedProjectId` | `nav.remembered_project_id` | Long? | null | DEC-28 |
| `dataEpoch` | `nav.data_epoch` | Int | 0 | [Data reset](#data-reset) |

- `resetSecurity()` sets the six `security.*` keys to their defaults and clears both remembered ids (Erase and the first-run reset, [security.md § 5.4](security.md#54-forgotten-password-erase-dec-63)). `onDataReplaced()` clears both remembered ids and the draft-insert records and increments `dataEpoch` in one edit.
- Draft-insert record, used only by `EditorViewModel` to survive a process death that falls between the database commit of a first insert and the saved state of the draft ([data-layer.md § Note save](data-layer.md#note-save), caller rules; lifecycle in [ui.md § 6.8](ui.md#68-saving-as-the-user-experiences-it)). `data class DraftInsert(val noteId: Long, val createdPersons: Map<String, Long>, val createdProjects: Map<String, Long>)` in `model` (`toString()` prints `DraftInsert(noteId=…)`; the map keys are draft-local UUIDs, never names). Members: `suspend fun putDraftInsert(draftKey: String, record: DraftInsert)`, `suspend fun draftInsert(draftKey: String): DraftInsert?`, `suspend fun dropDraftInsert(draftKey: String)`. Storage: one string preference `nav.draft_inserts` that holds at most 8 records, newest last; `putDraftInsert` appends and drops the oldest beyond 8, so records of abandoned drafts cannot accumulate. It is not a member of `Settings`, `resetSecurity()` leaves it alone, and the store returns only after the DataStore edit has completed, because the caller updates its `SavedStateHandle` after that.
- A corrupt preferences file is replaced with defaults (`ReplaceFileCorruptionHandler { emptyPreferences() }`). That fails open for the biometric lock, screenshot protection and the backoff counters; see [Top risks](#top-risks).

### Lifetimes

| Scope | Objects | Rebuilt or reset when |
|---|---|---|
| Open database | Room instance, DAOs, open-helper factory, raw key bytes; held only by DatabaseHolder | every open: Opening, and each reopen by DbMaintenance (backup, restore, key change) |
| Activity | BiometricPrompt inside AppLockController, the result launchers inside ExternalActivities, the window-policy collector | every `MainActivity.onCreate` (`attach`/`bind`); dropped in `onDestroy` |
| NavEntry | one NoteListViewModel per tab, EditorViewModel, SettingsViewModel, picker ViewModels, each with a SavedStateHandle | the entry is popped |
| Data | navigation stacks, NavEntry ViewModels, remembered selections | Restore, Start fresh, Erase ([Data reset](#data-reset)) |

Nothing else changes identity after a swap or a restore: repositories, KeyVault, SettingsStore, DbMaintenance and SessionGate keep their instances, and flows reconnect through `DatabaseHolder.observe`.

ViewModels are built with `viewModelFactory { initializer { } }` from `UiGraph`:

```kotlin
fun noteListFactory(graph: UiGraph, spec: PerspectiveSpec) = viewModelFactory {
    initializer {
        NoteListViewModel(spec, createSavedStateHandle(), graph.noteRepository,
            graph.entityRepository, graph.settingsStore, graph.appTime)
    }
}
// in the TabKey entry: val vm: NoteListViewModel = viewModel(factory = noteListFactory(LocalUiGraph.current, spec))
```

Tests: `reduce()` and other pure classes run on the JVM; SessionGate's effect runner, ViewModels and repositories are tested instrumented against the real components ([testing.md § 1](testing.md#1-test-layers)). Instrumented tests run `DoroTestRunner` (`com.doro.organizer.testing`), which instantiates `TestDoroApplication`, whose `startApp()` does nothing; a rule builds an `AppContainer` subclass that overrides the members under test and installs it before the first activity launches, so no real file, Keystore or DataStore is touched before the fakes are in place ([testing.md § 7](testing.md#7-fixtures-and-fakes), Wiring). Lifecycle tests that need a second container build it while no other container runs.

## Navigation 3

Gates sit above `NavDisplay` (DEC-05). Everything below exists only while DoroRoot shows the content ([Rendering](#rendering)).

### Keys and stacks

```kotlin
@Serializable data class TabKey(val perspective: Perspective, val epoch: Int) : NavKey   // tab root
@Serializable data class EditorKey(val noteId: Long?, val prefill: NotePrefill = NotePrefill()) : NavKey  // null = new
@Serializable data object SettingsKey : NavKey
@Serializable data class PickerKey(val kind: EntityKind, val purpose: PickerPurpose) : NavKey   // PickerPurpose: ui.md § 7.1
@Serializable data class NotePrefill(val personId: Long? = null, val projectId: Long? = null, val epochDay: Long? = null) // DEC-38
```

- Keys hold ids and enums only, never text, names, passwords or URIs (DEC-64, DEC-70). `Perspective` uses the internal names `All`, `Individual`, `Project`, `Date`, `Orphan` (DEC-50). `PickerPurpose` is a serializable sealed interface in `ui/navigation` whose cases carry ids ([ui.md § 7.1](ui.md#71-purposes)); `EntityKind` is in `model`.
- NavKeys keep the compiler-generated `toString()`: Navigation 3 derives `NavEntry.contentKey` from the key, and the saveable-state and ViewModelStore decorators key their state by it, so a redacted text would make all tab roots one entry. The generated text holds only ids and enums, so it contains no user data (DEC-70). The boundary test fails on any `toString` override in `ui.navigation`.
- `NavigationState` (`ui/navigation`) follows the official Navigation 3 multiple-back-stack recipe: one `rememberNavBackStack(TabKey(p, epoch))` per Perspective, the current tab in `rememberSaveable`, and for each stack its own decorator list `[rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()]` passed to `rememberDecoratedNavEntries`. A stack that is not displayed keeps its entries' saved state and ViewModels, so each tab keeps its own state (DEC-50, DEC-28). It is created by `rememberNavigationState(dataEpoch)` ([Data reset](#data-reset)).
- `NavDisplay(entries = ...)` receives the decorated entries of the All stack, followed by the current tab's stack when the current tab is not All.
- `NavigationSuiteScaffold` shows the five tabs in §2 order (labels in [ui.md § 2.1](ui.md#21-navigation-suite-and-tabs)). Its layout type is `NavigationSuiteType.None` whenever the top entry is not a `TabKey`, so the editor, Settings and pickers are full-screen (DEC-50). Tabs switch only at a tab root, so every other stack holds only its root and a key instance lives in one stack only.
- `Navigator` is the only writer: `selectTab(p)`, `open(key)` (push onto the current stack), `back()`, `resetAll(epoch)`.
- Tab events that composition cannot observe are counters in `NavigationState`, so a rotation, a lock or a covering picker never clears a selection or scrolls a list ([ui.md § 2.1](ui.md#21-navigation-suite-and-tabs)). `NavigationState` keeps two saveable `IntArray`s indexed by `Perspective`, `leaves` and `reselects`. `Navigator.selectTab(p)` increments `leaves[current]` when `p != current` and `reselects[p]` when `p == current`; Back from the root of a tab other than All increments `leaves[current]`. The `TabKey` entry passes `leaveCount = leaves[p]` and `reselectCount = reselects[p]` to `NoteListScreen`. `resetAll` zeroes both arrays.
- `NavigationState` wraps the content of every `NavEntry` in `CompositionLocalProvider(LocalIsTopEntry provides (key == currentStack.last()))`. The shell's single `appSnackbar` host is drawn only by the entry for which it is true ([ui.md § 2.4](ui.md#24-snackbars-and-notices)).
- Pickers return results through a `ResultEventBus` and `ResultEffect`, copied from the nav3-recipes "results" recipe into `ui/navigation` and provided as `LocalResultEventBus` above `NavDisplay`; the picker calls `sendResult(result)` and then `Navigator.back()`; the opener receives with `ResultEffect(resultKey)` ([ui.md § 7](ui.md#7-entitypicker)). Results live in memory only.

Verify at setup: (a) whether `rememberNavBackStack` on Android needs a `SavedStateConfiguration` whose SerializersModule registers the four key classes and the `PickerPurpose` cases; (b) that `createSavedStateHandle()` works in NavEntry-scoped ViewModels with this decorator order; (c) whether navigation3 1.2.1 ships a library result API equivalent to the recipe; if it does, use it and keep the names `sendResult` and `ResultEffect`; (d) that `MainContent` leaving and re-entering composition (Ready → Locked → Ready) keeps every entry's ViewModel instance and `rememberSaveable` state (testing.md L-21); if the decorators clear them when `NavDisplay` leaves composition, create the per-stack SaveableStateHolder and ViewModelStore decorators in `DoroRoot`, above the gate branch, and pass them into `MainContent`; (e) that an enabled `BackHandler` inside an entry consumes Back before `NavDisplay` pops, with gesture and three-button navigation on API 28 and 35; if not, use `NavigationBackHandler` from navigationevent-compose.

### Back behavior

| Top entry | Back does |
|---|---|
| Editor, Settings or a picker | pops it with `stack.removeAt(stack.lastIndex)` |
| Root of a tab other than All | switches to All; that tab's stack and state stay |
| Root of All | nothing intercepts it: `NavDisplay` holds one entry, so the system back-to-home animation runs. API 31+ moves the task to the back without finishing `MainActivity`; API 28–30 finishes it (DEC-50) |

In-screen states (selection mode DEC-36, an open search field) use `BackHandler(enabled = <only while active>)`, so predictive back stays intact (DEC-01, DEC-13; priority order in [ui.md § 2.5](ui.md#25-back-behavior)). Never override `onBackPressed` or intercept `KEYCODE_BACK`. Leaving the editor saves it ([Threading](#threading)), so Back never asks for confirmation (DEC-37).

### Data reset

Restore, Start fresh and Erase replace the data, so navigation must drop every id from the old database (DEC-84).

1. DbMaintenance calls `SettingsStore.onDataReplaced()` right after the new generation is committed (`DATA_RESET`). The data-reset marker makes this atomic with the commit: if the process dies in between, startup reconciliation calls it ([db-lifecycle.md § Data-reset marker](db-lifecycle.md#data-reset-marker)). Erase and the first-run reset call it too ([db-lifecycle.md § Startup reconciliation](db-lifecycle.md#startup-reconciliation)).
2. `rememberNavigationState(dataEpoch)` compares every stack's `TabKey.epoch` with the current `settings.dataEpoch` and, on a difference, calls `resetAll(dataEpoch)` synchronously before it builds the decorated entries: every stack becomes `[TabKey(p, dataEpoch)]` and the current tab becomes All. `NavDisplay` therefore never receives an entry of an old epoch. `MainContent` reads `dataEpoch` from `UiGraph.settingsStore.settings` (the exception to Layering rule 1); while `settings` is null nothing composes, because the gate is still `Starting`.
3. The old keys leave their stacks, so the decorators clear their saved state and ViewModels; filters held in SavedStateHandle go with them.
4. `EditorViewModel` stores the `dataEpoch` of its draft in its SavedStateHandle (key `"epoch"`) when the draft is first created, and skips save-on-leave and Save whenever that value differs from the current `dataEpoch`. A ViewModel recreated after process death reads the stored value, so a draft from the old database never lands in the new one.

Because `dataEpoch` is persisted and the reset follows the commit, the reset also happens when the process died between the commit and the next composition, and never happens after an ordinary process death.

### Result launchers and process death

External activities (SAF pickers, the share chooser, biometric enrollment, the API 28–29 device-credential screen) outlive composition: a lock removes the composition (DEC-90). Their plumbing therefore lives at activity and application level in `core.external`, not in composables.

```kotlin
enum class ExternalKind { Backup, Restore, Share, Enroll, CredentialConfirm }
class ExternalRequest(val kind: ExternalKind, val intent: Intent? = null, val suggestedName: String? = null,
                      val fallbackIntent: Intent? = null) {                           // enrollment: ACTION_SECURITY_SETTINGS (security.md § 7.1)
    override fun toString() = "ExternalRequest(kind=$kind)"                       // no URI, name or intent data (DEC-70)
}
class ExternalResult(val kind: ExternalKind, val uri: Uri?, val resultCode: Int) {
    override fun toString() = "ExternalResult(kind=$kind)"
}
interface ExternalCallbacks {                      // implemented by AppLockController; core cannot import security
    fun beforeLaunch(kind: ExternalKind)           // arms the one-shot re-lock exemption (security.md § 7.7)
    fun afterResult(kind: ExternalKind)            // clears it; called first in every result callback
    fun onCredentialResult(resultCode: Int)        // CredentialConfirm: RESULT_OK unlocks (security.md § 7.6)
}
interface ExternalActivities {
    val inbox: StateFlow<ExternalResult?>          // one slot: Backup or Restore results waiting for a consumer
    val failures: SharedFlow<ExternalKind>         // a launch that threw ActivityNotFoundException or SecurityException (see below)
    fun request(request: ExternalRequest)          // main thread; launched once MainActivity is STARTED
    fun take(kind: ExternalKind): ExternalResult?  // removes the inbox entry if it has this kind
    fun bind(activity: ComponentActivity)          // MainActivity.onCreate, before STARTED
}
```

- `bind` registers five launchers before STARTED: `Backup` = `CreateDocument("application/octet-stream")` (DEC-83), `Restore` = `OpenDocument()` with `*/*` (DEC-84), and `Share`, `Enroll`, `CredentialConfirm`, each a `StartActivityForResult()`. One launcher per kind means every callback knows which request it answers, with no saved "pending kind". It drops the launcher references at the activity's `ON_DESTROY`, so the application-scoped object never leaks an activity. `rememberLauncherForActivityResult` is never used for these (DEC-90).
- `request` queues the request; the collector installed by `bind` runs under `repeatOnLifecycle(STARTED)`, calls `callbacks.beforeLaunch(kind)` and then launches. Only one external activity runs at a time (the exemption is single-slot); a request made while another is queued replaces it, and the UI never offers two. The launch runs inside `try`: on `ActivityNotFoundException` or `SecurityException` the collector first launches `request.fallbackIntent` once when there is one; otherwise, or when that throws too, it calls `callbacks.afterResult(kind)` (so a launch that never paused the activity leaves no exemption armed, [security.md § 7.7](security.md#77-re-lock-rules)) and emits the kind on `failures`. The requesting screen maps it to `AppError.FileAccess` (`Backup`, `Restore`, `Share`) or shows only the enroll hint (`Enroll`); `CredentialConfirm` never fails this way, because AppLockController calls `createConfirmDeviceCredentialIntent` first and falls back to auto-off on null ([security.md § 7.6](security.md#76-device-credential-fallback-q7)).
- Every result callback first calls `callbacks.afterResult(kind)`. Delivery then depends on the kind:

| Kind | Delivered to | When |
|---|---|---|
| `CredentialConfirm` | `callbacks.onCredentialResult(resultCode)` | at once, in any gate state (it is what unlocks `Locked`) |
| `Backup`, `Restore` | the `inbox`; the requesting screen calls `take(kind)` | Settings in `Ready` (`Backup`, `Restore`); the Corrupt or KeyUnavailable gate screen for `Restore`, once it is composed and the gate is not `Locked`. A result that arrives while `Locked`, or in a new process after process death, waits in the inbox until its consumer composes. The consumer asks again for any password, because passwords are never saved (DEC-64) |
| `Share`, `Enroll` | nobody | discarded after `afterResult`; Settings re-checks availability on resume |

- A newer `Backup` or `Restore` result replaces the inbox entry; `resetAll` ([Data reset](#data-reset)) clears it. A `null` Uri is a cancelled picker and is not an error.
- Saved across process death: tab stacks, current tab, entry saveable state and SavedStateHandles (filters DEC-28, editor draft DEC-37). Never saved: gate state, passwords, keys, Result bus events, maintenance progress (an operation interrupted before its commit point is rolled back at startup, one interrupted after it is completed, DEC-87). Restored navigation is composed only when the gate reaches Ready again (DEC-90).

## SessionGate

`SessionGate` (application scope, `session` package) owns startup and locking (DEC-90). It is a pure reducer plus an effect runner:

```kotlin
interface SessionGate {
    val state: StateFlow<GateState>
    fun start()                      // DoroApplication.startApp()
    fun dispatch(event: GateEvent)   // main thread only; reads elapsed = AppTime.elapsedMillis() and applies reduce() synchronously
}
internal fun reduce(state: GateState, event: GateEvent, elapsedNow: Long): Pair<GateState, List<GateEffect>>
```

Effects run in `applicationScope` and report back through `dispatch` on the main thread. `reduce` is pure: the only clock value it needs is the `elapsedNow` that `dispatch` passes in, on the same base as `NeedsPassword.retryAt` (guard of T7). No gate decision uses the wall clock.

### States, events and effects

```kotlin
sealed interface GateState {
    data class Starting(val authenticated: Boolean) : GateState
    data class NeedsPassword(val failures: Int, val retryAt: Long, val couldNotOpen: Boolean) : GateState   // retryAt: elapsedMillis() base
    data class Locked(val resume: Resume, val authError: LockError? = null) : GateState
    data class Opening(val viaPassword: Boolean, val lockPending: Boolean, val transientError: Boolean) : GateState
    data object Ready : GateState
    data class Maintenance(val op: MaintenanceOp, val origin: Origin, val corruptPending: Boolean = false) : GateState
    data object Corrupt : GateState
    data object KeyUnavailable : GateState
}
sealed interface Resume { data object Open : Resume; data object Ready : Resume; data class Maintenance(val state: GateState.Maintenance) : Resume
                          data object Corrupt : Resume; data object KeyUnavailable : Resume }
enum class Origin { Ready, Corrupt, KeyUnavailable, NeedsPassword }
class LockError(val code: Int, val message: String?) {   // security package: BiometricPrompt error code and the system's errString (security.md § 7.4), never user data; message == null is a silent error (the lock screen shows no text)
    override fun toString() = "LockError(code=$code)"
}
```

- `Starting(authenticated)`: startup in progress. `authenticated` is true only after a recovery operation restarted the app (T30).
- `NeedsPassword(failures, retryAt, couldNotOpen)`: Password mode, before the database opens (DEC-62). `retryAt` is on the `AppTime.elapsedMillis()` base and has passed through `UnlockBackoff.effectiveRetryAt`, so it is never further away than the current delay (0 = no delay; [security.md § 5.3](security.md#53-backoff-dec-62)).
- `Locked(resume, authError)`: the biometric lock is shown. `resume` is what returns on unlock: `Open` (not yet opened), `Ready`, a `Maintenance` state, `Corrupt` or `KeyUnavailable`. A `Locked(Maintenance)` never has origin `NeedsPassword` (T32).
- `Opening(viaPassword, lockPending, transientError)`: unwrap the key, open, verify. `lockPending` means the app went to the background meanwhile (T24).
- `Ready`: database open, content composed.
- `Maintenance(op, origin, corruptPending)`: `op` is one of `Backup`, `Restore`, `KeyChange`, `StartFresh`, `Erase`; `origin` is the state it started from. `corruptPending` records runtime corruption reported while Room was being reopened (T38).
- `Corrupt` and `KeyUnavailable`: recovery screens (DEC-85, DEC-86).

Events (`GateEvent`; every class overrides `toString()` and none prints a password, DEC-70):

| Event | Payload | Raised by |
|---|---|---|
| `StartupLoaded` | `mode: KeyMode, lockEnabled: Boolean, failures: Int, retryAt: Long` (`elapsedMillis()` base) | E1 |
| `StartupKeyFailed` | `lockEnabled: Boolean` | E1 |
| `PasswordSubmitted` | `password: CharArray`; `toString() = "PasswordSubmitted(<redacted>)"` | gate UI |
| `Retry` | | gate UI |
| `Opened`, `OpenFailed(kind)` | `kind: OpenFailureKind` = `Corrupt` or `Transient` | E2, from `DatabaseHolder.open` |
| `PasswordRejected`, `PasswordNeeded` | `failures: Int, retryAt: Long` (`elapsedMillis()` base) | E2, from `UnlockResult` |
| `KeyFailed` | | E2, from `UnlockResult` or a `KeyVaultException` |
| `LockNow`, `Unlocked`, `AuthError(error)` | `error: LockError` | AppLockController, through `LockListener` |
| `CorruptAtRuntime` | | DatabaseHolder, through `CorruptionListener` |
| `MaintenanceStarted(op)`, `MaintenanceFinished(outcome)` | `outcome` = `Reopened`, `ReopenFailed(kind)`, `Restart` or `Aborted` | DbMaintenance, through `MaintenanceListener` |

`LockNow` is raised by AppLockController under the re-lock rules of [security.md § 7.7](security.md#77-re-lock-rules) (process ON_STOP, and ON_START after an expired exemption, only while the lock is on and no exemption is active) **whatever the gate state**; only the reducer decides what it means (T24, T25, T32, T34, T36). `Unlocked` is raised after a successful prompt, after a `CredentialConfirm` result with `RESULT_OK`, and after the lock turned itself off (security.md § 7.6).

Effects:
- **E1 RunStartup** (io), in this order: (1) `SqlcipherEngine.init()`, idempotent per process, in the order of [security.md § 3.4](security.md#34-sqlcipher-key-and-settings): `System.loadLibrary("sqlcipher")` (DEC-08), `SqlcipherLogging.configure()` (release: logger target `NoopTarget`), then `PRAGMA cipher_default_compatibility = 4` on a throwaway in-memory connection; (2) await the first `SettingsStore` value; (3) `DbMaintenance.reconcileAtStartup()`, which reads the key-state through KeyVault, deletes leftovers and returns a `StartupDecision` ([db-lifecycle.md § Startup reconciliation](db-lifecycle.md#startup-reconciliation)); (4) `SettingsStore.snapshot()` for `lockEnabled`, `failures` and `retryAt`, read after the reconciliation because the first-run reset may have changed them; `retryAt` is passed through `UnlockBackoff.effectiveRetryAt(failures, stored, appTime.elapsedMillis())`, so a reboot since the failures does not leave a stale countdown; (5) raise `StartupLoaded(mode, …)` for `Proceed(_, mode)`, `FirstRun` and `AdoptPlain` (mode `PLAIN`), or `StartupKeyFailed(lockEnabled)` for `KeyUnavailable`. E1 has no other outcome: an unreadable or invalid key-state is `KeyUnavailable` by the classification of db-lifecycle.md; any other exception is a bug and crashes the app, with nothing deleted that the decision table does not allow.
- **E2 UnlockAndOpen(password?)**, in this order: (1) `DbMaintenance.completeStartup()`, which returns `StartupCompletion { Done, Transient, KeyUnavailable }` (it creates the first-run database or adopts plaintext files, and is a no-op otherwise; [db-lifecycle.md § Completing startup in Opening](db-lifecycle.md#completing-startup-in-opening)): `Transient` (storage full or another transient failure) raises `OpenFailed(Transient)`, `KeyUnavailable` raises `KeyFailed`; (2) `KeyVault.readState()` for the generation: no file, or a `KeyVaultException(InvalidState)`, raises `KeyFailed`; (3) `KeyVault.unlock(passwordBytes?)` ([security.md § 3.6](security.md#36-keyvault-surface); Keystore calls on io, PBKDF2 on default, the password array zeroed afterwards, DEC-64) returns an `UnlockResult`: `Key(rawKey)` continues; `PasswordRejected(failures, retryAt)` and `Throttled(failures, retryAt)` both raise the gate event `PasswordRejected(failures, retryAt)`, with the counters that KeyVault persisted before it returned ([security.md § 5.3](security.md#53-backoff-dec-62)); `PasswordNeeded` (a `PASSWORD` slot without a password; defensive, because T2 normally routes Password mode to `NeedsPassword` first) raises `PasswordNeeded(failures, retryAt)` from `SettingsStore.snapshot()` after `UnlockBackoff.effectiveRetryAt`, with no Keystore call; `KeyFailed`, or a `KeyVaultException`, raises `KeyFailed`; (4) `DatabaseHolder.open(generation, rawKey)`, which takes ownership of the key (open and verify, [db-lifecycle.md § Open sequence](db-lifecycle.md#open-sequence)): `Opened` or `OpenFailed(kind)`. After a correct password KeyVault resets `security.unlock_failures` and `security.unlock_retry_at` ([security.md § 5.1](security.md#51-cold-start-per-mode) step 3).
- **E3 CloseDatabase**: `DatabaseHolder.close()`; the key is zeroed and no file is deleted (DEC-85).

Not every failure of the database layer fits `Corrupt` or `Transient`. A live database whose `user_version` exceeds `AppDatabase.VERSION` (an app downgrade) is classified `Transient` by [db-lifecycle.md § Failure classification](db-lifecycle.md#failure-classification): the gate shows the open-error screen, nothing destructive is offered and installing the newer version cures it.

### Event wiring

The import rules keep `security`, `data` and `data.maintenance` from importing `session`, so raisers cannot hold a SessionGate. They declare a listener interface in their own package, and SessionGate implements the three that concern the gate (dependency inversion):

| Raiser | Interface (package) | Methods | Implemented by |
|---|---|---|---|
| AppLockController | `LockListener` (`security`) | `onLockNow()`, `onUnlocked()`, `onAuthError(error: LockError)` | SessionGate |
| DatabaseHolder | `CorruptionListener` (`data`) | `onRuntimeCorruption()` | SessionGate |
| DbMaintenance | `MaintenanceListener` (`data.maintenance`) | `onMaintenanceStarted(op)`, `onMaintenanceFinished(outcome)` | SessionGate |
| DatabaseHolder | `FlowProblemListener` (`data`) | `onFlowProblem(error: AppError)`, may be called from any thread ([db-lifecycle.md § Observation](db-lifecycle.md#observation)) | AppContainer: posts a `UserMessage` to `AppMessages` (thread-safe); the gate is not involved |

- AppContainer passes each raiser a **provider** of the listener, for example `DefaultDatabaseHolder(…, corruptionListener = { sessionGate }, flowProblemListener = FlowProblemListener { e -> appMessages.post(errorMessage(e)) })`, where `errorMessage` builds the `UserMessage` of [ui.md § 2.4](ui.md#24-snackbars-and-notices) with `UiText.Error(e)` and `isError = true`; the provider is resolved at the first event. No constructor takes the gate, so the lazy members never recurse (SessionGate depends on the holder, the maintenance and the lock controller; they do not depend on SessionGate).
- Threads: `LockListener` and `MaintenanceListener` calls already arrive on the main thread (the lifecycle observer and BiometricPrompt callbacks run on it; DbMaintenance calls the listener inside `withContext(dispatchers.main)`, so the gate has changed before the next pipeline step runs, [db-lifecycle.md § Interface and status](db-lifecycle.md#interface-and-status)). `CorruptionListener.onRuntimeCorruption()` may arrive on any thread; the implementation launches `dispatch(CorruptAtRuntime)` on `dispatchers.main` in `applicationScope` and returns at once.
- SessionGate calls the other way directly, because `session` may import `security`: whenever the state changes from a non-`Locked` state to `Locked`, or back, `dispatch` calls `appLockController.onGateLockedChanged(locked)`, which arms the one-shot auto-prompt ([security.md § 7.3](security.md#73-applockcontroller-and-mainactivity)). AppLockController holds only the exemption and the prompt-in-flight and auto-prompt flags; the lock state is `GateState.Locked`.
- Components that must read the gate state read it through SessionGate's `state` flow (ui) or are told by SessionGate (AppLockController); nothing else observes it.

### Transition table

Rows are independent: for one (state, event) pair at most one row matches, because the guards are mutually exclusive. T35 applies to every pair that no other row (T1–T34 with their sub-rows, T36–T39) matches. Notation in the To column: a state named without arguments takes the defaults (`false`, `0`, `null`); `Opening()` is `Opening(viaPassword = false, lockPending = false, transientError = false)` and `Opening(viaPassword)` sets that flag only; `copy(f = v)` keeps every other field of the current state; `Locked(X)` is `Locked(Resume.X)`.

| # | From | Event (raised by) | Guard | To | Effects |
|---|---|---|---|---|---|
| T1 | — | `start()` (DoroApplication) | | Starting(false) | E1 |
| T2 | Starting | StartupLoaded (E1) | mode = Password | NeedsPassword(failures, retryAt, couldNotOpen = false) | |
| T3 | Starting | StartupLoaded (E1) | mode ≠ Password, lock on, not authenticated | Locked(Open) | |
| T4 | Starting | StartupLoaded (E1) | mode ≠ Password, and lock off or authenticated | Opening() | E2() |
| T5 | Starting | StartupKeyFailed (E1) | lock on, not authenticated | Locked(KeyUnavailable) | |
| T6 | Starting | StartupKeyFailed (E1) | lock off or authenticated | KeyUnavailable | |
| T7 | NeedsPassword | PasswordSubmitted (gate UI) | elapsedNow ≥ retryAt | Opening(viaPassword) | E2(password) |
| T8 | NeedsPassword | MaintenanceStarted(Erase) (DbMaintenance) | | Maintenance(Erase, NeedsPassword) | |
| T9 | Locked | Unlocked (AppLockController) | resume = Open | Opening() | E2() |
| T10 | Locked | Unlocked (AppLockController) | resume ≠ Open | the resume state (Ready, the wrapped Maintenance, Corrupt or KeyUnavailable) | |
| T11 | Locked | AuthError(error) (AppLockController) | | copy(authError = error) | |
| T12 | Locked(Ready, Corrupt or KeyUnavailable) | MaintenanceStarted(op) (DbMaintenance) | | Locked(Maintenance(op, origin = that resume)) | |
| T13 | Locked(Ready) | CorruptAtRuntime (DatabaseHolder) | | Locked(Corrupt) | E3 |
| T14a | Locked(Maintenance m) | MaintenanceFinished(Reopened) (DbMaintenance) | not m.corruptPending | Locked(Ready) | |
| T14b | Locked(Maintenance m) | MaintenanceFinished(Reopened) (DbMaintenance) | m.corruptPending | Locked(Corrupt) | E3 |
| T14c | Locked(Maintenance) | MaintenanceFinished(ReopenFailed(Corrupt)) (DbMaintenance) | | Locked(Corrupt) | E3 |
| T14d | Locked(Maintenance) | MaintenanceFinished(ReopenFailed(Transient)) (DbMaintenance) | | Locked(Open) | E3 |
| T14e | Locked(Maintenance m) | MaintenanceFinished(Aborted) (DbMaintenance) | | Locked(m.origin as resume); Locked(Corrupt) with E3 if `m.origin = Ready` and `m.corruptPending` | |
| T15 | Locked(Maintenance) | MaintenanceFinished(Restart) (DbMaintenance) | | Starting(false) | E1 |
| T16 | Opening | Opened (E2) | | Ready, or Locked(Ready) if lockPending | |
| T17 | Opening | OpenFailed(Corrupt) (E2) | | Corrupt, or Locked(Corrupt) if lockPending | E3 |
| T18 | Opening | OpenFailed(Transient) (E2) | viaPassword | NeedsPassword(failures = 0, retryAt = 0, couldNotOpen = true) | E3 |
| T19 | Opening | OpenFailed(Transient) (E2) | not viaPassword | copy(transientError = true) | E3 |
| T20 | Opening | Retry (gate UI) | transientError | copy(transientError = false) | E2() |
| T21 | Opening | PasswordRejected(failures, retryAt) (E2) | | NeedsPassword(failures, retryAt, couldNotOpen = false) | |
| T22 | Opening | PasswordNeeded(failures, retryAt) (E2) | | NeedsPassword(failures, retryAt, couldNotOpen = false) | |
| T23 | Opening | KeyFailed (E2) | | KeyUnavailable, or Locked(KeyUnavailable) if lockPending | |
| T24 | Opening | LockNow (AppLockController) | | copy(lockPending = true) | |
| T25 | Ready | LockNow (AppLockController) | | Locked(Ready) | none: Room stays open |
| T26 | Ready | CorruptAtRuntime (DatabaseHolder) | | Corrupt | E3 |
| T27 | Ready | MaintenanceStarted(op) (DbMaintenance) | | Maintenance(op, Ready) | |
| T28a | Maintenance m | MaintenanceFinished(Reopened) (DbMaintenance) | not m.corruptPending | Ready | |
| T28b | Maintenance m | MaintenanceFinished(Reopened) (DbMaintenance) | m.corruptPending | Corrupt | E3 |
| T29a | Maintenance | MaintenanceFinished(ReopenFailed(Corrupt)) (DbMaintenance) | | Corrupt (as T17) | E3 |
| T29b | Maintenance | MaintenanceFinished(ReopenFailed(Transient)) (DbMaintenance) | | Opening(transientError = true) (as T19) | E3 |
| T30 | Maintenance | MaintenanceFinished(Restart) (DbMaintenance) | | Starting(true) | E1 |
| T31a | Maintenance m | MaintenanceFinished(Aborted) (DbMaintenance) | m.origin = Ready (defensive) | Ready, or Corrupt with E3 if `m.corruptPending` | |
| T31b | Maintenance | MaintenanceFinished(Aborted) (DbMaintenance) | origin = Corrupt | Corrupt | |
| T31c | Maintenance | MaintenanceFinished(Aborted) (DbMaintenance) | origin = KeyUnavailable | KeyUnavailable | |
| T31d | Maintenance | MaintenanceFinished(Aborted) (DbMaintenance) | origin = NeedsPassword | Starting(false) | E1 (reads the persisted failures and retryAt again) |
| T32 | Maintenance m | LockNow (AppLockController) | m.origin ≠ NeedsPassword | Locked(Maintenance(m)) | none: the operation continues |
| T33 | Corrupt, KeyUnavailable | MaintenanceStarted(Restore or StartFresh) (DbMaintenance) | | Maintenance(op, this state) | |
| T34 | Corrupt, KeyUnavailable | LockNow (AppLockController) | | Locked(this state) | |
| T35 | any | any pair no other row matches | | unchanged | debug log of class names only; a `PasswordSubmitted` is zeroed |
| T36 | Starting(authenticated = true) | LockNow (AppLockController) | | Starting(false) | |
| T37 | Opening | CorruptAtRuntime (DatabaseHolder) | | as T17 | E3 |
| T38 | Maintenance | CorruptAtRuntime (DatabaseHolder) | | copy(corruptPending = true) | |
| T39 | Locked(Maintenance m) | CorruptAtRuntime (DatabaseHolder) | | Locked(Maintenance(m.copy(corruptPending = true))) | |

Rules that complete the table:
- **Passwords.** `dispatch` zeroes the `CharArray` of every `PasswordSubmitted` that produces no `E2(password)` effect (T7's guard failed, or T35 applied); E2 zeroes the one it consumes.
- **Exemptions that look like gaps.** `LockNow` in `NeedsPassword` or in `Maintenance` with origin `NeedsPassword` is ignored (T35, T32's guard): Password mode asks for the password at every cold start and never locks before it (Q8), and no data is open during that Erase. `LockNow` in `Locked` is ignored (already locked).
- **Locking while starting.** T24, T36 and T32 keep a lock that arrives during startup or an operation. `Starting(true)` skips the biometric lock only because the user authenticated before the recovery screen; if the app is left meanwhile, T36 withdraws the exemption so T3 or T5 applies when E1 finishes. `LockNow` in `Starting(false)` needs no row of its own: T35 may drop it, because `StartupLoaded` or `StartupKeyFailed` applies T3 or T5 whenever the lock is on, and AppLockController raises `LockNow` only while the lock is on. Nothing is therefore "remembered" in `Starting`; the lock setting is read again when E1 finishes.
- **Contract with DbMaintenance.** Every `MaintenanceStarted` is followed by exactly one `MaintenanceFinished`. `MaintenanceStarted` is raised only when every dialog of the operation has been answered, and a restore raises it only after the user's confirmation; its earlier steps run while the gate keeps its state ([db-lifecycle.md § Operation frame](db-lifecycle.md#operation-frame)). `Reopened`: Room is open again on the committed generation. `ReopenFailed(kind)`: no generation could be reopened. `Restart`: data replaced or the commit state unknown, run E1. `Aborted`: a recovery or Erase operation stopped before it changed anything (never raised from origin Ready, where the old database is reopened with `Reopened`; T31 handles it defensively).
- **Notes.** Q8 holds because Password mode never passes through `Locked` at cold start (T2, T7, T16). `Restart` ends Start fresh, Erase and every recovery-origin Restore; `Starting(true)` skips the biometric lock, while Password mode still asks for the password (T2). T29 with `Transient` from Password mode asks for the password again after Retry (the key was zeroed by E3): E2 raises `PasswordNeeded` (T22). T18 zeroes `failures` because a correct password already reset them; the screen shows the could-not-open message, never the wrong-password message.
- **Late events.** An effect reports whatever it finds after the gate has moved on; the reducer ignores events that do not match the current state (T35). E2 never runs twice at once: it starts only from T4, T7, T9 and T20, and E1 only from T1, T15, T30 and T31d.

### Rendering

| State | DoroRoot shows (screen designs in [ui.md § 10](ui.md#10-gate-screens)) |
|---|---|
| Starting | the held system splash at activity creation, otherwise a blank background |
| NeedsPassword | password screen with "Forgot password?" (DEC-62, DEC-63): the wrong-password error when `failures > 0`, the could-not-open message when `couldNotOpen`, and the countdown with the field disabled while `AppTime.elapsedMillis() < retryAt` ([security.md § 5.3](security.md#53-backoff-dec-62)) |
| Locked | lock screen; its "Unlock" button calls `AppLockController.prompt()`. The automatic prompt is fired by AppLockController at the activity's ON_RESUME ([security.md § 7.3](security.md#73-applockcontroller-and-mainactivity)), never from composition (DEC-65) |
| Opening | blank background with a delayed progress indicator; the busy password screen when `viaPassword`; an error with "Try again" when `transientError` |
| Ready | `NavigationSuiteScaffold` with `NavDisplay` |
| Maintenance | origin Ready: the content, hidden from accessibility services and keyboard focus, under an opaque overlay that consumes pointer input, key events and Back and holds the keyboard focus (snippet below, [ui.md § 10.6](ui.md#106-maintenance-overlay-and-screen)); other origins: a progress screen |
| Corrupt | "Restore from backup" and "Start fresh" (DEC-85) |
| KeyUnavailable | the same two actions (DEC-86) |

```kotlin
@Composable
fun DoroRoot(graph: UiGraph = LocalUiGraph.current) {
    val gate by graph.sessionGate.state.collectAsState()   // not lifecycle-aware on purpose: a background lock is in state before the first frame
    val holder = rememberSaveableStateHolder()
    val showContent = gate is GateState.Ready || (gate as? GateState.Maintenance)?.origin == Origin.Ready
    if (showContent) {
        val blocked = gate is GateState.Maintenance             // origin Ready: showContent implies it
        Box(Modifier.fillMaxSize()) {
            holder.SaveableStateProvider("main") {
                // The container is always present; only its modifier changes, so MainContent keeps its single call site and all of its state.
                // Pointer blocking alone is not enough: TalkBack, Switch Access and a hardware keyboard bypass it (ui.md § 10.6).
                Box(Modifier.fillMaxSize().then(
                    if (blocked) Modifier.clearAndSetSemantics { }.focusProperties { canFocus = false } else Modifier)
                ) { MainContent() }
            }
            if (blocked) MaintenanceOverlay()                   // opaque; consumes pointer input, key events and Back; focusable with a paneTitle
        }
    } else {
        GateScreen(gate, graph)                                 // receives the graph as a GateGraph only
    }
}
```

Dialogs of a restore that has not been confirmed yet (checking, passwords, confirmation) and failure dialogs are rendered by `MaintenanceStatusHost` over whatever screen is showing ([ui.md § 8.12](ui.md#812-maintenance-status-host)); the gate state does not change for them.

### Invariants

1. The Room instance on the live database is first built by E2 in `Opening` and afterwards rebuilt only by DatabaseHolder's exclusive mode (DEC-81). Nothing builds it in `DoroApplication.onCreate`, `Starting`, `NeedsPassword` or `Locked(Open)`. DbMaintenance may build temporary Room instances only on staged copies under `noBackupFilesDir`, held in local variables and never stored in a property ([db-lifecycle.md § Concurrency rules](db-lifecycle.md#concurrency-rules)).
2. The content (`NavDisplay` and every composable or ViewModel that reads NoteRepository or EntityRepository) is composed only while `showContent` is true. Gate screens receive only a `GateGraph`: SessionGate, DbMaintenance, AppLockController, ExternalActivities, AppTime and AppMessages. They own no ViewModel: state that must survive rotation lives in GateState and `DbMaintenance.status`; password fields use `remember { TextFieldState() }` ([security.md § 6.2](security.md#62-fields-and-state)), other inputs `rememberSaveable`.
3. `Locked` never composes content. The "main" SaveableStateProvider and the NavEntry ViewModels survive, so unlocking returns to the exact screen (DEC-66; Verify at setup item (d) of [Keys and stacks](#keys-and-stacks)).
4. Gate state is never saved; after process death the gate starts at `Starting` (DEC-90). States, events and effects never hold passwords or keys beyond the effect that consumes them, and their `toString()` redacts (DEC-64, DEC-70).
5. The reducer is total: rows T1–T39 (with sub-rows) plus the fallback T35 cover every (state, event) pair, and each row is a JVM test ([testing.md L-18](testing.md#5-security-and-lifecycle-tests)).
6. Every state except `Ready` has a way forward that some component raises: a prompt, a retry, a recovery action or a running effect (`Starting`: E1; `NeedsPassword`: the password; `Locked`: the prompt, the credential result or auto-off; `Opening`: E2 or "Try again"; `Maintenance`: its `MaintenanceFinished`; `Corrupt`, `KeyUnavailable`: Restore and Start fresh).

## Threading

| Work | Runs on |
|---|---|
| Composition, `SessionGate.dispatch`, window flags, launcher calls | main (`Dispatchers.Main.immediate`) |
| Room DAO suspend functions and Flows | Room's own executors; never wrapped in `withContext`, never `allowMainThreadQueries` |
| E1, E2, E3, DatabaseHolder open and close, DbMaintenance SQLCipher connection, file copies, SAF and FileProvider streams, Keystore calls | `dispatchers.io` |
| PBKDF2 derivation and calibration (DEC-60), heavy list mapping | `dispatchers.default` |
| DataStore | its own scope, `applicationScope + dispatchers.io` |
| Backup, restore, key change, Start fresh, Erase, every repository write, the editor's save-on-leave | `applicationScope`: survives rotation and leaving the screen |
| Observation, search debounce, one-shot reads started by the UI | `viewModelScope` |

- `applicationScope` has a SupervisorJob and is never cancelled. No handler swallows uncaught exceptions; they crash the app. Detached work uses `runDetached` ([Layering](#layering-and-data-flow), rule 6), which keeps that guarantee: an `async` whose awaiting caller is gone would lose its exception, a `launch` does not.
- Save-on-leave: the editor's save is `applicationScope.launch { val r = noteRepository.save(draft); if (r is AppResult.Err) appMessages.post(…) }` with no catch-all, so a bug still crashes while an expected `StorageFull` or `DatabaseUnavailable` reaches the user through `AppMessages` and the draft stays in the SavedStateHandle until a save succeeds ([ui.md § 6.8](ui.md#68-saving-as-the-user-experiences-it)).
- Rotation safety: long operations publish progress in `DbMaintenance.status: StateFlow` ([db-lifecycle.md § Interface and status](db-lifecycle.md#interface-and-status)), which any screen re-reads after rotation (status is not kept across process death). `MaintenanceStatusHost` (`ui/common`, [ui.md § 8.12](ui.md#812-maintenance-status-host)) renders the restore dialogs and every outcome; it is composed in the shell (inside `MainContent`) and on the Password, Forgot password, Corrupt and KeyUnavailable screens, because a restore resets navigation and an operation can end on a gate screen.
- `NonCancellable` appears only in DatabaseHolder close/reopen and in DbMaintenance commit/rollback ([db-lifecycle.md](db-lifecycle.md)).
- Banned (boundary test): `runBlocking`, `GlobalScope`, creating threads or executors, any `Dispatchers.` reference outside `core.coroutines`.
- The main thread never touches disk, the Keystore, PBKDF2, SQLCipher or a blocking DataStore read. The debug source set installs StrictMode in `DebugTools`: thread policy detecting disk reads and writes and custom slow calls, VM policy detecting leaked closables and unsafe intent launches (API 31+), each with a `penaltyListener` that logs only the violation's class name through `AppLog` (`penaltyLog()` would print the offending intent, including SAF URIs, DEC-70).
- EditorViewModel persists on its entry's ON_STOP, which Navigation 3 dispatches when the entry is popped or covered and when the activity stops (about 700 ms before ProcessLifecycleOwner's ON_STOP, so a lock never pre-empts it). The write runs in `applicationScope` (DEC-37). Verify at setup: entry lifecycles reach ON_STOP on pop, on cover and on activity stop; if entries have no lifecycle of their own, `EditorScreen` saves from `DisposableEffect.onDispose` and `LifecycleEventEffect(Lifecycle.Event.ON_STOP)`.

### MainActivity startup order

Window flags must be in place before the first app frame (DEC-67). The order below fixes only the sequence; the rules are in [security.md § 7.3, § 8 and § 11](security.md#8-screen-protection-dec-66-dec-67), which is the only description of the start-closed mechanism. The flags start closed in every `onCreate` and are relaxed only when `windowPolicy` (closed until the settings and the key mode are known, so it always emits) says so; the splash is held at launch until the first policy has been applied and the gate has left `Starting` (a recreated activity has no splash, and its window starts closed the same way):

```kotlin
class MainActivity : FragmentActivity() {
    private lateinit var container: AppContainer
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()                      // before super.onCreate
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()                                      // before setContent (DEC-13)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) // closed until the policy is known (DEC-67)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        if (Build.VERSION.SDK_INT >= 29) getSystemService(ContentCaptureManager::class.java)?.setContentCaptureEnabled(false)   // every onCreate (security.md § 8)
        container = (application as DoroApplication).container
        var policyApplied = false                               // main thread only
        lifecycleScope.launch { container.windowPolicy.collect { p ->
            applyWindowPolicy(p); policyApplied = true } }      // FLAG_SECURE, recents on API 33+, setHideOverlayWindows on API 31+ (security.md § 8)
        splash.setKeepOnScreenCondition { !policyApplied || container.sessionGate.state.value is GateState.Starting }
        container.externalActivities.bind(this)                 // every result launcher (DEC-90)
        container.appLockController.attach(this)                // one BiometricPrompt per activity instance (DEC-04)
        setContent {
            val policy by container.windowPolicy.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalUiGraph provides container, LocalKeyboardPrivacy provides policy.secure) {   // ui.md § 3.3
                DoroTheme { DoroRoot() }
            }
        }
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS   // after setContent (DEC-71)
    }
    override fun onDestroy() { container.appLockController.detach(this); super.onDestroy() }
}
```

## Time

`AppTime` is the only clock (DEC-20, DEC-26):

```kotlin
interface AppTime {
    fun nowMillis(): Long                     // System.currentTimeMillis(): every *_at value, the 1-hour share sweep, the backup file date
    fun today(): LocalDate                    // LocalDate.now(ZoneId.systemDefault()); zone read on every call
    fun dateOf(epochMillis: Long): LocalDate  // Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    fun elapsedMillis(): Long                 // SystemClock.elapsedRealtime(): monotonic, restarts at boot; the unlock backoff, including its persisted retryAt (security.md § 5.3), the lock exemption (§ 7.7) and the KDF calibration timing (§ 3.3)
    val todayFlow: StateFlow<LocalDate>       // re-evaluated on the triggers below; emits only when the local date differs
}
```

`todayFlow` is refreshed:
1. on ProcessLifecycleOwner ON_START and ON_RESUME;
2. by a timer in `applicationScope`, active from ON_START to ON_STOP, that waits until the next local midnight (recomputed from the current zone each time) and re-arms after every refresh;
3. by `TimeChangeReceiver` for `Intent.ACTION_TIME_CHANGED` ("android.intent.action.TIME_SET"), `ACTION_TIMEZONE_CHANGED` and `ACTION_DATE_CHANGED`, registered once in `installProcessObservers` with `registerReceiver(receiver, filter)` and no `RECEIVER_EXPORTED`/`RECEIVER_NOT_EXPORTED` flag: these protected system broadcasts are exempt from the API 34 flag rule. Doze can delay the timer; triggers 1 and 3 correct it.

Verify at setup: lint accepts the flagless registration for these protected broadcasts.

The production class takes its clocks and its scope from the outside, so `AppTimeTest` drives midnight, DST and zone changes in virtual time ([testing.md I-30](testing.md#4-invariant-catalog)):

```kotlin
// core.time. The only file besides TimeChangeReceiver that may read the system clocks (boundary test)
class DefaultAppTime(
    private val instant: () -> Instant,        // production: { Instant.now() }
    private val zone: () -> ZoneId,            // production: { ZoneId.systemDefault() }, read on every call
    private val scope: CoroutineScope,         // hosts the midnight timer: applicationScope in production, the test scope in tests
) : AppTime {
    fun onStart()          // ProcessLifecycleOwner ON_START: refresh, arm the timer
    fun onStop()           // ON_STOP: cancel the timer
    fun onResume()         // ON_RESUME: refresh
    fun onTimeChanged()    // TimeChangeReceiver: refresh and re-arm the timer
}
```

`nowMillis()` is `instant().toEpochMilli()` and `elapsedMillis()` is `SystemClock.elapsedRealtime()`. A refresh recomputes `today()` and emits only when the `LocalDate` differs from the current value of `todayFlow`, so a zone change that leaves the local date unchanged emits nothing. `AppContainer.installProcessObservers()` forwards the lifecycle events of `processLifecycle` to the four entry points; `TimeChangeReceiver.onReceive` calls `onTimeChanged()`.

Rules:
- Outside `core.time`, nothing calls `System.currentTimeMillis`, `System.nanoTime`, `SystemClock`, `Instant.now`, `LocalDate.now`, `LocalDateTime.now`, `ZonedDateTime.now`, `Clock.system*`, `ZoneId.systemDefault`, `TimeZone.getDefault`, `Calendar.getInstance` or `Date()` (boundary test). Tests use `FakeAppTime` with settable wall clock, zone and elapsed time ([testing.md § 7](testing.md#7-fixtures-and-fakes)).
- Repositories read `nowMillis()` once per write transaction and pass it down (DEC-20). `SessionGate.dispatch` reads `elapsedMillis()` once per event and passes it to `reduce`.
- `date` is a zone-free epoch day and never passes through a zone; Material 3 picker millis convert with `ZoneOffset.UTC` (DEC-26).
- Overdue (DEC-26) is evaluated in ViewModels by combining rows with `todayFlow`, never in SQL with a frozen "now", so it changes at midnight without a new query.

## Cross-cutting conventions

- UI text, plurals, accessibility semantics, theming and insets: [ui.md](ui.md) (DEC-55, DEC-56).
- Logging: only `AppLog` (`core.log`) logs, with ids, counts and exception class names. `toString()` redaction for entities, UI models, key holders (`KeyState`, `KeySlot`, `PreparedKey`), gate states and events, external requests and results, messages and drafts, the R8 log rule and the SQLCipher logger: [security.md § 10](security.md#10-logging-and-leak-hygiene-dec-70) (DEC-70). `GateEvent.PasswordSubmitted.toString()` is `"PasswordSubmitted(<redacted>)"`.
- Screens collect state with `collectAsStateWithLifecycle()`; the gate state in DoroRoot is the one exception ([Rendering](#rendering)).
- API-level pitfalls (compileSdk 37 against minSdk 28):
  - `MutableList.removeFirst()` and `removeLast()` bind to Java 21 methods that do not exist on API ≤ 34 (`NoSuchMethodError`). Use `removeAt(0)`, `removeAt(lastIndex)`, `removeFirstOrNull()` or `removeLastOrNull()`. The other Java 21 SequencedCollection members (`addFirst`, `addLast`, `getFirst`, `getLast`, `reversed`) carry the same risk. Verify at setup which of them Kotlin 2.4 hides; lint `NewApi` (error) catches the rest, and the boundary test additionally bans `.removeFirst()` and `.removeLast()`.
  - `java.time` is native from API 26, but `LocalDate.ofInstant`, `LocalDate.EPOCH`, `datesUntil` and `toEpochSecond` are API 34: use `Instant.atZone(zone).toLocalDate()` and `LocalDate.ofEpochDay`.
  - Never cache a `Clock`, a `ZoneId` or a formatter that carries a zone. Never put a password in `rememberSaveable` or a SavedStateHandle (DEC-64).
  - Case folding uses `Locale.ROOT` (DEC-29, [data-layer.md § Search](data-layer.md#search)).

## Performance budgets

Budgets apply to a release build on the low-end reference device (Physical B in [testing.md § 2](testing.md#2-device-matrix)), Plain mode, lock off, with the 10,000-note fixture (DEC-10, §10). Measurement mechanics (warm-ups, statistics, JSON output, regression gate) are defined once, in [testing.md § 8](testing.md#8-performance-tests); the table names the measurement.

| Path | Budget | Measured by |
|---|---|---|
| Cold start to first list with data | ≤ 1,500 ms median; Device mode ≤ 1,700 ms. Password mode to the password screen is recorded without a budget | `adb shell am start -S -W`, then the "Fully drawn" time that `ReportDrawnWhen { !uiState.loading }` in the All list reports (read from `adb logcat -d -s ActivityTaskManager`); P-5 |
| Perspective switch | ≤ 250 ms from tab tap to the first frame that shows the target tab's rows | instrumented Compose test (P-2); `android.os.Trace` section `doro:switch` for Perfetto |
| List query at 10,000 notes | first emission ≤ 100 ms for each case of P-1 | instrumented DAO timing test on the fixture (P-1) |
| Editor open | ≤ 300 ms from row tap to editable text; ≤ 600 ms for a 200,000-character note (DEC-24) | instrumented Compose test (P-6); trace section `doro:editor` |
| Batch action on 1,000 notes | ≤ 1,000 ms per associate, set-date or delete (one transaction, DEC-36) | instrumented repository test (P-7) |

- Regression gate on emulators and sign-off on Physical B: testing.md § 8 (nightly on `api28`, a median above budget × 3 fails; on Physical B the exact budget applies). Trace section names never contain user data (DEC-70).
- Levers already in the design: the preview projection without Paging (DEC-10, DEC-24); `WhileSubscribed(5_000)` keeps a tab's query warm across quick switches, and each tab's StateFlow shows its last list on the first frame; search input is debounced before it changes the NoteQuery (value: [ui.md § 3.3](ui.md#33-search-bar)); LazyColumn items are keyed by note id and use immutable row models.
- The open sequence reads every page once (`quick_check`, [db-lifecycle.md § Open sequence](db-lifecycle.md#open-sequence)); its cost is part of the cold-start budget.
- No app-specific Baseline Profile in v1: generating one needs a separate module, which DEC-03 excludes. Library profiles still install through profileinstaller.
- A missed query budget is fixed in the query, its indices or the connection `cache_size`, never with Paging or SQL built at runtime ([data-layer.md § Size and performance](data-layer.md#size-and-performance)).

## Top risks

| Risk | Mitigation |
|---|---|
| A race shows note content while locked, or a lock drops an activity result | Pure reducer tested row by row; dispatch on the main thread; content gated in one place (DoroRoot); launchers at activity level with an inbox |
| A query hits a closed Room during a swap (Room 2.8.5 throws `IllegalStateException` after close) | Only DatabaseHolder holds Room; flows go through `observe`; exclusive mode drains first ([db-lifecycle.md § Exclusive mode](db-lifecycle.md#exclusive-mode)); swap-under-load test |
| A maintenance pipeline bug or crash loses the live database | One pipeline, one commit point (the key-state rewrite), the old generation deleted only after the new one opens; failure and crash injection L-14 and L-15 ([testing.md § 5](testing.md#5-security-and-lifecycle-tests); [db-lifecycle.md § Crash-safety matrix](db-lifecycle.md#crash-safety-matrix)) |
| Save-on-leave fails (disk full) after the editor is gone | The error is posted to `AppMessages`; the draft stays in the SavedStateHandle until a save succeeds; `runDetached` and the save-on-leave rule keep bugs crashing instead of vanishing |
| Navigation 3 API churn (decorators, results, serialization) | All Navigation 3 code in `ui/navigation`; the verify-at-setup items above; back-behavior tests |
| Layering erosion by agents in a single module | Boundary and banned-token test fails the build; UiGraph and GateGraph narrow what UI code can reach |
| Toolchain friction (built-in Kotlin, KSP pins, compileSdk 37) | One version catalog, KSP 2.3.13 excluded, CI builds from a clean checkout |
| The single static list query is too slow at 10,000 notes | Budget tests; first a larger `cache_size`, then per-perspective static queries ([data-layer.md § Size and performance](data-layer.md#size-and-performance), which owns the fallback list) |
| A corrupt preferences file silently turns off the biometric lock and screenshot protection and resets the unlock backoff (all keys fall back to their defaults) | Accepted for v1: the keys are written only by the app and DataStore replaces files atomically. A fail-closed handler (security flags on, a notice shown) needs a new DEC in decisions.md first |
| API 28–29 users whose strong biometrics are gone cannot unlock | The `CredentialConfirm` result is delivered to AppLockController in any gate state ([Result launchers](#result-launchers-and-process-death)); tested on the API 28 and 29 emulators ([security.md § 7.6](security.md#76-device-credential-fallback-q7)) |
| Publishing on Google Play would require targetSdk 36 (not planned: the app is installed directly, DEC-01) | Edge-to-edge, predictive back and no orientation locks from day one, so the change is one line (DEC-01) |
