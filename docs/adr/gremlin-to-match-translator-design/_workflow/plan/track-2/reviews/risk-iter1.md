<!-- MANIFEST
findings: 6   severity: {blocker: 0, should-fix: 3, suggestion: 3}
index:
  - {id: R1, sev: should-fix, loc: design.md:1888, anchor: "### R1 ", cert: E4, basis: "Boundary clone() shares one SelectExecutionPlan across original+clone; class contract requires copy(ctx) per execution for thread safety — parallel iteration unsafe"}
  - {id: R2, sev: should-fix, loc: "track-2.md Step 1 / MatchExecutionPlanner.java:478", anchor: "### R2 ", cert: E2, basis: "Additive ctor leaves statement null; createExecutionPlan(useCache=true) NPEs at 478/629; only useCache=false guards it — latent trap for a future cache-enabling caller"}
  - {id: R3, sev: should-fix, loc: "track-2.md Step 3 / GremlinToMatchStrategy.apply", anchor: "### R3 ", cert: E1, basis: "Strategy runs on every traversal compilation (critical path); a throw in apply() breaks ALL Gremlin, not just recognized shapes — throw-safety net is Step 5, one step after the strategy lands in Step 3"}
  - {id: R4, sev: suggestion, loc: "track-2.md Step 1 / SelectExecutionPlanner.java:759", anchor: "### R4 ", cert: E3, basis: "handleProjectionsBlock double-append guard is scoped to a single QueryPlanningInfo instance; a future recogniser building a second info would double-append — D2's own caught bug"}
  - {id: R5, sev: suggestion, loc: "design.md:1881 / YTDBMatchPlanStep", anchor: "### R5 ", cert: E4, basis: "Abandoned mid-iteration traversal leaks the ExecutionStream; matches existing MATCH risk class but the every-traversal reach widens exposure"}
  - {id: R6, sev: suggestion, loc: "track-2.md Decision Log / GremlinPlanCache", anchor: "### R6 ", cert: A3, basis: "D5 GremlinPlanCache deferred; every translated traversal re-plans each execution — a perf regression vs native for hot repeated shapes until the cache lands in a later phase"}
evidence_base: {section: "## Evidence base", certs: 8, matches: 8}
cert_index:
  - {id: E1, verdict: MATCHES, anchor: "#### E1 "}
  - {id: E2, verdict: MATCHES, anchor: "#### E2 "}
  - {id: E3, verdict: MATCHES, anchor: "#### E3 "}
  - {id: E4, verdict: MATCHES, anchor: "#### E4 "}
  - {id: A1, verdict: VALIDATED, anchor: "#### A1 "}
  - {id: A2, verdict: VALIDATED, anchor: "#### A2 "}
  - {id: A3, verdict: VALIDATED, anchor: "#### A3 "}
  - {id: T1, verdict: ACHIEVABLE, anchor: "#### T1 "}
flags: [CONTRACT_OK]
-->

# Track 2 risk review — iteration 1

Track 2 wires a `GremlinToMatchStrategy` onto the every-Gremlin-compilation
path, adds a `YTDBMatchPlanStep` boundary with a stream/plan lifecycle, and
pins strategy ordering (D4). The critical-path exposure is real but
well-bounded by construction: idempotency (D7), all-or-nothing decline (D3),
and a throw-safety net leave a declined traversal's step list verbatim, so a
recognizer bug can only affect shapes the recognizer claims. The one finding
that survived tracing to a code contradiction is the boundary `clone()`
plan-sharing assumption (R1) — the design asserts sharing one
`SelectExecutionPlan` across clones is safe, which the plan class's own
documented contract contradicts for the parallel-execution case the design
names. The rest are latent traps (null-`statement` NPE, projection
double-append) and accepted trade-offs (deferred cache) worth pinning with a
test or a comment.

## Findings

