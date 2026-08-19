<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 1, suggestion: 3}
index:
  - {id: T1, sev: should-fix, loc: "track-5.md:36,49,81 (## Context and Orientation / Plan of Work step 1 / Interfaces); code: StepRecogniser.java:47, RecognitionContext.java:9-25, GremlinStepWalker.java:98-117", anchor: "### T1 ", cert: "I-subwalk", basis: "the sub-walker must drive a child walk against the registry, but a recogniser sees only the narrow RecognitionContext (no traversal / registry / cursor position) and the registry is private on GremlinStepWalker with no accessor; the Interfaces list enumerates other RecognitionContext additions but omits the sub-walk entry-point seam"}
  - {id: T2, sev: suggestion, loc: "track-5.md:39,40 (A4 sub-context bullets); code: WalkerContext.java:320-330,336-351", anchor: "### T2 ", cert: "P-lineref", basis: "A4 cites WalkerContext.java:156-163 for the AliasSequence restart (actually isReservedHasKey; the sequence is ~336-351) and :253-263 for setSingleReturnColumn clears-and-replaces (actually 320-330; 253-263 is addEdge/addEdgeAsNode)"}
  - {id: T3, sev: suggestion, loc: "track-5.md:36 (## Context and Orientation)", anchor: "### T3 ", cert: "P-connective-final", basis: "C&O calls all five steps the ConnectiveStrategy form with each child carrying a sub-traversal, but only AndStep/OrStep extend ConnectiveStep and WherePredicateStep (where(P)) carries a startKey+predicate, not a child traversal"}
  - {id: T4, sev: suggestion, loc: "track-5.md:46,53 (## Context and Orientation / Plan of Work step 5)", anchor: "### T4 ", cert: "E-d5fingerprint", basis: "the D5 cache key is the post-walk generic-statement fingerprint, but the translator path builds MatchPlanInputs with a null statement (useCache=false), so no generic statement exists to fingerprint; it must be synthesised from the IR"}
evidence_base: {section: "## Evidence base", certs: 9, matches: 5}
cert_index:
  - {id: P-notstep-final, verdict: MATCHES, anchor: "#### P-notstep-final "}
  - {id: P-patternbuilder, verdict: MATCHES, anchor: "#### P-patternbuilder "}
  - {id: P-managenot, verdict: MATCHES, anchor: "#### P-managenot "}
  - {id: P-r4net, verdict: MATCHES, anchor: "#### P-r4net "}
  - {id: P-d5seams, verdict: MATCHES, anchor: "#### P-d5seams "}
  - {id: I-subwalk, verdict: PARTIAL, anchor: "#### I-subwalk "}
  - {id: P-connective-final, verdict: PARTIAL, anchor: "#### P-connective-final "}
  - {id: P-lineref, verdict: PARTIAL, anchor: "#### P-lineref "}
  - {id: E-d5fingerprint, verdict: PARTIAL, anchor: "#### E-d5fingerprint "}
flags: [CONTRACT_OK]
-->

## Findings

### T1 [should-fix]
**Certificate**: I-subwalk
**Location**: track-5.md `## Context and Orientation` (line 36), `## Plan of Work` step 1 (line 49), `## Interfaces and Dependencies` (line 81); codebase: `StepRecogniser.recognize` (StepRecogniser.java:47), `RecognitionContext` contract (RecognitionContext.java:9-25), `GremlinStepWalker` registry (GremlinStepWalker.java:98-117).
**Issue**: The A4 sub-walker is the track's load-bearing new mechanism: a combinator recogniser (`AndStepRecogniser` / `OrStepRecogniser` / `NotStepRecogniser` / `WhereTraversalStepRecogniser`) must run each child sub-traversal "against the same recogniser registry" through a delegating sub-context. But a recogniser's only inputs are `(StepCursor cursor, RecognitionContext ctx)` (StepRecogniser.java:47), and `RecognitionContext`'s contract explicitly states a recogniser "cannot reach the traversal, the strategy list, the step cursor's position, or the pattern builder" (RecognitionContext.java:12-18). The recogniser registry is `private final Map<Class<?>, StepRecogniser> recognisers` on `GremlinStepWalker` with no accessor, and the dispatch loop lives inside `walk()` (GremlinStepWalker.java:184-206), whose public entry constructs its own fresh `WalkerContext` (line 178) and assembles a whole-traversal `TranslationResult` via `buildResult` — none of which a child sub-walk wants. So a combinator recogniser has no seam today by which to (a) reach the registry, (b) reuse the dispatch loop, and (c) hand it a delegating sub-context. The track's `## Interfaces and Dependencies` meticulously lists the *other* new `RecognitionContext` additions (`bindParam`, `notMatchExpressions`, the alias / boundary interception accessors) but omits this one — the sub-walk entry point a recogniser calls. The concrete sub-context *type* is deferred to the decomposer ("pinned against HEAD"), which is fine, but the *calling seam that crosses the recogniser→registry boundary* is a distinct new API surface that the plan does not name as required.
**Refutation considered**: Checked whether the sub-context type deferral already covers it — it does not: the delegating context implements the contribution/minting methods, but a recogniser still needs a *method to invoke* (a `RecognitionContext` sub-walk capability, or an extra recogniser input) plus registry/driver reachability, and neither exists on HEAD. Checked whether the recogniser could hold the registry itself — recognisers are stateless singletons (`StartStepRecogniser.INSTANCE`) constructed with no registry reference, so no.
**Proposed fix**: In `## Interfaces and Dependencies (modified)` add the sub-walk seam explicitly: either a `RecognitionContext` sub-walk method (e.g. `walkChild(Traversal.Admin, subContextConfig) → Outcome`) implemented on `WalkerContext` with access to the registry, or a shared sub-walk driver plus a `GremlinStepWalker` registry/driver exposure that the combinator recognisers reach. Note that the driver is the `walk()` dispatch loop factored out of the whole-traversal entry so the sub-walk skips the reserved-prefix scan, flag resolution, boundary-invariant assertion, and `buildResult`. The decomposer then pins the concrete shape.

