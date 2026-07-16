## bugs Review (gate check)

### Verdicts
- BG1: VERIFIED — WalkerContext.java:236-242 sweeps `clazz.getAllSubclasses()` under `if (polymorphic)`, declining a Text/regex predicate on a non-String property declared only on an included subclass; non-polymorphic path unchanged (sweep skipped, exact `@class` leaf filter already excludes subclass rows), `getAllSubclasses()` returns a non-null Set on the null-guarded `clazz` (no NPE, no over-declining), and the regression test is falsifiable — without the fix `countBoundarySteps` is 1, not the asserted 0.

### Summary
- PASS
