// TEMPLATE: the AREAS below are the failure groups of the first phone run (2026-11-19). Replace them with the
// current failure groups before a run. args: { root: 'C:/path/to/DoroOrganizer', evidenceDir: 'C:/path/to/evidence' }
export const meta = {
  name: 'doro-device-fix',
  description: 'Fix the first on-phone instrumented test failures of T1-T14: three sequential area fixers on the one phone, two-lens review, review fixes, docs record; no commit',
  phases: [
    { title: 'Fix', detail: 'holder+restore, backup fixture+crash matrix, security+session+infra; one phone, so sequential' },
    { title: 'Review', detail: 'product correctness, test integrity' },
    { title: 'Polish', detail: 'apply verified review findings, targeted device reruns' },
    { title: 'Record', detail: 'docs departures, SPEC §B backprop rows, T22 device outcomes' },
  ],
}

const ROOT = (args && args.root) || '.'   // pass the absolute repo path, e.g. 'C:/work/DoroOrganizer'
const SP = (args && args.evidenceDir) || (ROOT + '/build/device-evidence')   // failure brief, logcat and result XML of the run being fixed
const CHECKER = 'node "' + ROOT + '/dev/claude/check-docs.js" "' + ROOT + '" (must end with ALL CHECKS PASSED)'

const ENV = [
  'PROJECT: "Doro Organizer", a fully offline Android notepad. Repo: ' + ROOT + ' (git; agents commit locally and NEVER push). Build contract: SPEC.md (caveman encoding, FORMAT.md). Design: docs/*.md; docs/decisions.md wins over everything; read ' + ROOT + '\\CLAUDE.md from disk. Tasks T1-T14 are built and committed; T15+ (screens) do not exist yet.',
  'MACHINE: see dev/claude/HANDOFF.md (Machine setup). JDK 21; the Android SDK path is in local.properties (never commit it); use ./gradlew (Gradle 9.9.0 wrapper). adb lives in <sdk>/platform-tools and may not be on PATH. On Windows use Git Bash and pass every adb shell command as ONE quoted string, because Git Bash rewrites bare /data/... arguments into Windows paths.',
  'PHONE: the owner\'s POCO F7 is connected over USB (Android 16 / HyperOS 3.0, SDK 36, arm64-v8a, 4 KB pages; secure lock screen and an enrolled fingerprint). Touch ONLY com.doro.organizer.debug and com.doro.organizer.debug.test; never uninstall or clear any other package, never change device settings, never read other apps\' data. The Orchestrator clears the debug app\'s data after every test (clearPackageData).',
  'DEVICE RUNS: only through Gradle, e.g. ./gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.doro.organizer.data.FooTest,com.doro.organizer.data.BarTest#oneMethod" or "-Pandroid.testInstrumentationRunnerArguments.tests_regex=<regex over class#method>". Every Bash call must finish within 10 minutes: pass timeout 600000 and size each subset to finish in about 8 minutes (about 1.5-4 s per test; CrashSafetyTest has 226 rows, so split it by tests_regex on flow names). You are the only agent using the phone and Gradle right now: never start two Gradle runs at once. Results: app/build/outputs/androidTest-results/connected/debug/*.xml (overwritten by each run). Logcat per run: adb logcat -c before, adb logcat -d -v threadtime > ' + SP + '/<name>.txt after; grep it by "TestRunner: started: <method>" and the test process pid.',
  'FIRST RUN EVIDENCE: ' + SP + '/device-failures.md (every distinct failure with its trace, and two failures that were only caused by the owner tapping the app icon), full first-run logcat ' + SP + '/device-logcat.txt, archived XML ' + SP + '/device-run1/.',
  'METHOD (owner: "when reviewing code, I expect quality"): root cause before any change. For each failure read the test, the production code it drives, the logcat window and the doc sections; classify it as PRODUCT BUG, TEST TECHNIQUE (the test\'s way of provoking or observing the case does not work on a real device) or PLATFORM FACT (a documented assumption is false on a real device). Product bug: fix the code at its root and keep the device test as its regression test (it must fail without the fix; say how you know). Test technique: change the technique so the test still proves exactly what testing.md and the test name say; NEVER weaken or delete an assertion, skip a test (@Ignore, assume*), widen a timeout without a measured cause, or swallow an exception. Platform fact: follow docs/decisions.md; where the docs assumed wrongly, make the least-surprising sound choice that keeps JVM and device behavior identical, and list it in spec_issues (a later stage records it). Never hard-code values that only hold on this one phone.',
  'Do NOT commit and do NOT edit SPEC.md or docs/ (a later stage records departures). Keep JVM checks green: at the end of your stage run ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest. Read files in parallel, Grep docs instead of reading them whole, batch edits.',
].join('\n')