### T2 [suggestion]
**Certificate**: P-lineref
**Location**: track-5.md `## Context and Orientation` A4 bullets (lines 39-40); codebase: `WalkerContext.setSingleReturnColumn` (WalkerContext.java:320-330), `AliasSequence` (WalkerContext.java:336-351).
**Issue**: Two A4-bullet line citations have drifted against HEAD. "`AliasSequence` restarts at 0 per context (WalkerContext.java:156-163)" — lines 156-163 are the `isReservedHasKey` Javadoc; the `AliasSequence` class (whose per-context reset the bullet means) is at 336-351 and its `next()` at 348-350. "`setSingleReturnColumn` clears and replaces the projection, WalkerContext.java:253-263" — `setSingleReturnColumn` is at 320-330; lines 253-263 are the `addEdge` / `addEdgeAsNode` region. The substantive claims are correct (the sequence is per-context, and `setSingleReturnColumn` does clear-and-replace the three parallel return lists at 324-330), so this is a citation-precision nit, not a design error. For contrast, the `## Interfaces … Signatures` and A5/A6 citations are all exact (`MatchPatternBuilder.java:153,204,255`; `MatchExecutionPlanner.java:766-771`; `notMatchExpressions` :48/:154; `setInputParameters` :106), so only the older A4 prose refs drifted.
**Refutation considered**: Read the cited lines to confirm they point at unrelated methods; confirmed the intended methods live elsewhere.
**Proposed fix**: Repoint to `WalkerContext.java:336-351` (AliasSequence) and `:320-330` (setSingleReturnColumn), or drop the exact line numbers in favour of method names.

### T3 [suggestion]
**Certificate**: P-connective-final
**Location**: track-5.md `## Context and Orientation` (line 36).
**Issue**: The C&O opens "The step-level logical filters (`AndStep` / `OrStep` / `NotStep` / `WhereTraversalStep` / `WherePredicateStep`) are the `ConnectiveStrategy` form — each child carries a sub-traversal of arbitrary recognized steps." Two imprecisions: (a) per `javap` on the fork, only `AndStep` / `OrStep` extend `ConnectiveStep`; `NotStep`, `WhereTraversalStep`, and `WherePredicateStep` extend `FilterStep` directly — `ConnectiveStrategy` specifically mints only the And/Or pair from infix `and()` / `or()`. (b) `WherePredicateStep` (from `where(P)`) does not carry a child sub-traversal — it carries a `startKey` + `Predicate` + `selectKeys` (a `$matched.<label>` reference), so the sub-walker does not apply to it. The work plan itself is correct: step 4 handles `WherePredicateStepRecogniser` as the distinct "`$matched.<label>` references" case, separate from the sub-walk combinators. Only the umbrella framing is loose.
**Refutation considered**: Confirmed step 4 treats `where(P)` distinctly, so the taxonomy slip does not propagate into the plan of work; it is C&O framing only.
**Proposed fix**: Narrow the framing — group `and` / `or` (ConnectiveStrategy) and `not` / `where(traversal)` as the child-sub-traversal + sub-walker family, and call out `where(P)` / `WherePredicateStep` as the `$matched.<label>` reference form that carries a predicate rather than a child traversal.