### R1 [should-fix]
**Certificate**: E4 (Exposure — boundary step plan/stream lifecycle)
**Location**: `design.md:1888` "Plan reuse on `clone()`"; Track 2 Step 2 (`YTDBMatchPlanStep` … "clone supplier re-bind"); `core/.../sql/executor/SelectExecutionPlan.java:37-41, 85-87, 238`.
**Issue**: The design justifies the boundary's `clone()` sharing one
compiled `SelectExecutionPlan` across original and clone with the claim that
"`SelectExecutionPlan.start()` returns a fresh `ExecutionStream` on every
call" (design.md:1893-1895). Tracing the class contradicts this at the plan
level. `start()` is `return lastStep.start(ctx);` (SelectExecutionPlan.java:85-87)
— it drives the **shared** step chain over a **shared** `ctx`
(`CommandContext`, field at line 51), and the class Javadoc is explicit that
"Cached plans are **deep-copied via `copy(CommandContext)` before each
execution to ensure thread safety**" (lines 37-41). A fresh `ExecutionStream`
*object* may come back per `start()`, but the underlying stateful step chain
and command context are shared, so two iterations driving the same plan
instance is exactly the reuse the class documents as requiring a `copy(ctx)`
first. The design names "**parallel execution**" (design.md:1888-1889) as a
reason TinkerPop clones — concurrent iteration of original + clone over one
shared plan violates the documented thread-safety contract. Likelihood:
medium (sequential re-iteration after a `reset` may appear to work in the
common single-threaded test path, masking the defect until a parallel or
nested-reuse path exercises it). Impact: high (wrong results or a crash on a
shared-state race, on a path that is hard to reproduce).
**Proposed fix**: In Step 2, make `clone()` call `plan.copy(ctx)` (the
class's own reuse primitive) rather than share the plan reference — mirror
the existing `HashJoinMatchStep` copy-before-execute pattern the codebase
already relies on. If sharing is genuinely intended for the single-threaded
sequential case only, the step must not be clonable for parallel reuse, and
the design's "parallel execution" justification is wrong and must be
corrected. Add a Step 2 unit test that clones a translated traversal and
iterates original and clone independently, asserting both produce the full
result multiset (this is the test that fails today if the plan is shared).
Correct the design.md:1888 passage to match whichever contract the code
adopts.

### R2 [should-fix]
**Certificate**: E2 (Exposure — additive `MatchExecutionPlanner` ctor / null `statement`)
**Location**: Track 2 Step 1 (`MatchPlanInputs` + additive ctor, D2); Decision Log scope-down "`GremlinPlanCache` (D5) is deferred … the planner runs with `useCache=false`"; `core/.../sql/executor/match/MatchExecutionPlanner.java:251, 478-479, 627-632`.
**Issue**: The additive `MatchExecutionPlanner(MatchPlanInputs)` ctor leaves
the inherited `statement` field null (line 251; only the `SQLMatchStatement`
ctor sets it). `createExecutionPlan` dereferences `statement` at four sites —
`statement.executinPlanCanBeCached(session)` and
`statement.getOriginalStatement()` at lines 478-479 (cache read) and 627-632
(cache write). Every one is short-circuited by `useCache &&` first, so with
`useCache=false` the null field is never touched and the planner completes
the full planning pass (validated: no other `statement` read exists on the
`useCache=false` path). The Decision Log's scope-down is therefore correct
*today*. The residual risk is latency: the only thing standing between a
green build and an NPE is the caller passing `useCache=false`, and D5 plans a
`GremlinPlanCache` that will eventually want caching on. A later track that
flips `useCache=true` (or a maintainer wiring the deferred cache) without
also giving the record path a non-null `statement`/cache key NPEs at line 478
on the first cache probe. Likelihood: low now, medium once the cache lands.
Impact: high (NPE on every translated traversal).
**Proposed fix**: In Step 1, guard the ctor path against the trap rather than
rely on caller discipline: either assert/document that the `MatchPlanInputs`
ctor forces `useCache=false` at the call site (a comment on the field plus a
guard `if (statement == null) useCache = false;` at the top of
`createExecutionPlan`), or give the four dereference sites an explicit
`statement != null &&` co-guard so the field-null case can never NPE
regardless of the `useCache` argument. Add a Step 1 unit test that calls
`createExecutionPlan(ctx, false, true)` on a `MatchPlanInputs`-constructed
planner and asserts it does not NPE (pins the invariant the deferral rests
on).

### R3 [should-fix]
**Certificate**: E1 (Exposure — strategy on every-compilation critical path)
**Location**: Track 2 Step 3 (`GremlinToMatchStrategy` skeleton, "throw-safety net" listed in Step 5); Step 5 ordering; `server/.../gremlin/YTDBAbstractOpProcessor.java:822` (`traversal.applyStrategies()`); `core/.../gremlin/YTDBGraphImplAbstract.java:68-79` (registration).
**Issue**: `GremlinToMatchStrategy.apply()` runs inside
`traversal.applyStrategies()`, which fires on **every** Gremlin traversal
compilation (server op processor line 822; confirmed once-per-compilation,
not cached across compilations). An uncaught exception in `apply()` —
walker bug, recognizer NPE, malformed `MatchPlanInputs` — aborts
compilation for that traversal, and because the strategy is registered
globally for all `YTDBGraph` traversals (registration lines 68-79), the blast
radius on a bug is **every** Gremlin query the server runs, not only the
`g.V()`/`g.V(ids)` shapes Track 2 recognizes. The plan's mitigations are
sound (idempotency scan D7, all-or-nothing decline D3, kill-switch knob in
Step 3), but the **throw-safety net** — the catch-all that turns any
in-`apply()` throw into a clean decline so a translator bug degrades to the
native pipeline rather than breaking the query — is scheduled in **Step 5**,
two steps after the strategy first goes live in Step 3. Between Step 3 and
Step 5 the strategy is registered (or registration is also deferred to Step
5; the Concrete Steps put "Register" in Step 5) but the walker in Step 4
already runs under it. Likelihood: medium during development; the ordering
leaves a window where a Step-4 recognizer bug is a hard failure. Impact: high
(all Gremlin traffic).
**Proposed fix**: Move the throw-safety net into Step 3 (land it with the
skeleton, before any recognizer runs under the strategy), or make Step 3's
skeleton decline-only (no walk) and keep registration in Step 5 so the
strategy is never live without both the throw-net and the kill-switch. Either
way the invariant "a throw in `apply()` can only ever decline, never break a
query" must hold from the first moment the strategy is registered, not from
Step 5. Confirm the kill-switch knob (Step 3) follows the existing
`GlobalConfiguration.QUERY_GREMLIN_*` + early-return pattern
(`YTDBStrategyUtil.isPolymorphic`, GlobalConfiguration.java:945) so a
production incident can disable the translator without a redeploy.

### R4 [suggestion]
**Certificate**: E3 (Exposure — projection double-append)
**Location**: Track 2 Step 1 (planner ctor, D2); `core/.../sql/executor/match/MatchExecutionPlanner.java:623`; `core/.../sql/executor/SelectExecutionPlanner.java:320-376, 759-800`.
**Issue**: D2 records that the planner already calls
`SelectExecutionPlanner.handleProjectionsBlock` internally (line 623) and the
strategy/recognizer must **not** call it too, or projection steps append
twice. The guard against double-append is `if (!info.projectionsCalculated &&
info.projection != null)` with `info.projectionsCalculated = true` set after
(SelectExecutionPlanner.java:759-800) — but it is scoped to a single
`QueryPlanningInfo` instance. Two calls with the **same** `info` are safe; two
calls with **different** `info` objects each pass the guard and both append.
Track 2 does not build projections (that is Track 5), so no double-append
path exists in this track. The risk is forward: a later recognizer that
constructs its own `QueryPlanningInfo` and calls `handleProjectionsBlock`
directly would silently double the projection steps. Likelihood: low (no
projection recognizer in Track 2). Impact: medium (wrong result shape,
silent). This is the exact bug D2's rationale says the consistency review
already caught once.
**Proposed fix**: In Step 1, add a code comment on the additive ctor / at the
line 623 call site restating "the planner owns the projection block — callers
must not invoke `handleProjectionsBlock`" so Tracks 4-5 inherit the warning
at the point of temptation. No code change needed in Track 2 itself; this is
a documentation guard against a future-track footgun.

### R5 [suggestion]
**Certificate**: E4 (Exposure — boundary step plan/stream lifecycle)
**Location**: Track 2 Step 2 (`YTDBMatchPlanStep` AutoCloseable close); `design.md:1870-1886`.
**Issue**: The boundary step closes its `ExecutionStream` on exhaustion,
`Traversal.close()`, and exception (design.md:1870-1879). The design itself
names the one leak path (design.md:1881-1886): a traversal abandoned
mid-iteration — client stops calling `.next()` and never calls `.close()` —
holds the stream (and any resources it pins) until GC. The design correctly
notes this matches the existing `MatchExecutionPlanStep` risk class, so it is
not a new failure mode. The nuance the risk lens adds: the existing MATCH
path is reached only by SQL/GQL `MATCH` queries, whereas this boundary sits on
the Gremlin path, so the *population* of traversals that could abandon
mid-iteration is broader (any translated `g.V()`-family query). Likelihood:
low (TinkerPop idiom drives `close()` via try-with-resources). Impact: low-to-
medium (resource hold until GC, not correctness).
**Proposed fix**: Accept as an inherited risk class (no blocker). In Step 2,
add a unit test that opens a translated traversal, pulls one traverser, then
calls `Traversal.close()` and asserts the underlying `ExecutionStream` is
closed (verifies close-propagation path 2). Optionally note the abandoned-
iteration leak explicitly in the `YTDBMatchPlanStep` class Javadoc so it is
not mistaken for a bug later.

### R6 [suggestion]
**Certificate**: A3 (Assumption — deferred `GremlinPlanCache`, D5)
**Location**: Track 2 Decision Log scope-down ("`GremlinPlanCache` (D5) is deferred … Every translated traversal re-plans"); plan D5.
**Issue**: The Decision Log defers `GremlinPlanCache` (D5) to a later phase:
with the additive ctor's null `statement`, `useCache=false`, so **every**
translated traversal runs the full `MatchExecutionPlanner` planning pass on
every execution. For a hot, repeated recognized shape this is strictly more
per-execution work than the native Gremlin pipeline it replaces (which does
not re-plan). The whole feature's premise is that MATCH's cost-based plan
beats native execution enough to pay for translation; for cheap repeated
shapes like `g.V(id)` the un-cached planning overhead could erase the win.
Track 2 recognizes only `g.V()`/`g.V(ids)` — the cheapest shapes, where
planning overhead is most likely to dominate. Likelihood: medium (the
deferral is intentional and documented). Impact: low (perf only, no
correctness; the multiset-equality contract holds either way).
**Proposed fix**: Accept the deferral (it is a documented, intentional
scope-down, not a defect). In Step 5's end-to-end smoke tests, add or note a
translator-on-vs-off timing sanity check for `g.V(id)` so a gross per-call
planning regression is visible before the cache lands; the JMH on/off
baseline is Track 6, but a coarse assertion here catches an order-of-magnitude
regression early. No Track 2 code change required.

## Evidence base

#### E1 Exposure: `GremlinToMatchStrategy` on the every-compilation Gremlin path
- **Track claim**: Step 3 registers the strategy into the optimization chain (D1/D4); it runs on every recognized-or-not Gremlin traversal, declining unrecognized shapes verbatim (D3).
- **Critical path trace**:
  1. Entry: `traversal.applyStrategies()` @ `server/.../gremlin/YTDBAbstractOpProcessor.java:822` (comment: "compile the traversal — without it getEndStep() has nothing in it").
  2. Strategy set built once via `TraversalStrategies.GlobalCache.registerStrategies(...)` @ `core/.../gremlin/YTDBGraphImplAbstract.java:68-79`, adding `YTDBGraphStepStrategy`, `YTDBGraphCountStrategy`, `YTDBGraphMatchStepStrategy`, + IO/metrics; `GremlinToMatchStrategy` joins this set (Step 5).
  3. `applyStrategies()` runs each `ProviderOptimizationStrategy.apply(traversal)` in topo-sorted order; runs **once per compilation**, not memoized across compilations (confirmed: no per-execution strategy cache; the server processor calls it once per submitted traversal).
- **Blast radius**: an uncaught throw in `apply()` aborts compilation → the whole traversal fails, for ALL Gremlin shapes (strategy is global), not only Track 2's recognized `g.V()` family.
- **Existing safeguards**: idempotency early-return scan (D7, design.md:1536); all-or-nothing decline preserves the original step list verbatim (D3); kill-switch knob (Step 3) modeled on `GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT` early-return pattern (GlobalConfiguration.java:945, `YTDBStrategyUtil.isPolymorphic`:28-46); throw-safety net (Step 5). Existing per-strategy unit tests (`GraphStepStrategyTest`, `GraphMatchStrategyTest`, `GraphCountStrategyTest`) exercise `applyStrategies()` + post-optimization assertions.
- **Residual risk**: MEDIUM — throw-safety net lands in Step 5, one-to-two steps after the walker (Step 4) runs under the strategy; window where a recognizer bug is a hard failure rather than a clean decline (→ R3).

#### E2 Exposure: additive `MatchExecutionPlanner(MatchPlanInputs)` ctor / null `statement`
- **Track claim**: Step 1 adds one additive ctor routing a `MatchPlanInputs` record through the existing `createExecutionPlan`; Decision Log: it leaves `statement` null and runs `useCache=false`.
- **Critical path trace**:
  1. `statement` field declared @ `MatchExecutionPlanner.java:251`; set only by the `SQLMatchStatement` ctor (line 424), null in the two IR ctors (385, 398) and the new record ctor.
  2. `createExecutionPlan(ctx, enableProfiling, useCache)` @ line 472: cache read guarded `if (useCache && !enableProfiling && statement.executinPlanCanBeCached(session))` @ 478, `statement.getOriginalStatement()` @ 479.
  3. Cache write guarded identically @ 627-632.
  4. On `useCache=false` both `&&` chains short-circuit before dereferencing `statement`; the full planning pass (489-625) never reads `statement`.
- **Blast radius**: NPE on line 478 for every translated traversal if any caller ever passes `useCache=true` with a null-`statement` record-built planner.
- **Existing safeguards**: `useCache=false` short-circuit (the only guard); no `statement != null` co-guard.
- **Residual risk**: LOW today / MEDIUM once D5's cache wants `useCache=true` — the guard is caller discipline, not a structural invariant (→ R2).

#### E3 Exposure: projection double-append via `handleProjectionsBlock`
- **Track claim**: D2 — the planner owns the projection block; callers must not call `handleProjectionsBlock` too.
- **Critical path trace**:
  1. `MatchExecutionPlanner.createExecutionPlan` calls `SelectExecutionPlanner.handleProjectionsBlock(result, info, context, enableProfiling)` @ line 623 (the only call site in the planner).
  2. `handleProjectionsBlock` @ `SelectExecutionPlanner.java:320-376` dispatches to `handleProjections` (334/355/373).
  3. `handleProjections` guard @ line 759: `if (!info.projectionsCalculated && info.projection != null) { … result.chain(new ProjectionCalculationStep(...)); info.projectionsCalculated = true; }` (~800).
- **Blast radius**: a second call with a **different** `QueryPlanningInfo` bypasses the instance-scoped guard and appends a second `ProjectionCalculationStep` → doubled projection.
- **Existing safeguards**: `info.projectionsCalculated` guard — but scoped to one `info` instance, not global.
- **Residual risk**: LOW for Track 2 (no projection recognizer here; that is Track 5). Real forward-risk for Tracks 4-5 (→ R4).

#### E4 Exposure: `YTDBMatchPlanStep` plan/stream lifecycle (clone, open, close)
- **Track claim**: Step 2 — boundary holds one `SelectExecutionPlan` + a `BoundaryOutputType`; lazy stream open on first `processNextStart`; `AutoCloseable` close on exhaustion / `Traversal.close()` / exception; `clone()` **shares the plan** and resets `started`.
- **Critical path trace**:
  1. `SelectExecutionPlan.start()` @ line 85-87 = `return lastStep.start(ctx);` — drives the shared step chain over the shared `ctx` field (line 51).
  2. Class Javadoc @ lines 37-41: "Cached plans are deep-copied via `copy(CommandContext)` before each execution to ensure thread safety."
  3. `copy(ctx)` @ line 238 → `copyOn` deep-copies every step (line 260-276), re-linking prev/next and cloning `ctx`-bound state; this is the class's documented reuse primitive (used by `HashJoinMatchStep` before each execution).
  4. `reset(ctx)` @ line 107-109 resets each step but is a weaker guarantee than `copy` for independent concurrent iteration.
  5. Close: `SelectExecutionPlan.close()` @ line 76-78 propagates from `lastStep` backward; `ExecutionStream` implementations are single-use (stateful `executed`/`closed` flags, e.g. `SingletonExecutionStream`), `close()` must be called or wrapped in an `onClose` hook.
- **Blast radius**: (a) clone sharing a plan → wrong results / race under parallel or nested reuse; (b) abandoned mid-iteration → stream/resource leak until GC.
- **Existing safeguards**: `copy(ctx)` exists as the correct reuse primitive but the design's clone path does not use it; close-on-exhaustion / close-on-Traversal.close / close-on-exception covers the non-abandoned paths (design.md:1870-1879); the leak path matches the existing `MatchExecutionPlanStep` risk class.
- **Residual risk**: MEDIUM for the clone-sharing assumption (design's "fresh ExecutionStream" justification is contradicted by the plan-level shared-`ctx`/deep-copy contract) (→ R1); LOW for the documented abandoned-iteration leak (→ R5).

#### A1 Assumption: D4 strategy ordering (translator first, half-measures as fallback) is topologically valid
- **Track claim**: Step 5 adds `GremlinToMatchStrategy` to each of the three half-measure strategies' `applyPrior()`; the translator declares empty prior/post; TinkerPop's topo sort then runs the translator first, half-measures see the verbatim step list on decline.
- **Evidence search**: grep + Read of the three strategy files; confirmed current `applyPrior()` bodies (PSI project open, but these are concrete single-class returns — grep-confirmed and Read-confirmed).
- **Code evidence**: `YTDBGraphCountStrategy.applyPrior()` @ :114-116 returns `Collections.singleton(YTDBGraphStepStrategy.class)`; `YTDBGraphMatchStepStrategy.applyPrior()` @ :147-149 identical; `YTDBGraphStepStrategy` declares no ordering. Registration @ `YTDBGraphImplAbstract.java:68-79`. TinkerPop `DefaultTraversalStrategies` builds a dependency DAG from `applyPrior()`/`applyPost()` and topo-sorts; a class named in `applyPrior()` that is **not** in the registered set is silently skipped (no error).
- **Verdict**: VALIDATED — adding `GremlinToMatchStrategy` to each half-measure's `applyPrior()` produces edges `GremlinToMatchStrategy → {each half-measure}`; combined with the existing `→ YTDBGraphStepStrategy` edges the DAG stays acyclic and the sort places the translator first. Unregistered-dependency case is safe. No ordering finding.
- **Detail**: the sibling-branch "strategy-ordering enforcement" concern dissolves by construction — the topo sort is total over the registered set and the translator's empty prior/post plus the half-measures' new prior edge fully determine the order.

#### A2 Assumption: idempotency scan (D7) prevents re-translation of an already-translated traversal
- **Track claim**: Step 3 — a single early scan of the whole step list for any `YTDBMatchPlanStep` returns immediately if found.
- **Evidence search**: Read of design.md §"Strategy idempotency" (1524-1543); Read of D7 in the plan.
- **Code evidence**: design.md:1536-1543 specifies scan the **entire** list (not just the start step) because a wrapping source can place steps before a translated boundary; O(N) over single-digit N. Consistent with D7's rationale (plan:194-203) that strategy chains re-apply (clone, test re-application, lazy first-iteration apply).
- **Verdict**: VALIDATED — the design's whole-list scan closes the re-application hole D7 identifies; no finding. Testability covered by the "re-applying the strategy is a no-op" acceptance line (track-2.md:108).
- **Detail**: the only correctness dependency is that the scan runs before the walk in `apply()` — an ordering the skeleton (Step 3) owns; verified as an acceptance criterion, not a risk.

#### A3 Assumption: deferring `GremlinPlanCache` (D5) is acceptable in Track 2
- **Track claim**: Decision Log scope-down — `GremlinPlanCache` deferred; `useCache=false`; every translated traversal re-plans; a shape-keyed cache is a later-phase addition.
- **Evidence search**: Read of the existing SQL/YQL cache (`YqlExecutionPlanCache`) and its key.
- **Code evidence**: `YqlExecutionPlanCache` keys on `statement.getOriginalStatement()` (a String, SQL text) @ `MatchExecutionPlanner.java:479, 632`; `Cache<String, InternalExecutionPlan>` (line 26); capacity 0 disables caching (line 96). Schema-change invalidation via `MetadataUpdateListener` (`onSchemaUpdate`/`onIndexManagerUpdate`/… all call `invalidate()`, lines 149-173). A Gremlin traversal has no SQL text, so a shape-keyed cache genuinely needs a different key derivation — confirming the deferral is a real, separable piece of work, not a trivial reuse.
- **Verdict**: VALIDATED — the deferral is coherent and correctness-neutral (the multiset-equality contract does not depend on caching). The only consequence is per-execution planning overhead (→ R6, perf suggestion).
- **Detail**: the existing invalidation hook is reusable for the future `GremlinPlanCache`, but the key derivation is not — the value-independent generic-statement fingerprint D5 describes is new work.

#### T1 Testability: Track 2 steps against 85% line / 70% branch
- **Coverage target**: 85% line / 70% branch on changed code.
- **Difficulty assessment**: The strategy, walker, recognizer, and boundary are unit-testable in isolation; the boundary's stream lifecycle (open/close/exhaustion/exception/clone) needs explicit lifecycle tests. The end-to-end multiset-equality (translator-on vs -off) is the integration surface.
- **Existing test infrastructure**: `GraphStepStrategyTest` / `GraphMatchStrategyTest` / `GraphCountStrategyTest` (`core/.../gremlin/`) demonstrate the per-strategy pattern: build traversal → `applyStrategies()` → assert post-optimization step list; `YTDBAbstractGremlinTest` (`core/.../gremlin/gremlintest/scenarios/`) is the base class; `YTDBGraphInitUtil` seeds fixtures. The `EdgeTraversalEquivalenceTest` the invariants reference is new (arrives Track 3, per plan) — Track 2 needs its own translator-on-vs-off assertion for `g.V()`/`g.V(id)`/`g.V(ids)`.
- **Feasibility**: ACHIEVABLE — the existing per-strategy test pattern covers idempotency, decline-preserves-list, and the `$`-label collision pre-flight directly; the boundary lifecycle and the R1 clone-independence test are the only genuinely new test shapes, both writable with the existing base class.
- **Detail**: no infeasible step. The coverage risk is the exception/close branches on `YTDBMatchPlanStep` (Step 2) — flagged for explicit branch tests (R1, R5 fixes double as the missing branch coverage).
