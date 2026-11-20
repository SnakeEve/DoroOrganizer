# Handoff: continuing the Doro Organizer build

For Claude Code on any machine that continues this build, and for the owner. Read [CLAUDE.md](../../CLAUDE.md) (rules) and [SPEC.md](../../SPEC.md) (§T tasks) first; this file covers how the build has been run so far, the owner's working preferences, phone testing, and the pitfalls already solved. Last updated 2026-11-21.

## 1. Where things stand

- **Done:** T1–T14, built and committed. Together they are the whole non-UI core: data layer, KeyVault, fingerprint slot, DbMaintenance (backup, key changes, restore), the crash matrix and SessionGate.
- **Verified on the owner's phone** (commit "fix: device test failures of T1-T14 on the Poco F7"):
  - 875 instrumented tests, 873 pass.
  - The 2 failures are the CS-29 rows of `CrashSafetyTest`: open bug **B3** in SPEC.md, decided as **DEC-98**, fixed by **T24**.
  - JVM tests (391), lint, `:app:verificationGates` and `config/check-schemas.sh` are green.
- **Next, in this order:**
  1. **T24:** the CS-29 fix, plus guards against test classes that JUnit silently rejects. It needs the phone at the end.
  2. **T15–T21:** the screens and release hardening. These need no phone; the instrumented tests only have to compile.
  3. **T22:** the rest of the phone runs.
  4. **T23:** the manual QA checklist, with the owner.
- **Not in SPEC yet: release signing.** Where the owner's signing key lives and how it is backed up is still open. Ask the owner before T21, then add it as a task. A lost key means updates cannot install over the app without deleting the user's notes.
- **Waiting for the owner's sign-off:** the decisions made without them while building, chiefly DEC-75–79, DEC-92–98, plus the SPEC row amendments recorded in the "docs: record Tn …" and "spec: …" commits. Offer the owner a one-page plain-language summary.

## 2. The owner's working preferences

- **Quality first.** The owner's words: "when reviewing code, I expect quality".
  - Find the root cause before changing anything.
  - Review every task through several lenses and verify each fix.
  - Every commit is green.
  - Never weaken, skip or delete a test to get green.
  - Design docs have no length caps; CLAUDE.md stays small.
- **Phone only.** All device testing happens on the owner's own phone over USB, from the command line. No emulator, no Android Studio.
- **Progress reports.** The owner often asks to "check progress with detailed progress percentage and estimation". Answer with a percentage, a step-by-step table with status and an ETA.
- **Usage budget.** The account has 5-hour and weekly usage limits, and the owner may name a stopping point ("until T14, then stop"). Honor it, keep runs resumable, and save state in notes after each stage.
- **Git.**
  - Agents commit locally and never push. Push to GitHub (private repo `SnakeEve/DoroOrganizer`) only when the owner asks.
  - Before the first commit on a new machine, set this repo's `git config user.name` and `user.email` to match the existing commits (`git log -1 --format='%an <%ae>'`), never a work address.
  - One laptop at a time: `git pull` before working, commit and push after.
- **Decisions.** If the docs are unclear or conflict, add or amend a `DEC-nn` in docs/decisions.md first (CLAUDE.md). Product behavior choices belong to the owner. Example: for CS-29 the owner chose "each slot records its key" over "accept one extra fingerprint prompt". Ask when a choice changes what the user sees or the security model.

## 3. Build pipeline (one SPEC task at a time)

Each task ran through `dev/claude/workflows/doro-build-tasks.js`:

1. **Implement** (Opus, high effort). Read the SPEC row, its cited §V invariants and doc sections, and decisions.md. Implement the task with the tests testing.md assigns to it, and get the standard verification green. Do not commit and do not edit docs. List **every** departure from the docs in `spec_issues`.
2. **Review**, three lenses in parallel: fidelity and completeness; correctness and robustness; security, data safety and code quality. Reviewers do not edit files or run Gradle.
3. **Fix.** Verify each finding, apply the correct ones and reject the wrong ones with a reason. Verification green again.
4. **Record.** Triage each departure:
   - Sound departures go into the docs or decisions.md. Scope moved to a later task goes into both SPEC rows. Commit these with a pathspec: `git commit -m "docs: record Tn implementation details" -- docs/ SPEC.md`.
   - Shortcuts go back to a code fixer.
   - Never lower the bar.
