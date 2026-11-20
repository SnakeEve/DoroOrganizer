// Usage: node usage-sum.js <workflowsDir> <mainTranscript>
// Sums API usage per workflow run (deduped by message id) and for the main session.
const fs = require('fs')
const path = require('path')
const [wfDir, mainFile] = process.argv.slice(2)

function sumFiles(files) {
  const seen = new Set()
  const t = { in: 0, cw: 0, cr: 0, out: 0, msgs: 0, byModel: {}, first: null, last: null }
  for (const f of files) {
    for (const line of fs.readFileSync(f, 'utf8').split('\n')) {
      if (!line.includes('"usage"')) continue
      let j
      try { j = JSON.parse(line) } catch { continue }
      const m = j.message
      if (!m || !m.usage) continue
      const id = m.id || j.requestId || j.uuid
      if (seen.has(id)) continue
      seen.add(id)
      const u = m.usage
      const add = { in: u.input_tokens || 0, cw: u.cache_creation_input_tokens || 0, cr: u.cache_read_input_tokens || 0, out: u.output_tokens || 0 }
      t.in += add.in; t.cw += add.cw; t.cr += add.cr; t.out += add.out; t.msgs++
      const model = (m.model || '?').replace(/^claude-/, '')
      const bm = t.byModel[model] || (t.byModel[model] = { in: 0, cw: 0, cr: 0, out: 0 })
      bm.in += add.in; bm.cw += add.cw; bm.cr += add.cr; bm.out += add.out
      if (j.timestamp) {
        if (!t.first || j.timestamp < t.first) t.first = j.timestamp
        if (!t.last || j.timestamp > t.last) t.last = j.timestamp
      }
    }
  }
  return t
}
const M = n => (n / 1e6).toFixed(1) + 'M'
function show(name, t) {
  const models = Object.entries(t.byModel).map(([k, v]) => `${k}: out ${M(v.out)} fresh-in ${M(v.in + v.cw)} cache-read ${M(v.cr)}`).join(' | ')
  console.log(`${name}  ${(t.first || '').slice(5, 16)}..${(t.last || '').slice(11, 16)}  msgs ${t.msgs}  out ${M(t.out)}  fresh-in ${M(t.in + t.cw)}  cache-read ${M(t.cr)}\n    ${models}`)
}
for (const d of fs.readdirSync(wfDir).sort()) {
  const dir = path.join(wfDir, d)
  if (!fs.statSync(dir).isDirectory()) continue
  const files = fs.readdirSync(dir).filter(f => f.startsWith('agent-') && f.endsWith('.jsonl')).map(f => path.join(dir, f))
  show(d + ` (${files.length} agents)`, sumFiles(files))
}
if (mainFile) show('MAIN SESSION', sumFiles([mainFile]))
