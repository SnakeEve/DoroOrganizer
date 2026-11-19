# SPEC — Doro Organizer

Build contract, caveman per [FORMAT.md](FORMAT.md). Truth order: [docs/decisions.md](docs/decisions.md) > [stakeholder-notepad-spec.md](stakeholder-notepad-spec.md) > `docs/*.md` > SPEC. Conflict → decisions win; amend SPEC via spec skill. Detail lives in docs; SPEC cites doc §.
Verification note: no phone overnight → tasks T5–T21 verify by build + lint + JVM tests + compiled instrumented tests; device runs ∈ T22.

## §G

Offline Android notepad: notes ↔ 0..n persons & 0..n projects, 5 perspectives (All, Individual, Project, Date, Orphan), checklist flag, optional encryption + fingerprint lock, manual backup/restore; owner runs it on own Poco F7.

## §C

- Kotlin 2.4 · AGP 9.5 · Gradle wrapper (9.9.0 cached locally) · JDK 21 · Compose M3 · Navigation 3 · Room 2.8 (KSP) · SQLCipher `net.zetetic:sqlcipher-android` 4.20 · androidx.biometric 1.1.0 · DataStore Preferences; versions ! re-verified at setup (DEC-02)
- minSdk 28 · targetSdk 35 · compileSdk 37 (DEC-01)
- single `:app` module, pkg `com.doro.organizer`, manual DI `AppContainer`, ⊥ Hilt/Koin (DEC-03, DEC-06)
- ⊥ network: ⊥ INTERNET perm, ⊥ analytics/crash/ads SDK; ⊥ Auto Backup & D2D (DEC-68, DEC-69)
- CLI only (gradlew, adb), ⊥ Android Studio, ⊥ emulator required; device tests on owner phone (DEC-15)
- SQLCipher-touching tests ! instrumented; JVM tests pure Kotlin; ⊥ mocking lib, ⊥ Robolectric (DEC-12)
- git local commits only, ⊥ push; commit per §T: `T<n>: <goal>` + §V cites
- every task: implement → build/lint/tests green → independent review → verified fixes → commit

## §I

```
cmd: `./gradlew :app:assembleDebug` → debug APK (`com.doro.organizer.debug`)
cmd: `./gradlew :app:installDebug` → install on phone
cmd: `./gradlew :app:testDebugUnitTest` → JVM tests
cmd: `./gradlew :app:connectedDebugAndroidTest` → instr tests on phone, wipes `.debug` app only
cmd: `./gradlew :app:lintRelease` → lint, `NewApi` = error
file: `databases/doro-<generation>.db` → live DB, SQLCipher, plain = empty key (DEC-80)
file: `noBackupFilesDir/db-state.json` → key-state, AtomicFile: format version, generation, key slot, ? biometric slot (DEC-80, security.md §3.5)
file: `doro-organizer-backup-YYYY-MM-DD.db` → plain SQLite | SQLCipher 4 passphrase file (DEC-83)
intent: SAF `CreateDocument("application/octet-stream")` mode "wt" → backup; `OpenDocument("*/*")` → restore (DEC-83, DEC-84)
intent: `ACTION_SEND` via non-exported FileProvider over `cacheDir/share/` (DEC-83)
perm: allowlist only (security.md §9.3); INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE `tools:node="remove"`
pragma: `application_id = 0x444F524F`; `user_version` = Room version (DEC-30)
```

## §V

