# Handoff: continuing the Doro Organizer build

For Claude Code on any machine that continues this build, and for the owner. Read [CLAUDE.md](../../CLAUDE.md) (rules) and [SPEC.md](../../SPEC.md) (§T tasks) first; this file covers how the build has been run so far, the owner's working preferences, phone testing, and the pitfalls already solved. Last updated 2026-11-26.

## 0. Start-up checklist (every session, every machine)

1. **Sync.** Run `git pull`; the owner may have worked on another laptop. Then check that `git status` is clean, and read `git log --oneline -5`.
2. **Read.** Read this whole file, SPEC.md §T and the parts of docs/decisions.md you need. CLAUDE.md is already loaded.
3. **New machine?** Do the setup in section 5. Set this repo's `git config user.name` and `user.email` to match the existing commits: `git log -1 --format='%an <%ae>'`.
4. **Check before changing anything.** Run the standard verification (section 3) and tell the owner the result.
5. **Plan with the owner.** Propose the next tasks from section 1 ("Next") with an estimate, then follow the owner's go and stopping point. **Current plan (2026-11-26):** the backlog (except BL-10, a test flake, and BL-11, three keep rules, both found on 2026-11-26) and the owner's requests R-1 to R-4 are built (T28 to T31, section 1), the owner approved their SPEC rows and the DEC texts the builds amended (decisions.md sign-off item 14), and P-8 passed. Next, each on the owner's word (section 1, "Next"): the phone runs of BL-10's fix, the update of the owner's release app, then T23 with the owner and the phone; T27 when the owner has made the release key.
6. **Ground rules for the whole session:**
   - Ask the owner before any phone action.
   - Commit locally after each task, and push to GitHub only when the owner asks.
   - When asked for progress, answer with a percentage, a per-step table and an ETA.
   - Quality first: root cause before fixes, multi-lens review, never weaken or skip a test.
7. **Multi-agent runs.** The `Workflow` tool needs the owner's opt-in (the word "ultracode" in a message, or ultracode switched on for the session). Without it, run the section 3 steps with subagents, one task at a time.
8. **Before the session ends:** update section 1 to match reality (status, next steps, open items), commit, and push if the owner asks.

## 1. Where things stand