### T4 [suggestion]
**Certificate**: E-d5fingerprint
**Location**: track-5.md `## Context and Orientation` (line 46) and `## Plan of Work` step 5 (line 53).
**Issue**: D5 keys the `GremlinPlanCache` on "the value-independent post-walk generic-statement fingerprint (A3)". `SQLPositionalParameter.toGenericStatement` does emit a value-independent placeholder (SQLPositionalParameter.java:44-46), so the *value-independence* premise is sound. But on the translator path there is no generic statement to fingerprint: the strategy builds the plan through the additive `MatchExecutionPlanner(MatchPlanInputs)` constructor, which "leaves the inherited `statement` field null" (GremlinToMatchStrategy.java Javadoc "Plan caching"; the strategy runs `createExecutionPlan(ctx, false, /* useCache */ false)` at :391) — no `SQLMatchStatement` is ever produced. The fingerprint therefore has to be *synthesised* from the IR (the `Pattern` topology + the `aliasFilters` `SQLWhereClause.toGenericStatement` + the return items), which the plan assumes exists ("generic-statement fingerprint") rather than specifies as new work. This connects to the same `statement == null` constraint that already forces `useCache=false`.
**Refutation considered**: Verified the building blocks exist (`SQLWhereClause` / `SQLPositionalParameter` both carry `toGenericStatement`), so the fingerprint is *constructible* — this is a specification gap, not an infeasibility.
**Proposed fix**: In step 5 / C&O, state that the fingerprint is composed from the post-walk IR (pattern + alias-filter `toGenericStatement` renderings, positional-parameter placeholders in place of values) because the additive-constructor path carries no `SQLMatchStatement`; note the interaction with the existing `statement == null` / `useCache=false` constraint the decomposer must resolve.

## Evidence base

#### P-notstep-final: NotStep is final, hasNot desugars to NotStep(__.values(key)), and the Map.of registry rejects a duplicate key (A2)
- **Track claim**: A2 — one `NotStepRecogniser` under `NotStep.class`; `hasNot(key)` desugars to `NotStep(__.values(key))` and logical `not(...)` is also a `NotStep`; the fork's `NotStep` is `final`, so exact-class dispatch cannot separate them, and two registry entries throw a `Map.of` duplicate-key error at class init that the strategy's `RuntimeException` net does not swallow.
- **Search performed**: `javap` on `io.youtrackdb:gremlin-core:3.8.1-af9db90-SNAPSHOT` (the version pinned in the root pom, `gremlin.version`) for the filter steps; `javap -c` of `GraphTraversal.hasNot`; read of `GremlinStepWalker` registry + `GremlinToMatchStrategy` catch.
- **Code location**: `NotStep` = `public final class ... extends FilterStep` (javap); `GraphTraversal.hasNot(String)` bytecode: `new .../filter/NotStep` with `__.values([String])` as its child; `GremlinStepWalker.PRODUCTION_RECOGNISERS` is a `Map.of(...)` (GremlinStepWalker.java:98-103); `GremlinToMatchStrategy.apply` catches only `RuntimeException` (GremlinToMatchStrategy.java:217-224).
- **Actual behavior**: `NotStep` is `final`; `hasNot(key)` mints `NotStep(__.values(key))`, matching the track's desugaring. A second `NotStep.class` entry in the `Map.of` throws `IllegalArgumentException`; because it occurs in a static initialiser the JVM surfaces it as an `ExceptionInInitializerError` (an `Error`), which the `RuntimeException`-only net does not catch, so the compile fails loudly rather than declining — the effect the track relies on. The single-recogniser-branches-values-child-first design is therefore both required and correct.
- **Verdict**: MATCHES
- **Detail**: The Decision-Log phrasing "an `Error` the strategy's `RuntimeException` net rethrows" is slightly loose (the thrown `IllegalArgumentException` is itself a `RuntimeException`; the `Error` is the static-init wrapper), but the outcome — not swallowed, compile fails — is correct. Not raised as a finding.