const FIX_SCHEMA = {
  type: 'object',
  properties: {
    causes: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          tests: { type: 'array', items: { type: 'string' } },
          kind: { type: 'string', enum: ['product bug', 'test technique', 'platform fact', 'not a bug'] },
          root_cause: { type: 'string', description: 'mechanism, with the evidence (logcat line, code location, platform behavior)' },
          fix: { type: 'string', description: 'what changed, file:line' },
          regression_proof: { type: 'string', description: 'why the test fails without the fix (product bugs) or still proves its claim (technique changes)' },
        },
        required: ['tests', 'kind', 'root_cause', 'fix', 'regression_proof'],
      },
    },
    device_results: { type: 'array', items: { type: 'string' }, description: 'each device run: filter, passed/failed counts, failing tests' },
    jvm_verification: { type: 'array', items: { type: 'string' } },
    still_failing: { type: 'array', items: { type: 'string' }, description: 'tests in scope still failing, with what is known' },
    spec_issues: { type: 'array', items: { type: 'string' }, description: 'every departure from SPEC.md or the docs and every device fact the docs got wrong, with the choice made' },
  },
  required: ['causes', 'device_results', 'jvm_verification', 'still_failing', 'spec_issues'],
}
const REVIEW_SCHEMA = {
  type: 'object',
  properties: {
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          id: { type: 'string' },
          severity: { type: 'string', enum: ['critical', 'major', 'minor'] },
          location: { type: 'string' },
          problem: { type: 'string' },
          fix: { type: 'string' },
        },
        required: ['id', 'severity', 'location', 'problem', 'fix'],
      },
    },
    verdict: { type: 'string' },
  },
  required: ['findings', 'verdict'],
}
const POLISH_SCHEMA = {
  type: 'object',
  properties: {
    applied: { type: 'array', items: { type: 'string' } },
    rejected: { type: 'array', items: { type: 'string' }, description: 'finding id with reason' },
    device_results: { type: 'array', items: { type: 'string' } },
    jvm_verification: { type: 'array', items: { type: 'string' } },
    spec_issues: { type: 'array', items: { type: 'string' } },
  },
  required: ['applied', 'rejected', 'device_results', 'jvm_verification', 'spec_issues'],
}
const RECORD_SCHEMA = {
  type: 'object',
  properties: {
    recorded: { type: 'array', items: { type: 'string' } },
    backprop_rows: { type: 'array', items: { type: 'string' }, description: 'SPEC §B rows added, and any §V invariant added with its reason' },
    t22_outcomes: { type: 'array', items: { type: 'string' }, description: 'device facts now established, and where recorded' },
    checker: { type: 'string' },
    commit: { type: 'string', description: 'short hash of the docs commit' },
  },
  required: ['recorded', 'backprop_rows', 't22_outcomes', 'checker', 'commit'],
}