V1: ⊥ user data (note content, person/project fields, search text, passwords, backup names/URIs) ∈ logs | exception messages, any build; entity & UI-model `toString()` redact (DEC-70)
V2: SQL only via Room DAO + bound params; ⊥ string-built SQL; LIKE input escape `\` `%` `_` + `ESCAPE '\'` (DEC-29)
V3: SQLCipher only engine; plain key = `ByteArray(0)`; ⊥ framework SQLite helpers; ⊥ `minimumSupportedVersion`; ⊥ delete DB w/o explicit user action (DEC-08, DEC-85)
V4: DB access only via `DatabaseHolder`; ⊥ cached DAO; live Room built only in `SessionGate` `Opening` (DEC-81, DEC-90)
V5: ∀ multi-row write → 1 transaction (DEC-36, spec §9)
V6: ∀ list → `ORDER BY checked ASC, CASE WHEN checked=0 THEN updated_at END DESC, CASE WHEN checked=0 THEN created_at END DESC, CASE WHEN checked=1 THEN checked_at END ASC, id DESC`, defined once; `date` ⊥ sorts (DEC-23)
V7: orphan = ⊥ `note_persons` row & ⊥ `note_projects` row; derived, ⊥ stored (DEC-27)
V8: check → `checked=1, checked_at=now`, `updated_at` unchanged; uncheck → `checked=0, checked_at=NULL, updated_at=now`; both guarded (DEC-22)
V9: `updated_at` bump ∈ DEC-21 matrix only; ⊥ bump on check, open, no-op save, entity delete
V10: time only via `AppTime` (epoch ms UTC, monotonic `elapsedMillis()`); note `date` = `LocalDate.toEpochDay()` of picked day; M3 picker ms → `ZoneOffset.UTC` (DEC-20, DEC-26)
V11: `content` NOT NULL, empty = `""`, ≤ 200,000 chars; list preview = `substr(ltrim(content, <ws>), 1, 300)` (DEC-24)
V12: "(No data)" = empty-list state & blank-row preview; spec strings verbatim (DEC-25, DEC-55)
V13: overdue ⇔ `checked=0 & date ≠ NULL & date < today` (DEC-26)
V14: `content_fold = content.lowercase(Locale.ROOT)` ∀ row, written by single path; search on `content_fold` (DEC-29)
V15: ⊥ destructive migration, ⊥ `allowDataLossOnRecovery`; ∀ change to released schema → migration + test; released schema JSON committed & frozen (DEC-14, DEC-88)
V16: keys & passwords ⊥ hard-coded, ⊥ logged, ⊥ prefs/`SavedStateHandle`/nav keys; `CharArray`/`ByteArray` zeroed after use; DEK zeroed after raw-key build (DEC-64)
V17: key modes PLAIN | DEVICE | PASSWORD; DEK random 32 B; Keystore AES-256-GCM KEK; password KEK PBKDF2-HMAC-SHA256 ≥ 600,000; SQLCipher raw key (DEC-60)
V18: ∀ key change → new DEK + export + swap; commit = atomic key-state rewrite; crash before commit → old DB live (DEC-61, DEC-80, DEC-87)
V19: biometric slot only PASSWORD mode & lock on; auth-per-use `BIOMETRIC_STRONG` TEE key, invalidated by enrollment; damaged | dead slot → password route, ⊥ KeyUnavailable (DEC-60)
V20: unlock fingerprint-first; PASSWORD w/o usable slot | reminder due | "Use password" → startup password; PLAIN/DEVICE w/o biometric → device credential (DEC-62, DEC-65)
V21: slot creation outside post-password offer ! startup password first, then fingerprint (DEC-65)
V22: reminder: PASSWORD & fingerprint unlock & 14 d since last successful password → next unlock asks password; clock backwards | lost timestamp → due (DEC-74)
V23: wrong password ⊥ corruption; backoff after 5 fails: 30 s doubling ≤ 5 min, monotonic clock (DEC-62)
V24: backup ! export, ⊥ byte copy; encrypted DB → backup password required; plaintext backup → warning first (DEC-83)
V25: restore ! stage → validate (application_id, user_version ≤ current, identity hash, integrity_check, foreign_key_check, R-1..R-5) → migrate staged copy → convert to current key config → confirm → swap; ⊥ change security settings except KeyUnavailable origin → PLAIN (DEC-84)
V26: corruption | KeyUnavailable → screen w/ Restore & Start fresh; file kept until user chooses (DEC-85, DEC-86)
V27: FLAG_SECURE whole window when on, before first frame; Compose `SecureFlagPolicy.Inherit`; recents screenshot off API 33+ when lock | encryption on; window policy closed while settings | key mode unknown (DEC-66, DEC-67)
V28: merged manifest ⊥ network perms; exported = launcher activity + DUMP-protected `ProfileInstallReceiver` only; FileProvider ⊥ exported (DEC-68)
V29: `allowBackup="false"`; `dataExtractionRules` exclude ∀ domains for cloud-backup & device-transfer; `fullBackupContent` exclude all (DEC-69)
V30: autofill ⊥ ∀ windows; note/person/project/search fields always `IME_FLAG_NO_PERSONALIZED_LEARNING` (DEC-71)
V31: hard deletes only; ⊥ history, undo, dedup (spec §1)
V32: ∀ UI text ∈ `strings.xml`; §4 content descriptions verbatim (DEC-55)
V33: `ui` ⊥ import `data.db`, `DatabaseHolder`, `KeyVault` (DEC-03)
V34: release R8 strips ∀ `android.util.Log`; ⊥ `println`, ⊥ `printStackTrace`; SQLCipher logger off (DEC-70)
V35: release APK passes `zipalign -c -P 16 -v 4` & every LOAD segment align ≥ `0x4000` via `:app:checkReleasePageSize` (Kotlin ELF reader; `readelf` ⊥ installed) (DEC-14)
V36: data-clearing tests only on `.debug` | `.qa` | `.benchmark` ids (DEC-15)