#### P-patternbuilder: MatchPatternBuilder.build() exposes only the positive PatternIR; SQLMatchExpression is constructed internally; hasAlias() already exists (A5)
- **Track claim**: A5 — nothing Tracks 1-4 ship produces the detached NOT chain `manageNotPatterns` consumes; `MatchPatternBuilder` constructs `SQLMatchExpression` only internally and exposes only the positive `PatternIR build()` (:153, :204, :255).
- **Search performed**: Read of `MatchPatternBuilder.java` in full.
- **Code location**: `new SQLMatchExpression(-1)` at MatchPatternBuilder.java:153 (`addEdge`) and :204 (`addEdgeAsNode`); `build()` returns `PatternIR(pattern, aliasClasses, aliasFilters)` at :255-262; `hasAlias(String)` at :244-246.
- **Actual behavior**: `build()` returns only the positive pattern + alias maps; there is no API that emits a detached `SQLMatchExpression`. The A5 line citations are exact. The builder already carries `hasAlias(String)` (:244-246) whose Javadoc names "the pattern-form NOT recogniser checking that its origin alias matches an already-registered node … before constructing detached AST" — i.e. the A6 origin-alias-present check has a ready helper. A5's "add a builder capability or a documented direct-AST exemption" is a real, correctly-scoped gap.
- **Verdict**: MATCHES

#### P-managenot: manageNotPatterns has exactly the two throws A6 describes, at the cited lines
- **Track claim**: A6 — `manageNotPatterns`'s second throw fires on `exp.getOrigin().getFilter() != null` (MatchExecutionPlanner.java:766-771); the recogniser declines only when the NOT origin alias is absent from the positive pattern; build the NOT origin as a bare alias reference so the second throw never fires.
- **Search performed**: Read of `MatchExecutionPlanner.manageNotPatterns` (MatchExecutionPlanner.java:750-807) and its call site (:627).
- **Code location**: first throw at :760-764 (`pattern.aliasToNode.get(exp.getOrigin().getAlias()) == null` → "first alias in a NOT expression has to be present in the positive pattern"); second throw at :766-771 (`exp.getOrigin().getFilter() != null` → "WHERE condition on the initial alias").
- **Actual behavior**: Exactly two guard throws, matching A6. Line range 766-771 is exact for the second. The recogniser's plan — decline when origin alias absent (guards the first throw) and emit a bare origin filter (avoids the second) — is faithful to the code.
- **Verdict**: MATCHES

#### P-r4net: manageNotPatterns throws CommandExecutionException (a RuntimeException) eagerly inside the apply() net (R4)
- **Track claim**: R4 — `manageNotPatterns` builds eagerly inside the strategy's `apply()`, whose `RuntimeException` net catches its `CommandExecutionException` and degrades to a clean native decline.
- **Search performed**: Read of `CommandExecutionException` / `CoreException` / `BaseException` declarations; the `manageNotPatterns` call path; `GremlinToMatchStrategy.apply`.
- **Code location**: `CommandExecutionException extends CoreException` (CommandExecutionException.java:24); `CoreException extends BaseException` (CoreException.java:11); `BaseException extends RuntimeException` (BaseException.java:28). `manageNotPatterns` is reached from `createExecutionPlan`, called by `GremlinToMatchStrategy.buildPlan` (:387-392) inside `applyTranslation`, inside `apply()`'s `try` (:208-225). The `catch (RuntimeException e)` (:217) degrades to `declineOnThrow`.
- **Actual behavior**: `CommandExecutionException` is a `RuntimeException`, the plan build is eager and inside the try, so a disqualifying NOT shape's throw is caught and degrades to native — R4 holds. Consistent with the Track-4 Risk-review R4 finding.
- **Verdict**: MATCHES

#### P-d5seams: every D5 seam the track names resolves on HEAD
- **Track claim**: D5 adds `bindParam → SQLPositionalParameter` to `RecognitionContext`/`WalkerContext`; installs the per-walk param map via `ctx.setInputParameters(map)`; wires `notMatchExpressions` into `MatchPlanInputs.builder(...)`; mirrors `YqlExecutionPlanCache`'s `SharedContext` field + `MetadataUpdateListener` invalidation; `SQLPositionalParameter.getValue(params)`; `CommandContext.setInputParameters(map)` (:106); `MatchPlanInputs.notMatchExpressions` (:48, :154).
- **Search performed**: Read of `SQLPositionalParameter`, `MatchPlanInputs`; grep of `CommandContext.setInputParameters`, `SharedContext`.
- **Code location**: `SQLPositionalParameter.getValue(Map)` at :49-55 and value-independent `toGenericStatement` at :44-46; `CommandContext.setInputParameters(Map<Object,Object>)` at CommandContext.java:106; `MatchPlanInputs` record component `notMatchExpressions` at :48 and `Builder.notMatchExpressions` at :154 (with `buildResult` currently omitting it but the builder supporting it); `SharedContext.yqlExecutionPlanCache` field (:43) constructed and `registerListener`ed as a `MetadataUpdateListener` (:86-91) with `invalidate()` on schema change (:146).
- **Actual behavior**: All signatures and line citations are exact. The `MetadataUpdateListener` invalidation pattern `GremlinPlanCache` would mirror is present and wired the way the track describes. The D5 seams are feasible as stated (the only under-specified piece is the fingerprint *source*, see T4).
- **Verdict**: MATCHES

