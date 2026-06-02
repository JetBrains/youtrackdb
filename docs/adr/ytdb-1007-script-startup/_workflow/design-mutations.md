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

## Mutation 2 — 2026-06-02 — content-edit (design.md)

**Diff summary**: Phase 2 consistency-review fix CR1 (mechanical,
should-fix). Corrected the four byte-copy site census in §"Byte-source
consolidation" TL;DR. The sentence named "the same file's normalization
recompute" as the third walk copy, which is wrong against the live tree:
`workflow-drift-check.md:239` is a distinct presence-check loop (builds a
companion `STAMPED_FILES` path list), not a copy of the value-extraction
walk, and it is consolidated separately by Track 3 / §"No-drift
normalization path". The genuine third copy — `migrate-workflow/SKILL.md`
Step 2.0 (Bootstrap unstamped artifacts) — was omitted. Substituted
`migrate-workflow` Step 2.0 for "the same file's normalization recompute",
keeping the exact "four places / three copies → one implementation + one
spec" framing. The following sentence ("the three copies are replaced by a
call to the script") and the neighboring sections stay accurate.

**Mechanical checks** (target=design): PASS (0 findings)
**Cold-read** (scope: bounded): PASS (1 suggestion, out of scope)

**Findings**:
- suggestion (recorded, not applied): References-footer form uses
  `- D-records: D4, D5` rather than the per-record `- D7: <label>` form.
  Pre-existing, document-wide pattern not introduced by this edit; same
  known-debt footer item recorded under Mutation 1. Deferred to a future
  whole-doc pass.

**Iterations**: 1 of 3 (PASS) — mechanical clean on first run; cold-read
PASS with one out-of-scope suggestion; no blockers or should-fix to apply.
