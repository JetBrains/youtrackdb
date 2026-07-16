# Bugs dimensional review — gate check (iteration 2), Step 2

Gate check on the `Review fix:` commit `ec38d8fdc5` that addressed BG1 from
`bugs-step2-iter1.md`. Diff target: `ec38d8fdc5~1..ec38d8fdc5`.

## Verdicts

- **BG1: VERIFIED** — the `startingWith` path no longer throws. Both throw
  sites in `MatchWhereBuilder.startsWith` (empty prefix at line 156, and the
  all-max-code-point overflow via `incrementLastCodePoint` at lines 365-366)
  are handled by the new shared `startsWithRange` helper (guards empty before
  the call, catches `IllegalArgumentException` for the max-code-point case,
  returns `null` on both). `startingWith` and `notStartingWith` both route
  through it — `notStartingWith` declines the whole predicate when the range
  is null rather than composing a guarded negation over null. The normal path
  is preserved, and four decline tests (empty + max-code-point ×2) assert
  `isNull()`. (grep-only — mcp-steroid PSI timed out.)

## Summary

- **PASS** — dimensional review loop converged for Step 2 (BG2 is a Step-3
  deferral, out of scope for this loop).