- **Done:** T1–T22, T24–T26 and T28–T31, built and committed; `main` is pushed to github.com/SnakeEve/DoroOrganizer (private) at the owner's request, last on 2026-11-26 with the T28–T31 commits (`git status -sb` shows any local commit not yet pushed). T23 and T27 are open: T23 needs the owner at the phone, T27 the owner's key.
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
  - Green on `d8c8f2f` (T22's final production code: the rest of T22 changed only test code, docs and a dev script; T25, T26 and the release signing changed code after it, and the checks after T25 and T26 are recorded below): 662 JVM tests, `:app:lintRelease`, `:app:verificationGates` with build-logic 99/0, `config/check-schemas.sh`, `dev/claude/check-docs.js`, and the debug, `qa` and `benchmark` test APKs build.
- **The owner's answers of 2026-11-22 are recorded:** sign-off item 11 approves DEC-103, DEC-104, DEC-105 and DEC-107; Q13 to Q16 are answered (decisions.md § Answered, no question is open); DEC-108 (perf on a cool phone) and DEC-109 (the release key's custody) are decided. The SPEC tidy-up of T22 is applied under the owner's one-time exception (B rows for T22's device-found bugs, V updates, rows T25 to T27).
- **Done after the owner's answers (2026-11-22):** T25 (Q14: a note holds at most 100,000 lines, cut on input and on load with the notice `content_lines_truncated`) and T26 (Q16: the key-state stream is synced before `AtomicFile.finishWrite()`, and a failed sync stops the commit, which rolls back). Four of their new device tests fail without the changes (build/owner-red: the two Q16 tests, the line-limit paste test of `ContentCapUiTest`, and the probe-note test of `LongContentLayoutTest`, whose fixture is sized by the limit and could not be built without it, testing.md I-56); the other new tests have no red run on record. The selected tests pass with the changes on the phone (build/owner-green-a, 75 tests; build/owner-green-b, 51 tests). Release smoke on the final code: 2/2 (build/owner-smoke). Final checks: 676 JVM tests, lint, `:app:verificationGates`, schemas, docs, test names, all three test APKs.
- **The release app is on the owner's phone (2026-11-22):** `com.doro.organizer` 0.1.0, R8-minified, not debuggable, no internet permission, signed with this laptop's debug key by the owner's choice (SHA-256 `0eb603b5…`); it starts cleanly. The release build signs with the debug key until T27 configures the owner's key (DEC-109). At the owner's request every test package was removed from the phone (the debug, `qa` and `benchmark` apps, their test APKs, `androidx.test.orchestrator` and `androidx.test.services`) together with `/sdcard/googletest`; the device runs of 2026-11-25 and 2026-11-26 installed the debug app, its test app and the two androidx.test helpers again, and they stay installed (section 4.3), as do the `benchmark` app and its seed (BL-4); HyperOS asks to confirm only a first install. Test runs never touch `com.doro.organizer` (DEC-15).
- **Waiting for the owner:** T27, a real release key, whenever the owner wants it: generate it yourself (keytool; the passwords never pass through a session), keep the file and its two passwords in the password manager plus one offline copy, never in the repo, on the phone or in the backup folder (DEC-109), and name it in `~/.gradle/gradle.properties` (the four `DORO_RELEASE_*` lines; the build already reads them). Switching the installed release app from the debug key to it takes one backup, uninstall, install and restore; until then, a lost or regenerated debug key on this laptop costs the same steps.
- **Backlog (owner, 2026-11-22: nothing is fixed before Tuesday 2026-11-24, the weekly usage reset; then this list first, then T23):**
  - **BL-1 (major, T26): done 2026-11-25.** Every production file sync goes through `core.io.syncFile`: it calls `Os.fsync` and, when that fails, throws a `SyncFailedException` whose cause is the `ErrnoException`, so a failed key-state sync on a full storage is `StorageFull` again, and `FaultyStateSync` calls it too, so the `ENOSPC` case of `MaintenanceFailureTest` asserts a class production produces. `DefaultKeyVault`, `GenerationCommit`, `SafDocuments` and the biometric slot commits use it; `ArchitectureTest` rule 10 keeps `FileDescriptor.sync()`, `FileChannel.force` and `MappedByteBuffer.force` out of production code (`95d6337`, `930cb1e`, merge `5ca2750`). Phone, 2026-11-25 (build/chain/bl1): 206 class tests green (one known assumption skip, as in T22) and all 260 `CrashSafetyTest` rows; with build/bl1-red/red.patch exactly the two failure cases of `SyncFileTest` fail; `FileDescriptorSyncLosesErrnoTest` recorded a `SyncFailedException` without a cause for a closed descriptor and for a pipe. **Held by the owner (2026-11-24), a known mismatch until released:** the SPEC V18 and T26 wording, a §B B8 row, and the decisions.md changes of DEC-107 (4) and the Q16 row (build/bl1-held/decisions-dec107-q16.patch, whose Q16 hunk no longer applies with `git apply` once the R records put Q17 right after Q16: apply its two changed lines by hand); until then DEC-107 (4) still names `FileDescriptor.sync()` while the code uses `syncFile`.
  - **BL-2 (minor, T25): done 2026-11-25.** `DbInvariantsTest` has a violation of INV-1's line check (100,000 line feeds) and a boundary note (`d55bdd8`); red and green on the phone (build/chain/blmain).
  - **BL-3 (phone, T25 and T26): done 2026-11-22.** Four new device tests fail without the changes (build/owner-red) and the selected tests pass with them (build/owner-green-a and -b); T25 and T26 are `x`.
  - **BL-4 (perf): done 2026-11-25.** Perf on the `benchmark` build from a cool phone, out of its case (DEC-108, CPU maximum 36.4 °C): 7 of 7 within budget (build/bl4-perf, `b700f62`): P-1 All p90 83.6 ms (T22: 83.7 ms), search 79.0 ms; P-2 medians 92 and 100 ms; P-6 medians 109 and 237 ms; batch actions 30 to 63 ms. The `benchmark` keep-rules hash is recorded with the run in DEC-103: `5f919245…` (742 rules; BL-1 added `SyncFileKt.syncFile` to the `813b5a46…` of the T25 and T26 follow-up); `qa` unchanged at `21b4062e…`. The `benchmark` app and its 10,000-note seed are still on the phone: the owner keeps them for T29's P-8 (2026-11-25), so that run needs no install popups.
  - **BL-5 (docs): done 2026-11-24.** The follow-up's docs-and-records review (`6377653..39518f0`) was rerun and finished (`c761b2a`). Besides the rows it names, it aligned two more sentences of DEC-24 (Q14) with ui.md § 6.9 and the T25 fix: the line-break definition (U+000A alone; CR LF is one line break) and the load sentence (`EditorViewModel.applyLoaded` calls `ContentCap.cut`, which applies both limits). The owner approved them as recorded on 2026-11-25 (decisions.md sign-off item 13).
  - **BL-6 (SPEC): done 2026-11-24.** The T25 row and §B B6 no longer claim that the line cap ends every crash of DEC-105 (`c761b2a`).
  - **BL-7 (owner): accepted 2026-11-24,** with DEC-109's rewording, DEC-24's line-cap sentence and the SPEC record fixes of T22, T25 and T27 (decisions.md sign-off item 12).
  - **BL-8 (minor): done 2026-11-25.** `close()` logs its own constant `close: flow drain timeout` (`d55bdd8`; `DrainTimeoutLogTest` reads it from logcat); red and green on the phone (build/chain/blmain).
  - **BL-9: done 2026-11-24, the owner chose option A (keep the call, suppress its deprecation warning with a stated reason, no targetSdk move).** `ContentCaptureManager.setContentCaptureEnabled` is deprecated in API 37, and Android 17 lists it as no longer disabling content capture for apps that target 37 or higher; for targetSdk 35 it is expected to keep working (inferred, not yet observed). The call stays, in `MainActivity.disableContentCapture()` with a `@Suppress("DEPRECATION")` on that one statement and the reason in the helper's KDoc, so the build has no deprecation warning for it; DEC-66 and DEC-01 record that the protection rests on targetSdk staying below 37 and that moving targetSdk to 37 needs the owner's decision first (security.md § 8). No new DEC number and no new owner question; `WindowSecurityTest` is unchanged. Follow-up: after the phone first runs API 37, run `WindowSecurityTest` and record its `DoroSetupProbe` line in security.md § 8.
  - **BL-10 (minor, test flake): open, found 2026-11-26.** Two `DatabaseHolderTest` cases fail on the same timing precondition, the 200 ms minimum overlap in `LongStatement.assertPreconditions` (`DatabaseHolderTest.kt:717` at `b24a2c5`). In T29's implementation run both `exclusive_aFlowWhoseStatementIsRunningInSqlite_drainsOnlyAfterTheStatementReturns_andLeavesNoWal` (overlap about 145 ms) and `close_aFlowWhoseStatementIsRunningInSqlite_drainsOnlyAfterTheStatementReturns_andLeavesNoWal` (about 184 ms) failed (build/chain/n26/T29/green-data); in its verification the `close_` case failed again (about 110 ms; build/chain/n26/T29/final/data). The class passed 24/0 when run alone (build/chain/n26/T29/final/data-dbholder) and in green-listpos. The tests predate the R builds; T29 only changed an `observeOptions()` signature in that file. Root cause found on 2026-11-26 (build/bl10/investigation-wf_3beee1e5-b30.json): `LongQuery.boundFor` sizes the long statement from the last of two ~110 ms calibration samples, and in all three failures that sample was stalled by about 120–170 ms, so the statement ran about half as long as planned; the product behaved correctly every time. The fix, test only, with every limit unchanged: the fastest of five samples, the samples printed in the failure messages, and a pure `LongQueryCalibrationTest` that fails on the old rule; its red and green runs need the phone. Never lower the minimum to get green.
  - **BL-11 (minor, DEC-103): open, found 2026-11-26.** With T28 to T31 the `benchmark` keep rules grew from 742 to 754 (build/p8-perf/rules). Nine new rules keep app classes that the test hooks use. Three keep library members that no app source calls: `WindowCompat.getInsetsController` and `WindowInsetsControllerCompat.hide`, from `testing/Keyboard.kt`, and `kotlin.collections.LongIterator.nextLong`, whose caller is not yet found. DEC-103 asks that code the `benchmark` test APK compiles call only library members the app already keeps, or inline-only functions, so that it does not change the measured build. Check whether the app's R8 output keeps them anyway; if not, change those calls, then record the new hash with a perf run. P-8's margin (5 to 7 ms against 100 ms) makes an effect on its result implausible.
  - **The backlog on `main` (2026-11-25):** merges `5ca2750` (BL-1) and `9aac9a6` (BL-9). On the merged tree the standard verification is green (678 JVM tests, lint, gates, schemas, docs checker; build/chain/verify), and so are 501 device tests on the phone (two known assumption skips: the content-capture case of `WindowSecurityTest` and the fsync case of `AtomicFileSyncAndStaleNewTest`): `WindowSecurityTest`, `DbInvariantsTest`, `DrainTimeoutLogTest`, `DatabaseHolderTest`, the 15 BL-1 classes and all 260 `CrashSafetyTest` rows (build/chain/merged). Before the push, three read-only reviewers checked the commits (hygiene and held items, merge correctness, record accuracy); their fixes are in.
  - **Full device regression on the backlog (2026-11-25, `b700f62`):** 1,211 tests, 0 failures, the same two known assumption skips, in 13 chunks by package (PerfTest and RealBiometric excluded, as for T22's 1,197), every chunk on its first attempt (build/chain/regression). It is the baseline for the R builds: a later failure that this run passed points to the R work first, except a known flake recorded with its own analysis (BL-10).
- **Schema version 1 is released (2026-11-22):** the owner keeps real notes in the release app, so `config/released-schema-versions.txt` lists 1 and the `schema` gate freezes `app/schemas/.../1.json`. Any change to the database layout now needs `AppDatabase.VERSION` 2 with a migration and its test (CLAUDE.md rule 11); never again change version 1 in place as DEC-106 did before the release. Updates install over the app (`adb install -r`, same debug key) and keep the notes; ask the owner for a backup in the app before each one. Its M-20 fixture files (v1-plain.db, v1-password.db, manifest) are still to make, in T23.
- **The owner uses the release app until Tuesday (2026-11-22 on) and notes the bugs met in real use** (steps, expected, seen, time, repeatable). Done 2026-11-24: the list is REQUEST_AND_SUGGESTION.md; the owner approved R-1 to R-4 and deferred or dropped the rest (build/r-design/owner-answers-2026-11-24.md, not in git). The rule stays for later lists: the session starts by asking for that list and triaging it with the backlog: a bug the backlog work or T23 touches is fixed, others join the backlog, and notes that settle a T23 item (for example M-12, M-21) are recorded there. For a crash, read the system crash buffer (`adb logcat -d -b crash`) once the phone is connected.
- **The owner's requests of 2026-11-24 (REQUEST_AND_SUGGESTION.md):** the owner approved R-1 to R-4 and answered Q17 to Q31 (decisions.md § Owner questions; no question is open). Their docs and SPEC rows are applied (`docs: R-1 to R-4 records`; on 2026-11-26 the owner approved those rows and the DEC texts the builds amended, with the record wording fixes of that review, sign-off item 14), and they were built overnight on 2026-11-26 in this order, all before T23 (Q26), each through the section 3 pipeline with the phone on USB (evidence in the git-ignored build/chain/n26/<task>/):
  - **T28 = R-3** (DEC-110; Q17, Q27, Q28): a list at its very top stays there, the list an editor returns to shows the note just created, and a scrolled list keeps its place, also for edits. **Built** (`c7fc81a`, `fb6be94`): I-58's 31 cases (30 in `ListPositionUiTest`, (n2) in `ListPositionFontScaleTest`) pass on the phone except (n), which skips by its precondition: on the Poco F7 at font scale 1 the single-column Calendar's first row never fits below the month grid (it ends at 2159 px, the limit is 2073 px), so a revealed note's row comes to the top and the grid scrolls off; the keep-grid decision is tested on the JVM, DEC-110 (2) records it and M-28 has the owner judge it. The stop rule did not apply: `requestScrollToItem` in the composition works on this phone. Red: with the reveal, top and keep moves removed, the 17 cases the docs name fail by their position assertions.
  - **T29 = R-4 with R-4b** (DEC-111; Q19, Q20, Q29, Q30, Q31): Add person lists the people of the note's projects first; a person or project created earlier in the same edit shows checked (R-4b, R-4's finding F1, inside T29 by the owner's choice). **Built** (`d226b9d`, `b24a2c5`): `PickerGroupsUiTest` 13/0 on the phone. Red: with the picker opened at once, without the save first, its two R-4b cases fail on the phone and exactly the ten new `requestLinks` cases fail on the JVM. DEC-111 (7) now has the screen take the request through `takeLinksRequest(isTop)`, and a request whose save ends while the editor is off screen (a tab switch, Home or a lock during the save) opens its picker when the editor is next composed (no M-item checks this yet; approved by the owner with sign-off item 14). P-8 passed on 2026-11-26 from a cool phone (build/p8-perf, `39e9585`, CPU maximum 35.2 °C): p90 5.3 ms and 6.5 ms against its 100 ms budget, with the `benchmark` rules `074b27c0…` (DEC-103; BL-11).
  - **T30 = R-2** (DEC-112, DEC-110 (4); Q18, Q27): "All people" / "All projects" in the title's picker returns the tab to its list of everyone and forgets the choice; a newly chosen person, project or day opens its list at the top. **Built** (`9a4b858`, `0aece01`): the review found that a real day tap moved the single-column Calendar's grid by about 229 px, against Q27 and V41; the fix makes the loading and empty items fill the viewport below the header, with red runs. DEC-110 (4) records what remains for the owner to judge in M-30: when a day's rows end above the viewport's bottom the list settles and the grid can move down by up to the missing height, and an empty or loading day's list can scroll by the grid-plus-header height. `TopAnchor.positionForPicker` stays as a guard although I-63 (i) passes without it on this phone (DEC-112). Red: with rule 4's effect removed, I-64 (p1), (p2), (p3), (p6), (p8), (p9), (t), (w) and `NoteListBodyRulesUiTest` (c1) to (c3) fail.
  - **T31 = R-1** (DEC-113; Q21, Q22, Q23): a ⋮ menu in the People and Projects top bar renames or deletes the person or project shown. **Built** (`c5875b8`, `48a4746`): `EntityRenameTest` 12/0 and `EntityMenuUiTest` 13/0 on the phone; no red run, as a new feature. DEC-113 (4) and (6) gained three rules: a rename with a blank field does nothing; one "Delete…" impact read at a time, cancelled by any change of the open dialog; a Rename or Delete dialog restored in All, Calendar or Orphans is dropped. The new dialogs' titles are plain text, like the other confirmation dialogs, until the owner decides R-1 F4 ("Next").
  - Each task builds on the one before (build/r-design/PLAN.md, section 7, lists the seams). The design records (the drafts, the plan, the owner summary and runs/) are in build/r-design, not in git.
  - **Q31, answered 2026-11-25 ("yes, keep it"):** R-4b's fix keeps its recorded form: the editor saves the note first, then opens the picker with the entity checked, by name in the first group; a failed save keeps the picker closed. The owner still judges the wait on the phone in M-29 (b). No question is open, so no test name ends in a question id, and the `test-names` check covers every id (testing.md § 1, § 9).
  - **Deferred by the owner:** R-5 (import and templates; Q25), R-6 (REA Notion, owner note 61), and DeepSeek's suggestions S-1 except the rename (details, status, a directory screen, merging; Q22), S-2, S-3, S-5, S-6, S-7 and S-11. **Dropped:** S-4 (people × projects matrix), S-8 (saved teams), S-9 (date shortcuts, reminders and notifications) and S-10 (share into the app, home-screen widget). **The duplicate project** is the owner's to fix by hand (Q24): batch Associate its notes to the kept project, then delete the other one from the Projects tab's ⋮, which the owner's release app has once it is updated with T31 ("Next").
- **Next (each on the owner's word):**
  - **BL-10:** the fix is designed (BL-10 above); its red and green runs need the phone.
  - **The owner's release app:** an in-app backup, then the update over the release app with `adb install -r` (a release build signed with this laptop's debug key, SHA-256 `0eb603b5…`, so the notes are kept; on another machine the debug key differs and the update fails on the signature: stop and ask the owner, never uninstall; see "The release app is on the owner's phone" above).
  - **T23** with the owner and the phone, covering the backlog fixes and the R features (Q26): the manual QA checklist (testing.md § 10, with M-28 to M-31 for T28 to T31), M-27 on the `qa` build, L-10 over the M-20 fixtures, the visual check of the icons drawn offline in T16–T19, the setup checks T22 moved into the M-items (M-01, M-10, M-11, M-18 with the one-word 200,000-character note and P-8, M-21, M-22), and a full `device-tests` run on the final code (decided by the owner on 2026-11-25; baseline: the 1,211-test regression of 2026-11-25 above, plus the tests T28 to T31 added). BL-10 fixed and confirmed before that run, and BL-11 settled before its full perf run (M-18). Every perf run starts with `sh dev/claude/phone-cooldown.sh` (DEC-108) and records the `benchmark` keep-rules hash it ran with (DEC-103, testing.md § 9).
  - **T27** when the owner has made the release key.
  - **Held, on the owner's release:** the BL-1 records (the SPEC V18 and T26 wording, a §B B8 row, DEC-107 (4) and the Q16 row; BL-1 above), and folding the overnight pipeline changes into `workflows/doro-build-tasks.js` ("Lessons from the R builds" below).
  - **Listed for later by the owner:** R-1 F4 (dialog titles as TalkBack headings: make every dialog title a heading, or correct ui.md § 12.2), R-1 F1 (the log-privacy test the test plan names was never written), R-4b F-b1 ("Delete person…" can list a person created earlier in the same note under "Other people") and R-4b F-b2 (a person created twice if Android ends the app in the background exactly while a stored note is saved together with a newly created person).
- **Lessons from T22's session** (also in section 6 where they recur):
  - A killed VS Code (exit 143 at 08:53 on 2026-11-22, cause unknown) ends `run_in_background` jobs; long phone jobs ran detached (`nohup … & disown`) and survived.
  - `dumpsys thermalservice` prints a stale "Cached temperatures" section before the live "Current temperatures from HAL"; read only the live one (`phone-cooldown.sh` does).
  - T22's phone helpers (a device-run script with a machine-wide phone lock, a Gradle-busy check, logcat capture and result copying) lived in the session's scratchpad, not in the repo; section 4.3 has the commands they wrapped.
- **Lessons from the R builds (2026-11-26):**
  - A failing cable looks like the USB link resets of 2026-11-25, only more often: three drops within five minutes in the middle of a run, then the phone gone for adb and Windows alike. A new cable ended them (section 4.3).
  - The overnight run used a session copy of the pipeline for an absent owner (build/chain/n26/doro-build-tasks-n26.js, not in git). Its phone text: the four test packages are installed, so no taps; stay-awake stays 7 and only the main session changes it; `adb devices` before every run; up to three attempts, only on USB-loss messages; a `phone_tests_not_run` list when the phone is lost; results copied to build/chain/n26/<task>/. It also added red evidence for a task that fixes a behaviour the app has today (a `red.patch` that removes only the fix: applied, the covering cases run and fail, reversed, every file checked against its recorded sha256), the task's device tests rerun by the fixers and the verifier after code changes, and T28's stop rule as a `stop_rule` field that ends the run. Fold these into `workflows/doro-build-tasks.js` when the owner agrees.
  - One task per workflow run let the main session check each commit, and T28's stop rule, before starting the next.

## 2. The owner's working preferences

- **Quality first.** The owner's words: "when reviewing code, I expect quality".
  - Find the root cause before changing anything.
  - Review every task through several lenses and verify each fix.
  - Every commit is green.
  - Never weaken, skip or delete a test to get green.
  - Design docs have no length caps; CLAUDE.md stays small.
- **Phone only.** All device testing happens on the owner's own phone over USB, from the command line. No emulator, no Android Studio.
- **Phone windows.** The owner uses the phone between runs. Say when it is needed and when it is free; when they take it, start no device run until they give it back (T22's device-run script made a waiting run wait on a machine-wide phone lock, but that script lived in its session's scratchpad, section 1), and ask for it back as soon as a run needs it. Perf runs want it out of its case (DEC-108).
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
- T28–T31 with the phone (2026-11-26): 7 agents per task, 1.3–1.5M subagent tokens per task, 1 h 28 min to 2 h 23 min per task including the device runs.

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

**Full suite:** about 1,200 tests in about 70 minutes (build/t22-full-4, 2026-11-22: 1,197 testcases, 1 h 10 min of Gradle time). The Orchestrator runs each test in its own process with `clearPackageData`.

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
- **USB link resets (2026-11-25).** Twice in one night the phone left USB for about two seconds while a run was installing its four packages (adbd: "UsbFfs: connection terminated", then "Charging this device via USB" and a new adb transport id). The run then reports "device '…' not found" or "Test run failed to complete" with no results. It is the link, not the tests: run it again. The chain wrapped each device run in a retry of up to three attempts on exactly those messages, never on a test failure (build/chain/devrun.sh, not in git). The laptop's USB selective suspend is on. On 2026-11-26 three drops came within five minutes in the middle of a run, and then neither adb nor Windows saw the phone; a new cable ended them for the rest of the night (no drop in seven and a half hours). Drops that repeat within minutes point to the cable: swap it first.
- Touch only `com.doro.organizer.debug`, its test app, the two androidx.test helpers and, for perf runs, the `benchmark` app (`com.doro.organizer.benchmark`) and its test app; never `com.doro.organizer` (DEC-15). `/data/local/tmp` holds the owner's own files (Shizuku, scripts); leave them alone.

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
