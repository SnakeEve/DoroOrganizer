# FORMAT — SPEC.md encoding

SPEC.md is written in caveman encoding: drop articles, filler and hedging; fragments fine; symbols over words. Never compress code, paths, identifiers, numbers, versions, SQL, quoted strings or error messages.

Symbols: `→` leads to / becomes · `∴` therefore · `∀` every · `∃` exists · `!` must · `?` optional / unknown · `⊥` never / forbidden / nil · `≠` · `∈` · `∉` · `≤` · `≥` · `&` and · `|` or · `§` section.

Sections, in order: `§G` goal (1 line) · `§C` constraints · `§I` interfaces · `§V` invariants · `§T` tasks · `§B` bugs.

Shapes:

```
V<n>: <subject> <relation> <condition>        # §V, numbered, never reused
<kind>: <name> → <shape>                       # §I, kinds: cmd file intent perm pragma
id|status|task|cites                           # §T pipe table; status x done, ~ wip, . todo
id|date|cause|fix                              # §B pipe table; fix cites V<n> when an invariant was added
```

Rules: numbering monotonic (never reuse V, T or B ids); escape a literal `|` as `\|`; §T `cites` lists the §V / §I entries a task touches; only the spec skill edits SPEC.md, except build flipping §T status.
