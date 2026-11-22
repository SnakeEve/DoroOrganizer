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

- **Done:** T1–T22 and T24, built and committed locally; nothing is pushed yet.
  - T1–T14: the whole non-UI core: data layer, KeyVault, fingerprint slot, DbMaintenance (backup, key changes, restore), the crash matrix and SessionGate.
  - T15–T21: the UI shell and gate screens, the note list with its five perspectives, selection mode, the editor and picker, Settings, keyboard privacy with `SecureDialogWindow` on every dialog, and release hardening.
  - T24: the CS-29 fix (DEC-98).
  - T22 (2026-11-19 to 2026-11-22, the owner's Poco F7; evidence in the git-ignored `build/t22-*` folders): every instrumented test ran on the phone, every failure was fixed at its root cause, and every open "Verify at setup" item of the T22 row has a recorded outcome or moved to T23 with a reason. What it found and decided: DEC-103 (the release-like test APKs and their derived keep rules), DEC-104 (undo right after opening a note), DEC-105 (very tall notes), DEC-106 (the stored preview and id-only links for P-1, Q15), DEC-107 (Room's `close()`, the missing T9 flow drain in `DatabaseHolder.close()`, two setup-probe corrections, the leftover KEK) and DEC-108 (perf runs on a cool phone, approved by the owner).
- **Final runs (2026-11-22):**
  - `device-tests`: build/t22-full-4 on `1aee456` (T22's final code without the later content-capture check): 1,197 testcases in 154 classes, 0 failures, 1 skipped by assumption (DEC-107 (4)); every `CrashSafetyTest` row (260 + 2 + 2, L-15). Earlier passes: build/t22-full-2 (1,144/0) and build/t22-full-3 (1,158/0).
  - `release-smoke` on `qa`: build/t22-smoke-final2, 2/2, rules `21b4062e…`.
  - `perf` on `benchmark`: build/t22-perf-final3 on the final benchmark build, from a cool phone (40.4 °C), 7/7, rules `bb89cbdd…`, six JSON files and `seed-10k.db`. P-1 All p90 83.7 ms, search 76.9 ms. A run on a warm phone failed P-1 (build/t22-perf-final2); interleaved, cool-gated runs (build/t22-p1ab) showed the code was not the cause, hence DEC-108.
  - `real-biometric`: 3/3 with the owner at the sensor (build/t22-realbio).
  - The content-capture check of `WindowSecurityTest` (security.md § 8): not observable on this phone. On the Poco F7 (API 36) the system gives the debug app's `MainActivity` no `ContentCaptureManager`, with or without the opt-out, so the check skips by assumption (build/t22-cc-red without the opt-out, build/t22-cc-1, -2 and -3 with it; every other test of those runs passed). On a device that offers content capture it asserts the opt-out, after starting the session with `FLAG_SECURE` already cleared and waiting for the session's answer.
  - Green on `d8c8f2f` (the last code change since is test code and docs): 662 JVM tests, `:app:lintRelease`, `:app:verificationGates` with build-logic 99/0, `config/check-schemas.sh`, `dev/claude/check-docs.js`, and the debug, `qa` and `benchmark` test APKs build.
- **Next: T23**, with the owner and the phone: the manual QA checklist (testing.md § 10), M-27 on the `qa` build, L-10 over the M-20 fixtures, the visual check of the icons drawn offline in T16–T19, and the setup checks T22 moved into the M-items (M-01, M-10, M-11, M-18 with the one-word 200,000-character note, M-21, M-22). Every perf run starts with `sh dev/claude/phone-cooldown.sh` (DEC-108).
- **Waiting for the owner:**
  - Sign-off of DEC-103, DEC-104, DEC-105, DEC-106 (Owner, Q15) and DEC-107 (Owner, Q16). DEC-108 is approved (sign-off item 10).
  - Open questions Q13 (touch targets), Q14 (a line-break cap), Q15 (the list speed design, default A built) and Q16 (whether a failed sync of the key-state file should stop the change; recommended: yes). Each runs on its stated default.
  - **Release signing** (not in SPEC yet): where the signing key lives and how it is backed up. It must be settled before any release: a lost key means updates cannot install over the app without deleting the user's notes.
- **SPEC tidy-up, for the spec skill** (FORMAT.md lets a build change only the status cell; full list with sources in the T22 close-out result):
  1. T22 row: drop its "still open" list (all recorded, M-27 moved to T23) and name what T22 delivered: the full runs, fix round 1, DEC-103 to DEC-108, the 13 setup probes and L-22 `WindowSecurityTest`, the L-17 close tests; add V4, V11, V25 and V27 to its cites.
  2. T19 row: the full L-15 pass happened (build/t19-l15, then every T22 full run), so "full L-15 pass after T24 → T22" no longer holds.
  3. §B: B3 is fixed by T24 (DEC-98); add T22's device-found bugs as rows: the saved-state size (DEC-37, DEC-57), the undo after opening (DEC-104), the tall note (DEC-105) and the missing close drain (DEC-107 (2)).
  4. V25: R-1..R-6 (DEC-106). V11: the preview is stored in `notes.preview`, written only with the content, and lists never read content (INV-13). Optional: V4 with the close and exclusive drains (DEC-81, DEC-107), V18 with Q16.
  5. T23 row: name the M-items T22 moved there and the P-1 sign-off of M-18.
  6. §I: the `device-tests` command needs the `notAnnotation` filter (testing.md § 9), as CLAUDE.md now shows.
- **Lessons from T22's session** (also in section 6 where they recur):
  - A killed VS Code (exit 143 at 08:53 on 2026-11-22, cause unknown) ends `run_in_background` jobs; long phone jobs ran detached (`nohup … & disown`) and survived.
  - `dumpsys thermalservice` prints a stale "Cached temperatures" section before the live "Current temperatures from HAL"; read only the live one (`phone-cooldown.sh` does).
  - T22's phone helpers (a device-run script with a machine-wide phone lock, a Gradle-busy check, logcat capture and result copying) lived in the session's scratchpad, not in the repo; section 4.3 has the commands they wrapped.

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
| `phone-cooldown.sh` | `sh dev/claude/phone-cooldown.sh [limit_C=44] [max_wait_min=15]`: waits until the phone's hottest live CPU sensor is at or below the limit (DEC-108). Run it before every perf job and archive its line. Exit 0 cool, 1 still warm, 2 no reading |
| `usage-sum.js` | `node dev/claude/usage-sum.js <transcript workflows dir> [main transcript]`: token use per workflow run |