const AREAS = [
  {
    key: 'holder-restore',
    text: `AREA: database holder and restore.
- data.DatabaseHolderClassifyTest#classifyRuntime_storageFull_isStorageFull_andRollsBack: "cannot rollback - no transaction is active" from Room's endTransaction. Likely mechanism: on SQLITE_FULL inside a transaction SQLite may roll the whole transaction back itself, and the exception thrown by Room's finally block then replaces the original SQLiteFullException, so classification never sees StorageFull. If confirmed this is a PRODUCT bug in every write path (NoteRepository, EntityRepository, DbMaintenance, holder): find the right seam (one shared transaction helper, per DEC-81 / data-layer.md transactions) so the original exception survives and the transaction ends cleanly; check every caller.
- data.maintenance.RestoreTest#anOwnPlainBackup_restoresExactly_resetsTheData_andNeverReusesAnId: "attempt to re-open an already-closed object: SQLiteDatabase" at RestoreTest.kt:75.
- data.maintenance.RestoreValidationTest#anIntegrityCheckFailure_isDamaged: SQLiteDatabaseCorruptException thrown from the test helper stringsOf at RestoreValidationTest.kt:194.
- data.maintenance.RestoreValidationTest#aReportedSizeBeyondThreeTimesTheFreeSpace_isStorageFull_beforeAnythingIsCopied: AssertionError at RestoreValidationTest.kt:293.
- Re-run all of data.DatabaseHolderClassifyTest and data.DatabaseHolderTest (two of their failures were caused by the owner's taps; confirm they pass).
When done, re-run every class you touched and data.maintenance.RestoreTest, RestoreValidationTest, BackupTest in full.`,
  },
  {
    key: 'backup-crash-matrix',
    text: `AREA: crash-safety matrix and recovery backups.
- data.maintenance.CrashSafetyTest (all 226 rows) and CrashSafetyCoverageTest fail at CrashScenes.kt:321 "the planted backup": maintenance.backupToDocument(Uri.fromFile(input), password) is not Ok. data.maintenance.RecoveryFailureTest#aRestoreFromCorrupt_failingUpToItsCommit_keepsTheDamagedFiles_andAfterItTheCommitStands gets Err(Unexpected) from backupTo (Restores.kt:50). BackupTest (SAF content:// documents) passed. Find why these backups fail (logcat window of one CrashSafetyTest row and of RecoveryFailureTest; DbMaintenance's target handling; the fixture's Uri) and decide product vs technique: production targets are SAF documents and share files, so a file:// Uri may simply be outside the contract, or the product may mishandle a valid target.
- Once the plant works, the 226 rows run for the first time: every row that then fails is in scope (they test the crash matrix CS-nn of db-lifecycle.md: process kill at each step, then the next start must reach the matrix row). Run the class in chunks by tests_regex over flow names (BackupToDocument, BackupForShare, KeyChange, Restore, StartFresh, Erase, Startup, ...), each chunk under about 8 minutes.
When done, re-run CrashSafetyTest (all chunks), CrashSafetyCoverageTest, RecoveryFailureTest and BackupTest in full.`,
  },
  {
    key: 'security-session-infra',
    text: `AREA: security, session gate and test infrastructure.
- security.KeyVaultTest#failingCountWrite_bindsTheNextAttemptsInMemory_andTheCountCarriesOn: expected Throttled(failures=2, ...) but was Throttled(failures=3, ...) (KeyVaultTest.kt:445); DEC-75 owns the rule.
- data.maintenance.BiometricSlotCommitTest#creation_whenTheWrapFails_isAuthFailed_writesNothing_andLeavesNoKey: expected 0 but was 2 at BiometricSlotCommitTest.kt:234. Find what the 2 is (Keystore aliases? which ones?), whether a failed wrap really leaks a key (security bug) or whether entries survive from elsewhere (does the Orchestrator's pm clear remove AndroidKeyStore entries on this device?), and fix the right side.
- session.ReturnRouteMatrixTest#r4_passwordLockOn_noSecureLockScreen_onReturn_asksThePasswordOverTheOpenDatabase: 20 s timeout. The phone HAS a secure lock screen, so "no secure lock screen" must come from a test seam; check the wiring and what the gate actually did (logcat).
- testing.DoroAppRuleTest#rule_installsTimeAndLifecycleOverrides_andConnectsNoFakeClock: expected 0 but was 1 at DoroAppRuleTest.kt:63.
- data.db.NoteRowTest#whitespaceChars_listExactlyKotlinWhitespaceOnThisRuntime: on ART the whitespace set lacks U+180E (6158), which the JVM includes (newer Unicode data on Android). This is the T22 "ART vs JVM whitespace" verify item. Find which product rules depend on it (blank content / "(No data)", trimming, content_fold, search) per data-layer.md and the DECs, and make the rule identical on JVM and device (for example an explicit code-point set shared by Kotlin and SQL), then make the test pin that rule on both runtimes.
When done, re-run every class you touched plus the whole com.doro.organizer.security and com.doro.organizer.session packages (in chunks under about 8 minutes).`,
  },
]

const LENSES = [
  { key: 'product', text: 'PRODUCT CORRECTNESS AND FIDELITY: is each root cause right and fixed at its root; do the fixes hold in every caller and path, not only the tested one; any regression in other paths (transactions, classification, backup/restore, key handling, gate); consistency with docs/decisions.md and the cited doc sections; minSdk 28 compatibility; SPEC V1 (no user data in logs or exception messages) and V16 (secrets zeroed); dead code or duplication introduced.' },
  { key: 'test-integrity', text: 'TEST INTEGRITY: does every changed test still prove exactly what testing.md and its name claim; any weakened or deleted assertion, skipped test, widened timeout, swallowed exception, sleep, or assumption that holds only on this one phone; do product-bug fixes have a test that fails without them; are the new techniques deterministic and hermetic on any device of minSdk 28+.' },
]

function fixPrompt(area, prior) {
  return `ROLE: device-test fixer for Doro Organizer.

${ENV}

${area.text}

${prior.length ? 'EARLIER STAGES OF THIS RUN changed the tree as follows (build on their work, do not undo it):\n' + JSON.stringify(prior, null, 1) : ''}

Return the structured report.`
}

