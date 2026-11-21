# Handoff: continuing the Doro Organizer build

For Claude Code on any machine that continues this build, and for the owner. Read [CLAUDE.md](../../CLAUDE.md) (rules) and [SPEC.md](../../SPEC.md) (§T tasks) first; this file covers how the build has been run so far, the owner's working preferences, phone testing, and the pitfalls already solved. Last updated 2026-11-21.

## 0. Start-up checklist (every session, every machine)

1. **Sync.** Run `git pull`; the owner may have worked on another laptop. Then check that `git status` is clean, and read `git log --oneline -5`.
2. **Read.** Read this whole file, SPEC.md §T and the parts of docs/decisions.md you need. CLAUDE.md is already loaded.
3. **New machine?** Do the setup in section 5. Set this repo's `git config user.name` and `user.email` to match the existing commits: `git log -1 --format='%an <%ae>'`.
4. **Check before changing anything.** Run the standard verification (section 3) and tell the owner the result.
5. **Plan with the owner.** Propose the next tasks from section 1 ("Next") with an estimate, then follow the owner's go and stopping point. **Current plan:** T22 on the owner's mark (the phone stays attached for hours; the owner taps install prompts and does the fingerprint test), then T23 with the owner.
6. **Ground rules for the whole session:**
   - Ask the owner before any phone action.
   - Commit locally after each task, and push to GitHub only when the owner asks.
   - When asked for progress, answer with a percentage, a per-step table and an ETA.
   - Quality first: root cause before fixes, multi-lens review, never weaken or skip a test.
7. **Multi-agent runs.** The `Workflow` tool needs the owner's opt-in (the word "ultracode" in a message, or ultracode switched on for the session). Without it, run the section 3 steps with subagents, one task at a time.
8. **Before the session ends:** update section 1 to match reality (status, next steps, open items), commit, and push if the owner asks.

## 1. Where things stand

- **Done:** T1–T21 and T24, built and committed locally; nothing is pushed yet.
  - T1–T14: the whole non-UI core: data layer, KeyVault, fingerprint slot, DbMaintenance (backup, key changes, restore), the crash matrix and SessionGate.
  - T24: the CS-29 fix (DEC-98).
  - T15–T21, built 2026-11-21 without the phone: the UI shell and gate screens, the note list with its five perspectives, selection mode, the editor and picker, Settings, keyboard privacy with `SecureDialogWindow` on every dialog, and release hardening (perf tests, the release-smoke and real-biometric tests, verified open-source notices). Their instrumented tests compile but have never run on a device.
- **Verified on the owner's phone** (commit "fix: device test failures of T1-T14 on the Poco F7"):
  - 875 instrumented tests, 873 pass.
  - The 2 failures are the CS-29 rows of `CrashSafetyTest`: bug **B3** in SPEC.md, decided as **DEC-98**, fixed by **T24** (next bullet).
  - JVM tests (391), lint, `:app:verificationGates` and `config/check-schemas.sh` are green.
