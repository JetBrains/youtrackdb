<!-- MANIFEST
findings: 5   severity: {blocker: 0, should-fix: 3, suggestion: 2}
gate: PASS
-->

## Findings

### R1 [should-fix]
**Location**: track-6.md absent-vs-null; `EntityImpl.hasProperty` (:3180); `YTDBMatchPlanStep` projection
**Issue**: `Result.getProperty` collapses absent and null-valued; boundary `MAP`/`SINGLE_VALUE` projection must load the entity via the matched RID (`EntityImpl.hasProperty`) at iteration time, not at plan-build time. Getting this wrong silently merges two native-distinct multisets — the highest-severity correctness class in this track.
**Proposed fix**: Step 3 lands `GremlinProjectionAssembler` with entity-layer presence checks; Step 7 equivalence tests require both fixtures (absent key omitted vs present-null key included) before any aggregate work merges.

### R2 [should-fix]
**Location**: track-6.md count short-circuit; `SelectExecutionPlanner.handleHardwiredCountOnClass` (:501-530) / `handleHardwiredCountOnClassUsingIndex` (:566+); `YTDBGraphCountStrategy` (:113-118 `applyPrior`)
**Issue**: Count fast-path today runs only inside `SelectExecutionPlanner` after SQL parse. Gremlin `count()` will compile to `RETURN count(*)` via `MatchExecutionPlanner` → `SelectExecutionPlanner`, but the hardwired helpers are `private static` on `SelectExecutionPlanner` and are **not** invoked from `MatchExecutionPlanner` today (grep confirms no `handleHardwired` in `MatchExecutionPlanner.java`). Extraction must be callable from the MATCH additive path without changing SELECT semantics. `YTDBGraphCountStrategy` already runs after `GremlinToMatchStrategy` and covers `g.V().count()` / `g.V().hasLabel(L).count()` natively — translated shapes must either hit the same `CountFromClassStep` or decline so the strategy fallback still works.
**Proposed fix**: Step 6 extracts package-visible helper(s), invokes from `MatchExecutionPlanner` post-`buildPatterns` (per plan), and adds equivalence tests for single-class count + indexed-equality count; multi-label / non-polymorphic declines to `YTDBGraphCountStrategy`.

### R3 [should-fix]
**Location**: track-6.md `ByModulatorTranslator`; Track 5 sub-walker
**Issue**: Value-side `by(__.count())` / `by(__.fold())` runs a nested sub-traversal. Must reuse `SubTraversalPredicateAdapter` + `walkChild` with the same swallow/capture contract; a fresh context per `by` child reintroduces the Track 5 A4 alias trap inside group/order projections.
**Proposed fix**: Step 4 explicitly routes value-side `by` through `RecognitionContext.walkChild` with a documented sub-context config (capture-only, no `putAliasFilter` commit); unit test with two `by` children that must not alias-collide.

### R4 [suggestion]
**Location**: `GremlinPlanFingerprint`; Track 5 BG1
**Issue**: Track 6 adds new return/projection shapes that change `MatchPlanInputs.returnItems` fingerprint class. Any positive `matchExpressions` writer in later steps must extend the fingerprint — latent from Track 5.
**Proposed fix**: If Step 6 adds detached positive MATCH expressions for aggregates, extend fingerprint in the same step.

### R5 [suggestion]
**Location**: track-6.md aggregate empty-input; `YTDBMatchPlanStep.processNextStart`
**Issue**: `dropNullRows` loop must run **before** MAP accumulation for `group`/`groupCount` (single emitted map) but **per row** for `SCALAR` aggregates. Wrong ordering emits a traverser carrying `null` where native emits nothing.
**Proposed fix**: Step 7 documents iteration order: open stream → per-row drop flags → branch on `BoundaryOutputType` for payload shape.

## Gate verdict
**PASS** — 0 blockers; R1–R3 are test-gated should-fixes.
