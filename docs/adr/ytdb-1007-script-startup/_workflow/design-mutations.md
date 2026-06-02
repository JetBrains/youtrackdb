# Design Mutations Log — ytdb-1007-script-startup

Append-only review log for `design.md`. One entry per `edit-design`
mutation. This file is not stamped (append-only by contract) and is
removed by the Phase 4 cleanup commit.

## Mutation 1 — 2026-06-02 — phase1-creation (design.md)

**Diff summary**: Initial seed of `design.md` for the workflow startup
precheck script (YTDB-1007). Single file, no `design-mechanics.md`
companion (12 top-level sections, well under the length trigger).
Sections: Overview, Core Concepts (7 concepts), Component design,
Workflow, The JSON contract, State determination, No-drift
normalization path, Byte-source consolidation, migrate-range reuse,
Staging asymmetry, Mid-session re-entry, Testing strategy. Captures the
seven locked research decisions (script in `.claude/scripts/`, bash+jq,
`--mode {full,divergence-only,migrate-range}`, `actions_taken` =
autonomous-only, §1.6(h) keeps spec + script implements, walk-not-
compute boundary, staging asymmetry) and the four behavior-parity
invariants S1-S4.

**Mechanical checks** (target=design): PASS (0 findings on final run)
**Cold-read** (scope: whole-doc): PASS — could a cold reader build a
working mental model: YES

**Findings**:
- should-fix (mechanical, fixed): `overview-body` — Overview had only 4
  non-empty body lines; fixed by hard-wrapping the four paragraphs.
- should-fix (mechanical, fixed): `dsc-ai-tell` fragmented-header on
  "State determination" — single-line TL;DR triggered the one-line-
  paragraph heuristic; fixed by hard-wrapping the TL;DR.
- should-fix (mechanical, fixed): `dsc-ai-tell` fragmented-header on
  "migrate-range reuse" — same cause; fixed by hard-wrapping the TL;DR.
- suggestion (applied): footer-resolution — D/S records cited in
  References footers do not yet resolve (design-first; plan derived
  later). Added an Overview note that D/S records are defined in the
  forthcoming implementation plan.
- suggestion (recorded, not applied): Workflow section lacks Edge cases
  and References footers. Workflow is shape-exempt and the reviewer
  judged it comprehensible as-is; left for parity at author's
  discretion.
- suggestion (recorded, cleared): nine sibling sections share the
  standard TL;DR + Edge cases + References template, which is the
  prescribed per-section shape, not a custom structure — no
  consolidation warranted.

**Iterations**: 2 of 3 (PASS) — round 1 cleared the three mechanical
should-fix findings; cold-read PASS with three suggestions; applied the
footer-resolution suggestion and re-verified mechanical clean.
