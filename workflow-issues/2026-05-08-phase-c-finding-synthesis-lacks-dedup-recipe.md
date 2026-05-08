---
severity: medium
phase: phase-c
source-session: 2026-05-08 /execute-tracks unit-test-coverage
---

# Phase C finding-synthesis lacks a deduplication / grouping recipe

## Symptom

Phase C of Track 20 spawned 9 dimensional review sub-agents (4 baseline +
crash-safety pair + performance pair + test-structure). They returned ~74
raw findings — substantially more than would fit in any single review-fix
iteration. The orchestrator had to:

1. Cross-dedupe findings: 5 reviewers (`review-code-quality`,
   `review-bugs-concurrency`, `review-performance`,
   `review-test-concurrency`, `review-test-structure`) all flagged the
   same `Thread.sleep(10)` polling loop in
   `CASDiskWriteAheadLogLifecycleTest` from different framings; 3
   reviewers flagged the same `CoverageTestWALRecordIds` empty-class
   issue; 3 reviewers flagged the same reflective-field-probe brittleness
   from different angles. Manually walking nine review outputs to spot
   these overlaps cost roughly 5 minutes of the orchestrator's time.
2. Pivot the 74→~23 unique items into a multi-iteration plan respecting
   the soft `~15 findings / ~10 files` budget (plus a deferred bucket).
3. Synthesize a presentable findings table for the user.

The workflow doc that drives this — `track-code-review.md` §Synthesis —
is three short bullets ("Deduplicate / Prioritize / Attribute"). The
agent reinvents the dedup-and-group strategy each Phase C.

## Reproduction context

- Phase: phase-c
- Workflow doc(s) involved: `.claude/workflow/track-code-review.md`
  §Synthesis (the three bullets); also referenced from
  `.claude/workflow/code-review-protocol.md`
- Tool / sub-agent involved: any track-level review with ≥6 sub-agents
  spawned (i.e., baseline + ≥2 conditional groups)
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: any Phase C track-level review where the cumulative
  findings count exceeds the iteration budget heuristic (~15 in-scope) —
  in practice this fires whenever the diff touches ≥3 conditional review
  dimensions and ≥4 source files

## Why it's a problem

Without a recipe, every Phase C agent re-invents:

- *How* to spot cross-dimension duplicates (pivot by file:line? by issue
  shape? by suggested fix?). This session's pivot key was
  "issue + suggested fix shape" — but I had no doc telling me to start
  there, and other agents will reasonably pick a different key.
- *How* to classify the deduped set into iteration buckets. I used
  "blocker → iter-1; should-fix concurrency-correctness + falsifiability
  → iter-1; should-fix style + boundary cases → iter-2; suggestion →
  deferred". This split worked, but it is not the only sensible split —
  another orchestrator might do "all should-fix in one mega-iter, all
  suggestions in iter-2".
- *How* to format the synthesized list when presenting to the user.

The cost of re-inventing the recipe is bounded (a few minutes per
session) but recurring — and the current shape is fragile because two
agents on the same finding-set could legitimately produce different
splits, which makes the workflow non-deterministic across resumes.

## Proposed fix

Edit `.claude/workflow/track-code-review.md` §Synthesis (or add a new
companion file `.claude/workflow/finding-synthesis-recipe.md` and
reference it from §Synthesis) with a worked example:

1. **Pivot order**: file:line → issue shape → severity. List unique
   findings as a table with `(file, line, issue, fixed-by)` columns;
   merge rows whose `(file, line, issue)` triple matches across
   reviewers.
2. **Iteration bucketing**: blocker + concurrency-correctness +
   falsifiability + CI-hang risks → iter-1; style + boundary cases +
   smaller falsifiability tightenings → iter-2; suggestion-tier or
   structural-refactor → deferred (plan correction).
3. **Pre-spawn budget**: target 8–12 in-scope findings, ≤6–8 distinct
   source files per iteration. The current heuristic (~15 / ~10) is the
   soft ceiling; this is the comfortable target.
4. **Worked mini-example**: a 6-finding scenario showing the merge,
   the iteration split, and the deferred bucket — using a real shape
   like the Track-20 `Thread.sleep` finding (5-way duplicate → single
   row in iter-1).

Alternative: add a synthesis script under
`.claude/scripts/synthesize-review-findings.py` that takes the raw
review outputs and emits a deduped table + iteration-bucket suggestion
based on heuristics. Heavier lift; the doc-only fix is the cheap
intermediate.

## Acceptance criteria

- A Phase C orchestrator reading the synthesis recipe in a single pass
  can dedupe and bucket a 50+ raw-finding set without re-deriving
  strategy from scratch.
- The recipe lives at a single canonical path
  (`.claude/workflow/track-code-review.md` §Synthesis or a new file
  referenced from there).
- A worked example demonstrates the cross-dimension merge and the
  iteration split.
- Regression check: future Phase C sessions in similar tracks (e.g.,
  Track 21, Track 22) reach the iter-1 implementer spawn faster than
  Track 20's session did, with no need to invent the dedup pivot.
