export const meta = {
  name: 'doro-build-tasks',
  description: 'Build SPEC.md tasks in order: implement, 3-lens review, verified fixes, docs record of departures, fresh verification + local commit, one repair round on refusal',
  phases: [
    { title: 'Implement', detail: 'one implementer per task' },
    { title: 'Review', detail: 'fidelity, correctness, security/quality lenses' },
    { title: 'Fix', detail: 'verified fixes, verification green' },
    { title: 'Record', detail: 'triage departures from the docs: record legitimate ones, send shortcuts back to code' },
    { title: 'Verify', detail: 'fresh verification, SPEC status flip, local commit; one repair round on refusal' },
  ],
}

// args: { root: 'C:/path/to/DoroOrganizer', tasks: ['T24', 'T15', ...], phone?: false, verifyOnly?: ['T24'], prior?: { T24: ['major finding text', ...] } }
// How to launch, resume and recover a run: dev/claude/HANDOFF.md (Build pipeline).
const TASKS = (args && args.tasks) || []
const VERIFY_ONLY = (args && args.verifyOnly) || []
const PRIOR = (args && args.prior) || {}
const REVERIFY_NOTE = 'Implementation, review and fixes for this task were done in an earlier run and are in the working tree; the lead has since addressed the earlier refusal. Re-verify everything from scratch.'
const ROOT = (args && args.root) || '.'   // pass the absolute repo path, e.g. 'C:/work/DoroOrganizer'
const CHECKER = 'node "' + ROOT + '/dev/claude/check-docs.js" "' + ROOT + '" (must end with ALL CHECKS PASSED)'

const ENV = [
  'PROJECT: "Doro Organizer", a fully offline Android notepad. Repo: ' + ROOT + ' (git; agents commit locally and NEVER push; the owner decides when to push to GitHub). Build contract: SPEC.md (caveman encoding, FORMAT.md). Design: docs/*.md; docs/decisions.md wins over everything; CLAUDE.md rules apply. Tasks marked x in SPEC.md §T are built and committed; read their code instead of re-deriving it.',
  'MACHINE: see dev/claude/HANDOFF.md (Machine setup). JDK 21; the Android SDK path is in local.properties (never commit it); use ./gradlew (Gradle 9.9.0 wrapper). adb lives in <sdk>/platform-tools and may not be on PATH. On Windows use Git Bash and pass every adb shell command as ONE quoted string, because Git Bash rewrites bare /data/... arguments into Windows paths.',
  'CLAUDE.md and dev/claude/HANDOFF.md: read both from disk under ' + ROOT + ' (an injected copy may be stale); package root is com.doro.organizer.',
  (args && args.phone)
    ? 'PHONE CONNECTED: the owner\'s phone is on USB and the owner is present to tap the HyperOS install popups (4 per run, about 12 s each; dev/claude/HANDOFF.md, Phone testing). Only the implementer, fixers and verifier may run device tests, never two Gradle runs at once; run only targeted subsets of the classes this task touches, each finishing within about 8 minutes. Touch nothing but com.doro.organizer.debug and its test app. Keep the phone\'s screen on and the phone unlocked during device runs: adb shell settings put global stay_on_while_plugged_in 7 before the first run and 0 (the owner\'s default) after the last (HANDOFF section 4.3, testing.md section 2); test code never changes it. Never run the perf job (connectedBenchmarkAndroidTest with the PerfTest annotation): perf runs only on a cool phone out of its case, with the owner (DEC-108, testing.md section 8). A task that adds a perf test compiles it here, the debug run skips it (PerfRecorder), and its SPEC.md row says when it is measured.'
    : 'NO PHONE IS CONNECTED: never run connected*AndroidTest or installDebug. Instrumented tests must compile (:app:assembleDebugAndroidTest) and will run on the owner\'s phone later (SPEC T22).',
  'QUALITY BAR (owner: "when reviewing code, I expect quality"): production-grade Kotlin; exact names from the decisions.md component table and the docs; small focused files; no dead code; no TODO stubs for behavior inside the task scope; comments only where a rule is not obvious, citing DEC-nn or SPEC V<n>; every test asserts real behavior, deterministic and hermetic (FakeAppTime, no sleeps).',
  'EFFICIENCY (hard account usage limit): read files in parallel, Grep docs for the sections you need instead of reading whole docs, batch edits. An interrupted earlier attempt may have left partial work in the tree: inspect git status and git diff first and continue from it.',
  'DOCS AHEAD OF CODE: the docs may already describe tasks whose SPEC.md rows are still open (T28 to T31 were recorded together before T28 was built). Build only what the SPEC.md row of this task names. A part of a cited section that a later open row owns (for T28 to T31: DEC-111 belongs to T29, DEC-112 and DEC-110 (4) to T30, DEC-113 to T31) is neither built before its task, nor reported missing, nor recorded as a departure: it stays in the docs as written.',
].join('\n')