5. **Verify** (Sonnet).
   - Run the full verification from scratch.
   - Check hygiene: no `local.properties`, build outputs or IDE files are staged.
   - Check fidelity, and confirm every major finding is fixed.
   - Then flip the SPEC §T status to `x` and commit `Tn: <task text>` with every change except docs/, including the line `§V: <cites>` and the `Co-Authored-By` trailer.
   - If it refuses: one repair round, then verify again.

**Standard verification:** `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest :app:verificationGates`, plus `sh config/check-schemas.sh`, plus `node dev/claude/check-docs.js <repo>`, which must print `ALL CHECKS PASSED`.

**Running it with the Workflow tool** (ultracode):

```
Workflow({ scriptPath: "<repo>/dev/claude/workflows/doro-build-tasks.js",
           args: { root: "<absolute repo path>", tasks: ["T24"], phone: true } })
```

- **T15–T21** run with `phone: false`.
- **After a usage-limit cut,** resume with the same `scriptPath`, the same `args` and `resumeFromRunId`; finished agents replay from cache.
- **To stop at a task boundary,** pass only the tasks wanted. The script starts the next task right after a commit, so a stop request in the middle of a run needs a watch for the `Tn:` commit followed by `TaskStop`.
- **Without the Workflow tool,** follow the same five steps with subagents or the `/build` skill, one task at a time.

**Planning numbers:**
- T10–T14: 38 agents, about 6.3 h, 2.4M output tokens, 320M cache-read tokens.
- T15–T21: expect about 1.3–1.5× that.
- The device-fix run after the first phone test: 7 agents, 0.4M output tokens, 60M cache reads.

## 4. Phone testing (the owner's POCO F7)

**The phone:** Android 16 (API 36), HyperOS 3.0, arm64-v8a, 4 KB pages. It has a secure lock screen and an enrolled fingerprint. In Developer options, three switches are on: **USB debugging**, **Install via USB** (needs a Mi account) and **USB debugging (Security settings)**.

**Ask the owner before any phone action.** That covers installing, running tests and changing settings. The owner has to be at the phone anyway, to tap the install popups.

### 4.1 Build, install, run and debug the app (the cumbuckets loop)

This is the same loop as the owner's puja_android ("cumbuckets") project. adb is not on PATH; from Git Bash:

```bash
adb="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"     # Windows default SDK location
"$adb" devices                                             # the phone must show as "device"
./gradlew :app:assembleDebug
"$adb" install -r app/build/outputs/apk/debug/app-debug.apk   # owner taps Install; retry if cancelled (below)
"$adb" shell am start -n com.doro.organizer.debug/com.doro.organizer.MainActivity
"$adb" logcat -c                                           # then use the app, then:
"$adb" logcat -d --pid=$("$adb" shell pidof com.doro.organizer.debug)   # this app's log only
```

**Looking at the screen:**
- Screenshot protection is off by default, so `"$adb" exec-out screencap -p > build/phone-screen.png` works. With protection on, screenshots come out black (`FLAG_SECURE`, DEC-67).
- The UI tree works either way:
  ```bash
  MSYS_NO_PATHCONV=1 "$adb" shell uiautomator dump /sdcard/window_dump.xml && MSYS_NO_PATHCONV=1 "$adb" pull /sdcard/window_dump.xml build/window_dump.xml
  ```
  Its `bounds` give the coordinates to tap.
- Use `build/` for these files, because it is git-ignored. Delete the dump on the phone afterwards: `MSYS_NO_PATHCONV=1 "$adb" shell rm /sdcard/window_dump.xml`.

**Driving the app:**
- `"$adb" shell input tap X Y`, `input text`, `input swipe` and `input keyevent KEYCODE_BACK` work, because "USB debugging (Security settings)" is on. HyperOS blocks injected taps only on its own install popup.
- Check the effect of each tap. In cumbuckets, the first tap after a dump was sometimes lost.
- A fingerprint prompt needs the owner's real finger.

**Release checks without the phone:** `./gradlew :app:verificationGates` covers the merged-manifest permissions, 16 KB alignment, release log stripping and banned dependencies.

### 4.2 Install popups and retries

