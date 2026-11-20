# Doro Organizer

<p align="center"><img src="docs/images/doro.png" alt="Doro" width="220"></p>

A fully offline Android notepad for analysts and team leads. Each note can be linked to any number of persons and projects, or to none, and the notes are browsed through five perspectives: **All**, **Individual**, **Project**, **Date** and **Orphan**.

> **Status: in development.** The non-UI core is built and verified on a real phone (Poco F7, Android 16): data layer, encryption, fingerprint unlock, backup and restore, crash safety and the lock gate (tasks T1–T14). The screens (T15–T21) are next. [SPEC.md](SPEC.md) §T shows every task and its status.

## What the app does

- **Notes with links.** A note links to persons and projects. A note with neither is shown as "Orphaned".
- **Five perspectives.** All notes; per person; per project; by date, with a month calendar; and orphans.
- **Find things fast.** Filters, search, done/undone, "Overdue" for open notes past their date, and "(No data)" for empty notes.
- **Private by design.**
  - No internet permission at all. No analytics, crash reporting or ads.
  - Android Auto Backup and device-to-device transfer are off.
  - Encryption is optional: none, a device-held key, or your password (SQLCipher).
  - Fingerprint first, with the password as fallback.
  - Screenshot protection, and the keyboard never learns what you type.
- **Backups you control.** Back up to a file or share it, and restore it, also on a new phone.

The full product spec is [stakeholder-notepad-spec.md](stakeholder-notepad-spec.md).

## Tech stack

| Area | Choice |
|---|---|
| Language | Kotlin 2.4.21 (JVM target 17) |
| Build | Gradle 9.9.0 wrapper, Android Gradle Plugin 9.5.1, KSP; built and tested from the command line, no Android Studio needed |
| UI | Jetpack Compose (Material 3, BOM 2026.09.00), Navigation 3 |
| Data | Room 2.8.5 on SQLCipher 4.20.0 (`net.zetetic:sqlcipher-android`) |
| Security | AndroidKeyStore, PBKDF2-HMAC-SHA256, AES-GCM, androidx.biometric 1.1.0 |
| Settings | DataStore |
| SDK levels | minSdk 28, targetSdk 35, compileSdk 37 (minor 2) |
| DI | Manual (`AppContainer`); no Hilt or Koin |

## Build it

You need:

- **JDK 21**, which runs Gradle. The app compiles to Java 17 bytecode.
- **Android SDK command-line tools**, with these packages:
  ```
  sdkmanager "platform-tools" "platforms;android-37.2" "build-tools;36.0.0"
  sdkmanager --licenses
  ```
- **Git.** On Windows, Git for Windows, whose Git Bash runs the shell scripts in `config/`.

Then:

```sh
git clone https://github.com/SnakeEve/DoroOrganizer.git
cd DoroOrganizer
# Point Gradle at your SDK (machine-specific, never committed), for example on Windows:
echo 'sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk' > local.properties
./gradlew :app:assembleDebug
```

The first build downloads Gradle and the libraries, so it needs internet. The app itself never does.

### Everyday commands

```sh
./gradlew :app:assembleDebug               # build the debug app (com.doro.organizer.debug)
./gradlew :app:installDebug                # install it on the connected phone
./gradlew :app:testDebugUnitTest           # JVM tests (pure Kotlin)
./gradlew :app:connectedDebugAndroidTest   # instrumented tests on the phone over USB
./gradlew :app:lintRelease
./gradlew :app:verificationGates           # manifest permissions, 16 KB pages, release logs, banned dependencies
sh config/check-schemas.sh                 # Room schema JSON gate
```

## Testing on a phone

Anything that touches the encrypted database is tested on a real phone, never on the JVM or an emulator.

1. On the phone, open Developer options and turn on **USB debugging**. On Xiaomi phones (HyperOS or MIUI), also turn on **Install via USB**, which needs a Mi account, and **USB debugging (Security settings)**.
2. Connect the USB cable and allow the computer when the phone asks. `adb devices` should list the phone.
3. Run `./gradlew :app:connectedDebugAndroidTest`.

What to expect:

- The run installs four packages: the app, the test app and two test helpers. **HyperOS asks you to confirm each install and cancels it after about 12 seconds**, so watch the phone and tap *Install*. Automated taps are blocked by the system.
- The full suite has about 875 tests and takes about 45 minutes. Each test runs in a fresh, cleared copy of the debug app, and only the debug app's data is wiped. Gradle uninstalls the test packages at the end.
- Don't open the debug app while tests run.

The handoff notes cover two more loops in [dev/claude/HANDOFF.md](dev/claude/HANDOFF.md) §4:
- **By hand:** install, launch, read logs and inspect the screen over adb.
- **Fewer install popups:** a test loop that keeps the packages installed.

## Project layout

| Path | Contents |
|---|---|
| `app/` | The app (single module), package `com.doro.organizer`, with its JVM, instrumented and shared test sources |
| `app/schemas/` | Room schema JSON of every database version (committed, never edited) |
| `build-logic/` | Build-only Gradle plugin with the verification gates |
| `config/` | Gate configuration (permission allowlist, banned dependencies, native-library allowlist) and the schema scripts |
| `docs/` | Design documents (below) |
| `SPEC.md` | Build contract: invariants (§V) and ordered tasks with status (§T); encoding in [FORMAT.md](FORMAT.md) |
| `CLAUDE.md` | Working rules for Claude Code in this repo |
| `dev/claude/` | How the build is run with Claude Code: [handoff notes](dev/claude/HANDOFF.md), build workflows, docs checker |

## Design documents

| Document | Covers |
|---|---|
| [docs/decisions.md](docs/decisions.md) | Every decision (`DEC-nn`) and the owner's answers. Takes precedence over all other documents |
| [docs/architecture.md](docs/architecture.md) | Stack, packages, dependency wiring, navigation, the startup and lock state machine, threading, time |
| [docs/data-layer.md](docs/data-layer.md) | Entities, queries, list order, write rules, transactions, migrations |
| [docs/ui.md](docs/ui.md) | Screens, interactions, dialogs, strings, accessibility |
| [docs/security.md](docs/security.md) | Keys, passwords, fingerprint unlock, screenshot protection, manifest, logging |
| [docs/db-lifecycle.md](docs/db-lifecycle.md) | Database files, open and verify, backup, restore, encryption changes, corruption and crash safety |
| [docs/testing.md](docs/testing.md) | Test layers, invariant catalog, gates, device facts, manual QA checklist |

## Working on it with Claude Code

Open a terminal in the repo root and start Claude Code; it reads [CLAUDE.md](CLAUDE.md) on its own. The next task is the first open (`.`) row of [SPEC.md](SPEC.md) §T.

Today the order is:
1. **T24:** the fingerprint-slot fix, DEC-98.
2. **T15–T21:** the screens and release hardening.
3. **T22:** the full phone test run.
4. **T23:** the manual QA checklist on the phone.