const VERIFY_CMDS = 'Standard verification (all must pass): ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest :app:verificationGates, plus sh config/check-schemas.sh, plus every check the task itself introduces.'

const RECORD_RULES = `HOW TO RECORD A DEPARTURE (docs must describe the code that ships):
- Amend the section that owns the topic (CLAUDE.md doc table: architecture, data-layer, ui, security, db-lifecycle, testing). If it changes a rule, a component name or a signature that decisions.md states, amend that DEC row or the component table in docs/decisions.md in place, in its existing table style; a genuinely new rule takes the next free DEC number of its block. Never renumber, never open owner questions.
- Scope moved to a later task: edit only the task text of the two SPEC.md §T rows (current row says what moved and where, e.g. "(X → T14)"; target row gains X), caveman encoding per FORMAT.md; never touch a status cell, §V or §B.
- The banned-dependency lists in security.md § 9.3 and testing.md must stay byte-identical (BannedDependenciesTest compares them).
- Run the docs checker: ${CHECKER}. Then commit ONLY the doc files you changed, with a pathspec so code is never swept in: git commit -m "docs: record <task> implementation details" -m "<one paragraph: what was recorded and why>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- <the changed files among docs/, SPEC.md, CLAUDE.md>. Never push.`

const IMPL_SCHEMA = {
  type: 'object',
  properties: {
    files_changed: { type: 'array', items: { type: 'string' } },
    tests_added: { type: 'array', items: { type: 'string' } },
    verification: { type: 'array', items: { type: 'string' }, description: 'each command run and its final result' },
    green: { type: 'boolean' },
    spec_issues: { type: 'array', items: { type: 'string' }, description: 'every place the code departs from SPEC.md or the docs (names, signatures, behavior, file locations, test ids, scope), and docs defects, contradictions or infeasible points found, each with the choice made' },
    notes_for_reviewers: { type: 'string' },
  },
  required: ['files_changed', 'tests_added', 'verification', 'green', 'spec_issues', 'notes_for_reviewers'],
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
          location: { type: 'string', description: 'file:line' },
          problem: { type: 'string', description: 'what is wrong and why (cite doc section, DEC-nn, SPEC V<n> or platform fact)' },
          fix: { type: 'string', description: 'concrete change' },
        },
        required: ['id', 'severity', 'location', 'problem', 'fix'],
      },
    },
    verdict: { type: 'string' },
  },
  required: ['findings', 'verdict'],
}
const FIX_SCHEMA = {
  type: 'object',
  properties: {
    applied: { type: 'array', items: { type: 'string' } },
    rejected: { type: 'array', items: { type: 'string' }, description: 'finding id with reason' },
    verification: { type: 'array', items: { type: 'string' } },
    green: { type: 'boolean' },
    spec_issues: { type: 'array', items: { type: 'string' } },
  },
  required: ['applied', 'rejected', 'verification', 'green', 'spec_issues'],
}
const RECORD_SCHEMA = {
  type: 'object',
  properties: {
    recorded: { type: 'array', items: { type: 'string' }, description: 'departure -> where it was recorded (file § and DEC-nn if any)' },
    scope_moves: { type: 'array', items: { type: 'string' }, description: 'part -> target task' },
    must_fix_in_code: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          location: { type: 'string' },
          change: { type: 'string', description: 'what the code must do, citing the doc section / DEC-nn / V<n>' },
          reason: { type: 'string', description: 'why the docs are right and feasible here' },
        },
        required: ['location', 'change', 'reason'],
      },
    },
    checker: { type: 'string', description: 'last line of the docs checker output' },
    commit: { type: 'string', description: 'short hash of the docs commit, empty if nothing recorded' },
  },
  required: ['recorded', 'scope_moves', 'must_fix_in_code', 'checker', 'commit'],
}
const VERIFY_SCHEMA = {
  type: 'object',
  properties: {
    passed: { type: 'boolean' },
    verification: { type: 'array', items: { type: 'string' } },
    commit: { type: 'string', description: 'short hash, empty if not committed' },
    unfixed_major_findings: { type: 'array', items: { type: 'string' } },
    failures: { type: 'array', items: { type: 'string' }, description: 'every reason for refusing, each concrete enough to act on (file:line, command output, missing record)' },
    concerns: { type: 'array', items: { type: 'string' } },
  },
  required: ['passed', 'verification', 'commit', 'unfixed_major_findings', 'failures', 'concerns'],
}

