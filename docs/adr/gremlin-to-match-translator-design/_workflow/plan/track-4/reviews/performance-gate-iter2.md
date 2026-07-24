# Performance dimensional review — gate check (iteration 2)

Gate check on the `Review fix:` commit `953ebc7fab` that addressed PF1
(and folded/declined PF3) from `performance-iter1.md`. Diff target:
`953ebc7fab~1..953ebc7fab`.

## Verdicts

- **PF1: VERIFIED** — the per-row `ResultInternal` wrapper is removed from
  `evaluate(Identifiable)` in both `SQLContainsTextCondition` and
  `SQLEndsWithCondition` (line 42). The new `collateFromSchema` helper
  (`getImmutableSchemaClass(session).getProperty(name).getCollate()`)
  matches `SQLSuffixIdentifier.getCollate`'s schema lookup exactly, and a
  declared property yields a non-null `DefaultCollate`, so the wrapper is
  genuinely skipped on the common hot path while schema-less / nested-link /
  non-entity records fall through to the unchanged wrapper resolution.
  Behavior-preserving. (grep-only — mcp-steroid PSI unavailable this session.)

## New findings

- **PF4** [should-fix] `SQLContainsTextCondition.java:127` (also
  `SQLEndsWithCondition.java:124`) — the new fast path calls
  `left.getDefaultAlias().getStringValue()` per row. `getDefaultAlias()`
  allocates a fresh `SQLIdentifier` (a `SimpleNode` subclass, ~8 fields) on
  every call for a **row-invariant** value, reintroducing a per-row
  allocation heavier than the `ResultInternal` PF1 removed and undercutting
  PF1's allocation goal. **Fix:** resolve the base-identifier property name
  once (it never changes per record) rather than rebuilding an
  `SQLIdentifier` per row.

  > Numbering note: the gate-check agent emitted this as `PF2`, colliding
  > with iteration-1's `PF2` (regex ReDoS suggestion). Renumbered to `PF4`
  > (iter-1 high-water-mark was `PF3`) for a collision-free audit trail.

## Summary

- **FAIL** — PF1 verified, but a new should-fix (PF4) forces another fix
  iteration.