## §T

id|status|task|cites
T1|x|Gradle skeleton: wrapper (copy from local project, Gradle 9.9.0), version catalog (verify DEC-02 vs known-good local catalogs), `:app` SDK levels, build types debug `.debug` / release R8 `proguard-android-optimize.txt` / `qa` / `benchmark`, manifest hardening + `data_extraction_rules.xml` + `backup_rules.xml`, `.gitattributes` LF, `.gitignore`, `local.properties` uncommitted, empty `MainActivity : FragmentActivity` Compose shell w/ edge-to-edge, base `strings.xml`, SQLCipher dep + `System.loadLibrary`; assembleDebug + lintRelease + testDebugUnitTest green (architecture.md §Stack and versions, security.md §9)|V28,V29,V34,V35,V36,I.cmd,I.perm
T2|x|verification gates as local Gradle tasks/scripts: merged-manifest permission allowlist, 16 KB check, banned dependencies, release log-stripping check, schema JSON gate w/ `config/released-schema-versions.txt` (testing.md §9)|V15,V28,V34,V35
T3|x|test infra: `src/sharedTest`, `FakeAppTime`, `TestDatabases`, `assertDbInvariants`, `ArchitectureTest` (I-41), `MigrationCoverageTest` + `SchemaJsonTest` (I-48), `DoroAppRule` (testing.md §3 item 1, §7)|V4,V15,V33
T4|x|`AppTime` + `EpochDays` + date math + overdue rule, JVM tests across zones & DST (architecture.md §Time)|V10,V13
T5|x|entities + `AppDatabase` v1: schema export, `application_id` callback, explicit index names, redacted `toString()`, `SchemaTest` (data-layer.md §Entities)|V1,V3,V7,V11,V14,V15,I.pragma
T6|x|DAOs: perspective query, canonical order, filters, search escaping, calendar marks, select-all ids, picker queries + I-01–I-04, I-10–I-16, I-24–I-27, I-43, I-47; deferred parts: I-13 via production toggles & I-25 `content_fold` on edit → T7, I-43 impact-count & delete plans → T8, I-03 20 holder close/reopen cycles → T9 (data-layer.md §Read model, §Search)|V2,V6,V7,V12,V14
T7|x|`NoteRepository` writes: save insert/update + link diff, guarded check/uncheck, `updated_at` matrix, content cap, note delete + I-05–I-09, I-28–I-30, I-42 (a)–(c), I-13 via production `NoteDao` check/uncheck, I-25 edit updates `content_fold`; also delivered `loadNote`, `hasAnyData` and batch associate/setDate/deleteNotes (data-layer.md §Write rules, §Deletes)|V5,V8,V9,V10,V11
T8|x|`EntityRepository` + links + batch ops (ops landed in T7; T8 adds their tests and `NoteRepository.selectIds`/`observeRows`) + delete impact counts + I-17–I-20, I-21/I-23 repo parts, I-42 (d), I-43 impact-count & note/junction delete plans (data-layer.md §Deletes, §Batch operations)|V5,V7,V31
T9|x|`SettingsStore` + `DatabaseHolder` open/verify/observe/exclusive + failure classification + L-13, L-17, holder parts of L-03/L-05/L-12, I-03 20 close/reopen cycles; L-04 → T10; L-05 KeyUnavailable cases (a)–(n) → T10/T14; gate halves of L-05/L-12 → T14 (db-lifecycle.md §DatabaseHolder, §Failure classification)|V3,V4,V26
T10|x|`KeyVault`: key-state file + parser, KEK, PBKDF2, raw key, unlock/verify, backoff, reminder timestamp + L-04 (`KeyVaultTest`, `KeyMaterialTest`), L-27, L-28 live raw-key half (`CipherSettingsTest`, `@PageSizeSmoke`), `KeyVaultTest`, KeyVault-side KeyUnavailable cases of L-05 (`KeyStateJsonTest`, `KeyVaultKeyUnavailableTest`); pulled fwd: `UnlockBackoffTest` + vault part of `UnlockBackoffPersistenceTest` (L-02 ← T14), `PasswordReminderTest` + vault part of `PasswordReminderPersistenceTest` (L-37 ← T11); L-28 backup half (passphrase file, `kdf_iter` 256000) → T12; biometric `KeyVault` members + `BioKeystore` + `eraseState` deletes `doro_bio_v1` + kekBroken KEK replacement in `prepare` (§4.3) → T11; wire `AppContainer.keyVault` → T14 (security.md §3, §5; DEC-75, DEC-76)|V16,V17,V22,V23,I.file
T11|x|biometric slot in `KeyVault` (biometric members of §3.6, `SlotCipher`/`SlotCipherResult`/`WrapResult`/`BioSlotStatus`, `UnlockResult.SlotFailed`, `BioKeystore` seam, `eraseState` deletes `doro_bio_v1`, `brokenKekGeneration` + kekBroken KEK replacement in `prepare` §4.3; ← T10) + `UnlockPolicy` + `BiometricKeySpecTest` (JVM + device halves), `UnlockPolicyTest`, `KeyVaultBiometricTest` (L-03 slot paths); pulled fwd: `BiometricAvailability` (§7.1 ← T14); `PasswordReminderTest` → done in T10 (security.md §3.7, §5; DEC-77–DEC-79)|V19,V20,V21,V22
T12|x|`DbMaintenance`: export pipeline, backup SAF + share, key changes, slot-only commits, startup reconciliation, crash-safety mechanics (commit order, syncs, read-back, `Unknown`, reconciliation) + L-06–L-08, L-14, L-16, L-47, L-28 backup half (exported passphrase file, `kdf_iter` 256000; ← T10), L-13 DbMaintenance & Documents rows (`MaintenanceClassificationTest`; ← T9), mechanics of L-34, L-36, L-41 (testing.md §3 item 6); pulled fwd: `PasswordPolicy` + `PasswordPolicyTest` (L-03 policy part), `BackupFileProvider` + `file_paths.xml` (security.md §9.1); restore/Start fresh/Erase members + L-09–L-11 → T13; L-15, L-16 E1-event half, L-47 (a), `AppContainer` wiring → T14; L-08 `BackupUiTest` → T19 (db-lifecycle.md; DEC-92–DEC-94)|V18,V24,V26,I.file,I.intent
T13|.|restore pipeline + validation + staged migrations + recovery origins + L-09–L-11, L-13 restore/Start fresh/Erase rows, mechanics of L-24, L-43, L-51; ← T12: `restore`, `answerPassword`, `answerBiometric`, `answerConfirmation`, `cancelRestore`, `startFresh`, `erase`, `Needs*` statuses, `RestorePreview`, results `Restored`/`RestoreCancelled`/`StartedFresh`/`Erased`, `DbFiles.deleteEverything`, `MARK_RESET`/`DATA_RESET` writes (reconciliation already applies marker); restore export reuses `exportUnderExclusive`, `COPY_INPUT` progress via `copyWithProgress` (db-lifecycle.md §Restore, §Corruption and KeyUnavailable recovery)|V25,V26
T14|.|`SessionGate` + `AppLockController` + `BiometricGateway` (`BiometricAvailability` done T11) + `ExternalActivities` + `WindowPolicy` + L-02 gate/screen parts (`UnlockBackoffTest` + vault persistence done T10), L-18, L-19, L-29, L-31, L-32, L-38–L-40, L-42, L-44, L-46, L-48, gate halves of L-05/L-12 & gate-side KeyUnavailable cases; wire `AppContainer.databaseHolder` (`DefaultDatabaseHolder` + SessionGate corruption listener + AppMessages flow-problem listener) & production `DbFiles.forApp(context)` & `AppContainer.keyVault` = `DefaultKeyVault.create(app, settingsStore, appTime, dispatchers)` (← T10) & `AppContainer.faultHook` + `dbMaintenance` (`DefaultDbMaintenance`) + ON_START `sweepShareFiles` observer (← T12), check `DbFiles.forApp` & `DefaultKeyVault.create` share `noBackupFilesDir`; ← T12: L-15 (debug `CrashTestService` in `:crashtest`, `KillingFaultHook`, `CrashSafetyTest` keyed CS-nn + meta-test + 4 matrix invariants), L-16 E1-event half, L-47 (a) cold-start slot-keyed open, L-03 gate & screen parts (`onSaveInstanceState` sentinel, effect zeroing via recording `FakeKeyVault`, `PasswordScreenTest`) (architecture.md §SessionGate, security.md §7, §8)|V4,V20,V21,V22,V27
T15|.|UI shell: theme, `NavigationSuiteScaffold`, Navigation 3 stacks, gate screens (ui.md §1, §2, §10)|V12,V27,V32,V33
T16|.|note list, five perspectives, filters, search, `MonthGrid` (ui.md §3, §4)|V6,V12,V13
T17|.|selection mode + batch actions (ui.md §5)|V5,V31
T18|.|note editor, `EntityPicker`, dialogs + I-08 JVM part (`ContentCap` truncation at 200,000 / 199,999 on a high surrogate, notice, truncation on load) (ui.md §6–§8)|V9,V11,V12
T19|.|Settings: backup, restore, encryption & password, biometric lock, screenshot protection, backup offer + L-08 `BackupUiTest` (← T12); ! L-15 green before any Backup/Restore/encryption setting visible (testing.md §3 item 6) (ui.md §8, §9)|V24,V25,V27
T20|.|keyboard/autofill + window policy wiring + strings audit (security.md §8, §11; ui.md §11)|V27,V30,V32
T21|.|release hardening: all verification gates green, perf tests in place (testing.md §8, §9)|V34,V35
T22|.|phone: installDebug + full connectedDebugAndroidTest on Poco F7, fix failures; record device outcomes of open "Verify at setup" items (TEMP_STORE, 600 KB cursor value, ART vs JVM whitespace incl. U+180E, M-27 native cipher log, CorruptionTest tampered-page expectations, Room 2.8.5 close() vs running query, Room absolute-path name, db-lifecycle.md § Open sequence items (1)–(5), L-17 flow-drain log line, T12: empty-blob `ATTACH … KEY` → plaintext target, attached target journal mode, 4-arg `FileProvider.getUriForFile`, `DocumentsContract.deleteDocument` reaching `RecordingDocumentsProvider.call`, `KeyChangeTest` unusable-KEK technique, `BackupTest` corrupt-source page overwrite) (needs phone)|V36,V34,I.cmd
T23|.|manual QA checklist on Poco F7 (testing.md §10) (needs phone)|V20,V27

## §B

id|date|cause|fix