const LENSES = [
  { key: 'fidelity', text: 'FIDELITY AND COMPLETENESS: does the code implement every requirement of this task exactly as SPEC.md (task row, cited V<n> and I.*), docs/decisions.md and the cited doc sections specify? Missing behavior, deviations, wrong names or signatures versus the docs, scope creep into later tasks, tests that testing.md assigns to this task missing or asserting the wrong thing.' },
  { key: 'correctness', text: 'CORRECTNESS AND ROBUSTNESS: bugs, edge cases (empty, null, limits, time zones, process death), coroutine and dispatcher misuse, transactions, error handling, resource leaks, Android lifecycle, API-level compatibility for minSdk 28 (NewApi), Gradle/AGP 9 configuration mistakes, R8/manifest correctness, performance pitfalls.' },
  { key: 'security-quality', text: 'SECURITY, DATA SAFETY AND CODE QUALITY: SPEC V1 (no user data in logs or exception messages, redacted toString), V2 (bound SQL only), V3/V16 (engine, keys and secrets handling, zeroing), V28/V29 (manifest, permissions, backup rules), V33 (layering), test quality (meaningful assertions, determinism, hermetic, no flakiness), maintainability, naming, dead code, duplication.' },
]

function implPrompt(t) {
  return `ROLE: implementer of SPEC.md task ${t} for Doro Organizer.

${ENV}

READ: SPEC.md (§C, §I, §V, the §T row for ${t} and the rows before it, to know what exists), docs/decisions.md, and every doc section the ${t} row cites (Grep for the rest). Implement EXACTLY what they specify for this task's scope; later tasks build the rest, so leave clean seams but no stubs for in-scope behavior. Write the tests the docs assign to this task alongside the code.

${VERIFY_CMDS} Iterate until green. Do NOT commit and do NOT edit SPEC.md or docs/. Follow the docs exactly; where they are wrong, contradictory, silent or infeasible on the real platform, follow decisions.md, make the least-surprising choice, and list EVERY departure in spec_issues (a later stage records them in the docs; an unlisted departure blocks the commit).

Return the structured report.`
}

function reviewPrompt(t, lens, impl) {
  return `ROLE: independent code reviewer of SPEC.md task ${t} for Doro Organizer. The implementer's uncommitted changes are in the working tree of ${ROOT} (git status, git diff, untracked files).

${ENV}

LENS: ${lens.text}

Read the SPEC.md row for ${t} and the invariants it cites, docs/decisions.md, the cited doc sections, and every changed file in full. Do NOT edit files and do NOT run Gradle (another agent owns the build). Assume defects exist. Each finding: file:line, severity, the problem with its justification (doc section, DEC-nn, V<n> or platform fact) and a concrete fix. No style nits unless they hurt clarity or maintainability.

Implementer report:
${JSON.stringify(impl, null, 1)}

Return the findings, most severe first.`
}

function fixPrompt(t, findings, impl) {
  return `ROLE: fixer for SPEC.md task ${t} for Doro Organizer. Independent reviewers reported the findings below on the uncommitted changes in ${ROOT}.

${ENV}

Verify each finding against the code, SPEC.md and the docs before acting. Apply every correct one; reject wrong ones with a reason (when you reject because the docs are wrong, say so: that becomes a recorded departure). Then run the full verification until green. ${VERIFY_CMDS} Do NOT commit and do NOT edit SPEC.md or docs/. List every remaining departure from the docs in spec_issues.

Implementer report:
${JSON.stringify(impl, null, 1)}

FINDINGS:
${JSON.stringify(findings, null, 1)}

Return the structured report.`
}