There are two HyperOS behaviors on this phone, and both end in `INSTALL_FAILED_USER_RESTRICTED`:
- **This repo (2026-11-19/20):** a confirmation popup with about a 12 s countdown appears for every install. The owner taps *Install*.
- **cumbuckets (2026-11-10/15):** the Security app sometimes cancelled an install within about 45 ms. Retrying `adb install -r` worked, after up to five tries. An *update* of an installed app once went through while the phone was locked, but a *new* package needed the phone unlocked.

So: ask the owner to unlock the phone and watch it, then retry up to about five times. Never try to automate the popup: the system ignores injected taps on it, by design.

### 4.3 Instrumented tests through Gradle (verified here)

**Install popups:** each Gradle connected run installs four packages (the app, the test app, the Orchestrator, test services), so it shows **four popups** (section 4.2). Tell the owner before each run. Gradle uninstalls all four at the end, so the next run asks again; section 4.4 avoids that.

**Full suite:** about 875 tests in about 46 minutes. The Orchestrator runs each test in its own process with `clearPackageData`.

**Splitting runs (agent commands time out at 10 minutes):**
- Size each run to about 8 minutes.
- `CrashSafetyTest` has about 260 rows at 3–8 s each; key-change rows take about 7.5 s. Split it by flow with `tests_regex`.
- Targeted runs:
  ```
  ./gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.doro.organizer.data.FooTest,com.doro.organizer.session.BarTest#method"
  ./gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.tests_regex=<regex over class#method>"
  ```

**Long runs:**
- Run them in the background.
- The phone's log buffer is only 2 MiB, so stream logcat to a file: run `adb logcat -c`, then `adb logcat -v threadtime -b main,system,crash > file &`.
- To track progress, count `TestRunner: run started: 1 tests`. The first line, `run started: N tests`, is the Orchestrator's listing pass.

**Results:**
- Results go to `app/build/outputs/androidTest-results/connected/debug/` and each run overwrites them, so copy them aside.
- Look for `*initializationError*` files. JUnit rejects a whole class if a `@Test` returns a value, for example `fun x() = runBlocking { … }`. AGP then leaves the class out of the XML and Gradle still exits 0. Write `runBlocking<Unit>`. T24 adds guards for this.

**Don't interfere:**
- Nobody opens the debug app during a run: it starts `MainActivity` inside the test process and fails the running test.
- Activity tests need the screen on: `adb shell settings put global stay_on_while_plugged_in 7`. Set it back to `0` afterwards, the owner's default.
- Touch only `com.doro.organizer.debug`, its test app and the two androidx.test helpers. `/data/local/tmp` holds the owner's own files (Shizuku, scripts); leave them alone.

### 4.4 Instrumented tests without reinstalling (cumbuckets flow; try it, not yet verified here)

`connectedDebugAndroidTest` installs four packages and uninstalls them at the end, so **every** run costs four popups. cumbuckets avoids that by keeping the packages installed and running tests with `am instrument`.

This app needs two adaptations:
- Its runner is `com.doro.organizer.testing.DoroTestRunner`.
- Its tests expect the Orchestrator to clear the app's data after every test (`clearPackageData`). So run them through the Orchestrator, not straight through the runner.

```bash
adb="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
"$adb" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# once per phone: the Orchestrator and its shell service (versions in gradle/libs.versions.toml and AGP's test-services)
"$adb" install -r ~/.gradle/caches/modules-2/files-2.1/androidx.test/orchestrator/1.6.1/*/orchestrator-1.6.1.apk
"$adb" install -r ~/.gradle/caches/modules-2/files-2.1/androidx.test.services/test-services/1.6.0/*/test-services-1.6.0.apk
# run classes or Class#method, comma-separated; each test gets a fresh process and cleared data
"$adb" shell 'CLASSPATH=$(pm path androidx.test.services) app_process / androidx.test.services.shellexecutor.ShellMain am instrument -r -w -e targetInstrumentation com.doro.organizer.debug.test/com.doro.organizer.testing.DoroTestRunner -e clearPackageData true -e class com.doro.organizer.data.db.NoteRowTest androidx.test.orchestrator/.AndroidTestOrchestrator'
```

