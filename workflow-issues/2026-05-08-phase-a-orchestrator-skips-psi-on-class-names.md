---
severity: medium
phase: phase-a
source-session: 2026-05-08 /execute-tracks unit-test-coverage
---

# Phase A orchestrator pattern-induces target class names instead of PSI-verifying them before writing the step file

## Symptom

While drafting Track 21's step file `## Description` (sub-step 2c, atomic
write), the orchestrator named the live B-tree lifecycle classes as
`CellBTreeSingleValueV3`, `CellBTreeMultiValueV2`, and `SBTreeV2` —
inferred by pattern from the existing V1 packages
(`CellBTreeBucketSingleValueV1` / `CellBTreeSingleValueEntryPointV1` /
`SBTreeBucketV1`). All three names resolve to **NOT FOUND** via PSI
shortname search project-wide; the actual live engine class is
`com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTree`
(generic, used by both single- and multi-value engines via two wrapped
instances at `BTreeMultiValueIndexEngine.java:75-83`). The misreference
was caught only by the iter-1 risk reviewer's PSI re-validation, costing
the session one iter-2 fix round (apply_patch full description rewrite +
three iter-2 gate spawns).

## Reproduction context

- Phase: phase-a
- Workflow doc(s) involved: `.claude/workflow/track-review.md` §What You
  Do sub-step 2c (atomic step file Write); §Tooling — PSI is required
  for symbol audits in Phase A.
- Tool / sub-agent involved (if any): main-agent orchestrator before
  spawning the Phase A reviews; risk-review sub-agent caught it.
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: any Phase A track whose `## Description` names
  specific production classes that the orchestrator has not previously
  read or PSI-verified — particularly tracks reconstructed from a
  backlog gap, where the orchestrator infers names from cluster naming
  conventions rather than from existing tests / production callers.

## Why it's a problem

The Phase A "Tooling — PSI is required for symbol audits" rule
(`track-review.md` lines 27-58) binds review sub-agents — it instructs
them to use PSI for find-usages / find-implementations / type-hierarchy
queries. It does not explicitly require the orchestrator to PSI-verify
class names *before* committing them to the step file. Pattern-inducing
class names from precedent is a known trap: refactors rename, version
bumps re-shape, and the V1 → V2/V3 naming pattern often does not
survive a generic-extraction refactor (as happened here — V2/V3 share
a single generic `BTree` class instead of getting their own
`CellBTreeSingleValueV3` etc.). Each occurrence costs one iter-2 round
that an upstream PSI check would have prevented. With ~7-step tracks
and multi-class step files, the failure mode is recurring.

## Proposed fix

Add an explicit pre-write rule to `track-review.md` §What You Do
sub-step 2c (or insert a new sub-step 2c-prep) that reads roughly:

> Before authoring the step file's `**What/How/Constraints/Interactions**`
> blocks, route every named target class — and any FQN, package, or
> SPI service — through `mcp-steroid` PSI find-class (when the IDE is
> reachable) to confirm existence and current FQN. Pattern-inducing
> class names from precedent (e.g., V1 → V2/V3) is a known trap;
> refactors and generic extractions can rename or collapse classes
> across version-suffixed packages. If `mcp-steroid` is unreachable,
> use `find … -name '<ClassName>.java'` and add a reference-accuracy
> caveat to the step file's Reconstruction note.

Cross-link to the existing PSI-rule paragraph (lines 27-58) so the
orchestrator-side requirement is co-located with the sub-agent-side
requirement.

## Acceptance criteria

- `track-review.md` §What You Do has an explicit pre-2c PSI-verify
  step or a paragraph inside 2c that names this requirement.
- The instruction explicitly cites the V1 → V2/V3 pattern-induction
  trap (or equivalent named trap from precedent), so the rule is
  obvious in context rather than an abstract "verify symbols".
- Regression check: a `grep -n "PSI-verify\|verify via PSI\|PSI find-class"
  .claude/workflow/track-review.md` matches the new rule.
- Optional: extend `prompts/risk-review.md` with a stock check item
  "Verify that every target class named in the step file resolves
  via PSI find-class" so the iter-1 risk reviewer continues to catch
  pattern-induced names that slip past the orchestrator-side rule.