function recordPrompt(t, impl, fix, fidelity) {
  return `ROLE: departure recorder for SPEC.md task ${t} for Doro Organizer. Implementation, three-lens review and fixes are in the uncommitted working tree of ${ROOT}. The final verifier refuses to commit when the code departs from SPEC.md or the docs without a written record. Your job: make the docs true to sound code, and send unsound departures back to code. Never lower the bar to make a commit happen.

${ENV}

1. DEPARTURES: start from the reported ones below, then do your own pass: read git diff and the untracked files against the SPEC.md row ${t}, its cited V<n>, docs/decisions.md and the cited doc sections. Look for names, signatures, constructor shapes, file locations, behavior, test ids and test placement that differ from the docs, and for parts of the row the code does not deliver.
2. TRIAGE each departure:
   (a) RECORD: the docs are wrong, contradictory, silent or infeasible on the real platform or toolchain, and the code's choice is sound and consistent with every DEC and V<n>.
   (b) CODE: the docs are right and feasible here; the code took a shortcut or is wrong. Do not record it; put it in must_fix_in_code with the exact change.
   (c) SCOPE: a part of the row is not delivered. Allowed only when it truly needs a component a later task builds; record it as a scope move to that task. Anything doable now is (b).
3. Record every (a) and (c). ${RECORD_RULES}
4. Do NOT edit code, do NOT run Gradle.

Reported departures:
${JSON.stringify({ implementer_spec_issues: impl.spec_issues, fixer_spec_issues: fix.spec_issues || [], fixer_rejected: fix.rejected || [], fidelity_findings: fidelity }, null, 1)}

Return the structured report.`
}

function codeFixPrompt(t, items) {
  return `ROLE: fixer for SPEC.md task ${t} for Doro Organizer. The departure recorder found places where the uncommitted code in ${ROOT} departs from docs that are right and feasible. Make the code follow the docs.

${ENV}

Verify each item against the docs first; if an item is itself wrong, leave the code, record the departure instead (${RECORD_RULES}) and list it under rejected. Then run the full verification until green. ${VERIFY_CMDS} Do NOT commit code.

ITEMS:
${JSON.stringify(items, null, 1)}

Return the structured report.`
}

function verifyPrompt(t, majors, history) {
  return `ROLE: final verifier and committer of SPEC.md task ${t} for Doro Organizer, working tree ${ROOT}.

${ENV}

1. Run the full verification from scratch. ${VERIFY_CMDS} Also run the docs checker: ${CHECKER}.
2. Check scope and hygiene: every change belongs to ${t}; nothing that must stay local is staged (local.properties, build outputs, .gradle, .claude/, IDE files); .gitignore covers them; docs/ has no uncommitted changes (the recorder commits docs; if any remain, list them in concerns and leave them).
3. Check fidelity: the code matches SPEC.md row ${t}, its cited V<n>, docs/decisions.md and the cited doc sections, or every departure is recorded in them (git log -5 -- docs SPEC.md shows the recorder's commit). An unrecorded departure is a failure.
4. Confirm each critical or major finding below is really fixed in the code (or was rightly rejected, with the doc record where the rejection says the docs were wrong); list any that are not in unfixed_major_findings.
5. If and only if everything passes and unfixed_major_findings is empty: change the status cell of the SPEC.md §T row ${t} from . (or ~) to x and change nothing else in SPEC.md; stage everything except docs/ (git add -A -- . ":(exclude)docs"), and stage every shell script with its executable bit (git add --chmod=+x <file>; NTFS does not record it); commit with the message "${t}: <the task text of its SPEC.md row, shortened to one line>", a blank line, "§V: <the cites of the row>", a blank line, and "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>". Never push. Report the short hash.
6. Otherwise do not commit; leave the tree as it is and list every reason in failures, each concrete enough for a fixer to act on.

Critical and major findings and the history of this task's fix stages:
${JSON.stringify({ majors, history }, null, 1)}

Return the structured report.`
}

function repairPrompt(t, ver) {
  return `ROLE: repairer for SPEC.md task ${t} for Doro Organizer. The final verifier refused to commit the uncommitted work in ${ROOT}. Resolve every reason it gave, without lowering the bar.

${ENV}

For each failure: a code defect or unfixed finding → fix the code; a departure that is sound but unrecorded → record it (${RECORD_RULES}); a departure that is unsound → fix the code; a failing command → find the root cause and fix it (never weaken a test, a gate or a lint rule to pass). Then run the full verification until green. ${VERIFY_CMDS} Do NOT commit code and do NOT touch the SPEC.md status cell.

VERIFIER REPORT:
${JSON.stringify(ver, null, 1)}

Return the structured report.`
}