**Verifying it before relying on it:**
- Run one small class this way and through Gradle, and compare the results. The raw output (`-r`) ends with the test counts. Failures print in full, and `println` lines go to logcat (`-s System.out:I`).
- Find out whether an `install -r` **update** of an installed package passes without a popup on HyperOS 3.0. If it does, a code change costs at most two popups, and test-only changes cost one.
- Afterwards, uninstall what the owner doesn't want left on the phone: `"$adb" uninstall com.doro.organizer.debug.test`. Keep the app if the owner agrees.
- Record the outcome here.

## 5. Machine setup

**Install:**
- JDK 21
- Git for Windows; its Git Bash runs `config/*.sh`
- Node.js, for the docs checker
- Claude Code
- The Android command-line tools, then:
  ```
  sdkmanager "platform-tools" "platforms;android-37.2" "build-tools;36.0.0"
  sdkmanager --licenses
  ```

**Configure:**
- `local.properties` holds `sdk.dir=…`. It is machine-specific and never committed.
- adb is `<sdk>/platform-tools/adb(.exe)` and is usually not on PATH.

**If `adb devices` lists nothing:**
- Check Windows Device Manager. A Xiaomi phone showing as USB ID `VID_2717&PID_FF40` is in file-transfer mode only, meaning USB debugging is off. `PID_FF48` means file transfer plus ADB.
- If the ADB interface has no driver, install `sdkmanager "extras;google;usb_driver"`.
- Accept the "Allow USB debugging?" prompt on the phone for each new computer.

**First build:** `./gradlew :app:assembleDebug` downloads Gradle 9.9.0 and the libraries, so it needs internet. The app itself never does.

## 6. Pitfalls already solved

**Windows and shell:**
- **Git Bash path rewriting.** A bare argument like `/data/local/tmp/x` becomes a Windows path before adb sees it. Either pass the remote command as one quoted string (`adb shell "cat /data/local/tmp/x"`) or prefix the call with `MSYS_NO_PATHCONV=1`, which you need for `adb pull /sdcard/...` anyway.
- **CRLF.** Python on Windows writes CRLF unless you open files with `newline=''`. The repo is LF (`.gitattributes`); check with `git ls-files --eol`.
- **Lost backslashes.** Shell heredocs sent through the tool layer can lose backslashes. Write files with the file-writing tool instead.
- **TaskStop and MSYS loops.** `TaskStop` on a background Bash task may leave an MSYS `sh` loop running. Check `ps -ef` and kill it.

**Agents and verification:**
- **Command time limit.** Agent commands time out at 10 minutes. Size device runs, and run long builds in the background.
- **Verifier refusals.** Verifiers refuse when code departs from the docs without a record. That is why the Record stage exists. Scope splits go into the SPEC rows.

**App and test rules:**
- **Lazy settings.** `SettingsStore.settings` is shared `Lazily`. A caller outside the UI awaits `keyMode` and `settings` before deciding on a backup (DEC-94 (8)); the crash fixture uses `awaitBackupInputs`.
- **Transactions.** Open them only through `AppDatabase.transaction` (DEC-97, V37). Normalize whitespace only through `model.TextWhitespace` (DEC-24, V38), because ART's `Char.isWhitespace` counts U+180E and the JVM's does not.
- **Database handles.** Never keep a database or DAO handle across maintenance. A backup closes and reopens Room in exclusive mode, so a kept handle is closed.
- **Disk-full rollback.** On `SQLITE_FULL`, SQLite rolls the transaction back by itself, and the failing `ROLLBACK` used to hide the real error. That case is handled now; keep its tests.

## 7. Tools in this folder

| File | Use |
|---|---|
| `workflows/doro-build-tasks.js` | The per-task pipeline of section 3. Args: `root`, `tasks`, `phone`, `verifyOnly`, `prior` |
| `workflows/doro-device-fix-template.js` | Fixing a phone run's failures, area by area: sequential fixers on the one phone, then two review lenses, review fixes, and the Record stage. Replace its `AREAS` with the current failure groups. Args: `root`, `evidenceDir` |
| `check-docs.js` | `node dev/claude/check-docs.js <repo>`: links, anchors, DEC and Q references, leftover "pending decision" notes |
| `journal-report.js` | `node dev/claude/journal-report.js <run>/journal.jsonl`: one line per agent result of a workflow run |
| `usage-sum.js` | `node dev/claude/usage-sum.js <transcript workflows dir> [main transcript]`: token use per workflow run |
