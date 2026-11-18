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
- Everything that exists only for tests is defined once, in the Gradle sketch of [testing.md § 9](testing.md#9-ci-pipeline): `testInstrumentationRunner` (`com.doro.organizer.testing.DoroTestRunner`), `testBuildType`, the `qa` and `benchmark` build types and their source sets, `targetSdkOverride` (used only by the optional `target36-canary` job), the Orchestrator, the optional Gradle Managed Devices and the `src/sharedTest` source set. The excerpt below shows the production configuration, the debug `applicationIdSuffix` and the `doro.srcRoot` property only.
- **Command line only (DEC-15).** The project is built and tested with the Gradle wrapper and `adb`; Android Studio and an emulator are never required, and nothing in the build may depend on an IDE-generated file. The required commands are `./gradlew :app:assembleDebug`, `./gradlew :app:testDebugUnitTest` (JVM), `./gradlew :app:connectedDebugAndroidTest` (instrumented, on the owner's Poco F7 connected over USB) and `./gradlew :app:lintRelease`. Gradle Managed Devices and emulator jobs may be configured in testing.md § 9, but they are optional and never a required gate.
- **Debug application id (DEC-15).** The `debug` build type sets `applicationIdSuffix = ".debug"`, so the debug app (`com.doro.organizer.debug`) is a separate installation with its own data directory, `noBackupFilesDir`, DataStore and AndroidKeyStore aliases. Instrumented tests, which clear the app's data and delete its Keystore entries, therefore never touch the owner's notes in the release app on the same phone. The `namespace` (and so the package of every class, `R` and the test runner) stays `com.doro.organizer`; only the installed id changes. Anything that names the application id uses the `${applicationId}` placeholder, never a literal (the FileProvider authority is `${applicationId}.fileprovider`, [security.md § 9.1](security.md#91-manifest)). The `qa` and `benchmark` build types of testing.md § 9 also run instrumented tests on the same phone, so for the same reason each sets its own suffix (`applicationIdSuffix = ".qa"` and `".benchmark"`); they are created with `initWith(release)`, which does not carry the debug suffix. `release` never has a suffix.
- **API 28–29-only paths (DEC-15).** Code that runs only on API 28–29 (the `createConfirmDeviceCredentialIntent` credential path, auth-per-use key validity −1, `fullBackupContent`, the missing Recents screenshot API) is never run on a device: it is covered by lint `NewApi` (an error), JVM tests of the branching logic and review. To make the branching testable on the JVM, each such decision is a pure function that takes the API level as a parameter (for example the window policy applier and the credential-route choice); only a thin caller reads `Build.VERSION.SDK_INT`. This is an accepted risk.

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
        applicationId = "com.doro.organizer"   // DEC-03 (Q12)
        minSdk = 28; targetSdk = 35           // DEC-01 (testing.md § 9 reads a targetSdkOverride property for the canary job only)
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }   // DEC-15: tests never touch the release app's data or Keystore keys
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
- The 16 KB page check is static (DEC-14, DEC-15): CI runs `zipalign -c -P 16 -v 4` on the release APK, and [testing.md § 9](testing.md#9-ci-pipeline) adds the ELF check (`readelf -lW` on every `lib/*/*.so`, each `LOAD` segment aligned to `0x4000`). A run on a 16 KB page-size emulator image is optional and never a required gate.
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
│                     DeleteImpact, EntityDeleteResult, DraftInsert, KeyMode, WindowPolicy, UnlockBackoff, PasswordReminder,
│                     PasswordReason, UnlockRoute
├── settings/         SettingsStore, Settings (Preferences DataStore)
├── security/         KeyVault and its key-state types (security.md § 3.6), AppLockController, BiometricGateway,
│                     LockListener, LockError, LockAvailability, SlotState, BiometricAvailability, AuthPurpose,
│                     ToggleOutcome, UnlockPolicy, LockScreen, SlotCipher, SlotOffer, PromptRequest, PromptResult,
│                     BiometricSlotWriter (§ SessionGate)
├── data/             DatabaseHolder, CorruptionListener, FlowProblemListener, SqlcipherEngine, DbFiles, NoteRepository, EntityRepository
│   ├── db/           Room database class, entities, DAOs, NoteRowRaw, migrations (data-layer.md)
│   └── maintenance/  DbMaintenance, MaintenanceListener, status and result types (db-lifecycle.md)
├── session/          SessionGate, GateState, PasswordEntry, GateEvent, GateEffect, reduce(), lockScreenOf()
└── ui/
    ├── UiGraph, GateGraph, DoroRoot   what the UI may reach; gate switch
    ├── navigation/   keys, PickerPurpose, NavigationState, Navigator, ResultEventBus, entry provider
    ├── theme/        Material 3 theme (DEC-56)
    ├── common/       NoteRowItem, EntityPicker, MonthGrid, AppError to string
    ├── gate/         gate screens (ui.md § 10), ForgotPasswordViewModel (the one gate ViewModel, § SessionGate invariant 2)
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
| `session` | `core`, `model`, `settings`, `security` (`KeyVault` with its key-state, `UnlockResult` and `SlotCipher` types, `AppLockController`, `LockListener`, `LockError`, `LockScreen`; `UnlockRoute` and `PasswordReason` come from `model`), `data` (`DatabaseHolder`, `OpenResult`, `OpenFailureKind`, `CorruptionListener`, `SqlcipherEngine`), `data.maintenance` (all public types) | |
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
    data class InvalidInput(val rule: InputRule) : AppError           // password policy (security.md § 6.1); BlankName also from NoteRepository.save and EntityRepository for a blank new person or project name (data-layer.md INV-9)
    data class InvalidBackup(val reason: BackupRejection) : AppError  // db-lifecycle.md § Restore validation
    data object StorageFull : AppError              // SQLiteFullException, ENOSPC, EDQUOT, failed free-space check
    data object FileAccess : AppError               // SAF or FileProvider I/O error, null stream, revoked grant
    data object KeyUnavailable : AppError           // Keystore failure while obtaining or changing a key (DEC-86)
    data object AuthFailed : AppError               // no successful authentication when toggling the lock, creating the biometric slot or authorizing with it (fingerprint reset, restore key), or the slot proved unusable there (DEC-63, DEC-65); also a slot creation whose write did not land
    data object Busy : AppError                     // DbMaintenance already runs an operation
    data object DatabaseUnavailable : AppError      // holder closed or replaced, or a transient I/O failure; the caller keeps its input
    data object OutcomeUnknown : AppError           // DbMaintenance only (AppLockController passes on the one from a slot creation): the commit could not be read back. A generation commit restarts and startup decides (db-lifecycle.md § Generation commit); a slot-only commit restarts nothing (db-lifecycle.md § Biometric slot commits)
    data object Unexpected : AppError               // DbMaintenance only, after rollback
}
enum class InputRule { BlankName, PasswordMalformed, PasswordTooShort, PasswordMismatch, AcknowledgementMissing }   // BlankName: data-layer.md § Note save; the four password rules in the order of security.md § 6.1
// BackupRejection { NotABackup, NewerVersion, Incompatible, Damaged }: declared in core.result, values and producers in db-lifecycle.md § Restore validation
```

- Every public suspend method of `NoteRepository`, `EntityRepository`, `DbMaintenance` and the `AppLockController` toggles and slot methods (`confirm`, `confirmWithPassword`, `createSlot`, `slotPrompt`) returns `AppResult<T>`, where `T` is the type the owning doc names for it (data-layer.md, db-lifecycle.md, security.md § 7.3). Flows are plain `Flow<T>`; their failures are handled inside `observe` ([db-lifecycle.md § Observation](db-lifecycle.md#observation)).
- `KeyVault` is the exception: only `session` (E2: `readState`, `unlock`, `unlockWithBiometric`, `deleteBiometricKey`; E4: `verifyPassword`), `security` (`AppLockController`: `biometricSlotStatus`, `isPasswordReminderDue`, `readState`, `verifyPassword`, `biometricUnlockCipher`, `verifyBiometric`, `biometricArmCipher`) and `data.maintenance` call it, and its surface ([security.md § 3.6](security.md#36-keyvault-surface)) returns `UnlockResult` from `unlock`, `openKey`, `verifyPassword`, `unlockWithBiometric` and `verifyBiometric`, `SlotCipherResult` from the two cipher preparations and `WrapResult` from `wrapForBiometric`, and throws `KeyVaultException(KeyFailure)` for every other failure. `DbMaintenance` and `AppLockController.confirmWithPassword` map them to `AppError` (`PasswordRejected` to `WrongPassword`, `Throttled` to `Throttled`, `KeyFailed` and every `KeyVaultException` to `KeyUnavailable`); E2 and E4 map them to gate events ([States, events and effects](#states-events-and-effects)). `KeyVault` also catches the `IOException` that `SettingsStore.edit` throws on a full disk: the success-path write (backoff reset, `password_verified_at`) is best effort, and a failing counting write returns `Throttled` computed in memory ([security.md § 3.6](security.md#36-keyvault-surface)); no `SettingsStore` exception leaves `KeyVault`.
- Rows that vanished are success values, not errors: `SaveResult.NoteGone`, `loadNote` returning null, `EntityDeleteResult(deleted = false)` ([data-layer.md](data-layer.md)). There is no `NotFound` error.
- `InvalidInput`: the password rules are checked by `DbMaintenance` before it enters `Maintenance` ([db-lifecycle.md § Operation frame](db-lifecycle.md#operation-frame), step 4). A blank new person or project name is prevented by the UI; if one still reaches a repository, `NoteRepository.save` and `EntityRepository` return `Err(InvalidInput(InputRule.BlankName))` before any write, while the other name violations (a name longer than `MAX_NAME_CHARS`) stay `IllegalArgumentException` ([data-layer.md § Note save](data-layer.md#note-save), INV-9). The acknowledgement case exists so that `AppError.messageRes()` stays total ([ui.md § 11.13](ui.md#1113-errors)).
- Repositories, KeyVault and DbMaintenance turn expected exceptions into AppError at their boundary. SQLite and SQLCipher classification: [db-lifecycle.md § Failure classification](db-lifecycle.md#failure-classification); Keystore and crypto classification: [security.md § 5.2](security.md#52-error-classes). `CancellationException` is always rethrown.
- A runtime `SQLiteDatabaseCorruptException` makes DatabaseHolder call `CorruptionListener.onRuntimeCorruption()`, which dispatches `CorruptAtRuntime` to SessionGate; the failed call returns `DatabaseUnavailable` and the gate replaces the UI (DEC-85).
- Any other exception is a bug and crashes the app, except inside DbMaintenance, which first restores the previous database ([db-lifecycle.md](db-lifecycle.md)) and returns `Unexpected`. DbMaintenance may also return `DatabaseUnavailable` (a transient failure, or a source found corrupt) and `OutcomeUnknown` (a `Throwable` after the commit started, or a commit that cannot be read back: nothing is deleted and the outcome is `Restart`, so its message must not claim that nothing changed; [ui.md § 9.6](ui.md#96-outcome-messages)). The slot-only commit of `createBiometricSlot` is the exception: its `OutcomeUnknown` restarts nothing, the database stays open, and ui.md § 8.13 shows `slot_outcome_unknown` ([db-lifecycle.md § Biometric slot commits](db-lifecycle.md#biometric-slot-commits)).
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
| `appMessages: AppMessages` | application-scoped queue of `UserMessage`s shown by the shell (`pending`, `post`, `consume`) plus the in-memory fingerprint-reset marker (`markFingerprintReset()`, `consumeFingerprintReset(): Boolean`, `clearFingerprintReset()`) ([ui.md § 2.4](ui.md#24-snackbars-and-notices)) |
| `externalActivities` | takes an `ExternalCallbacks` provider ([Result launchers](#result-launchers-and-process-death)) |
| `biometricGateway: BiometricGateway` | the platform adapter beneath `AppLockController` ([security.md § 7.3](security.md#73-applockcontroller-and-mainactivity)): availability, the prompt (with a `CryptoObject` for the biometric slot, [AppLockController and BiometricGateway](#applockcontroller-and-biometricgateway)), the credential intent and the enrollment intent. Overridable; tests bind `FakeBiometricGateway`, which reports `cryptoMatches = true` unless a test scripts otherwise |
| `appLockController` | takes `biometricGateway`, `externalActivities`, `settingsStore`, `keyVault` (route, slot status and ciphers, `verifyPassword`, `verifyBiometric`, reminder), `appTime`, `processLifecycle`, a `() -> LockListener` provider (SessionGate) and a `() -> BiometricSlotWriter` provider (DbMaintenance) ([Event wiring](#event-wiring)); E2 calls `reportKekBroken()` on it |
| `sessionGate` | `DefaultSessionGate`; implements `LockListener`, `CorruptionListener` and `MaintenanceListener` ([Event wiring](#event-wiring)) |
| `windowPolicy: StateFlow<WindowPolicy>` | below |

`windowPolicy` is the single source of the window flags; it carries nothing for the keyboard (below). It combines the settings, the committed key mode and the gate state. While the settings have not been read or the key mode is unknown (null before startup has read it, or while the key-state is invalid) it is the **closed** policy: `FLAG_SECURE` on, recents screenshot off, overlays hidden. A closed policy is still emitted, so the splash hold of [MainActivity startup order](#mainactivity-startup-order) is released (DEC-66, DEC-67; rules: [security.md § 8](security.md#8-screen-protection-dec-66-dec-67)):

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

`MainActivity` collects it to apply the window flags; composables never read it.

Keyboard privacy is not part of the window policy and follows no setting (DEC-71, Q11). Every note, person-name, project-name and search field (perspective search, `EntityPicker`) always asks the keyboard for no personalized learning (`IME_FLAG_NO_PERSONALIZED_LEARNING`), whatever screenshot protection, the key mode or the gate state says. The fields wear a fixed wrapper from `ui/common` that takes no input, so there is no `CompositionLocal` for it, nothing to provide in `MainActivity` and nothing to recompose when a setting changes. The wrapper and the list of covered fields are owned by [security.md § 11](security.md#11-keyboard-and-autofill-dec-71-q11), the fields themselves by [ui.md](ui.md). The autofill exclusion of DEC-71 stays in `MainActivity` ([MainActivity startup order](#mainactivity-startup-order)).

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

`SettingsStore` is the only DataStore user. It exposes `settings: StateFlow<Settings?>` (null until the first read), `suspend fun snapshot(): Settings` (reads DataStore directly, so it is current right after an edit), one setter per toggle, `setLockAutoOff()` (one edit: `security.app_lock = false` and `security.lock_auto_off_notice = true`, the auto-off of [security.md § 7.6](security.md#76-device-credential-fallback-q7)), `setBackoff(failures, retryAtElapsedMs)`, `setPasswordVerifiedAt(epochMillis)` (written only by `KeyVault`, DEC-74), `setRemembered(personId, projectId)`, the draft-insert record below, `resetSecurity()` and `onDataReplaced()`. Values are never user text, secrets or URIs.

| Property | DataStore key | Type | Default | Owner of the semantics |
|---|---|---|---|---|
| `screenshotProtection` | `security.screenshot_protection` | Boolean | false | [security.md § 2](security.md#2-security-settings) |
| `biometricLock` | `security.app_lock` | Boolean | false | security.md § 2 |
| `encryptBackups` | `security.encrypt_backups` | Boolean | false | security.md § 2 |
| `unlockFailures` | `security.unlock_failures` | Int | 0 | security.md § 2, § 5.3 |
| `unlockRetryAtElapsedMs` | `security.unlock_retry_at` | Long, `AppTime.elapsedMillis()` base | 0 | security.md § 2, § 5.3 |
| `lockAutoOffNotice` | `security.lock_auto_off_notice` | Boolean | false | security.md § 2, § 7.6 |
| `passwordVerifiedAt` | `security.password_verified_at` | Long, `AppTime.nowMillis()` base | 0 (the reminder is due) | security.md § 2, § 5.6 (DEC-74) |
| `rememberedPersonId` | `nav.remembered_person_id` | Long? | null | DEC-28 |
| `rememberedProjectId` | `nav.remembered_project_id` | Long? | null | DEC-28 |
| `dataEpoch` | `nav.data_epoch` | Int | 0 | [Data reset](#data-reset) |

- `resetSecurity()` sets the seven `security.*` keys to their defaults and clears both remembered ids (Erase and the first-run reset, [security.md § 5.4](security.md#54-forgotten-password-erase-dec-63)). `onDataReplaced()` clears both remembered ids and the draft-insert records and increments `dataEpoch` in one edit.
- Draft-insert record, used only by `EditorViewModel` to survive a process death that falls between the database commit of a first insert and the saved state of the draft ([data-layer.md § Note save](data-layer.md#note-save), caller rules; lifecycle in [ui.md § 6.8](ui.md#68-saving-as-the-user-experiences-it)). `data class DraftInsert(val noteId: Long, val createdPersons: Map<String, Long>, val createdProjects: Map<String, Long>)` in `model` (`toString()` prints `DraftInsert(noteId=…)`; the map keys are draft-local UUIDs, never names). Members: `suspend fun putDraftInsert(draftKey: String, record: DraftInsert)`, `suspend fun draftInsert(draftKey: String): DraftInsert?`, `suspend fun dropDraftInsert(draftKey: String)`. Storage: one string preference `nav.draft_inserts` that holds at most 8 records, newest last; `putDraftInsert` appends and drops the oldest beyond 8, so records of abandoned drafts cannot accumulate. It is not a member of `Settings`, `resetSecurity()` leaves it alone, and the store returns only after the DataStore edit has completed, because the caller updates its `SavedStateHandle` after that.
- A corrupt preferences file is replaced with defaults (`ReplaceFileCorruptionHandler { emptyPreferences() }`). That fails open for the biometric lock, screenshot protection and the backoff counters; see [Top risks](#top-risks). In `PASSWORD` mode the lock reads as off, so the next cold start asks for the password anyway, and after it E5's `reconcileLock()` removes the now-stale biometric slot (DEC-09). It fails closed for the password reminder: `passwordVerifiedAt` falls back to 0, so once the lock is on again the next unlock asks for the password (DEC-74).

### Lifetimes

| Scope | Objects | Rebuilt or reset when |
|---|---|---|
| Open database | Room instance, DAOs, open-helper factory, raw key bytes; held only by DatabaseHolder | every open: Opening, and each reopen by DbMaintenance (backup, restore, key change) |
| Activity | BiometricPrompt inside AppLockController, the result launchers inside ExternalActivities, the window-policy collector | every `MainActivity.onCreate` (`attach`/`bind`); dropped in `onDestroy` |
| One slot authorization | the `SlotCipher` of one successful fingerprint prompt (an authorized cipher, no key bytes) | from the prompt callback until its one use: E2 (`unlockWithBiometric`), `KeyVault.verifyBiometric` in AppLockController, `DbMaintenance.createBiometricSlot` (ENCRYPT), `resetPasswordWithBiometric` or `answerBiometric` (DECRYPT); `dispatch` drops an unused one (T35) |
| NavEntry | one NoteListViewModel per tab, EditorViewModel, SettingsViewModel, picker ViewModels, each with a SavedStateHandle | the entry is popped |
| Activity `ViewModelStore` | `ForgotPasswordViewModel` (`ui/gate`), the only ViewModel of a gate screen (DEC-90; [SessionGate](#sessiongate) invariant 2): no repository, no note-derived data, no password property and no `SavedStateHandle` | survives rotation, so the fingerprint reset's prompt and call continue ([ui.md § 8.15](ui.md#815-set-a-new-password-with-the-fingerprint-dec-63)); cleared when the activity finishes; lost with the process |
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
fun forgotPasswordFactory(graph: GateGraph) = viewModelFactory {
    initializer { ForgotPasswordViewModel(graph.appLockController, graph.dbMaintenance, graph.appMessages) }   // GateGraph only: no repository, no SettingsStore
}
// in GateScreen, outside NavDisplay, so the owner is the activity: val vm: ForgotPasswordViewModel = viewModel(factory = forgotPasswordFactory(graph))
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

Verify at setup: (a) whether `rememberNavBackStack` on Android needs a `SavedStateConfiguration` whose SerializersModule registers the four key classes and the `PickerPurpose` cases; (b) that `createSavedStateHandle()` works in NavEntry-scoped ViewModels with this decorator order; (c) whether navigation3 1.2.1 ships a library result API equivalent to the recipe; if it does, use it and keep the names `sendResult` and `ResultEffect`; (d) that `MainContent` leaving and re-entering composition (Ready → Locked → Ready) keeps every entry's ViewModel instance and `rememberSaveable` state (testing.md L-21); if the decorators clear them when `NavDisplay` leaves composition, create the per-stack SaveableStateHolder and ViewModelStore decorators in `DoroRoot`, above the gate branch, and pass them into `MainContent`; (e) that an enabled `BackHandler` inside an entry consumes Back before `NavDisplay` pops, with gesture and three-button navigation on the owner's phone (API 28 is not run on a device, DEC-15); if not, use `NavigationBackHandler` from navigationevent-compose.

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

Effects run in `applicationScope` and report back through `dispatch` on the main thread. `reduce` is pure: the only clock value it needs is the `elapsedNow` that `dispatch` passes in, on the same base as `NeedsPassword.retryAt` and `PasswordEntry.retryAt` (guards of T7 and T47). No gate decision uses the wall clock. The 14-day password reminder (DEC-74), which does, is decided outside the reducer by `KeyVault` and `AppLockController` and reaches the reducer only as the `reason` of an event ([Unlock routes](#unlock-routes-q8)).

### Unlock routes (Q8)

With the biometric lock on and a usable `BIOMETRIC_STRONG` biometric, the fingerprint alone unlocks, at cold start and on return from background, in every key mode (DEC-65). In `PASSWORD` mode this goes through the biometric slot, a second wrap of the DEK under a Keystore key that needs a strong biometric for every use (DEC-60). [security.md § 7](security.md#7-biometric-app-lock-dec-65) owns the rules that choose a route for a given mode, slot, biometric availability and reminder; this section defines how the gate carries each route out. The route is resolved by `AppLockController` (`unlockRoute()`, [AppLockController and BiometricGateway](#applockcontroller-and-biometricgateway)), never by the reducer.

| Route (`UnlockRoute`) | When (rules: security.md § 7) | What unlocks | Without a usable strong biometric |
|---|---|---|---|
| `Biometric` | `PLAIN` and `DEVICE` with the lock on; any mode while the lock screen is `PromptWithoutKey`: locked over `KeyUnavailable`, over a `Maintenance` from `KeyUnavailable`, or over an Erase (no key-state can check a key there, so no slot or password check can run; `UnlockPolicy.routeOverKeyUnavailable`) | `strongPrompt` without a `CryptoObject` | the device credential (Q7); with no secure lock screen the lock turns itself off (`AutoOff`, security.md § 7.6) |
| `Slot` | `PASSWORD`, lock on, usable strong biometric, `KeyVault.biometricSlotStatus() == Live` (file plus `containsAlias`; no Keystore operation), reminder not due (after a KEK failure with a live slot the reminder is ignored for the rest of the process, DEC-86; the same rule decides whether `slotPrompt(RestoreKey)` prompts for the key of a Restore from `Corrupt`). A key that is present but no longer usable is found when the prompt's cipher is prepared and turns the route into `Password(…)` at prompt time (T45, T46), see [Prompt handling](#applockcontroller-and-biometricgateway) | `strongPrompt` with a `CryptoObject` that carries an unwrap cipher on the slot key; negative button "Use password" | the startup password; never the device credential |
| `Password(reason, fingerprintUsable)` | `PASSWORD` in every other case: lock off, no usable strong biometric or no secure lock screen, no slot in the key-state (`None`), a slot whose key is gone (`Dead`: reason `FingerprintChanged`), a slot found invalidated or unavailable when the prompt is prepared, reminder due (DEC-74), or the user chose "Use password". The cause-to-route mapping (`PasswordCause` → `reason`, `fingerprintUsable`) is security.md § 5.1's; after a KEK failure with a live slot (DEC-86) a route without a usable strong biometric carries the reason `KeyStoreFailed` | the startup password | — |

How the gate runs each case:

| Situation | Database | Gate path |
|---|---|---|
| Cold start, `PASSWORD`, route `Slot` | closed | `Locked(Open)` (T40) → prompt → `SlotUnlocked(cipher)` → `Opening(viaSlot)` with E2(cipher) (T42) → `Ready` (T16) |
| Cold start, `PASSWORD`, route `Password(reason)` | closed | `NeedsPassword(reason)` (T2) → `PasswordSubmitted` → `Opening(viaPassword)` with E2(password) (T7) → `Ready` (T16) |
| Cold start, `PLAIN` or `DEVICE`, lock on | closed | `Locked(Open)` (T3) → `Unlocked` → `Opening()` with E2() (T9) |
| "Use password" (prompt negative button or lock-screen button), or the slot found unusable when the prompt is prepared, before the database opened | closed | `PasswordRequired` → `NeedsPassword(reason, fingerprintUsable)` (T45) |
| The slot fails inside E2 | closed | invalidated, or the released key is rejected by the database file (`OpenResult.Failed(Corrupt, keyRejected = true)`): `PasswordNeeded(FingerprintChanged)` → `NeedsPassword` (T22); any other slot failure: `SlotUnlockFailed` → `Locked(Open)` with an error, "Unlock" prepares a new cipher (T44) |
| "Use fingerprint" on the password screen | closed | `UseFingerprintChosen` → `Locked(Open)` (T51) |
| Return from background, `PASSWORD`, route `Slot` | open | `Locked(Ready)` (T25) → prompt → `AppLockController` checks the cipher with `KeyVault.verifyBiometric` → `Unlocked` → `Ready` (T10) |
| Return from background, `PASSWORD`, route `Password(reason)`: no usable fingerprint, slot invalidated or missing, reminder due, or "Use password" | open | `Locked(Ready)` (T25) → `PasswordRequired` → `Locked(Ready, password)` (T46) → `PasswordSubmitted` → E4 checks the password without reopening (T47) → `PasswordVerified` → `Ready` (T48) |
| Return from background, `PLAIN` or `DEVICE` | open | `Locked(Ready)` (T25) → `Unlocked` → `Ready` (T10) |
| "Use fingerprint" on the password screen with the database open | open | `UseFingerprintChosen` → `Locked(Ready)` without the password entry, the prompt is armed again (T52) |
| Cold start, `PASSWORD`, password path, the Keystore (KEK) layer fails while the biometric slot is live (DEC-86) | closed | E2 → `AppLockController.reportPasswordKekFailure()` returns true → `KekFailedWithSlot` → `Locked(Open)` (T59) → the slot prompt, also when the reminder is due → `SlotUnlocked` → `Opening(viaSlot)` (T42) → `Ready` (T16) with the kekBroken notice; never `KeyUnavailable` |
| Return from background, `PASSWORD`, password check, KEK failure while the biometric slot is live (DEC-86) | open | E4 → `reportPasswordKekFailure()` returns true → `KekFailedWithSlot` → the same `Locked` without the password entry (T60) → the slot prompt, also when the reminder is due → `Unlocked` (T10) → `Ready` with the kekBroken notice |
| Either KEK row above when no strong biometric is usable at prompt time (the slot still `Live`, DEC-86) | closed / open | `PasswordRequired(KeyStoreFailed, fingerprintUsable = false)` → `NeedsPassword` (T45) or the password entry of `Locked` (T46), whose reason line explains the key store failure; never `KeyUnavailable` |
| Fingerprint password reset from the cold-start password screen (DEC-63) | closed | `MaintenanceStarted(KeyChange, fingerprintReset)` → `Maintenance(KeyChange, NeedsPassword, resetFrom)` (T41, progress screen) → `OpenWithNewKey` → `Opening(viaReset)` with E2(reset key) (T55) → `Ready` with E5 (T16); failure → `Aborted` → the same password screen (T56) |
| Fingerprint password reset from the password screen of `Locked(Ready)` (DEC-63) | open | `MaintenanceStarted(KeyChange, fingerprintReset)` → `Locked(Maintenance(KeyChange, Ready, resetFrom))` (T12, progress screen, no prompt) → `Reopened` → `Ready` with E5 (T57); failure → `Aborted` → `Locked(Ready)` with the saved password entry (T58) |

- **How the key reaches `Opening` (DEC-64).** The fingerprint prompt never yields key bytes. `AppLockController` receives a `SlotCipher` from `KeyVault.biometricUnlockCipher()` (`SlotCipherResult.Ready`), passes it to `BiometricGateway.authenticate` in `PromptRequest.crypto` (the gateway builds the `CryptoObject`), checks `PromptResult.Succeeded.cryptoMatches` on success and passes on that same `SlotCipher`; it never constructs one (`SlotCipher` has an internal constructor; package `security`, owned by [security.md § 3.6](security.md#36-keyvault-surface)). A `SlotCipher` authorizes exactly one decryption and holds no key material. `SlotUnlocked(cipher)` carries it to the reducer, which hands it to E2 (T42); E2 runs in `Opening` and calls `KeyVault.unlockWithBiometric(cipher)`, which performs the single authorized `doFinal`, builds the 67-byte raw key, zeroes the 32-byte DEK and returns `UnlockResult.Key(rawKey)`. From there the path is the password path: `DatabaseHolder.open(generation, rawKey)` takes ownership of the raw key. So no gate state or event ever holds a key or a DEK, the cipher lives from the prompt callback to the dispatch that starts E2 and is then owned by E2, and the Room instance is still first built in `Opening` (invariant 1).
- **Return from background in `PASSWORD` mode** also uses the `CryptoObject` prompt, not a plain one: an enrollment change invalidates the slot key (`setInvalidatedByBiometricEnrollment(true)`, DEC-60), so the cipher cannot be initialized and a finger enrolled later by someone who knows the device PIN never unlocks the app. The database is already open, so the authenticated cipher is only proof: `AppLockController` runs `KeyVault.verifyBiometric(cipher)` (which unwraps and zeroes at once) and raises `Unlocked` on `Verified`. The device credential never unlocks `PASSWORD` mode.
- **Password check without reopening.** In `Locked(Ready)` and `Locked(Corrupt)` the password screen is a `Locked` state with a `PasswordEntry`; the database stays as it is (open for `Ready`, closed for `Corrupt`). Effect E4 runs `KeyVault.verifyPassword(bytes)`, which applies the same throttle, counting and reminder restart as a cold-start password ([security.md § 5.5](security.md#55-return-from-background)) and zeroes the key it derived; DatabaseHolder is not touched. A cold-start password is one way to open the database and nothing more: it does not stand in for the lock later, and every return from background resolves the route again.
- **The 14-day reminder (DEC-74)** turns the next unlock into a password unlock: `unlockRoute()` returns `Password(Reminder, fingerprintUsable = false)` at cold start (T2) or after a return from background (T46). After a KEK failure with a live slot it is ignored for the rest of the process (DEC-86, next bullets). The screen shows the one-line reason and no "Use fingerprint"; "Forgot password?" there leads to the fingerprint reset, because `fingerprintUnlockAvailable()` ignores the reminder. Every successful startup-password check restarts the 14 days inside `KeyVault` (`unlock` at the gate; `verifyPassword` on return from background and in `confirmWithPassword`; `openKey` for a key change's current password and the password of a Restore from `Corrupt`), and so does the commit of every newly typed password (set, change, fingerprint reset). A fingerprint unlock, `verifyBiometric`, a Restore from `Ready` and a slot-only commit never restart it ([security.md § 5.6](security.md#56-password-reminder-dec-74)). The reducer never compares dates.
- **Re-creation offer.** After a successful password unlock in `PASSWORD` mode (T16 from `Opening(viaPassword)`, T48), after a committed key change from `Ready` (T28a with `op == KeyChange`) and after a successful fingerprint password reset, which counts as the unlock (DEC-63: T16 from `Opening(viaReset)`, T57), SessionGate runs E5, which calls `AppLockController.offerSlotSetup()`. A Restore never runs E5: it keeps the DEK and the slot (DEC-84) and is not a password entry. The controller first runs `reconcileLock()`, then decides whether the offer applies (lock on, usable strong biometric, slot missing or invalidated; [security.md § 7](security.md#7-biometric-app-lock-dec-65)) and publishes it as `slotOffer`; the shell renders it in `Ready` ([ui.md § 8.13](ui.md#813-fingerprint-set-up-offer-dec-65)). "Set up" calls `AppLockController.createSlot()`, which prompts only while the offer is pending (DEC-65: the password-free arming follows a password entry in the same foreground session; leaving `Content` clears the offer) and otherwise returns `Ok(false)` without a prompt; Settings' own "Set up" asks the password first (`confirmWithPassword(SetUpSlot, …)`). `createSlot()` runs one prompt with a wrap cipher and commits the slot through `BiometricSlotWriter.createBiometricSlot` (implemented by DbMaintenance, [db-lifecycle.md § Biometric slot commits](db-lifecycle.md#biometric-slot-commits)). This is not a maintenance operation: no `MaintenanceStarted`, no gate change, Room stays open. A cancelled or declined offer is cleared and comes back after the next password unlock (DEC-65), because only those rows run E5. The fingerprint reset's offer is a password-free arming of the same kind: the reset committed a newly typed password in this foreground session.
- **Fingerprint password reset (DEC-63).** "Forgot password?" on a password screen (`NeedsPassword`, or `Locked` with a `PasswordEntry` over `Ready` or `Corrupt`) opens the Forgot password screen, a local UI state of the gate screen ([ui.md § 10.3](ui.md#103-forgot-password-screen-dec-63)). It offers "Set a new password with your fingerprint" while the gate is `NeedsPassword` or `Locked(Ready)` with a `PasswordEntry` and `AppLockController.fingerprintUnlockAvailable()` is true (evaluated when the screen opens and again before the prompt). The flow is coordinated by the activity-scoped `ForgotPasswordViewModel` (invariant 2), so the prompt and the call survive a rotation. The screen collects the new password (twice, with the acknowledgement), obtains an authenticated unwrap cipher with `AppLockController.slotPrompt(AuthPurpose.ResetPassword)` (`Ok(null)`: the password is zeroed and the availability re-evaluated; `Err(AuthFailed)`: the same plus a failure dialog), calls `AppMessages.markFingerprintReset()` (the marker that lets the outcome message say "New startup password set", [ui.md § 9.6](ui.md#96-outcome-messages)) and then `DbMaintenance.resetPasswordWithBiometric(origin, cipher, newPassword)` with origin `NeedsPassword` (database closed) or `Ready` (database open behind the lock); mechanics in [db-lifecycle.md § Fingerprint password reset](db-lifecycle.md#fingerprint-password-reset). DbMaintenance raises `MaintenanceStarted(KeyChange, fingerprintReset = true)` only after the cipher unwrapped the DEK; every failure before that leaves the gate on the same password screen, where the failure dialog appears. **The reset counts as the unlock (DEC-63):** the fingerprint has just authorized the slot, so while the reset runs the gate shows the progress screen "Updating encryption…" and never prompts for the fingerprint (the slot is about to be dropped); on success the gate goes to `Ready` and E5 publishes the slot re-creation offer; on failure it returns to the password screen the reset started from. `GateState.Maintenance.resetFrom` marks the state as a fingerprint reset and keeps that password screen (a `PasswordEntry`, which holds no password):
  - From `NeedsPassword` (database closed): T41 enters `Maintenance(KeyChange, NeedsPassword, resetFrom)`, rendered as the progress screen. Success: DbMaintenance commits, keeps the new raw key for E2 and ends with `OpenWithNewKey`; T55 enters `Opening(viaReset)`, and E2 opens the new generation with that key (`DbMaintenance.takeResetKey`), so the live Room instance is still first built in `Opening` (invariant 1, DEC-90); `Opened` gives `Ready` with E5 (T16). Failure after `MaintenanceStarted` and before the commit: `Aborted`, and T56 returns to the same password screen (`NeedsPassword` rebuilt from `resetFrom`) without running E1. Commit outcome unknown: `Restart` (T30). A `LockNow` while it runs sets `lockPending` (T53); the open then ends in `Locked(Ready)` (T55, T16), and the offer follows the next password unlock (T48).
  - From `Locked(Ready)` with a `PasswordEntry` p (database open behind the lock): T12 enters `Locked(Maintenance(KeyChange, Ready, resetFrom = p))`; the `Locked` itself carries no `PasswordEntry`. `lockScreenOf` returns `Other` for it, so nothing prompts, and the gate renders the progress screen, not the lock screen ([Rendering](#rendering)); T10 and T46 do not apply to it. Success: `Reopened` and T57: `Ready` with E5, or `Locked(Ready)` without E5 if a `LockNow` arrived while it ran (T54 sets `lockPending`). Failure before the commit (Room reopened on the old generation): `Aborted` and T58: `Locked(Ready, password = p)`, the password screen it started from, whose status host shows the failure dialog in lock mode (DEC-66, [ui.md § 8.12](ui.md#812-maintenance-status-host)). `ReopenFailed` (T14c, T14d), a runtime corruption reported meanwhile (T14b) and `Restart` (T15) apply as for any locked operation: no open database remains to unlock to, so the gate locks again and resolves the route afresh.

  The new DEK has no slot, so after a reset fingerprint unlock returns only through the re-creation offer. "Erase all data and start over" stays available on the same screen: from `NeedsPassword` it is `DbMaintenance.erase(NeedsPassword)` and T8; from a `Locked` password screen over `Ready` or `Corrupt` it is `DbMaintenance.erase(Ready)` or `erase(Corrupt)`, starts as `Locked(Maintenance(Erase, origin))` (T12) and ends with `Restart` (T15) or, when nothing was erased, `Reopened` (T14a) or `Aborted` (T14e). The reset is offered only from `NeedsPassword` and `Locked(Ready)` with a password entry; over `Locked(Corrupt)` the Forgot password screen shows its "Not available" variant (Erase only; [db-lifecycle.md § Fingerprint password reset](db-lifecycle.md#fingerprint-password-reset) accepts only origins `NeedsPassword` and `Ready`). While an operation runs behind a locked password screen (a `Maintenance` resume), "Forgot password?" and with it Erase are hidden ([Rendering](#rendering), DEC-63), so neither the reset nor Erase can start there; a running reset shows the progress screen, which offers neither.
- **KEK failure during a password unlock (DEC-86).** In `PASSWORD` mode the password slot and the biometric slot wrap the same DEK independently, and only the password slot needs the Keystore KEK. When the password path fails in the Keystore layer (`UnlockResult.KeyFailed` or a `KeyVaultException(KeyUnavailable)` from `KeyVault.unlock` in E2 on a cold start, or `KeyFailed` from `KeyVault.verifyPassword` in E4 on return from background), the effect calls `AppLockController.reportPasswordKekFailure()` ([security.md § 7.3](security.md#73-applockcontroller-and-mainactivity)). It returns true when the biometric slot is live, which DEC-86 defines exactly as `PASSWORD` mode, lock on (`security.app_lock`) and `KeyVault.biometricSlotStatus() == Live` (a stale slot left behind with the lock off, a `Dead` slot or none does not count; whether a strong biometric is usable is not part of it), and has then set the kekBroken notice and the controller's `kekBroken` flag. The flag is never cleared before the process ends, so after a KEK failure the reminder is ignored for the rest of the process: `unlockRoute()` treats it as not due, and so does `slotPrompt(RestoreKey)` ([security.md § 5.6](security.md#56-password-reminder-dec-74)). The effect then raises `KekFailedWithSlot` instead of `KeyFailed`: from `Opening(viaPassword)` the gate goes to `Locked(Open)` (T59), from a checking password entry to the same `Locked` without the entry (T60), and the auto-prompt asks for the fingerprint through the slot, even when the 14-day reminder is due. The data opens (T42, T16) or unlocks (T10) through the slot, and the shell shows the broken-key notice in `Ready` ("Back up now"; its text points to "Forgot password?", whose fingerprint reset replaces the KEK). Only without a live slot does a cold-start KEK failure lead to `KeyUnavailable` (T23); over an open database a failed check without a live slot stays the retry line of T50. If the fingerprint cannot be used at prompt time (no usable strong biometric, the slot found invalidated), `AppLockController` raises `PasswordRequired` as usual and the password screen returns (T45 into `NeedsPassword`, T46 into the password entry of `Locked(Ready)` or `Locked(Corrupt)`); that is never `KeyUnavailable`, and "Forgot password?" with Erase stays available there. With no usable strong biometric while the slot is still live, the event carries `reason = KeyStoreFailed` (security.md § 5.1), and the password screen explains in its reason line that the device key store failed and that the fingerprint or a backup restore is the way back (DEC-86; ui.md owns the text); a slot found invalidated is no longer live and carries `FingerprintChanged`. No new state, event or row is needed: the reason travels in `PasswordRequired`, `NeedsPassword.reason` and `PasswordEntry.reason` like the other reasons. A KEK failure never counts toward the backoff (it is not a wrong password). The same fallback applies to the key of a Restore from `Corrupt` (DEC-86): there it happens before `MaintenanceStarted`, inside `DbMaintenance`'s questions (`NeedsDatabasePassword` → `NeedsBiometricUnlock(afterKekFailure = true)`, [db-lifecycle.md § Origins and target key](db-lifecycle.md#origins-and-target-key)), so the gate stays `Corrupt` and raises no event; the status host calls `reportPasswordKekFailure()` and then `slotPrompt(RestoreKey)`.

### States, events and effects

```kotlin
sealed interface GateState {      // every class overrides toString() with class name, booleans, counts and enums only (DEC-70; omitted here)
    data class Starting(val authenticated: Boolean) : GateState
    data class NeedsPassword(val failures: Int, val retryAt: Long, val couldNotOpen: Boolean,   // retryAt: elapsedMillis() base
                             val reason: PasswordReason = PasswordReason.None,
                             val fingerprintUsable: Boolean = false) : GateState
    data class Locked(val resume: Resume, val authError: LockError? = null,
                      val password: PasswordEntry? = null) : GateState                    // password != null: the password screen, database as `resume` says
    data class Opening(val viaPassword: Boolean, val lockPending: Boolean, val transientError: Boolean,
                       val viaSlot: Boolean = false,
                       val reason: PasswordReason = PasswordReason.None,                  // viaPassword only: what the password screen showed (T18, T21)
                       val fingerprintUsable: Boolean = false,
                       val viaReset: Boolean = false) : GateState                         // E2 takes the key a fingerprint reset committed (T55)
    data object Ready : GateState
    data class Maintenance(val op: MaintenanceOp, val origin: Origin, val corruptPending: Boolean = false,
                           val resetFrom: PasswordEntry? = null,     // non-null only for a fingerprint password reset (DEC-63): the password screen it started from
                           val lockPending: Boolean = false) : GateState   // fingerprint reset only: a LockNow arrived while it ran (T53, T54)
    data object Corrupt : GateState
    data object KeyUnavailable : GateState
}
data class PasswordEntry(val reason: PasswordReason, val fingerprintUsable: Boolean,
                         val failures: Int, val retryAt: Long, val checking: Boolean = false,   // retryAt: elapsedMillis() base
                         val checkFailed: Boolean = false)                                       // the last check hit a Keystore failure (T50): the screen shows a generic retry line, not a wrong-password error
sealed interface Resume { data object Open : Resume; data object Ready : Resume; data class Maintenance(val state: GateState.Maintenance) : Resume
                          data object Corrupt : Resume; data object KeyUnavailable : Resume }
enum class Origin { Ready, Corrupt, KeyUnavailable, NeedsPassword }
enum class PasswordReason { None, Reminder, FingerprintChanged, KeyStoreFailed }    // model: the reason line of the password screen (texts: ui.md § 10.2); KeyStoreFailed: DEC-86, security.md § 5.1
// model, owned by security.md § 5.1: sealed interface UnlockRoute { Biometric; Slot; Password(reason: PasswordReason, fingerprintUsable: Boolean) }
class LockError(val code: Int, val message: String?) {   // security package: BiometricPrompt error code and the system's errString (security.md § 7.4), never user data; message == null is a silent error (the lock screen shows no text)
    override fun toString() = "LockError(code=$code)"
    companion object { const val SLOT_FAILED = -1 }      // not a BiometricPrompt code: the slot cipher failed (T44, T11, security.md § 7.4); the lock screen shows gate_slot_failed (ui.md § 10.4), never silent
}
```

- `Starting(authenticated)`: startup in progress. `authenticated` is true only after a recovery operation restarted the app (T30). It skips the biometric lock in `PLAIN` and `DEVICE` mode only; `PASSWORD` mode always needs a key, so T2 or T40 apply whatever its value.
- `NeedsPassword(failures, retryAt, couldNotOpen, reason, fingerprintUsable)`: `PASSWORD` mode, database closed, the password screen (DEC-62). `retryAt` is on the `AppTime.elapsedMillis()` base and has passed through `UnlockBackoff.effectiveRetryAt`, so it is never further away than the current delay (0 = no delay; [security.md § 5.3](security.md#53-backoff-dec-62)). `reason` selects the reason line; `fingerprintUsable` shows "Use fingerprint" (T51) and is true only when the user left a usable fingerprint route with "Use password".
- `Locked(resume, authError, password)`: the app is locked. `resume` is what returns on unlock: `Open` (not yet opened), `Ready`, a `Maintenance` state, `Corrupt` or `KeyUnavailable`. With `password == null` it is the lock screen and `AppLockController` prompts on the route it resolves; with a `PasswordEntry` it is the password screen and nothing prompts. One exception: a `Locked` whose resume is a fingerprint password reset (`Resume.Maintenance(m)` with `m.resetFrom != null`, entered by T12 from the password screen of `Locked(Ready)`) shows the maintenance progress screen and never prompts, because the reset counts as the unlock (DEC-63); `lockScreenOf` returns `Other` for it. `Locked(Open)` never has a `PasswordEntry` (T45 goes to `NeedsPassword` instead), and neither does `Locked(KeyUnavailable)` (T46's guard) or a `Locked` over a fingerprint reset (T46's guard). A `Locked(Maintenance)` never has origin `NeedsPassword` (T32; a fingerprint reset from `NeedsPassword` records a lock in `lockPending` instead, T53).
- `Opening(viaPassword, lockPending, transientError, viaSlot, reason, fingerprintUsable, viaReset)`: unwrap the key, open, verify. At most one of `viaPassword`, `viaSlot` and `viaReset` is true. `lockPending` means the app went to the background meanwhile (T24). `reason` and `fingerprintUsable` are copied from `NeedsPassword` by T7, so a wrong password (T21) or a failed open (T18) returns to the same password-screen variant. `viaReset` (T55) means E2 opens with the raw key that a fingerprint reset from `NeedsPassword` has just committed and handed over (`DbMaintenance.takeResetKey`); it is never true together with `viaPassword` or `viaSlot`, and a successful open from it runs E5 (T16).
- `Ready`: database open, content composed.
- `Maintenance(op, origin, corruptPending, resetFrom, lockPending)`: `op` is one of `Backup`, `Restore`, `KeyChange`, `StartFresh`, `Erase` (the fingerprint reset is a `KeyChange`); `origin` is the state it started from. `corruptPending` records runtime corruption reported while Room was being reopened (T38). `resetFrom` is non-null exactly for a fingerprint password reset (`MaintenanceStarted(KeyChange, fingerprintReset = true)`, T41 or T12): it holds the password screen the reset started from (from `NeedsPassword`: its `failures`, `retryAt`, `reason` and `fingerprintUsable` as a `PasswordEntry`; from `Locked(Ready)`: the entry with `checking` and `checkFailed` cleared), which a failure returns to (T56, T58); it holds no password. `lockPending` is used only while `resetFrom != null`: a `LockNow` during the reset (T53, T54) makes its success end locked instead of in `Ready` (T55, T57).
- `Corrupt` and `KeyUnavailable`: recovery screens (DEC-85, DEC-86).

Events (`GateEvent`; every class overrides `toString()` and none prints a password or cipher, DEC-70):

| Event | Payload | Raised by |
|---|---|---|
| `StartupLoaded` | `mode: KeyMode, lockEnabled: Boolean, failures: Int, retryAt: Long` (`elapsedMillis()` base), `route: UnlockRoute` (`PASSWORD`: `Slot` or `Password(…)` from `AppLockController.unlockRoute()`; other modes: `Biometric`, which no row reads) | E1 |
| `StartupKeyFailed` | `lockEnabled: Boolean` | E1 |
| `PasswordSubmitted` | `password: CharArray`; `toString() = "PasswordSubmitted(<redacted>)"` | gate UI |
| `Retry` | | gate UI |
| `UseFingerprintChosen` | | gate UI: "Use fingerprint" on the password screen |
| `Opened`, `OpenFailed(kind)` | `kind: OpenFailureKind` = `Corrupt` or `Transient` | E2, from `DatabaseHolder.open` |
| `PasswordRejected` | `failures: Int, retryAt: Long` (`elapsedMillis()` base) | E2 and E4, from `UnlockResult` |
| `PasswordNeeded` | `failures: Int, retryAt: Long, reason: PasswordReason` | E2, from `UnlockResult` |
| `PasswordVerified` | | E4, from `UnlockResult.Verified` |
| `KeyFailed` | | E2 and E4, from `UnlockResult` or a `KeyVaultException` |
| `KekFailedWithSlot` | | E2 (password path) and E4, in place of `KeyFailed` when the Keystore layer of the password slot failed and `AppLockController.reportPasswordKekFailure()` returned true (a live biometric slot, DEC-86) |
| `SlotUnlockFailed` | | E2, from `UnlockResult.SlotFailed(invalidated = false)` |
| `LockNow`, `Unlocked`, `AuthError(error)` | `error: LockError` | AppLockController, through `LockListener` |
| `SlotUnlocked(cipher)` | `cipher: SlotCipher`; `toString() = "SlotUnlocked"` | AppLockController, through `LockListener`: a successful slot prompt while the screen is `PromptToOpen` |
| `PasswordRequired` | `reason: PasswordReason, fingerprintUsable: Boolean, failures: Int, retryAt: Long` (counters from `SettingsStore.snapshot()` through `UnlockBackoff.effectiveRetryAt`) | AppLockController, through `LockListener`: route `Password` resolved at prompt time, "Use password", or the slot found unusable |
| `CorruptAtRuntime` | | DatabaseHolder, through `CorruptionListener` |
| `MaintenanceStarted(op, fingerprintReset)`, `MaintenanceFinished(outcome)` | `fingerprintReset: Boolean` (true only from `resetPasswordWithBiometric`, DEC-63); `outcome` = `Reopened`, `ReopenFailed(kind)`, `Restart`, `Aborted` or `OpenWithNewKey` (only a fingerprint reset from `NeedsPassword` after its commit: the new raw key waits in DbMaintenance for E2) | DbMaintenance, through `MaintenanceListener` |

`LockNow` is raised by AppLockController under the re-lock rules of [security.md § 7.7](security.md#77-re-lock-rules) (process ON_STOP, and ON_START after an expired exemption, only while the lock is on and no exemption is active) **whatever the gate state**; only the reducer decides what it means (T24, T25, T32, T34, T36). `Unlocked` is raised after a successful prompt on the `Biometric` route, after a `Slot` prompt over an open or recovering database whose cipher `KeyVault.verifyBiometric` accepted, after a `CredentialConfirm` result with `RESULT_OK`, and after the lock turned itself off (security.md § 7.6).

Effects:
- **E1 RunStartup** (io), in this order: (1) `SqlcipherEngine.init()`, idempotent per process, in the order of [security.md § 3.4](security.md#34-sqlcipher-key-and-settings): `System.loadLibrary("sqlcipher")` (DEC-08), `SqlcipherLogging.configure()` (release: logger target `NoopTarget`), then `PRAGMA cipher_default_compatibility = 4` on a throwaway in-memory connection; (2) await the first `SettingsStore` value; (3) `DbMaintenance.reconcileAtStartup()`, which reads the key-state through KeyVault, deletes leftovers and returns a `StartupDecision` ([db-lifecycle.md § Startup reconciliation](db-lifecycle.md#startup-reconciliation)); (4) `SettingsStore.snapshot()` for `lockEnabled`, `failures` and `retryAt`, read after the reconciliation because the first-run reset may have changed them; `retryAt` is passed through `UnlockBackoff.effectiveRetryAt(failures, stored, appTime.elapsedMillis())`, so a reboot since the failures does not leave a stale countdown; (5) for `Proceed(_, PASSWORD)` only, `appLockController.unlockRoute()`, which reads the lock setting, `BiometricGateway.isDeviceSecure()`, the biometric availability, `KeyVault.biometricSlotStatus()` (committed key-state plus `containsAlias`, no Keystore operation) and `KeyVault.isPasswordReminderDue()`, and evaluates `UnlockPolicy.route(…, coldStart = true, …)` ([security.md § 5.1](security.md#51-cold-start-per-mode)), and never prompts and never starts a Keystore operation: `Cipher.init` on an AndroidKeyStore key begins an operation that cannot be aborted, so an unwrap cipher that was initialized only to probe the slot and then dropped would stay open until it is pruned and could cause the operation of the real prompt to be pruned (`SlotFailed(invalidated = false)`, [security.md § 3.7.3](security.md#373-unwrapping-through-the-slot)). A missing alias already reads as `Dead` at E1; a key that is present but invalidated is detected when the prompt's cipher is prepared ([security.md § 7.4](security.md#74-unlock-route-and-error-mapping): `Invalidated` becomes `PasswordRequired(FingerprintChanged)`, T45 or T46); (6) raise `StartupLoaded(mode, lockEnabled, failures, retryAt, route)` for `Proceed(_, mode)`, `FirstRun` and `AdoptPlain` (mode `PLAIN`, route `Biometric`), or `StartupKeyFailed(lockEnabled)` for `KeyUnavailable`. E1 has no other outcome: an unreadable or invalid key-state is `KeyUnavailable` by the classification of db-lifecycle.md; any other exception is a bug and crashes the app, with nothing deleted that the decision table does not allow.
- **E2 UnlockAndOpen(password? or cipher? or resetKey)**, at most one of the three (`resetKey` only from T55, `Opening(viaReset)`), in this order: (1) `DbMaintenance.completeStartup()`, which returns `StartupCompletion { Done, Transient, KeyUnavailable }` (it creates the first-run database or adopts plaintext files, and is a no-op otherwise; [db-lifecycle.md § Completing startup in Opening](db-lifecycle.md#completing-startup-in-opening)): `Transient` (storage full or another transient failure) raises `OpenFailed(Transient)`, `KeyUnavailable` raises `KeyFailed`; (2) `KeyVault.readState()` for the generation: no file, or a `KeyVaultException(InvalidState)`, raises `KeyFailed`; (3a) without a cipher, `KeyVault.unlock(passwordBytes?)` ([security.md § 3.6](security.md#36-keyvault-surface); Keystore calls on io, PBKDF2 on default, the password array zeroed afterwards, DEC-64) returns an `UnlockResult`: `Key(rawKey)` continues; `PasswordRejected(failures, retryAt)` and `Throttled(failures, retryAt)` both raise the gate event `PasswordRejected(failures, retryAt)`, with the counters that KeyVault persisted before it returned ([security.md § 5.3](security.md#53-backoff-dec-62)); `PasswordNeeded` (a `PASSWORD` slot without a password: T9 or T20 in `PASSWORD` mode, after a lost cipher or a defensive path) raises `PasswordNeeded(failures, retryAt, reason = None)` from `SettingsStore.snapshot()` after `UnlockBackoff.effectiveRetryAt`, with no Keystore call; `KeyFailed`, or a `KeyVaultException`, raises `KeyFailed`, except on the password path (a password was passed and the key-state read in step 2 has a `PASSWORD` slot): there E2 first calls `appLockController.reportPasswordKekFailure()` and, when it returns true (a live biometric slot, DEC-86), raises `KekFailedWithSlot` instead (T59: the fingerprint route, also when the reminder is due); (3b) with a cipher, `KeyVault.unlockWithBiometric(cipher)` returns `Key(rawKey)`, which continues; `SlotFailed(invalidated = true)`, which raises `PasswordNeeded(failures, retryAt, reason = FingerprintChanged)` with the snapshot counters; `SlotFailed(invalidated = false)`, which raises `SlotUnlockFailed`; or `KeyFailed` (or a `KeyVaultException`), which raises `KeyFailed`; (3c) with `resetKey` (`Opening(viaReset)`), `DbMaintenance.takeResetKey(state.generation)`, which transfers ownership of the raw key that the fingerprint reset committed for that generation ([db-lifecycle.md § Fingerprint password reset](db-lifecycle.md#fingerprint-password-reset)): a key continues to step 4 (the reset's commit already reset the backoff and restarted the reminder, so no `KeyVault.unlock` call follows; a `kekBroken` slot result was handled inside the reset, whose `prepare` replaced the KEK); `null` (no key waiting, or one for another generation) raises `PasswordNeeded(failures, retryAt, reason = None)` with the snapshot counters (T22: the password screen for the new password). A slot failure never counts toward the backoff and is never `KeyUnavailable` while the password can still open the database ([db-lifecycle.md § Failure classification](db-lifecycle.md#failure-classification), BiometricUnusable). On `Key(rawKey, kekBroken = true)` (either path) E2 calls `appLockController.reportKekBroken()` before opening ([security.md § 7.3](security.md#73-applockcontroller-and-mainactivity)); (4) `DatabaseHolder.open(generation, rawKey)`, which takes ownership of the key (open and verify, [db-lifecycle.md § Open sequence](db-lifecycle.md#open-sequence)) and returns `OpenResult`: `Opened` raises `Opened`, `Failed(kind)` raises `OpenFailed(kind)`. One exception: when E2 runs with a cipher and `open` returns `OpenResult.Failed(Corrupt, keyRejected = true)` (header/mode mismatch, or `SQLiteNotADatabaseException` at raw verification, [db-lifecycle.md § Failure classification](db-lifecycle.md#failure-classification)), E2 calls `KeyVault.deleteBiometricKey()` and raises `PasswordNeeded(failures, retryAt, reason = FingerprintChanged)` with the snapshot counters (T22) instead of `OpenFailed(Corrupt)`: a slot-released key is not proven correct, so this is never `Corrupt` (DEC-60, DEC-85). With a password, `keyRejected` is ignored and `Failed(Corrupt, _)` raises `OpenFailed(Corrupt)`. After a correct password KeyVault resets `security.unlock_failures` and `security.unlock_retry_at` and restarts the reminder ([security.md § 5.1](security.md#51-cold-start-per-mode) step 3); a slot unlock restarts neither.
- **E3 CloseDatabase**: `DatabaseHolder.close()`; the key is zeroed and no file is deleted (DEC-85).
- **E4 VerifyPassword(password)** (io; PBKDF2 on default): `passwordBytes(password)` ([security.md § 6.3](security.md#63-normalization-and-encoding); a null result is passed as `ByteArray(0)`), then `KeyVault.verifyPassword(bytes)`: `Verified` raises `PasswordVerified`; `PasswordRejected(failures, retryAt)` and `Throttled(failures, retryAt)` raise `PasswordRejected(failures, retryAt)`; `KeyFailed` or a `KeyVaultException` first calls `appLockController.reportPasswordKekFailure()`: when it returns true (a live biometric slot, DEC-86) E4 raises `KekFailedWithSlot` (T60: the entry is dropped and the slot prompt asks for the fingerprint, also when the reminder is due; the kekBroken notice follows in `Ready`); otherwise it raises `KeyFailed`, which in `Locked` means "the password could not be checked" (T50), **not** "the key cannot be obtained": the database is open (or, for `Corrupt`, was never reachable through this password) and healthy, so nothing is closed and the screen stays for a retry or the fingerprint. A `verifyPassword` call on a slot that is not `PASSWORD` is a caller bug; it is also reported as `KeyFailed` and so ends in the same harmless retry screen. A Keystore failure that persists without a live biometric slot is classified properly after a restart, where E1 and E2 turn it into `KeyUnavailable` (T5, T6, T23); with a live slot E2 sends a cold-start password unlock to the fingerprint route instead (T59, DEC-86). E4 zeroes the chars and the bytes in its `finally`, never touches DatabaseHolder and never reopens anything. KeyVault resets the backoff and restarts the reminder on success, exactly as for a cold-start password.
- **E5 OfferSlotSetup** (launched on main in `applicationScope`; the controller's KeyVault and DataStore calls run on `dispatchers.io`): `appLockController.offerSlotSetup()`, which runs `reconcileLock()` first (stale-slot removal while the lock is off and the `PASSWORD`-mode auto-off, [security.md § 3.7.5](security.md#375-slot-lifecycle) and [§ 7.6](security.md#76-device-credential-fallback-q7)), then decides the offer. No event comes back; the offer is controller state ([Unlock routes](#unlock-routes-q8)). E5 runs only at T16 (result `Ready` from `Opening(viaPassword)` or `Opening(viaReset)`), T48 (resume `Ready`), T28a (`op == KeyChange`) and T57 (a fingerprint reset from `Locked(Ready)` that ends in `Ready`).

Not every failure of the database layer fits `Corrupt` or `Transient`. A live database whose `user_version` exceeds `AppDatabase.VERSION` (an app downgrade) is classified `Transient` by [db-lifecycle.md § Failure classification](db-lifecycle.md#failure-classification): the gate shows the open-error screen, nothing destructive is offered and installing the newer version cures it.

### Event wiring

The import rules keep `security`, `data` and `data.maintenance` from importing `session`, so raisers cannot hold a SessionGate. They declare a listener interface in their own package, and SessionGate implements the three that concern the gate (dependency inversion):

| Raiser | Interface (package) | Methods | Implemented by |
|---|---|---|---|
| AppLockController | `LockListener` (`security`) | `onLockNow()`, `onUnlocked()`, `onAuthError(error: LockError)`, `onSlotUnlocked(cipher: SlotCipher)`, `onPasswordRequired(reason: PasswordReason, fingerprintUsable: Boolean, failures: Int, retryAt: Long)` | SessionGate |
| DatabaseHolder | `CorruptionListener` (`data`) | `onRuntimeCorruption()` | SessionGate |
| DbMaintenance | `MaintenanceListener` (`data.maintenance`) | `onMaintenanceStarted(op, fingerprintReset)`, `onMaintenanceFinished(outcome)` | SessionGate |
| DatabaseHolder | `FlowProblemListener` (`data`) | `onFlowProblem(error: AppError)`, may be called from any thread ([db-lifecycle.md § Observation](db-lifecycle.md#observation)) | AppContainer: posts a `UserMessage` to `AppMessages` (thread-safe); the gate is not involved |
| AppLockController | `BiometricSlotWriter` (`security`) | `suspend fun createBiometricSlot(cipher: SlotCipher): AppResult<Unit>` (gate `Ready`; `Err(Busy)` while another operation holds the operation mutex), `suspend fun removeBiometricSlot(): AppResult<Unit>` (any gate state; deletes the key first; always `Ok(Unit)` in v1, never `Busy`) | DbMaintenance, which declares `interface DbMaintenance : BiometricSlotWriter` ([db-lifecycle.md § Biometric slot commits](db-lifecycle.md#biometric-slot-commits)); the gate is not involved |

- AppContainer passes each raiser a **provider** of the listener, for example `DefaultDatabaseHolder(…, corruptionListener = { sessionGate }, flowProblemListener = FlowProblemListener { e -> appMessages.post(errorMessage(e)) })`, where `errorMessage` builds the `UserMessage` of [ui.md § 2.4](ui.md#24-snackbars-and-notices) with `UiText.Error(e)` and `isError = true`; the provider is resolved at the first event. AppLockController receives `slotWriter = { dbMaintenance }` the same way. No constructor takes the gate, so the lazy members never recurse (SessionGate depends on the holder, the maintenance and the lock controller; they do not depend on SessionGate; AppLockController reaches DbMaintenance only through the provider, and DbMaintenance never reaches AppLockController).
- Threads: `LockListener` and `MaintenanceListener` calls already arrive on the main thread (the lifecycle observer and BiometricPrompt callbacks run on it; AppLockController switches back to `dispatchers.main` after any KeyVault call before it calls the listener; DbMaintenance calls the listener inside `withContext(dispatchers.main)`, so the gate has changed before the next pipeline step runs, [db-lifecycle.md § Interface and status](db-lifecycle.md#interface-and-status)). `CorruptionListener.onRuntimeCorruption()` may arrive on any thread; the implementation launches `dispatch(CorruptAtRuntime)` on `dispatchers.main` in `applicationScope` and returns at once.
- SessionGate calls the other way directly, because `session` may import `security`. After every state change `dispatch` computes `lockScreenOf(state)` and `showsError = (state as? GateState.Locked)?.authError != null` and, when the pair differs from the previous one, calls `appLockController.onGateScreenChanged(screen, showsError)`. E1 calls `unlockRoute()`, E2 calls `reportKekBroken()` and `reportPasswordKekFailure()`, E4 calls `reportPasswordKekFailure()`, and E5 calls `offerSlotSetup()`. Outside the gate, `MaintenanceStatusHost` (ui) calls `reportPasswordKekFailure()` and `slotPrompt(RestoreKey)` for a restore's `NeedsBiometricUnlock` status; DbMaintenance itself still never reaches AppLockController, and the status is how its KEK failure reaches the controller. AppLockController holds only the exemption, the prompt-in-flight, auto-prompt and slot-offer state, the current `LockScreen`, the last resolved route, the `kekNotice` and the `kekBroken` flag (security.md § 7.3); whether the app is locked is `GateState.Locked`.

```kotlin
// security: what the gate currently shows, as far as prompting is concerned
enum class LockScreen { Content, Other, PromptToOpen, Prompt, PromptWithoutKey, Password }
// session
fun lockScreenOf(s: GateState): LockScreen = when (s) {
    GateState.Ready -> LockScreen.Content
    is GateState.NeedsPassword -> LockScreen.Password
    is GateState.Locked -> when {
        s.password != null -> LockScreen.Password
        s.resume is Resume.Maintenance && s.resume.state.resetFrom != null ->
            LockScreen.Other                                      // a running fingerprint reset counts as the unlock: progress screen, never a prompt (DEC-63)
        s.resume == Resume.Open -> LockScreen.PromptToOpen        // a successful slot prompt raises SlotUnlocked (T42)
        s.resume == Resume.KeyUnavailable -> LockScreen.PromptWithoutKey   // route Biometric whatever the mode
        s.resume is Resume.Maintenance &&
            (s.resume.state.origin == Origin.KeyUnavailable || s.resume.state.op == MaintenanceOp.Erase) ->
            LockScreen.PromptWithoutKey                           // no key-state can check a key: KeyUnavailable origin, or an Erase that may have deleted it
        else -> LockScreen.Prompt                                 // Ready, Corrupt, any other Maintenance: the database key is not needed
    }
    else -> LockScreen.Other                                      // Starting, Opening, Maintenance of any origin, Corrupt, KeyUnavailable
}
```

`Other` for a `Locked` over a fingerprint reset is what keeps the slot prompt away while the reset runs (DEC-63): `onGateScreenChanged(Other, …)` arms no auto-prompt, a process ON_START re-arms none, the lock screen's "Unlock" and "Use password" do not exist there, and `AppLockController` raises neither `PasswordRequired` nor `Unlocked` for it (T10 and T46 exclude it anyway).

`PromptWithoutKey` makes `AppLockController` use `UnlockPolicy.routeOverKeyUnavailable(lockOn, deviceSecure, biometric)` in every mode (DEC-65, DEC-86): `AutoOff`, `Biometric` (`strongPrompt` without `CryptoObject`) or `DeviceCredential` (Q7). `PasswordRequired` is never raised there and "Use password" is not shown, because the route is never `Slot`.

- Components that must read the gate state read it through SessionGate's `state` flow (ui) or are told by SessionGate (AppLockController); nothing else observes it.

### AppLockController and BiometricGateway

`AppLockController` (application scope, `security`) is the only component that starts an authentication prompt, and `BiometricGateway` is the only one that touches `BiometricManager`, `BiometricPrompt`, `KeyguardManager` and the system intents. The platform details (PromptInfo, error codes, the cipher parameters of the slot, the route rules per mode, the reminder rule) are in [security.md § 3.7 and § 7](security.md#7-biometric-app-lock-dec-65); this section fixes the responsibilities and the contract with the gate and the UI.

| Member of `AppLockController` | Responsibility |
|---|---|
| `attach(activity)`, `detach(activity)` | One `BiometricPrompt` per `MainActivity` instance through `BiometricGateway.attach`/`detach`; the auto-prompt collector under `repeatOnLifecycle(RESUMED)` ([security.md § 7.3](security.md#73-applockcontroller-and-mainactivity)) |
| `suspend fun availability(): LockAvailability` | Settings row state including `slot: SlotState` (`NotUsed`, `Ready`, `Missing`, `Invalidated`; security.md § 7.1) |
| `suspend fun unlockRoute(): UnlockRoute` | Resolves the route through `UnlockPolicy` from the committed key mode, `security.app_lock`, `BiometricGateway.isDeviceSecure()`, `canAuthenticate()`, `KeyVault.biometricSlotStatus()` and `KeyVault.isPasswordReminderDue()` (treated as not due while the controller's `kekBroken` flag is set, DEC-86); while the flag is set and the slot is `Live`, a password route with cause `NoBiometric` carries the reason `KeyStoreFailed` (security.md § 5.1); over `PromptWithoutKey` it uses `UnlockPolicy.routeOverKeyUnavailable`. Never prompts and starts no Keystore operation. Called by E1 and before every prompt on `Prompt`, `PromptToOpen` and `PromptWithoutKey` |
| `val lockRoute: StateFlow<UnlockRoute?>` | The route resolved for the lock screen currently shown (null while none is resolved or the screen is not a lock). The lock screen shows "Use password" while it is `Slot` ([ui.md § 10.4](ui.md#104-lock-screen)) |
| `onGateScreenChanged(screen: LockScreen, showsError: Boolean)` | Called by SessionGate; `showsError` is true when the new state is a `Locked` with `authError != null`. Entering `Prompt`, `PromptToOpen` or `PromptWithoutKey` arms the one-shot auto-prompt **unless `showsError` is true**: a lock screen that was entered because an unlock just failed (T44) waits for the "Unlock" button or the next ON_START instead of prompting again at once ([security.md § 7.3](security.md#73-applockcontroller-and-mainactivity): after any error that shows the Unlock button nothing prompts until the next ON_START). Every process ON_START without an active exemption re-arms it while the screen is one of these three, error or not. `Password`, `Content` and `Other` never prompt for unlocking; leaving `Content` clears `slotOffer` |
| `prompt()` | The lock screen's "Unlock" button: resolves the route and prompts, as the auto-prompt does |
| `usePassword()` | The lock screen's "Use password" button: on the `Slot` route, raises `onPasswordRequired(None, fingerprintUsable = true, counters)`; otherwise does nothing |
| `suspend fun confirm(purpose: AuthPurpose): AppResult<ToggleOutcome>` | `EnableLock` / `DisableLock` for the Settings toggle; `ToggleOutcome` is `Done`, `Cancelled` or `PasswordRequired`. In `PLAIN`/`DEVICE` mode one prompt (`EnableLock`: strong biometric only; `DisableLock`: the unlock route with the Q7 fallback). In `PASSWORD` mode `EnableLock` returns `Ok(PasswordRequired)` without a prompt, and `DisableLock` uses the slot prompt while `fingerprintUnlockAvailable()` is true or returns `Ok(PasswordRequired)` ("Use password", a slot that cannot authorize, no fingerprint route). Result mapping: [security.md § 7.1, § 7.4](security.md#71-availability-and-the-settings-row) |
| `suspend fun confirmWithPassword(purpose: AuthPurpose, password: CharArray): AppResult<ToggleOutcome>` | `PASSWORD` mode only, purposes `EnableLock`, `DisableLock` and `SetUpSlot` (anything else is a `require` failure). Owns and zeroes the password. Runs `KeyVault.verifyPassword` first (counts toward the backoff, restarts the reminder; `Err(WrongPassword)`, `Err(Throttled)`, or `Err(KeyUnavailable)` as a message only, the gate is not touched, DEC-86), then: `EnableLock` arms the slot (ENCRYPT prompt, `security.app_lock = true`, `createBiometricSlot`, the setting written back to false on any `Err`); `SetUpSlot` arms without a setting write; `DisableLock` writes `security.app_lock = false`, then `removeBiometricSlot()` (DEC-65). Steps: security.md § 7.1 |
| `suspend fun fingerprintUnlockAvailable(): Boolean` | `PASSWORD` mode, lock on, usable strong biometric, `KeyVault.biometricSlotStatus() == Live` (no Keystore operation, same reason as `unlockRoute()`). Shown as the fingerprint option of the Forgot password screen; the reminder does not affect it. A slot whose key proves dead when `slotPrompt(ResetPassword)` prepares its cipher returns `Ok(null)`, and the screen then withdraws the option |
| `suspend fun slotPrompt(purpose: AuthPurpose): AppResult<SlotCipher?>` | One prompt with an unwrap cipher on the slot, for a caller that hands the cipher to DbMaintenance: `ResetPassword` (the fingerprint reset) and `RestoreKey` (the database key for a restore from `Corrupt`, status `NeedsBiometricUnlock`, [db-lifecycle.md § Interface and status](db-lifecycle.md#interface-and-status)). `ResetPassword`: `Ok(cipher)` authenticated; `Ok(null)` cancelled or slot or biometric not usable (`NoSlot`, `Invalidated`, `Unavailable`; the negative button is "Cancel", so "Use password" does not occur); `Err(AuthFailed)` any other error or `cryptoMatches == false`. `RestoreKey`: `Ok(cipher)`, and `Ok(null)` for every other outcome, errors included, because every failure falls back to the password (DEC-84). It prompts only when the route would be `Slot` (lock on, usable, `Live`; `RestoreKey` also requires the reminder not due, and counts it as not due while the `kekBroken` flag is set, DEC-86); otherwise `Ok(null)` without a prompt. After a KEK failure of the restore's password question with a live slot, the status host calls `reportPasswordKekFailure()` before `slotPrompt(RestoreKey)`, so the restore asks for the fingerprint even when the reminder is due, and an `Ok(null)` there ends the restore with `Err(KeyUnavailable)` (db-lifecycle.md). Raises no gate event |
| `val slotOffer: StateFlow<SlotOffer?>`, `suspend fun offerSlotSetup()`, `suspend fun createSlot(): AppResult<Boolean>`, `dismissSlotOffer()` | The re-creation offer. `offerSlotSetup()` (E5) runs `reconcileLock()`, then sets `slotOffer` to `Invalidated` (`Dead`) or `Missing` (`None`) when the conditions of security.md § 7.8 hold (mode `PASSWORD`, lock on, biometric usable), otherwise leaves it null. `createSlot()` returns `Ok(false)` without a prompt while `slotOffer` is null; otherwise it prepares a wrap cipher (`KeyVault.biometricArmCipher()`), prompts once, and on success calls `BiometricSlotWriter.createBiometricSlot(cipher)`; `Ok(true)` created, `Ok(false)` cancelled, `Err` from cipher preparation (`AuthFailed`) or from `createBiometricSlot`. The offer is cleared in every outcome. `dismissSlotOffer()` clears it ("Not now"). Never persisted |
| `suspend fun reconcileLock()` | Stale-slot removal while the lock is off (`biometricSlotStatus() != None` → `removeBiometricSlot()`) and the `PASSWORD`-mode auto-off (no secure lock screen: one `SettingsStore` edit `app_lock = false, lock_auto_off_notice = true`, then `removeBiometricSlot()`); failures are ignored. Called only from `offerSlotSetup()`, so it runs only after a verified password |
| `val kekNotice: StateFlow<Boolean>`, `reportKekBroken()`, `dismissKekNotice()` | The kekBroken notice (security.md § 7.3): E2 calls `reportKekBroken()` for `UnlockResult.Key(kekBroken = true)`, the controller itself for `Verified(kekBroken = true)`, and `reportPasswordKekFailure()` sets it too. Each also sets the in-memory `kekBroken` flag (not cleared by `dismissKekNotice()` and never before the process ends), under which `unlockRoute()` and `slotPrompt(RestoreKey)` ignore a due reminder for the rest of the process and a password route without a usable strong biometric over a `Live` slot carries `KeyStoreFailed` (DEC-86; scope: security.md § 7.3). The shell shows the notice in `Ready` with "Back up now" and "OK" (`dismissKekNotice()`); its text points to "Forgot password?" and it never starts the fingerprint reset itself (DEC-63) |
| `suspend fun reportPasswordKekFailure(): Boolean` | Called by E2 (password path) and E4 when the Keystore layer of the password slot failed, and outside the gate by `MaintenanceStatusHost` for `NeedsBiometricUnlock(afterKekFailure = true)` (the KEK failure of a Restore-from-`Corrupt` password question). Returns true when the fingerprint can replace the password, that is when the slot is live: mode `PASSWORD`, lock on and `KeyVault.biometricSlotStatus() == Live` (no Keystore operation; a stale slot with the lock off does not count); it has then set `kekNotice` and `kekBroken`, and the effect raises `KekFailedWithSlot` (T59, T60), or the host calls `slotPrompt(RestoreKey)`. Returns false otherwise (no live slot), and the effect raises `KeyFailed` (T23 at cold start, T50 over an open database), or the host answers `answerBiometric(null)`, which ends the restore with `Err(KeyUnavailable)`. Never prompts; owned by [security.md § 7.3](security.md#73-applockcontroller-and-mainactivity) |
| `ExternalCallbacks` members | `beforeLaunch` and `afterResult` arm and clear the one-shot re-lock exemption; `onCredentialResult` handles the `CredentialConfirm` result (`RESULT_OK` → `onUnlocked()`, or `Ok(Done)` for a `DisableLock` confirmation) ([Result launchers](#result-launchers-and-process-death), security.md § 7.6, § 7.7) |

Prompt handling per screen, all on the main thread, KeyVault calls on `dispatchers.io`:

| Screen | Route | Prompt | Success | Unavailable, invalidated or "Use password" | Other errors |
|---|---|---|---|---|---|
| `PromptToOpen` | `Slot` | `strongPrompt` with `PromptRequest.crypto` = the `SlotCipher` of `KeyVault.biometricUnlockCipher()`, negative "Use password" | `cryptoMatches == true`: `onSlotUnlocked(cipher)` (T42); `cryptoMatches == false`: `onAuthError(LockError(LockError.SLOT_FAILED, null))` (T11) | `onPasswordRequired(reason, fingerprintUsable, counters)` (T45); never the device credential | `onAuthError` as security.md § 7.4 |
| `PromptToOpen` | `Biometric` | `strongPrompt`, no `CryptoObject` | `onUnlocked()` (T9) | device credential (Q7) | `onAuthError` |
| `Prompt` | `Slot` | as `PromptToOpen` | `KeyVault.verifyBiometric(cipher)`: `Verified` → `onUnlocked()` (T10); `SlotFailed(invalidated = true)` → `onPasswordRequired(FingerprintChanged, false, counters)` (T46); `SlotFailed(invalidated = false)` and `KeyFailed` → `onAuthError(LockError(LockError.SLOT_FAILED, null))` (T11; the database stays open, "Use password" stays); a `kekBroken` result also calls `reportKekBroken()` | `onPasswordRequired(…)` (T46) | `onAuthError` |
| `Prompt` | `Biometric` | `strongPrompt`, no `CryptoObject` | `onUnlocked()` (T10) | device credential (Q7) | `onAuthError` |
| `Prompt`, `PromptToOpen` | `Password(reason, fingerprintUsable)` | none | — | `onPasswordRequired(reason, fingerprintUsable, counters)` at once (T45, T46) | — |
| `Prompt`, `PromptToOpen` | `PLAIN`/`DEVICE` `AutoOff` (no secure lock screen) | none | — | writes `app_lock = false` and `lock_auto_off_notice = true`, then `onUnlocked()` (T9, T10) | — |
| `PromptWithoutKey` | always `Biometric`, from `routeOverKeyUnavailable` (over `Locked(KeyUnavailable)`, `Locked(Maintenance(_, KeyUnavailable))` and `Locked(Maintenance(Erase, …))`) | `strongPrompt`, no `CryptoObject` | `onUnlocked()` (T10) | device credential (Q7); `AutoOff` → `onUnlocked()` after writing the setting | `onAuthError` |
| `Password`, `Content`, `Other` | — | no unlock prompt; `Content` may show the re-creation prompt of `createSlot()` when the user taps "Set up" | — | — | — |

- **Where invalidation is detected.** `unlockRoute()` starts no Keystore operation. The unwrap cipher of `KeyVault.biometricUnlockCipher()` is prepared when a prompt is about to be shown, and that one operation is the one the prompt authorizes, so no operation is begun and abandoned. Its `Invalidated` result becomes `onPasswordRequired(FingerprintChanged, false)`, `NoSlot` becomes `onPasswordRequired(None, false)` and `Unavailable` becomes `onPasswordRequired(None, true)` for this unlock, and no prompt is shown (security.md § 7.4, Cipher preparation row). Prompt error codes 1, 9, 11, 12 and 15 on the `Slot` route become `onPasswordRequired(None, false)` (`onPasswordRequired(KeyStoreFailed, false)` while the `kekBroken` flag is set and the slot is `Live`, DEC-86), code 13 ("Use password") `onPasswordRequired(None, true)`; codes 5, 10 and 3 are silent `AuthError`s.
- **Repeated failures.** Two consecutive `Unavailable` or `SlotFailed(invalidated = false)` results for the same slot make `KeyVault` judge it dead ([security.md § 3.7.4](security.md#374-failure-classes-of-the-slot)); `biometricSlotStatus()` then reads `Dead` and the existing T45 and T46 apply with reason `FingerprintChanged`.
- `counters` are `failures` and `retryAt` from `SettingsStore.snapshot()`, passed through `UnlockBackoff.effectiveRetryAt(failures, stored, appTime.elapsedMillis())`, so the password screen shows a running countdown at once.
- `BiometricGateway.authenticate(request: PromptRequest, onResult: (PromptResult) -> Unit)`: `PromptRequest(title, credentialOnly, negativeText, crypto: SlotCipher? = null)`; the gateway wraps `crypto` in a `BiometricPrompt.CryptoObject`. `PromptResult.Succeeded(cryptoMatches: Boolean)` is true when `result.cryptoObject?.cipher` is the very cipher of `request.crypto` (always true without crypto); `PromptResult.Error(code, message)` carries the BiometricPrompt code. `cryptoMatches == false` counts as `SlotFailed(invalidated = false)`, never as an unlock. `FakeBiometricGateway` reports `cryptoMatches = true` unless a test scripts otherwise ([security.md § 7.3](security.md#73-applockcontroller-and-mainactivity), [testing.md § 7](testing.md#7-fixtures-and-fakes)).
- `AuthPurpose` = `Unlock`, `EnableLock`, `DisableLock`, `SetUpSlot`, `ResetPassword`, `RestoreKey`. Only `Unlock` prompts raise gate events; the others return to their caller.
- Verify at setup: an operation of an auth-per-use Keystore key (`setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`, API 28–29 validity −1) that BiometricPrompt authorized stays usable from the callback until E2's `doFinal` on `dispatchers.io` a few milliseconds later, also when other Keystore operations run in between, on the owner's phone (`BiometricSlotDeviceTest`, [testing.md § 2.1](testing.md#21-biometric-slot-tests-what-runs-where)). The API 28–29 behavior (validity −1) is not verified on a device (DEC-15, accepted risk).

### Transition table

Rows are independent: for one (state, event) pair at most one row matches, because the guards are mutually exclusive. T35 applies to every pair that no other row (T1–T34 with their sub-rows, T36–T60) matches. Notation in the To column: a state named without arguments takes the defaults (`false`, `0`, `null`, `PasswordReason.None`); `Opening()` is `Opening(viaPassword = false, lockPending = false, transientError = false, viaSlot = false)` and `Opening(viaPassword)` or `Opening(viaSlot)` sets that flag only; `copy(f = v)` keeps every other field of the current state; `Locked(X)` is `Locked(Resume.X)` with `authError = null` and `password = null`. In the From column, `Locked(X)` matches every `Locked` whose resume is `X`, with or without a `PasswordEntry`, unless the guard says otherwise.

| # | From | Event (raised by) | Guard | To | Effects |
|---|---|---|---|---|---|
| T1 | — | `start()` (DoroApplication) | | Starting(false) | E1 |
| T2 | Starting | StartupLoaded (E1) | mode = Password, route ≠ Slot | NeedsPassword(failures, retryAt, couldNotOpen = false, reason, fingerprintUsable), both taken from a `Password` route (`None`, false for any other route) | |
| T3 | Starting | StartupLoaded (E1) | mode ≠ Password, lock on, not authenticated | Locked(Open) | |
| T4 | Starting | StartupLoaded (E1) | mode ≠ Password, and lock off or authenticated | Opening() | E2() |
| T5 | Starting | StartupKeyFailed (E1) | lock on, not authenticated | Locked(KeyUnavailable) | |
| T6 | Starting | StartupKeyFailed (E1) | lock off or authenticated | KeyUnavailable | |
| T7 | NeedsPassword | PasswordSubmitted (gate UI) | elapsedNow ≥ retryAt | Opening(viaPassword, reason and fingerprintUsable copied) | E2(password) |
| T8 | NeedsPassword | MaintenanceStarted(Erase) (DbMaintenance) | | Maintenance(Erase, NeedsPassword) | |
| T9 | Locked | Unlocked (AppLockController) | resume = Open | Opening() | E2() |
| T10 | Locked | Unlocked (AppLockController) | resume ≠ Open, password = null, the resume is not a fingerprint reset (`Resume.Maintenance(m)` with `m.resetFrom ≠ null` never unlocks this way: it shows progress and nothing prompts, DEC-63) | the resume state (Ready, the wrapped Maintenance, Corrupt or KeyUnavailable) | |
| T11 | Locked | AuthError(error) (AppLockController) | | copy(authError = error) | |
| T12 | Locked(Ready, Corrupt or KeyUnavailable) | MaintenanceStarted(op, fingerprintReset) (DbMaintenance) | not fingerprintReset; or fingerprintReset with op = KeyChange, resume = Ready and password = p ≠ null (the password screen of `Locked(Ready)`, DEC-63) | Locked(Maintenance(op, origin = that resume)); for a fingerprint reset Locked(Maintenance(KeyChange, Ready, resetFrom = p.copy(checking = false, checkFailed = false))): the `Locked` carries no `PasswordEntry`, the progress screen shows and nothing prompts (`lockScreenOf` = `Other`) | |
| T13 | Locked(Ready) | CorruptAtRuntime (DatabaseHolder) | | Locked(Corrupt) | E3 |
| T14a | Locked(Maintenance m) | MaintenanceFinished(Reopened) (DbMaintenance) | not m.corruptPending, m.resetFrom = null (a fingerprint reset: T57) | Locked(Ready) | |
| T14b | Locked(Maintenance m) | MaintenanceFinished(Reopened) (DbMaintenance) | m.corruptPending | Locked(Corrupt) | E3 |
| T14c | Locked(Maintenance) | MaintenanceFinished(ReopenFailed(Corrupt)) (DbMaintenance) | | Locked(Corrupt) | E3 |
| T14d | Locked(Maintenance) | MaintenanceFinished(ReopenFailed(Transient)) (DbMaintenance) | | Locked(Open) | E3 |
| T14e | Locked(Maintenance m) | MaintenanceFinished(Aborted) (DbMaintenance) | m.resetFrom = null (a fingerprint reset: T58) | Locked(m.origin as resume); Locked(Corrupt) with E3 if `m.origin = Ready` and `m.corruptPending` | |
| T15 | Locked(Maintenance) | MaintenanceFinished(Restart) (DbMaintenance) | | Starting(false) | E1 |
| T16 | Opening | Opened (E2) | | Ready, or Locked(Ready) if lockPending | E5 if the result is Ready and (viaPassword or viaReset) |
| T17 | Opening | OpenFailed(Corrupt) (E2) | | Corrupt, or Locked(Corrupt) if lockPending | E3 |
| T18 | Opening | OpenFailed(Transient) (E2) | viaPassword | NeedsPassword(failures = 0, retryAt = 0, couldNotOpen = true, reason, fingerprintUsable) | E3 |
| T19 | Opening | OpenFailed(Transient) (E2) | not viaPassword | copy(transientError = true) | E3 |
| T20 | Opening | Retry (gate UI) | transientError, not viaSlot | copy(transientError = false, viaReset = false): the reset key was zeroed by the failed open, so a retry after T55 is an ordinary password-less E2 and ends in `PasswordNeeded` (T22) in `PASSWORD` mode | E2() |
| T21 | Opening | PasswordRejected(failures, retryAt) (E2) | | NeedsPassword(failures, retryAt, couldNotOpen = false, reason, fingerprintUsable) | |
| T22 | Opening | PasswordNeeded(failures, retryAt, reason) (E2) | | NeedsPassword(failures, retryAt, couldNotOpen = false, reason) | |
| T23 | Opening | KeyFailed (E2) | | KeyUnavailable, or Locked(KeyUnavailable) if lockPending | |
| T24 | Opening | LockNow (AppLockController) | | copy(lockPending = true) | |
| T25 | Ready | LockNow (AppLockController) | | Locked(Ready) | none: Room stays open |
| T26 | Ready | CorruptAtRuntime (DatabaseHolder) | | Corrupt | E3 |
| T27 | Ready | MaintenanceStarted(op) (DbMaintenance) | | Maintenance(op, Ready) | |
| T28a | Maintenance m | MaintenanceFinished(Reopened) (DbMaintenance) | not m.corruptPending | Ready | E5 if m.op is KeyChange (a Restore keeps the DEK and the slot, DEC-84) |
| T28b | Maintenance m | MaintenanceFinished(Reopened) (DbMaintenance) | m.corruptPending | Corrupt | E3 |
| T29a | Maintenance | MaintenanceFinished(ReopenFailed(Corrupt)) (DbMaintenance) | | Corrupt (as T17) | E3 |
| T29b | Maintenance | MaintenanceFinished(ReopenFailed(Transient)) (DbMaintenance) | | Opening(transientError = true) (as T19) | E3 |
| T30 | Maintenance | MaintenanceFinished(Restart) (DbMaintenance) | | Starting(true) | E1 |
| T31a | Maintenance m | MaintenanceFinished(Aborted) (DbMaintenance) | m.origin = Ready (defensive) | Ready, or Corrupt with E3 if `m.corruptPending` | |
| T31b | Maintenance | MaintenanceFinished(Aborted) (DbMaintenance) | origin = Corrupt | Corrupt | |
| T31c | Maintenance | MaintenanceFinished(Aborted) (DbMaintenance) | origin = KeyUnavailable | KeyUnavailable | |
| T31d | Maintenance m | MaintenanceFinished(Aborted) (DbMaintenance) | origin = NeedsPassword, m.resetFrom = null (an Erase; a fingerprint reset: T56) | Starting(false) | E1 (reads the persisted failures and retryAt again) |
| T32 | Maintenance m | LockNow (AppLockController) | m.origin ≠ NeedsPassword | Locked(Maintenance(m)) | none: the operation continues |
| T33 | Corrupt, KeyUnavailable | MaintenanceStarted(Restore or StartFresh) (DbMaintenance) | | Maintenance(op, this state) | |
| T34 | Corrupt, KeyUnavailable | LockNow (AppLockController) | | Locked(this state) | |
| T35 | any | any pair no other row matches | | unchanged | debug log of class names only; a `PasswordSubmitted` is zeroed; a `SlotUnlocked` cipher is dropped |
| T36 | Starting(authenticated = true) | LockNow (AppLockController) | | Starting(false) | |
| T37 | Opening | CorruptAtRuntime (DatabaseHolder) | | as T17 | E3 |
| T38 | Maintenance | CorruptAtRuntime (DatabaseHolder) | | copy(corruptPending = true) | |
| T39 | Locked(Maintenance m) | CorruptAtRuntime (DatabaseHolder) | | Locked(Maintenance(m.copy(corruptPending = true))) | |
| T40 | Starting | StartupLoaded (E1) | mode = Password, route = Slot | Locked(Open) | |
| T41 | NeedsPassword | MaintenanceStarted(KeyChange, fingerprintReset) (DbMaintenance: the fingerprint reset with origin NeedsPassword) | fingerprintReset | Maintenance(KeyChange, NeedsPassword, resetFrom = PasswordEntry(reason, fingerprintUsable, failures, retryAt)) from the current fields (`couldNotOpen` is not kept) | none: the progress screen; nothing prompts (DEC-63) |
| T42 | Locked(Open) | SlotUnlocked(cipher) (AppLockController) | | Opening(viaSlot) | E2(cipher) |
| T43 | Opening | Retry (gate UI) | transientError, viaSlot | Locked(Open) | none: the auto-prompt asks for the fingerprint again |
| T44 | Opening | SlotUnlockFailed (E2) | | Locked(Open, authError = LockError(SLOT_FAILED, null)) | none: the error state suppresses the auto-prompt (`showsError`, [AppLockController and BiometricGateway](#applockcontroller-and-biometricgateway)); "Unlock" or the next ON_START prompts again |
| T45 | Locked(Open) | PasswordRequired(reason, fingerprintUsable, failures, retryAt) (AppLockController) | | NeedsPassword(failures, retryAt, couldNotOpen = false, reason, fingerprintUsable) | |
| T46 | Locked | PasswordRequired(reason, fingerprintUsable, failures, retryAt) (AppLockController) | resume is Ready, a Maintenance that is not a fingerprint reset (`resetFrom = null`), or Corrupt; no check running (`password?.checking ≠ true`) | copy(authError = null, password = PasswordEntry(reason, fingerprintUsable, failures, retryAt)); over a `Maintenance` resume the entry renders with the field disabled ([Rendering](#rendering)) and T47 refuses submission | |
| T47 | Locked, password = p | PasswordSubmitted (gate UI) | resume is Ready or Corrupt, not p.checking, elapsedNow ≥ p.retryAt | copy(password = p.copy(checking = true, checkFailed = false)) | E4(password) |
| T48 | Locked, password = p | PasswordVerified (E4) | p.checking | the resume state (Ready or Corrupt) | E5 if the resume is Ready |
| T49 | Locked, password = p | PasswordRejected(failures, retryAt) (E4) | p.checking | copy(password = p.copy(failures = failures, retryAt = retryAt, checking = false)) | |
| T50 | Locked, password = p | KeyFailed (E4) | p.checking | copy(password = p.copy(checking = false, checkFailed = true)) | none: the database stays as it is, nothing is closed |
| T51 | NeedsPassword | UseFingerprintChosen (gate UI) | fingerprintUsable | Locked(Open) | |
| T52 | Locked, password = p | UseFingerprintChosen (gate UI) | p.fingerprintUsable, not p.checking | copy(password = null) | none: `onGateScreenChanged` re-arms the prompt |
| T53 | Maintenance m | LockNow (AppLockController) | m.resetFrom ≠ null, m.origin = NeedsPassword (a fingerprint reset from NeedsPassword; T32's guard excludes this origin, so the two rows never both match) | copy(lockPending = true) | none: the reset continues; its success then ends locked (T55, T16) |
| T54 | Locked(Maintenance m) | LockNow (AppLockController) | m.resetFrom ≠ null | Locked(Maintenance(m.copy(lockPending = true))) | none: the reset continues; its success then ends locked (T57) |
| T55 | Maintenance m | MaintenanceFinished(OpenWithNewKey) (DbMaintenance) | m.resetFrom ≠ null, origin = NeedsPassword | Opening(viaReset, lockPending = m.lockPending) | E2(resetKey): `DbMaintenance.takeResetKey`, then `DatabaseHolder.open` |
| T56 | Maintenance m | MaintenanceFinished(Aborted) (DbMaintenance) | m.resetFrom = e ≠ null, origin = NeedsPassword | NeedsPassword(e.failures, e.retryAt, couldNotOpen = false, e.reason, e.fingerprintUsable) | none: nothing changed, so no E1; the password screen it started from shows the failure dialog |
| T57 | Locked(Maintenance m) | MaintenanceFinished(Reopened) (DbMaintenance) | m.resetFrom ≠ null, not m.corruptPending (otherwise T14b) | Ready, or Locked(Ready) if m.lockPending | E5 if the result is Ready (the reset counts as the unlock, DEC-63) |
| T58 | Locked(Maintenance m) | MaintenanceFinished(Aborted) (DbMaintenance) | m.resetFrom = p ≠ null | Locked(Ready, password = p); Locked(Corrupt) with E3 if `m.corruptPending` | none: back on the password screen the reset started from, whose status host shows the failure dialog in lock mode (DEC-66) |
| T59 | Opening | KekFailedWithSlot (E2) | viaPassword | Locked(Open) | none: the auto-prompt asks for the fingerprint through the slot; `unlockRoute()` ignores a due reminder while `kekBroken` is set (DEC-86) |
| T60 | Locked, password = p | KekFailedWithSlot (E4) | p.checking | copy(password = null) | none: `onGateScreenChanged` re-arms the prompt on the slot route, also when the reminder is due (DEC-86) |

Rules that complete the table:
- **Passwords and ciphers.** `dispatch` zeroes the `CharArray` of every `PasswordSubmitted` that produces no `E2(password)` or `E4(password)` effect (a failed guard of T7 or T47, or T35); E2 and E4 zero the one they consume. `dispatch` drops the `SlotCipher` of every `SlotUnlocked` that produces no `E2(cipher)` (T35); the cipher holds no key material, and an unused authorization expires with it.
- **Exemptions that look like gaps.** `LockNow` in `NeedsPassword` is ignored (T35): the database is closed and the password screen is itself the lock, whether it came from a cold start, from "Use password" or from the reminder. `LockNow` in `Maintenance` with origin `NeedsPassword` is ignored for an Erase (T32's guard): no data is open during it, and it ends in `Restart` or `Aborted`, which run E1 again. A fingerprint reset from `NeedsPassword` instead records it in `lockPending` (T53), because its success opens the database and would otherwise reach `Ready` while the app is in the background. `LockNow` in `Locked` is ignored (already locked), also on its password screen: the `PasswordEntry` stays, so "Use password" and the reminder survive a trip to the background. The one exception is a `Locked` over a running fingerprint reset, which counts as the unlock: T54 records the lock in `lockPending`, so its success ends in `Locked(Ready)` instead of `Ready` (T57).
- **Fresh locks.** Every row whose To is `Locked(X)` (not a `copy`) starts without `authError` and without `PasswordEntry`, so the lock screen comes back and `AppLockController` resolves the route again. This matters after T12, T13 and T14a–e: the operation that ran may have changed the key mode or dropped the slot, so a password entry from before it must not be reused. A late E4 result for such a state finds no `checking` entry and is ignored (T35). The one row that restores a password entry is T58, a fingerprint reset that failed before its commit: nothing changed (the old key slot and the old biometric slot are still committed, unless the slot-released key was rejected and its key deleted), so the password screen it started from is the right screen to return to (DEC-63), with `checking` and `checkFailed` cleared. A reset that ended otherwise without reaching `Ready` (T14b–d, T15) brings back a fresh lock.
- **Locking while starting.** T24, T36 and T32 keep a lock that arrives during startup or an operation. `Starting(true)` skips the biometric lock in `PLAIN` and `DEVICE` mode only because the user authenticated before the recovery screen; if the app is left meanwhile, T36 withdraws the exemption so T3 or T5 applies when E1 finishes. `LockNow` in `Starting(false)` needs no row of its own: T35 may drop it, because `StartupLoaded` or `StartupKeyFailed` applies T3, T5 or T40 (or T2, the password screen) whenever the lock is on, and AppLockController raises `LockNow` only while the lock is on. Nothing is therefore "remembered" in `Starting`; the lock setting is read again when E1 finishes.
- **Contract with DbMaintenance.** Every `MaintenanceStarted` is followed by exactly one `MaintenanceFinished`. `MaintenanceStarted` is raised only when every dialog of the operation has been answered, and a restore raises it only after the user's confirmation; its earlier steps run while the gate keeps its state ([db-lifecycle.md § Operation frame](db-lifecycle.md#operation-frame)). The fingerprint reset raises `MaintenanceStarted(KeyChange, fingerprintReset = true)` only after its cipher unwrapped the DEK, from `NeedsPassword` (T41) or behind the password screen of `Locked(Ready)` (T12), never from a `Locked(Open)`, `Locked(Corrupt)` or `Locked(KeyUnavailable)`; every other operation raises it with `fingerprintReset = false`. From `NeedsPassword` the reset ends with `OpenWithNewKey` (committed: the new raw key waits in DbMaintenance for E2, T55), `Aborted` (nothing changed, T56) or `Restart` (commit outcome unknown, T30). From `Ready` it ends with `Reopened` (committed and reopened, T57), `Aborted` (failed before the commit, Room reopened on the old generation, T58), `ReopenFailed(kind)` (T14c, T14d) or `Restart` (T15). `Reopened`: Room is open again on the committed generation. `ReopenFailed(kind)`: no generation could be reopened. `Restart`: data replaced or the commit state unknown, run E1. `Aborted`: a recovery operation, an Erase (origin `NeedsPassword` or `Corrupt`) or a fingerprint reset (origin `NeedsPassword`) from a closed database stopped before it changed anything, or a fingerprint reset from `Ready` that failed before its commit and reopened the old generation (the only `Aborted` from origin `Ready`; it always arrives in `Locked(Maintenance)`, T58). Every other operation from `Ready` ends `Reopened`, `ReopenFailed` or `Restart`; T31a handles an `Aborted` in an unlocked `Maintenance` from `Ready` defensively. `OpenWithNewKey`: only the fingerprint reset from `NeedsPassword`, after `Committed`.
- **Notes.** In `PASSWORD` mode the gate reaches `Ready` only through a key-producing or key-checking step: a cold-start password (T7, T16), a slot unwrap (T42, T16), a password checked with the database open (T47, T48), on return from background a slot cipher that `KeyVault.verifyBiometric` accepted (T10), or a fingerprint password reset, which unwrapped the DEK through the slot and committed a newly typed password (T55 and T16, T57; DEC-63); the device credential and a plain biometric prompt never unlock it except on `PromptWithoutKey` (over `KeyUnavailable`, a `Maintenance` from `KeyUnavailable` or an Erase in progress), where no data is readable and unlocking (T10) returns only to a recovery or progress screen. `Restart` ends Start fresh, Erase (when it erased), every recovery-origin Restore and an operation whose commit outcome is unknown (a fingerprint reset included); a successful fingerprint reset ends in `Ready` (T55 and T16, T57), not in a restart. After T55 a transient open failure (T19) and `Retry` (T20, which clears `viaReset`) run E2 without the reset key, which the failed open zeroed: in `PASSWORD` mode E2 raises `PasswordNeeded` (T22) and the new password is asked; no E5 follows from that `Opening`. T29b with `Transient` from `PASSWORD` mode asks for the password again after Retry (the key was zeroed by E3): E2 raises `PasswordNeeded` (T22). T18 zeroes `failures` because a correct password already reset them; the screen shows the could-not-open message, never the wrong-password message. T43 sends a failed slot open back to the fingerprint instead, because the cipher is spent and only a new prompt can produce another.
- **Late events.** An effect reports whatever it finds after the gate has moved on; the reducer ignores events that do not match the current state (T35). E2 never runs twice at once: it starts only from T4, T7, T9, T20, T42 and T55, and E1 only from T1, T15, T30 and T31d. E4 starts only from T47, whose guard excludes a running check, so at most one runs.

### Rendering

| State | DoroRoot shows (screen designs in [ui.md § 10](ui.md#10-gate-screens)) |
|---|---|
| Starting | the held system splash at activity creation, otherwise a blank background |
| NeedsPassword | password screen with "Forgot password?" (DEC-62, DEC-63): the reason line for `reason` (DEC-74 reminder, fingerprints changed, DEC-86 key store failed), "Use fingerprint" while `fingerprintUsable` (T51), the wrong-password error when `failures > 0`, the could-not-open message when `couldNotOpen`, and the countdown with the field disabled while `AppTime.elapsedMillis() < retryAt` ([security.md § 5.3](security.md#53-backoff-dec-62)). "Forgot password?" opens the Forgot password screen, a local UI state of `GateScreen`: "Set a new password with your fingerprint" while `AppLockController.fingerprintUnlockAvailable()`, and "Erase all data and start over" |
| Locked, `password == null` | lock screen; its "Unlock" button calls `AppLockController.prompt()`, and "Use password" (`AppLockController.usePassword()`) is shown while `AppLockController.lockRoute` is `Slot`; `authError` shows its text unless silent, and `authError.code == LockError.SLOT_FAILED` shows `gate_slot_failed` ([ui.md § 10.4](ui.md#104-lock-screen)). The automatic prompt is fired by AppLockController at the activity's ON_RESUME ([security.md § 7.3](security.md#73-applockcontroller-and-mainactivity)), never from composition (DEC-65). A fresh `Locked(Maintenance(…))` (T12, T32) shows this lock screen while the operation runs; `lockScreenOf` makes it `PromptWithoutKey` for an Erase or a `KeyUnavailable` origin. Exception: a `Locked(Maintenance(m))` with `m.resetFrom != null` (a fingerprint reset started from the password screen of `Locked(Ready)`, T12) shows the maintenance progress screen "Updating encryption…" ([ui.md § 10.6](ui.md#106-maintenance-overlay-and-screen)) without "Unlock", "Use password" or "Forgot password?", and nothing prompts (`lockScreenOf` = `Other`): the reset counts as the unlock (DEC-63) |
| Locked, `password != null` | the same password screen as `NeedsPassword`, fed from the `PasswordEntry`: reason line, "Use fingerprint" while `fingerprintUsable` (T52), wrong-password error, `gate_password_check_failed` while `checkFailed` (T50; not error-styled), countdown, busy while `checking`. While the resume is a `Maintenance` the field and "Unlock" are disabled, the reason line shows the operation's maintenance text ([ui.md § 10.6](ui.md#106-maintenance-overlay-and-screen)) and "Forgot password?" is hidden (DEC-63: while an operation runs behind a locked password screen, "Forgot password?" and Erase are hidden); the operation's end brings back a fresh lock ("Fresh locks"). A running fingerprint reset never has this screen: its `Locked(Maintenance)` carries no `PasswordEntry` and shows the progress screen (row above). Otherwise "Forgot password?" offers the fingerprint reset only while the resume is `Ready`, and Erase while the resume is `Ready` or `Corrupt` |
| Opening | blank background with a delayed progress indicator (also `viaSlot` and `viaReset`); the busy password screen when `viaPassword`; an error with "Try again" when `transientError` (after a slot open it returns to the lock screen, T43) |
| Ready | `NavigationSuiteScaffold` with `NavDisplay`; the shell also renders `AppLockController.slotOffer` ([ui.md § 8.13](ui.md#813-fingerprint-set-up-offer-dec-65)) and `AppLockController.kekNotice` ([ui.md § 2.4](ui.md#24-snackbars-and-notices)) |
| Maintenance | origin `Ready` and op ≠ `Erase`: the content, hidden from accessibility services and keyboard focus, under an opaque overlay that consumes pointer input, key events and Back and holds the keyboard focus (snippet below, [ui.md § 10.6](ui.md#106-maintenance-overlay-and-screen)); an Erase from any origin and every other origin (including the fingerprint reset from `NeedsPassword`, which never prompts, DEC-63): the progress screen, never content |
| Corrupt | "Restore from backup" and "Start fresh" (DEC-85) |
| KeyUnavailable | the same two actions (DEC-86) |

```kotlin
@Composable
fun DoroRoot(graph: UiGraph = LocalUiGraph.current) {
    val gate by graph.sessionGate.state.collectAsState()   // not lifecycle-aware on purpose: a background lock is in state before the first frame
    val holder = rememberSaveableStateHolder()
    val showContent = gate is GateState.Ready ||
        (gate is GateState.Maintenance && gate.origin == Origin.Ready && gate.op != MaintenanceOp.Erase)
    if (showContent) {
        val blocked = gate is GateState.Maintenance             // origin Ready, not Erase: showContent implies it
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

An Erase from `Ready` closes Room and deletes the key-state, so its content is never composed ([db-lifecycle.md § Erase (forgotten password)](db-lifecycle.md#erase-forgotten-password)); this is stricter than DEC-90's "only", not a deviation.

Dialogs of a restore that has not been confirmed yet (checking, passwords, the fingerprint question of a restore from `Corrupt`, which comes first and comes again after a KEK failure of the database-password question with a live slot, DEC-86; confirmation) and failure dialogs are rendered by `MaintenanceStatusHost` over whatever screen is showing ([ui.md § 8.12](ui.md#812-maintenance-status-host)); the gate state does not change for them. While the gate is `Locked` the host is restricted (DEC-66): it is not composed on the lock screen or on the progress screen of a running fingerprint reset, and over a locked password screen it runs in lock mode, rendering only the fixed-text failure dialogs of a fingerprint reset or an Erase ([Threading](#threading), rotation safety).

### Invariants

1. The Room instance on the live database is first built by E2 in `Opening` and afterwards rebuilt only by DatabaseHolder's exclusive mode (DEC-81). Nothing builds it in `DoroApplication.onCreate`, `Starting`, `NeedsPassword` or `Locked(Open)`; a fingerprint prompt at `Locked(Open)` only produces a `SlotCipher`, and the unwrap runs inside E2 in `Opening`. DbMaintenance may build temporary Room instances only on staged copies under `noBackupFilesDir`, held in local variables and never stored in a property ([db-lifecycle.md § Concurrency rules](db-lifecycle.md#concurrency-rules)).
2. The content (`NavDisplay` and every composable or ViewModel that reads NoteRepository or EntityRepository) is composed only while `showContent` is true. Gate screens receive only a `GateGraph`: SessionGate, DbMaintenance, AppLockController, ExternalActivities, AppTime and AppMessages. They own no ViewModel, with one exception (DEC-90): the activity-scoped `ForgotPasswordViewModel` (`ui/gate`, obtained with `viewModel(factory = forgotPasswordFactory(graph))` in `GateScreen`, so its owner is the activity's `ViewModelStore`, [Lifetimes](#lifetimes)), which coordinates the fingerprint password reset so that `AppLockController.slotPrompt(ResetPassword)` and `DbMaintenance.resetPasswordWithBiometric` survive a rotation while the system prompt is shown ([ui.md § 8.15](ui.md#815-set-a-new-password-with-the-fingerprint-dec-63)). It is built from the `GateGraph` only (AppLockController, DbMaintenance, AppMessages), reads no repository, holds no note-derived data, has no `SavedStateHandle`, and keeps no password in a property or flow: the new password is a local `CharArray` of the running call and is handed to DbMaintenance or zeroed ([security.md § 6.2](security.md#62-fields-and-state)); its UI state (`ForgotPasswordUiState`, [ui.md § 8.15](ui.md#815-set-a-new-password-with-the-fingerprint-dec-63)) is only the busy flag, the local `Err(AuthFailed)` of the prompt (every other error is reported through `DbMaintenance.status`) and the fingerprint-availability answer of `AppLockController.fingerprintUnlockAvailable()` (`null` until it has answered), nothing else (DEC-90). Every other piece of gate state that must survive rotation lives in GateState, `DbMaintenance.status` and AppLockController's flows; password fields use `remember { TextFieldState() }` ([security.md § 6.2](security.md#62-fields-and-state)), other inputs `rememberSaveable`.
3. `Locked` never composes content, also not with a `PasswordEntry` over an open database. The "main" SaveableStateProvider and the NavEntry ViewModels survive, so unlocking returns to the exact screen (DEC-66; Verify at setup item (d) of [Keys and stacks](#keys-and-stacks)).
4. Gate state is never saved; after process death the gate starts at `Starting` (DEC-90). States, events and effects never hold a key, a DEK or a password beyond the effect that consumes it (DEC-64): the fingerprint route carries a `SlotCipher` (an authorized cipher, no key bytes) only from the prompt callback to E2, and E2 or `dispatch` drops it. Their `toString()` redacts (DEC-70).
5. The reducer is total: rows T1–T60 (with sub-rows) plus the fallback T35 cover every (state, event) pair, and each row is a JVM test ([testing.md L-18](testing.md#5-security-and-lifecycle-tests)).
6. Every state except `Ready` has a way forward that some component raises: a prompt, a retry, a recovery action or a running effect (`Starting`: E1; `NeedsPassword`: the password, "Use fingerprint", the fingerprint reset or Erase; `Locked`: the prompt, the credential result, auto-off, "Use password" or the password check, and over a running fingerprint reset its `MaintenanceFinished`; `Opening`: E2 or "Try again"; `Maintenance`: its `MaintenanceFinished`; `Corrupt`, `KeyUnavailable`: Restore and Start fresh).
7. In `PASSWORD` mode only a correct password (E2, E4) or the biometric slot (an unwrap in E2, `KeyVault.verifyBiometric` on return from background, or the unwrap that authorizes a fingerprint password reset, which counts as the unlock, DEC-63) unlocks; the device credential and a prompt without a `CryptoObject` never do, except on `PromptWithoutKey` (over `KeyUnavailable`, a `Maintenance` from `KeyUnavailable` or an Erase in progress; no readable data, and unlocking returns only to a recovery or progress screen). AppLockController enforces this through the route; the reducer cannot see the key mode and does not need to.
8. The route, the biometric availability and the 14-day reminder are decided outside the reducer (E1 and AppLockController) and arrive only as event payloads (`StartupLoaded.route`, `PasswordRequired`), so `reduce` stays pure and free of the wall clock.

## Threading

| Work | Runs on |
|---|---|
| Composition, `SessionGate.dispatch`, window flags, launcher calls | main (`Dispatchers.Main.immediate`) |
| Room DAO suspend functions and Flows | Room's own executors; never wrapped in `withContext`, never `allowMainThreadQueries` |
| E1, E2, E3, E4, DatabaseHolder open and close, DbMaintenance SQLCipher connection, file copies, SAF and FileProvider streams, Keystore calls | `dispatchers.io` |
| PBKDF2 derivation and calibration (DEC-60), heavy list mapping | `dispatchers.default` |
| BiometricPrompt callbacks, `AppLockController` decisions and listener calls, E5 | main; the KeyVault calls they need (`biometricSlotStatus` and `isPasswordReminderDue` for `unlockRoute`, slot cipher preparation, the `doFinal` of `verifyBiometric`, `verifyPassword` in `confirmWithPassword` with PBKDF2 on `dispatchers.default`) run off main and return to main before an event is raised or a result is returned |
| DataStore | its own scope, `applicationScope + dispatchers.io` |
| Backup, restore, key change, Start fresh, Erase, every repository write, the editor's save-on-leave | `applicationScope`: survives rotation and leaving the screen |
| Observation, search debounce, one-shot reads started by the UI | `viewModelScope` |

- `applicationScope` has a SupervisorJob and is never cancelled. No handler swallows uncaught exceptions; they crash the app. Detached work uses `runDetached` ([Layering](#layering-and-data-flow), rule 6), which keeps that guarantee: an `async` whose awaiting caller is gone would lose its exception, a `launch` does not.
- Save-on-leave: the editor's save is `applicationScope.launch { val r = noteRepository.save(draft); if (r is AppResult.Err) appMessages.post(…) }` with no catch-all, so a bug still crashes while an expected `StorageFull` or `DatabaseUnavailable` reaches the user through `AppMessages` and the draft stays in the SavedStateHandle until a save succeeds ([ui.md § 6.8](ui.md#68-saving-as-the-user-experiences-it)).
- Rotation safety: long operations publish progress in `DbMaintenance.status: StateFlow` ([db-lifecycle.md § Interface and status](db-lifecycle.md#interface-and-status)), which any screen re-reads after rotation (status is not kept across process death). `MaintenanceStatusHost` (`ui/common`, [ui.md § 8.12](ui.md#812-maintenance-status-host)) renders the restore dialogs (including the fingerprint question of `NeedsBiometricUnlock`, which it answers through `AppLockController.slotPrompt(RestoreKey)` and `DbMaintenance.answerBiometric`) and every outcome; it is composed in the shell (inside `MainContent`) and on the Password, Forgot password, Corrupt and KeyUnavailable screens, because a restore resets navigation and an operation can end on a gate screen. On the password screens of `NeedsPassword` it renders without restriction. Over a locked password screen (`Locked` with a `PasswordEntry`, and the Forgot password screen reached from it) it runs in lock mode (DEC-66, [ui.md § 8.12](ui.md#812-maintenance-status-host)): it renders only the fixed-text failure dialogs of a fingerprint reset or an Erase (`Finished(KeyChange, Err(…))` and `Finished(Erase, Err(…))`, except the key-change dialog's own `WrongPassword` and `Throttled`), and every other status (the restore dialogs, every `Ok` outcome, other failures) waits unacknowledged until the gate is unlocked. On the lock screen (`password == null`) and on the progress screen of a running fingerprint reset it is not composed. `ForgotPasswordViewModel` (invariant 2 of [SessionGate](#sessiongate)) keeps the fingerprint reset's prompt and call alive across a rotation; its outcome reaches the user through this host.
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
            CompositionLocalProvider(LocalUiGraph provides container) {   // no keyboard-privacy local: always on (DEC-71)
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
    fun nowMillis(): Long                     // System.currentTimeMillis(): every *_at value, the 1-hour share sweep, the backup file date, the 14-day password reminder (DEC-74, decided in KeyVault)
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
- Logging: only `AppLog` (`core.log`) logs, with ids, counts and exception class names. `toString()` redaction for entities, UI models, key holders (`KeyState`, `KeySlot`, `PreparedKey`, `SlotCipher`), gate states and events, external requests and results, messages and drafts, the R8 log rule and the SQLCipher logger: [security.md § 10](security.md#10-logging-and-leak-hygiene-dec-70) (DEC-70). `GateEvent.PasswordSubmitted.toString()` is `"PasswordSubmitted(<redacted>)"` and `GateEvent.SlotUnlocked.toString()` is `"SlotUnlocked"`.
- Screens collect state with `collectAsStateWithLifecycle()`; the gate state in DoroRoot is the one exception ([Rendering](#rendering)).
- API-level pitfalls (compileSdk 37 against minSdk 28):
  - `MutableList.removeFirst()` and `removeLast()` bind to Java 21 methods that do not exist on API ≤ 34 (`NoSuchMethodError`). Use `removeAt(0)`, `removeAt(lastIndex)`, `removeFirstOrNull()` or `removeLastOrNull()`. The other Java 21 SequencedCollection members (`addFirst`, `addLast`, `getFirst`, `getLast`, `reversed`) carry the same risk. Verify at setup which of them Kotlin 2.4 hides; lint `NewApi` (error) catches the rest, and the boundary test additionally bans `.removeFirst()` and `.removeLast()`.
  - `java.time` is native from API 26, but `LocalDate.ofInstant`, `LocalDate.EPOCH`, `datesUntil` and `toEpochSecond` are API 34: use `Instant.atZone(zone).toLocalDate()` and `LocalDate.ofEpochDay`.
  - Never cache a `Clock`, a `ZoneId` or a formatter that carries a zone. Never put a password in `rememberSaveable` or a SavedStateHandle (DEC-64).
  - Case folding uses `Locale.ROOT` (DEC-29, [data-layer.md § Search](data-layer.md#search)).

## Performance budgets

Budgets apply to the `benchmark` build (release configuration) on the sign-off phone of [testing.md § 8](testing.md#8-performance-tests): the owner's phone (Poco F7, the only required test device, DEC-15), or the optional low-end second phone (Physical B, [testing.md § 2](testing.md#2-device-matrix)) when one is available; Plain mode, lock off, with the 10,000-note fixture (DEC-10, §10). Measurement mechanics (warm-ups, statistics, JSON output, regression gate) are defined once, in [testing.md § 8](testing.md#8-performance-tests); the table names the measurement.

| Path | Budget | Measured by |
|---|---|---|
| Cold start to first list with data | ≤ 1,500 ms median; Device mode ≤ 1,700 ms. Password mode to the password screen or to the fingerprint prompt is recorded without a budget | `adb shell am start -S -W`, then the "Fully drawn" time that `ReportDrawnWhen { !uiState.loading }` in the All list reports (read from `adb logcat -d -s ActivityTaskManager`); P-5 |
| Perspective switch | ≤ 250 ms from tab tap to the first frame that shows the target tab's rows | instrumented Compose test (P-2); `android.os.Trace` section `doro:switch` for Perfetto |
| List query at 10,000 notes | first emission ≤ 100 ms for each case of P-1 | instrumented DAO timing test on the fixture (P-1) |
| Editor open | ≤ 300 ms from row tap to editable text; ≤ 600 ms for a 200,000-character note (DEC-24) | instrumented Compose test (P-6); trace section `doro:editor` |
| Batch action on 1,000 notes | ≤ 1,000 ms per associate, set-date or delete (one transaction, DEC-36) | instrumented repository test (P-7) |

- Sign-off runs on that phone over USB with the exact budgets; each budget is asserted on the median, except the list-query budget, which is asserted on the p90 ([testing.md § 8](testing.md#8-performance-tests)). Emulator or Gradle Managed Device performance runs are optional (DEC-15); where testing.md configures them, they compare against 2 × the budget and never gate a merge or release. The Poco F7 is faster than a low-end phone, so a measurement close to its budget is treated as a finding to investigate, not as a pass with margin. Trace section names never contain user data (DEC-70).
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
| A corrupt preferences file silently turns off the biometric lock and screenshot protection and resets the unlock backoff (all keys fall back to their defaults) | Accepted for v1 (DEC-09): the keys are written only by the app and DataStore replaces files atomically; the reminder timestamp is lost (reminder due) and a leftover slot is removed by `reconcileLock()` after the next password unlock. A fail-closed handler (security flags on, a notice shown) needs a new DEC in decisions.md first |
| A person who knows the device PIN arms a slot with a newly enrolled finger while the lock is off in `PASSWORD` mode | Turning the lock on and Settings "Set up" verify the startup password first (`confirmWithPassword`); the password-free offer exists only after a password entry and is cleared when the shell leaves `Content` (DEC-65, [security.md § 7.1, § 7.8](security.md#78-slot-re-creation-prompt-dec-65)) |
| A finger enrolled later by someone who knows the device PIN opens `PASSWORD` mode | Every fingerprint unlock in `PASSWORD` mode, also on return from background with the database open, goes through the `CryptoObject` of the biometric slot, whose key an enrollment change invalidates (DEC-60); the device credential never unlocks `PASSWORD` mode ([Unlock routes](#unlock-routes-q8), invariant 7) |
| A user who always unlocks with the fingerprint forgets the startup password | The 14-day reminder (DEC-74), the fingerprint password reset while the slot works (DEC-63), and "Back up first?" before a password is set (DEC-73) |
| The authenticated slot cipher is lost or no longer usable between the prompt and E2 | E2 runs right after `SlotUnlocked`; a slot failure other than invalidation returns to the lock screen with an error and no automatic prompt (T44, `showsError`), never to KeyUnavailable, and "Use password" stays available; Verify at setup item of [AppLockController and BiometricGateway](#applockcontroller-and-biometricgateway) |
| API 28–29 users whose strong biometrics are gone cannot unlock | The `CredentialConfirm` result is delivered to AppLockController in any gate state ([Result launchers](#result-launchers-and-process-death)); the API 28–29 branch is covered by lint `NewApi`, JVM tests of the branching logic and review, never by a device run (DEC-15, accepted risk; [security.md § 7.6](security.md#76-device-credential-fallback-q7)) |
| Publishing on Google Play would require targetSdk 36 (not planned: the app is installed directly, DEC-01) | Edge-to-edge, predictive back and no orientation locks from day one, so the change is one line (DEC-01) |