const results = []
for (const t of TASKS) {
  if (VERIFY_ONLY.includes(t)) {
    phase('Verify')
    const rv = await agent(verifyPrompt(t, PRIOR[t] || [], { note: REVERIFY_NOTE }), { label: 'reverify:' + t, phase: 'Verify', schema: VERIFY_SCHEMA, model: 'sonnet', effort: 'high' })
    results.push({ task: t, reverify: true, passed: !!(rv && rv.passed), commit: rv ? rv.commit : '', failures: rv ? rv.failures : ['verify agent failed'], concerns: rv ? rv.concerns : [] })
    if (!rv || !rv.passed) break
    log(t + ' re-verified and committed ' + rv.commit)
    continue
  }
  phase('Implement')
  const impl = await agent(implPrompt(t), { label: 'impl:' + t, phase: 'Implement', schema: IMPL_SCHEMA, effort: 'high' })
  if (!impl) { results.push({ task: t, stage: 'implement', failed: true }); break }

  phase('Review')
  const reviews = (await parallel(LENSES.map(l => () =>
    agent(reviewPrompt(t, l, impl), { label: 'review:' + t + ':' + l.key, phase: 'Review', schema: REVIEW_SCHEMA, effort: 'high' })
      .then(r => r && { lens: l.key, ...r })
  ))).filter(Boolean)
  const findings = reviews.flatMap(r => r.findings)
  const fidelity = reviews.filter(r => r.lens === 'fidelity').flatMap(r => r.findings)
  const majors = findings.filter(f => f.severity !== 'minor').map(f => f.id + ' ' + f.location + ': ' + f.problem)
  log(t + ': ' + findings.length + ' findings (' + majors.length + ' critical/major) from ' + reviews.length + '/3 reviewers')

  phase('Fix')
  const fix = findings.length
    ? await agent(fixPrompt(t, findings, impl), { label: 'fix:' + t, phase: 'Fix', schema: FIX_SCHEMA, effort: 'high' })
    : { applied: [], rejected: [], verification: impl.verification, green: impl.green, spec_issues: [] }
  if (!fix) { results.push({ task: t, stage: 'fix', failed: true }); break }

  phase('Record')
  const rec = await agent(recordPrompt(t, impl, fix, fidelity), { label: 'record:' + t, phase: 'Record', schema: RECORD_SCHEMA, effort: 'high' })
  let codeFix = null
  if (rec && rec.must_fix_in_code.length) {
    log(t + ': recorder sent ' + rec.must_fix_in_code.length + ' item(s) back to code')
    codeFix = await agent(codeFixPrompt(t, rec.must_fix_in_code), { label: 'codefix:' + t, phase: 'Fix', schema: FIX_SCHEMA, effort: 'high' })
  }

  phase('Verify')
  const history = { fix, record: rec || 'recorder failed', codeFix }
  let ver = await agent(verifyPrompt(t, majors, history), { label: 'verify:' + t, phase: 'Verify', schema: VERIFY_SCHEMA, model: 'sonnet', effort: 'high' })
  let repair = null
  if (ver && !ver.passed) {
    log(t + ': verifier refused (' + ver.failures.length + ' reason(s)); one repair round')
    repair = await agent(repairPrompt(t, ver), { label: 'repair:' + t, phase: 'Verify', schema: FIX_SCHEMA, effort: 'high' })
    if (repair) {
      ver = await agent(verifyPrompt(t, majors.concat(ver.unfixed_major_findings), { ...history, firstRefusal: ver.failures, repair }), { label: 'verify2:' + t, phase: 'Verify', schema: VERIFY_SCHEMA, model: 'sonnet', effort: 'high' })
    }
  }
  results.push({
    task: t,
    files: impl.files_changed.length,
    tests: impl.tests_added.length,
    findings: findings.length,
    majors: majors.length,
    applied: fix.applied.length,
    rejected: fix.rejected,
    recorded: rec ? rec.recorded : [],
    scope_moves: rec ? rec.scope_moves : [],
    docs_commit: rec ? rec.commit : '',
    sent_back_to_code: rec ? rec.must_fix_in_code.length : 0,
    repaired: !!repair,
    verdicts: reviews.map(r => r.lens + ': ' + r.verdict),
    passed: !!(ver && ver.passed),
    commit: ver ? ver.commit : '',
    failures: ver ? ver.failures : ['verify agent failed'],
    concerns: ver ? ver.concerns : [],
  })
  if (!ver || !ver.passed) break
  log(t + ' committed ' + ver.commit)
}
return { tasks: TASKS, done: results.filter(r => r.passed).map(r => r.task), results }