- **Crash suite (L-15) after T24, verified on the owner's phone 2026-11-21:**
  - Run over the T19 working tree (base `8b91fc7`, before T19's commit), because T24 changed the assertions of every `CrashSafetyTest` case and T19 touches the backup code.
  - `CrashSafetyTest` 260/260, `CrashSafetyCoverageTest` 2/2 and `CrashSafetyMoreCasesTest` 2/2 pass, in 24 minutes; `:app:checkDeviceTestResults` passed. The four CS-29 rows of B3 pass.
  - This meets T19's precondition "! L-15 green before any Backup/Restore/encryption setting visible". Results are in `build/t19-l15/` (git-ignored).
  - Still open in T22: the full-suite pass after the fixes.
- **Green after T21** (the run's final check, 2026-11-21, no phone): 624 JVM tests, lint, `:app:verificationGates`, `config/check-schemas.sh` and `dev/claude/check-docs.js`; the debug, `qa` and `benchmark` test APKs build.
- **T22 in progress (started 2026-11-21 21:05; results in the git-ignored `build/t22-*` folders):**
  - Full `device-tests` run over commit `a61649f`: 1,128 tests, 1,103 pass, 25 fail. Every `CrashSafetyTest` row passes, so L-15 is green after T24 and the sign-off batch.
  - Two of the 25 hang forever: a test that launches or rotates `MainActivity` while the scripted gate is `Starting`. `MainActivity` keeps the splash screen, which blocks the first draw, while the gate is `Starting`, so `ActivityScenario` waits for a frame that never comes (`InputPolicyTest$Fields`, `ForgotFlagTest#a_…withARotationAtEachStep`). Force-stop the debug app to let the Orchestrator move on.
  - The other 23 were diagnosed and adversarially checked; at the time of writing, 15 are test bugs, and the saved-state size (`TransactionTooLargeException` of about 808 KB with a 200,000-character draft) and fingerprint groups were still being checked.
  - `real-biometric` passed 3/3 with the owner at the sensor.
  - `release-smoke` and `perf` run no test yet: the test APKs expect library code (androidx.tracing, the Kotlin stdlib, coroutines, lifecycle) that R8 removed or finalized in the minified `qa` and `benchmark` apps (DEC-103 Verify at setup: fails as built). Planned fix: keep rules generated by R8 TraceReferences from class inputs, for `qa` and `benchmark` only.
  - Still to do: fix the 25, one full clean run, the R8 fix with `release-smoke` and `perf`, record the outcomes, then mark T22 done.
- **Next, in this order:**
  1. **T22, on the owner's mark (phone):**
     - Start from a clean build, or run `./gradlew :app:compileDebugAndroidTestKotlin --rerun` first (section 6: moved test sources can leave stale classes).
     - Run the full `connectedDebugAndroidTest`: about 860 `@Test` methods in the debug test APK, with the T15–T21 UI tests on a device for the first time, so expect failures to root-cause and fix. Then one full-suite pass after the fixes; it also closes the open L-15 item.
     - Run the before-release jobs that T21 moved here: `perf` on `benchmark`, `release-smoke` on `qa`, and `real-biometric` with the owner at the sensor. Record their Verify-at-setup outcomes (DEC-103).
     - The first install of the `.qa` and `.benchmark` apps and their test apps needs the owner's tap on HyperOS (section 4.2). Updates after that do not.
  2. **T23:** the manual QA checklist (testing.md §10) with the owner, L-10 over the M-20 fixtures, and the visual check of the icons drawn offline in T16–T19.
- **Not in SPEC yet: release signing.** Where the owner's signing key lives and how it is backed up is still open, and the owner has not been asked yet. Ask at the next chance, then add it as a task; it must be settled before any release. A lost key means updates cannot install over the app without deleting the user's notes.
- **Owner sign-off, 2026-11-21:** the owner approved the 20 decisions the build made without them (docs/decisions.md § Sign-off of build decisions). They chose the stricter or clearer option in DEC-75, DEC-77, DEC-96, DEC-99 and DEC-101 and asked for two fixes (DEC-57, DEC-59). That follow-up batch runs before T22, outside SPEC §T: FORMAT.md lets only the spec skill add a row, and this machine has none.
- **Still waiting for the owner's sign-off:** DEC-103, after T22's device runs, and the SPEC row amendments recorded in the "docs: record Tn …" and "spec: …" commits.
- **SPEC tidy-up, for the spec skill** (FORMAT.md lets a build change only the status cell): B3 still reads OPEN although T24 fixed it, and the L-15 wording in the T19 and T22 rows predates the 260/260 run above.

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
adb="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}/platform-tools/adb.exe"   # ANDROID_HOME if set, else the Windows default SDK location
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
- **Updates pass without a popup (verified 2026-11-21, HyperOS OS3.0.303.0):** the fresh install of the debug app asked; an `adb install -r` over it went through in 3 s with nobody touching the phone.

So: ask the owner to unlock the phone and watch it, then retry up to about five times. Never try to automate the popup: the system ignores injected taps on it, by design.

### 4.3 Instrumented tests through Gradle (verified here)

**Install popups:** each Gradle connected run installs four packages (the app, the test app, the Orchestrator, test services), so it shows **four popups** (section 4.2). Tell the owner before each run. Gradle uninstalls all four at the end, so the next run asks again; section 4.4 avoids that.

**Keeping the four packages installed (verified 2026-11-21, AGP 9.5.1):** put `android.injected.androidTest.leaveApksInstalledAfterRun=true` in the machine's user-level `~/.gradle/gradle.properties`, never in the repo. Gradle then leaves all four packages on the phone after a run, and later runs only update them, so they need no taps (a second `NoteRowTest` run passed hands-off). The owner taps the four popups once per phone. After that, never uninstall the packages while the owner is away: the next fresh install would wait for a tap.

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

**Perf tests this way (`benchmark` app and test APK):** `PerfRecorder.openOutput` writes through TestStorage and fails every JSON write and `exportSeedBackup` without the runner argument, so add `-e useTestStorageService true`. Gradle then does not copy the output either: pull it by hand with `MSYS_NO_PATHCONV=1 "$adb" pull /sdcard/googletest/test_outputfiles <dir>` (testing.md § 8, § 9 `perf`). The test services app also needs the all-files grant that Gradle gives it on each run (`appops set androidx.test.services MANAGE_EXTERNAL_STORAGE allow`; it stays while the app is installed).

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
- **Moved test sources and stale Kotlin output.** After test files move between `src/androidTest` and `src/androidTestDebug` (or another variant source set), the incremental `compileDebugAndroidTestKotlin` output can stay stale and drop the moved classes from the test APK; in T21 only `ArchitectureTest`'s `CrashSafetyTest` check caught it. Before a phone run after such a move, run `./gradlew :app:compileDebugAndroidTestKotlin --rerun` or a clean build. The standard verification does not compile the release-like test APKs, so a debug-only reference in `src/androidTest` breaks them silently: after such a change also run `./gradlew :app:assembleQaAndroidTest -PtestBuildType=qa` and `./gradlew :app:assembleBenchmarkAndroidTest -PtestBuildType=benchmark`, each in its own Gradle invocation (one invocation cannot select two test build types).

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
