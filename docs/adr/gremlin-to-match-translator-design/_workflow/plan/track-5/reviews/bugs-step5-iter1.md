<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: BG1, sev: suggestion, loc: "GremlinPlanFingerprint.java:32-38", anchor: "### BG1 ", cert: "C-match-expr", basis: "fingerprint omits matchExpressions — latent cache-key collision if a future recogniser populates positive detached MATCH expressions"}
overall: PASS
evidence_base: {section: "## Evidence base", certs: 8, matches: 7}
cert_index:
  - {id: C-bind-total, verdict: MATCHES, anchor: "#### C-bind-total "}
  - {id: C-slot-shape, verdict: MATCHES, anchor: "#### C-slot-shape "}
  - {id: C-rid-bypass, verdict: MATCHES, anchor: "#### C-rid-bypass "}
  - {id: C-fingerprint-not, verdict: MATCHES, anchor: "#### C-fingerprint-not "}
  - {id: C-rebind-exec, verdict: MATCHES, anchor: "#### C-rebind-exec "}
  - {id: C-cache-lifecycle, verdict: MATCHES, anchor: "#### C-cache-lifecycle "}
  - {id: C-starts-with-range, verdict: MATCHES, anchor: "#### C-starts-with-range "}
  - {id: C-match-expr, verdict: CONFIRMED, anchor: "#### C-match-expr "}
flags: [CONTRACT_OK, MCP_STEROID_UNAVAILABLE]
-->

GATE VERDICT: PASS

Step-5 bugs review (GremlinPlanCache / D5 — HEAD~1..HEAD). mcp-steroid unreachable; symbol audits used diff read + direct source inspection (grep-only).

## Findings

### BG1 [suggestion]
**Certificate**: C-match-expr
**Location**: `GremlinPlanFingerprint.fingerprint` (GremlinPlanFingerprint.java:32-38).
**Issue**: The fingerprint enumerates pattern topology, alias filters, `notMatchExpressions`, and return projection, but not `MatchPlanInputs.matchExpressions()`. The current Gremlin walk never populates positive detached MATCH expressions (the field stays empty in `GremlinStepWalker.buildResult`), so there is no live collision today. A later recogniser that writes to `matchExpressions` without extending the fingerprint could map two shapes onto one cache entry.
**Refutation considered**: Traced `buildResult` — only `notMatchExpressions` is wired from `WalkerContext`; `matchExpressions` is left at the builder default. R6 tests exercise NOT-differing shapes via `notMatchExpressions` and pass by construction.
**Suggestion**: When positive detached MATCH expressions land, add an `;M:` (or equivalent) section to `GremlinPlanFingerprint` mirroring `appendNotExpressions`.

## Evidence base

#### C-bind-total: GremlinPredicateAdapter total parameterization (production path)
- **Trace**: Production recognisers pass non-null `RecognitionContext` into `toFilter(..., ctx)`. `valueExpression` binds every scalar `Compare`, `Contains` element, `Text`/`TextP` operand, and regex pattern via `ctx.bindParam`; structural tokens (`classEquals`, inline RIDs, `~label`) stay out of the adapter. **Verdict: MATCHES.**

#### C-slot-shape: shape-pure slot numbering
- **Trace**: `WalkerContext.bindParam` allocates `nextParamSlot++` per call regardless of value; `SubTraversalPredicateAdapter.bindParam` forwards to the parent so sub-walk and top-level share one monotonic sequence. Slot count depends on predicate *shape* (e.g. `within` arity, declared-String `startingWith` range vs strict), not on bound values. **Verdict: MATCHES.**

#### C-rid-bypass: RID-bearing walks bypass cache
- **Trace**: `StartStepRecogniser` and `HasStepRecogniser` (`ID_KEY`) call `markRidBearing()` before emitting inline `@rid IN [...]` literals; `buildResult` sets `cacheEligible = !ctx.ridBearing()`. `buildPlan` skips get/put when ineligible. Test `hasId_bypassesPlanCache` pins this. **Verdict: MATCHES.**

#### C-fingerprint-not: NOT-differing shapes occupy distinct entries
- **Trace**: `appendNotExpressions` walks `inputs.notMatchExpressions()` with structural path rendering (`NO_PARAMS` / `toGenericStatement` for `?`). Test `notDifferingShapes_distinctFingerprints` covers `not(out("knows"))` vs `not(out("likes"))` and vs bare `V()`. **Verdict: MATCHES.**

#### C-rebind-exec: cached plan rebinds per-walk values at execution
- **Trace**: `TranslationResult.inputParameters` (Integer-keyed map from walk) is installed on `YTDBMatchPlanStep` and set on `openArming` via `ctx.setInputParameters`. Cache hit returns `cached.copy(ctx)`; miss returns `plan.copy(ctx)` after storing a closed template. Test `cachedPlan_rebindsSecondValue` asserts second predicate value multiset. Invariant: any `?` in the plan implies a non-empty `inputParameters` map from the same walk. **Verdict: MATCHES.**

#### C-cache-lifecycle: put/get/close mirrors YqlExecutionPlanCache
- **Trace**: `putInternal` deep-copies with `BasicCommandContext`, closes the stored template, `getInternal` returns `result.copy(ctx)`. Timeout change invalidates via `MetadataUpdateListener` hooks registered in `SharedContext`. Same pattern as `YqlExecutionPlanCache.putInternal`. **Verdict: MATCHES.**

#### C-starts-with-range: declared-String startingWith range with positional params
- **Trace**: When `ctx != null` and `startsWithRange` succeeds, lower and upper bounds each bind separate slots; upper bound is `incrementLastCodePoint(prefix)` computed at translation time and stored alongside the prefix in the same walk's `inputParameters`, so cache reuse rebinding both slots stays consistent. Empty-prefix and max-code-point cases fall back to strict `STARTSWITH ?` (one slot). **Verdict: MATCHES.**

#### C-match-expr: fingerprint omits matchExpressions (BG1)
- CONFIRMED-as-latent: see BG1 body. Not reachable on current Gremlin recogniser set.