function reviewPrompt(lens, stages) {
  return `ROLE: independent reviewer of the device-test fixes in the uncommitted working tree of ${ROOT} (git status, git diff, untracked files).

${ENV}

LENS: ${lens.text}

Read every changed file in full, the tests and production code around each change, the cited doc sections and docs/decisions.md. Do NOT edit files, do NOT run Gradle and do NOT use the phone (another agent owns them). Assume defects exist. Each finding: file:line, severity, the problem with its justification and a concrete fix. No style nits unless they hurt clarity.

Fixer reports:
${JSON.stringify(stages, null, 1)}

Return the findings, most severe first.`
}

function polishPrompt(findings, stages) {
  return `ROLE: fixer of review findings on the device-test fixes in ${ROOT}.

${ENV}

Verify each finding against the code, the docs and the device evidence before acting; apply every correct one, reject wrong ones with a reason. Re-run on the phone every test class your changes touch (chunks under about 8 minutes), then the JVM checks.

Fixer reports:
${JSON.stringify(stages, null, 1)}

FINDINGS:
${JSON.stringify(findings, null, 1)}

Return the structured report.`
}

function recordPrompt(stages, polish) {
  return `ROLE: recorder for the device-test fixes in ${ROOT}. The docs must describe the code that ships and the device facts this run established.

${ENV}

Do NOT edit code, do NOT run Gradle, do NOT use the phone. You MAY edit docs/, SPEC.md and CLAUDE.md, as follows.
1. Departures: from the reports below (spec_issues, causes of kind "platform fact" or "test technique" that change a documented technique) and your own read of git diff against the docs, record every sound departure in the section that owns it (CLAUDE.md doc table); a changed rule, name or signature that decisions.md states is amended in its DEC row or component table in place, in its table style; a new rule takes the next free DEC number of its block; never renumber; never open owner questions. If a departure is unsound, do not record it: list it in recorded as "UNSOUND: ..." for the lead.
2. Backprop (SPEC §B, FORMAT.md caveman pipe-table shape): one row per PRODUCT BUG cause: what failed, the root cause, the fix, and the regression test; add a §V invariant only when it would catch a recurrence that no existing V<n> covers, and say why.
3. T22: record the device outcomes this run established for the "Verify at setup" items of the SPEC T22 row and the docs (for example the ART whitespace set, SQLITE_FULL rolling back the transaction, Keystore entries and pm clear, how corruption and low free space can be provoked on a device): write each outcome into the doc section that asked for it, and shorten the T22 row's list to the items still open. Never touch any status cell.
4. The banned-dependency lists in security.md § 9.3 and testing.md must stay byte-identical.
5. Run ${CHECKER}. Commit ONLY the files you changed among docs/, SPEC.md, CLAUDE.md with a pathspec: git commit -m "docs: record device-test fixes of T1-T14" -m "<one paragraph>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- <files>. Never push.

Reports:
${JSON.stringify({ stages, polish }, null, 1)}

Return the structured report.`
}

const stages = []
for (const area of AREAS) {
  phase('Fix')
  const r = await agent(fixPrompt(area, stages.map(s => ({ area: s.area, causes: s.report.causes, still_failing: s.report.still_failing }))), { label: 'fix:' + area.key, phase: 'Fix', schema: FIX_SCHEMA, effort: 'high' })
  if (!r) { stages.push({ area: area.key, report: { causes: [], device_results: [], jvm_verification: [], still_failing: ['fixer failed'], spec_issues: [] } }); continue }
  stages.push({ area: area.key, report: r })
  log(area.key + ': ' + r.causes.length + ' cause(s), still failing ' + r.still_failing.length)
}

phase('Review')
const reviews = (await parallel(LENSES.map(l => () =>
  agent(reviewPrompt(l, stages), { label: 'review:' + l.key, phase: 'Review', schema: REVIEW_SCHEMA, effort: 'high' })
    .then(r => r && { lens: l.key, ...r })
))).filter(Boolean)
const findings = reviews.flatMap(r => r.findings)
log('review: ' + findings.length + ' findings (' + findings.filter(f => f.severity !== 'minor').length + ' critical/major)')

phase('Polish')
const polish = findings.length
  ? await agent(polishPrompt(findings, stages), { label: 'polish', phase: 'Polish', schema: POLISH_SCHEMA, effort: 'high' })
  : null

phase('Record')
const rec = await agent(recordPrompt(stages, polish), { label: 'record', phase: 'Record', schema: RECORD_SCHEMA, effort: 'high' })

return {
  stages: stages.map(s => ({ area: s.area, causes: s.report.causes.map(c => ({ kind: c.kind, tests: c.tests.length, root_cause: c.root_cause })), still_failing: s.report.still_failing })),
  review: reviews.map(r => ({ lens: r.lens, findings: r.findings.length, verdict: r.verdict })),
  polish: polish && { applied: polish.applied.length, rejected: polish.rejected, device_results: polish.device_results },
  record: rec,
}
