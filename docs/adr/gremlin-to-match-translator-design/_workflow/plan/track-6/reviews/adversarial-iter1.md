<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 2, suggestion: 1}
gate: PASS
-->

## Findings

### A1 [should-fix]
**Location**: track-6.md Plan of Work item 5 — walker post-processing for `g.V().values("age").mean()`
**Issue**: Aggregate recogniser that only looks at the aggregate step will compile `mean(currentAlias)` — wrong for property-extraction prefixes. The plan requires walker post-processing when the immediately preceding accepted step was `PropertiesStep`; if implemented only inside the aggregate recogniser without cursor rewind/peek, the `values` step will have already been consumed or never translated. The decomposer must pin **who** re-points the aggregate: a dedicated `GremlinStepWalker` post-pass over accepted steps, or an aggregate recogniser that `cursor.peek(-1)` / retains the last field-access IR on `WalkerContext`.
**Proposed fix**: Step 6 adds `WalkerContext.lastPropertyProjection` (or equivalent) set by `PropertiesStepRecogniser` and read by aggregate recognisers; decline when the prefix is not a single-key `values`/`properties` extraction.

### A2 [should-fix]
**Location**: track-6.md dedup with named labels
**Issue**: `dedup("a","b")` requires RETURN projection over labels that must be addressable in the MATCH result. If `as(label)` propagation (Step 2) does not surface user labels in return items, named dedup cannot compile and must decline — but anonymous `dedup()` only needs `returnDistinct`. Tests must cover both paths; a silent wrong dedup (distinct on wrong column) is worse than decline.
**Proposed fix**: Step 2 acceptance: `dedup()` → `returnDistinct`; `dedup("lbl")` declines until `as("lbl")` + projection exposes `lbl` in return items; document decline in Validation.

### A3 [suggestion]
**Location**: track-6 scope ~20 files; four families merged
**Issue**: Footprint is at the soft ceiling (~20–25 files) but below Track 5's split trigger (~29–38). No re-split recommended unless Phase B discovers `ByModulatorTranslator` + projection assembler each exceed ~12 files alone.
**Proposed fix**: Monitor during Step 4/3; keep one track unless file count exceeds ~25 production files.

## Gate verdict
**PASS** — 0 blockers.
