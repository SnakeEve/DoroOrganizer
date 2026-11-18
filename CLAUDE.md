# Doro Organizer

Fully offline Android notepad for analysts and team leads. Notes link to any number of persons and projects (or none) and are browsed through five perspectives: All, Individual, Project, Date, Orphan. Kotlin · Jetpack Compose (Material 3) · Room on SQLCipher · minSdk 28, targetSdk 35, compileSdk 37.

**Status:** build in progress; [SPEC.md](SPEC.md) §T shows done (`x`) and open tasks.

## Source of truth

Precedence: [docs/decisions.md](docs/decisions.md) > [spec](stakeholder-notepad-spec.md) > other docs. If something is unclear or two sources conflict, do not guess: add or amend a `DEC-nn` entry in decisions.md first. Owner questions `Qn` and their answers are in decisions.md; if a new one opens, build on its stated default and keep it easy to change.

| Doc | Read when working on |
|---|---|
| [stakeholder-notepad-spec.md](stakeholder-notepad-spec.md) | Product behavior (cited as §n) |
| [docs/decisions.md](docs/decisions.md) | Anything: spec corrections, `DEC-nn` rules, open questions, component names |
| [docs/architecture.md](docs/architecture.md) | Stack, packages, DI, navigation, `SessionGate`, threading, `AppTime` |
| [docs/data-layer.md](docs/data-layer.md) | Entities, queries, list order, write rules, transactions, migrations |
| [docs/ui.md](docs/ui.md) | Screens, interactions, dialogs, strings, accessibility |
| [docs/security.md](docs/security.md) | Keys, passwords, biometric lock, screenshot protection, manifest, logging |
| [docs/db-lifecycle.md](docs/db-lifecycle.md) | Database files, open/verify, backup, restore, encryption changes, corruption |
| [docs/testing.md](docs/testing.md) | Test layers, invariant catalog, CI gates, release checklist |
| [SPEC.md](SPEC.md) | Build contract: invariants §V, ordered tasks §T with status (format: [FORMAT.md](FORMAT.md)) |

## Commands

```
./gradlew :app:assembleDebug
./gradlew :app:installDebug                # install the debug app (com.doro.organizer.debug) on the phone
./gradlew :app:testDebugUnitTest           # JVM tests, pure Kotlin only
./gradlew :app:connectedDebugAndroidTest   # instrumented, on the phone over USB; wipes only the debug app's data
./gradlew :app:lintRelease
adb logcat --pid=$(adb shell pidof com.doro.organizer.debug)   # app logs
```

## Rules that must never break

1. Offline: no INTERNET or other network permission, no network, analytics, crash-reporting or ad SDKs. Auto Backup and device-to-device transfer stay disabled.
2. No user data in logs or exception messages, in any build: note content, person/project fields, search text, passwords, backup names or URIs. Entities and UI models redact `toString()`.
3. SQL only through Room DAOs with bound parameters, never string-built. Escape LIKE input (`\`, `%`, `_`) and use `ESCAPE '\'`.
4. SQLCipher is the only SQLite engine (plain mode = empty key). Never use the framework SQLite helpers: they silently delete files they consider corrupt. Nothing deletes the database without an explicit user action.
5. Database access only through `DatabaseHolder`. Never cache a DAO. The live Room instance is built only when `SessionGate` reaches `Opening`.
6. Every multi-row write runs in one transaction.
7. Every list uses the single canonical `ORDER BY` (DEC-23). The note `date` never affects order.
8. Orphan = no person link and no project link. Always derived, never stored.
9. Check sets `checked=1, checked_at=now` and leaves `updated_at` alone. Uncheck sets `checked=0, checked_at=NULL, updated_at=now`. Both are guarded updates (DEC-22).
10. Time comes only from `AppTime` (epoch millis, UTC). A note date is `LocalDate.toEpochDay()` of the picked day; convert Material date-picker millis with UTC.
11. No destructive migration and no data-loss recovery flags. Every change to a released schema version ships a migration and its test; the schema JSON of every released version is committed and never changed.
12. Keys and passwords are never hard-coded, logged, or stored in preferences, `SavedStateHandle` or navigation keys. Hold them in `ByteArray`/`CharArray` and zero them after use.
13. Hard deletes only. No history, undo or deduplication.
14. All text comes from `strings.xml`. Spec strings are verbatim: "(No data)", "Orphaned", "Overdue" and the §4 content descriptions.

## Conventions

- One `:app` module, package root `com.doro.organizer`. `ui` never imports `data.db`, `DatabaseHolder` or `KeyVault`. Manual DI through `AppContainer`; no Hilt or Koin.
- Use the component names listed in decisions.md (`SessionGate`, `DatabaseHolder`, `DbMaintenance`, `KeyVault`, `NoteRepository`, …).
- Anything that touches SQLCipher is tested with instrumented tests on the owner's phone, never on the JVM or Robolectric; no emulator, no Android Studio needed (DEC-15). No mocking library; write fakes.
- A test that pins the default of an open owner question ends its name with the question id (`…_Qn`).
- Every change ships with its tests; a bug fix ships with a test that fails without the fix.
