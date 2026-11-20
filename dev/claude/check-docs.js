// Usage: node check-docs.js <repoRoot>
// Checks: relative links + anchors (GitHub slug rules), DEC/Q references vs decisions.md, leftover "pending decision" notes.
const fs = require('fs');
const path = require('path');
const root = process.argv[2];
const files = ['CLAUDE.md', 'stakeholder-notepad-spec.md', ...fs.readdirSync(path.join(root, 'docs')).filter(f => f.endsWith('.md')).map(f => 'docs/' + f)];

function slug(h) {
  return h.trim().toLowerCase()
    .replace(/<[^>]+>/g, '')
    .replace(/[^\p{L}\p{N}\s_-]/gu, '')
    .replace(/\s/g, '-');
}
const anchors = {};
const texts = {};
for (const f of files) {
  const t = fs.readFileSync(path.join(root, f), 'utf8');
  texts[f] = t;
  const seen = {};
  const set = new Set();
  let inFence = false;
  for (const line of t.split(/\r?\n/)) {
    if (/^\s*```/.test(line)) { inFence = !inFence; continue; }
    if (inFence) continue;
    const m = /^(#{1,6})\s+(.*)$/.exec(line);
    if (!m) continue;
    const text = m[2].replace(/`/g, '').replace(/\[([^\]]*)\]\([^)]*\)/g, '$1');
    let s = slug(text);
    if (seen[s] !== undefined) { seen[s]++; s = s + '-' + seen[s]; } else seen[s] = 0;
    set.add(s);
  }
  anchors[f] = set;
}

let problems = 0;
for (const f of files) {
  const t = texts[f];
  const noCode = t.replace(/```[\s\S]*?```/g, '').replace(/`[^`\n]*`/g, '');
  const re = /\[[^\]]*\]\(([^)\s]+)\)/g;
  let m;
  while ((m = re.exec(noCode))) {
    const target = m[1];
    if (/^(https?:|mailto:)/.test(target)) continue;
    const [p, a] = target.split('#');
    const resolved = p ? path.posix.normalize(path.posix.join(path.posix.dirname(f), p)) : f;
    if (!anchors[resolved]) {
      if (!fs.existsSync(path.join(root, resolved))) { console.log(`BROKEN FILE  ${f} -> ${target}`); problems++; }
      continue;
    }
    if (a && !anchors[resolved].has(a.toLowerCase())) { console.log(`BROKEN ANCHOR ${f} -> ${target}`); problems++; }
  }
}

const dec = texts['docs/decisions.md'];
const decDefined = new Set([...dec.matchAll(/^\|\s*(DEC-\d+)\s*\|/gm)].map(x => x[1]));
const qDefined = new Set([...dec.matchAll(/^\|\s*(Q\d+)\s*\|/gm)].map(x => x[1]));
for (const f of files) {
  if (f === 'stakeholder-notepad-spec.md') continue;
  const refs = new Set([...texts[f].matchAll(/\bDEC-(\d+)\b/g)].map(x => 'DEC-' + x[1]));
  for (const r of refs) if (!decDefined.has(r)) { console.log(`UNDEFINED ${r} in ${f}`); problems++; }
  const qrefs = new Set([...texts[f].matchAll(/\bQ(\d{1,2})\b/g)].map(x => 'Q' + x[1]));
  for (const r of qrefs) if (!qDefined.has(r)) { console.log(`UNDEFINED ${r} in ${f}`); problems++; }
}

const pending = /(not yet recorded|needs this one added|needs the matching amendment|needs a decision entry|does not yet name|decisions\.md needs|amendment (requested|needed|pending))/i;
for (const f of files) {
  texts[f].split(/\r?\n/).forEach((line, i) => {
    if (pending.test(line)) { console.log(`PENDING NOTE ${f}:${i + 1}: ${line.slice(0, 160)}`); problems++; }
  });
}

console.log(`decisions defined: ${decDefined.size} (${[...decDefined].sort((x, y) => +x.slice(4) - +y.slice(4)).join(' ')})`);
console.log(`owner questions defined: ${qDefined.size}`);
console.log(problems ? `PROBLEMS: ${problems}` : 'ALL CHECKS PASSED');
