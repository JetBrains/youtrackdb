# design-author params — phase1-creation, round 8 (dual-clean inner loop, readability findings)

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/research-log.md
- round: 8

## Scope (read this first)

Prose / presentation polish round. **Do NOT change decision content** (D1–D14,
D5-R), diagrams, code identifiers, or invariants. **Preserve the line-1
`<!-- workflow-sha: ... -->` stamp exactly.** Edit in place with `Edit`. Locate
each passage by its verbatim quote, not its line number. None needs new code
grounding.

## flagged_passages

1. **Four-piece deliverables crammed into one prose sentence (~lines 24-29,
   Overview).** "S0 builds four pieces over the sealed type: a visitor/transform
   framework; a lowering pass that converts the covered AST subset to
   `AnalyzedExpr`; a runtime evaluator over that IR; and a `NumericOps` helper
   extracted from the AST so both evaluators share one promotion engine." — convert
   the four semicolon-chained pieces to a short bulleted list under a stem ("S0
   builds four pieces over the sealed type:"), one bullet per piece, so the
   `NumericOps` "shared promotion engine" rationale stands on its own bullet. Mirror
   the bulleted treatment already used for the six concepts in Core Concepts. Keep
   the round-trip-parity sentence that follows as running prose.

2. **Tangled sentence split by an em-dash clause (~lines 448-450, Field-walk
   section).** "`Var`'s name path comes from flattening `SQLBaseIdentifier` — one of
   `levelZero` (a `SQLLevelZeroIdentifier`) or `suffix` (a `SQLSuffixIdentifier`) is
   non-null — into a `List<String>` via an `identifierToPath` mapper." — the core
   action "flattening `SQLBaseIdentifier` … into a `List<String>`" is split by an
   em-dash clause asserting a different fact, forcing a re-read. Split into two
   sentences: state the non-null invariant first ("`SQLBaseIdentifier` has exactly
   one of `levelZero` (a `SQLLevelZeroIdentifier`) or `suffix` (a
   `SQLSuffixIdentifier`) non-null."), then the flatten ("`Var`'s name path flattens
   that identifier into a `List<String>` via an `identifierToPath` mapper.").

3. **Test-matrix cell hides its mechanism (~line 761, Round-trip parity matrix).**
   The cell "NE null-session threading vs EQ (D11)" compresses its mechanism into a
   coined nominalization, while every sibling cell states its mechanism plainly
   ("Collation transform in comparison", "Integer-vs-double divide widening"). Make
   this cell consistent by naming the concrete contrast, e.g. "NE passes a null
   session to coercion, EQ the live one (D11)". Presentation only — keep the row's
   left-hand input column and the `(D11)` pointer; do not change any decision.

## Do NOT touch

- The worked interleavings / sequence traces, decision content, Mermaid diagrams,
  code identifiers, and invariants.
