// Usage: node journal-report.js <journal.jsonl>
// Prints per-agent structured results of a doro build run (impl/review/fix/record/codefix/verify/repair).
const fs = require('fs')
const lines = fs.readFileSync(process.argv[2], 'utf8').split('\n').filter(Boolean)
const labels = {}
for (const l of lines) {
  let j
  try { j = JSON.parse(l) } catch { continue }
  if (j.type === 'started') { labels[j.key] = j.label; continue }
  const label = j.label || labels[j.key] || '?'
  const r = j.result || j.output || j.value
  if (!r || typeof r !== 'object') { console.log(`[${j.type}] ${label}`); continue }
  const short = s => String(s).replace(/\s+/g, ' ').slice(0, 400)
  if (label.startsWith('impl:')) console.log(`${label}: files ${r.files_changed?.length} tests ${r.tests_added?.length} green ${r.green}; spec_issues ${r.spec_issues?.length}`)
  else if (label.startsWith('review:')) console.log(`${label}: ${r.findings?.length} findings (${r.findings?.filter(f => f.severity === 'critical').length} critical, ${r.findings?.filter(f => f.severity === 'major').length} major)`)
  else if (label.startsWith('fix:') || label.startsWith('codefix:') || label.startsWith('repair:')) console.log(`${label}: applied ${r.applied?.length} rejected ${r.rejected?.length} green ${r.green}` + (r.rejected?.length ? '\n   rejected: ' + r.rejected.map(short).join('\n   rejected: ') : ''))
  else if (label.startsWith('record:')) console.log(`${label}: recorded ${r.recorded?.length}, scope moves ${r.scope_moves?.length}, sent back to code ${r.must_fix_in_code?.length}, docs commit ${r.commit}, checker "${r.checker}"` + (r.scope_moves?.length ? '\n   move: ' + r.scope_moves.map(short).join('\n   move: ') : ''))
  else if (label.startsWith('verify')) console.log(`${label}: passed ${r.passed} commit ${r.commit}` + (r.failures?.length ? '\n   failure: ' + r.failures.map(short).join('\n   failure: ') : '') + (r.concerns?.length ? '\n   concern: ' + r.concerns.map(short).join('\n   concern: ') : '') + (r.unfixed_major_findings?.length ? '\n   unfixed: ' + r.unfixed_major_findings.map(short).join('\n   unfixed: ') : ''))
  else console.log(`${label}: ${short(JSON.stringify(r))}`)
}