#### I-subwalk: the recogniser→registry sub-walk seam is not enumerated (T1)
- **Track claim**: the sub-walker runs each child sub-traversal "against the same recogniser registry" via a delegating sub-context (Plan of Work step 1; Interfaces line 81).
- **Search performed**: Read of `StepRecogniser`, `StepCursor`, `RecognitionContext`, `GremlinStepWalker`.
- **Code location**: `StepRecogniser.recognize(StepCursor, RecognitionContext)` (StepRecogniser.java:47); `RecognitionContext` "cannot reach the traversal, the strategy list, the step cursor's position, or the pattern builder" (RecognitionContext.java:12-18); `private final Map<Class<?>, StepRecogniser> recognisers` with no accessor (GremlinStepWalker.java:117); dispatch loop private inside `walk()` (:184-206).
- **Actual behavior**: A recogniser has no way to reach the registry or the dispatch loop through its inputs; the delegating sub-context (deferred to the decomposer) covers *contributions* but not the *invocation seam*. The Interfaces list enumerates other `RecognitionContext` additions but not this one.
- **Verdict**: PARTIAL → T1

#### P-connective-final: only AndStep/OrStep are ConnectiveStep; WherePredicateStep carries no child traversal (T3)
- **Track claim**: the five logical-filter steps "are the `ConnectiveStrategy` form — each child carries a sub-traversal".
- **Search performed**: `javap` on the fork for `AndStep`, `OrStep`, `NotStep`, `WhereTraversalStep`, `WherePredicateStep`, `TraversalFilterStep`, `ConnectiveStep`.
- **Code location**: `AndStep` / `OrStep` `extends ConnectiveStep` (both `final`); `NotStep` / `WhereTraversalStep` / `TraversalFilterStep` `extends FilterStep` (`final`); `WherePredicateStep extends FilterStep` with fields `protected String startKey` (javap) — a predicate + `$matched` reference form, not a child traversal.
- **Actual behavior**: All six are `final` (exact-class dispatch is sound). But the "ConnectiveStrategy form" umbrella and "each child carries a sub-traversal" over-generalise: `where(P)` carries no child traversal. Step 4 handles it distinctly, so the plan of work is correct; only the C&O framing is loose.
- **Verdict**: PARTIAL → T3

#### P-lineref: two A4 C&O line citations point at unrelated methods (T2)
- **Track claim**: `AliasSequence` restart "WalkerContext.java:156-163"; `setSingleReturnColumn` clears/replaces "WalkerContext.java:253-263".
- **Search performed**: Read of `WalkerContext.java` in full.
- **Code location**: lines 156-163 = `isReservedHasKey` Javadoc; `AliasSequence` at 336-351. Lines 253-263 = `addEdge` / `addEdgeAsNode`; `setSingleReturnColumn` at 320-330.
- **Actual behavior**: The behaviours described are real (per-context alias reset; clear-and-replace of the three parallel return lists) but the two line ranges are stale. The re-pin attribution to `VertexHopRecogniser` (line 40) is defensible — `VertexHopRecogniser.recognize` (:84) drives the re-pin via `GremlinPatternAssembler.appendFoldedHop` → `rePinBoundaryToTarget` (:152-154), and the VertexHopRecogniser:81 comment describes it — so only the two WalkerContext refs drifted.
- **Verdict**: PARTIAL → T2

#### E-d5fingerprint: no SQLMatchStatement exists on the translator path to fingerprint (T4)
- **Track claim**: key the cache on "the value-independent post-walk generic-statement fingerprint".
- **Search performed**: Read of `GremlinToMatchStrategy` "Plan caching" Javadoc + `buildPlan` (:387-392); `MatchPlanInputs` constructor path; `SQLPositionalParameter.toGenericStatement`.
- **Code location**: `createExecutionPlan(ctx, false, /* useCache */ false)` (GremlinToMatchStrategy.java:391); the additive `MatchExecutionPlanner(MatchPlanInputs)` leaves `statement` null (class Javadoc); `SQLPositionalParameter.toGenericStatement` emits `PARAMETER_PLACEHOLDER` (:44-46).
- **Actual behavior**: Value-independence is achievable (placeholder rendering), but there is no `SQLMatchStatement` on this path — the fingerprint must be synthesised from the IR. The plan assumes a generic statement exists rather than specifying its construction. Constructible, so a spec gap, not an infeasibility.
- **Verdict**: PARTIAL → T4
