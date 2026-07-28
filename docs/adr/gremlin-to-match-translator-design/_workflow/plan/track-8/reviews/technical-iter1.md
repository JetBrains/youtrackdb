<!-- MANIFEST
findings: 5   severity: {blocker: 0, should-fix: 3, suggestion: 2}
index:
  - {id: T1, sev: should-fix, loc: "AbstractMatchPlanStep.java:237-285,394", anchor: "### T1 ", cert: C7, basis: "N-plan advance-on-drain has no base seam; base lifecycle machinery is private, so advance needs a Track 7 base edit not in Track 8 scope"}
  - {id: T2, sev: should-fix, loc: "GremlinToMatchStrategy.java:384-454", anchor: "### T2 ", cert: I1, basis: "build+splice path and TranslationResult are single-plan; multi-plan branch is unlisted in the strategy modified-scope"}
  - {id: T3, sev: should-fix, loc: "AbstractMatchPlanStep.java:422-424", anchor: "### T3 ", cert: E1, basis: "base installs one inputParameters map on the current plan context; per-child union params need per-child install"}
  - {id: T4, sev: suggestion, loc: "GremlinToMatchStrategy.java:348-355", anchor: "### T4 ", cert: C5, basis: "D7 scan already keys on AbstractMatchPlanStep; the track's broaden-the-scan sub-item is a no-op"}
  - {id: T5, sev: suggestion, loc: "GremlinPlanCache.java:70-88, GremlinToMatchStrategy.java:399-421", anchor: "### T5 ", cert: I2, basis: "cache value is one InternalExecutionPlan keyed on one MatchPlanInputs fingerprint; union multi-plan caching approach not yet pinned"}
evidence_base: {section: "## Evidence base", certs: 12, matches: 9}
cert_index:
  - {id: C7, verdict: PARTIAL, anchor: "#### C7 "}
  - {id: I1, verdict: MISMATCHES, anchor: "#### I1 "}
  - {id: E1, verdict: PARTIAL, anchor: "#### E1 "}
  - {id: C5, verdict: CONFIRMED, anchor: "#### C5 "}
  - {id: I2, verdict: PARTIAL, anchor: "#### I2 "}
flags: [CONTRACT_OK]
-->

# Track 8 — Technical review (iteration 1)

Track 8's load-bearing codebase claims all hold: `SelectExecutionPlan.start()`, `BranchStep.getGlobalChildren()`, the union-child `EndStep`, the `hasVertexGraphStart` start gate, the predicate-adapter `walkChild`, and the RID-bypass cache all read as the track describes, and the two new classes are correctly marked planned-new. No blockers. The five findings are all decomposition-scoping gaps, not falsified premises: the N-plan advance-on-drain path has no base seam and the base's lifecycle machinery is private (T1); the single-plan build/splice pipeline and `TranslationResult` need a multi-plan branch the strategy's modified-scope omits (T2); per-child union parameters collide with the base's single-map install (T3); the "broaden the D7 scan" sub-item is already done (T4); and the union cache policy still needs a concrete pin against the single-plan cache value (T5).

**Reference-accuracy caveat (applies to every finding).** mcp-steroid PSI (`steroid_execute_code`) times out in this repo (cold kotlinc compile exceeds the ~60s MCP HTTP limit), confirmed again this session, so all symbol facts rest on grep + declaration reads + `javap` bytecode of the vendored fork (`io.youtrackdb:gremlin-core:3.8.1-af9db90-SNAPSHOT`), not PSI find-usages. A caller or override reachable only through reflection or a non-obvious factory could be missed. The findings that depend on a "sole caller / no other consumer" claim (T2, T4) carry the residual caveat inline.

## Findings

### T1 [should-fix]
**Certificate**: C7 (Premise — base hooks + one-live-stream `processNextStart` support N-plan advance-on-drain)
**Location**: Track 8 `## Plan of Work` item 2, `## Interfaces and Dependencies` ("In scope (new): `MultiPlanMatchStep` (subclass of the Track 7 boundary base)"; "Out of scope: the boundary base extraction ... (Track 7)"); `AbstractMatchPlanStep.java:237-285` (`processNextStart`), `:394` (`openArming`), `:144-170` (`private enum State` + `private State state`), `:451,469` (`releaseStream` / `releaseStreamAndClosePlan`), `:293-297` (`openShapedPayloads`).
**Issue**: The base's advance decision is unconditional and every primitive an advance path needs is `private`. On drain, `processNextStart` sets `state = DRAINED`, calls `releaseStream()`, and throws `FastNoSuchElementException` (`:258-263`) — there is no hook that lets a subclass say "another plan remains, keep going." The only overridable/reachable surface is `processNextStart()` (protected), `reset()` (public), `close()` (public), `resetLifecycleForClone()` (protected final), and the four plan-seam hooks. `openArming()`, `state`/`State`, `openStream`, `shapedPayloads`, `openShapedPayloads()`, `releaseStream()` are all private, so a `processNextStart` override cannot reuse the base's open/project/shape/drain machinery — a full override would re-implement the entire lifecycle, defeating the reuse the base exists to provide. Track 7's own §Surprises frames this exactly ("The one seam the base does not yet provide is N-plan iteration ... Track 8 must add that path (override `processNextStart` or introduce an advance hook)"), and Step 2 requires each child reopen to "go through the NEW/REARMED reopen" so `shapedPayloads` rebuilds fresh — but the only public lever that reaches REARMED from DRAINED is `reset()`, which also runs `AbstractStep.reset()` (super.reset() at `:508`) and would fire mid-`next()` (processNextStart is called from `AbstractStep.next()`), disturbing the step's traverser plumbing. So the base-modification-free path is fragile, and the clean path ("introduce an advance hook") edits `AbstractMatchPlanStep` — a Track 7 file that Track 8's `## Interfaces` lists under **Out of scope** and never names in "In scope (modified)."
**Proposed fix**: At decomposition, decide the advance mechanism explicitly and reconcile scope. Preferred: add `AbstractMatchPlanStep` to Track 8's "In scope (modified)" and introduce a small protected advance seam — e.g. a `protected boolean advanceToNextArming()` the base calls in the drain branch (instead of unconditionally throwing) that a multi-plan subclass overrides to bump its plan index and move `state` to REARMED without going through `AbstractStep.reset()`, so `openArming()` rebuilds `shapedPayloads` per child. Record that this reopens Track 7's behavior-neutrality obligation for the single-plan path (the drain branch changes), and pin the equivalence suite re-run in `## Validation`. If instead the reset()-based no-base-change path is chosen, pin the exact protocol and prove `reset()` is safe to invoke re-entrantly from within `processNextStart`.

### T2 [should-fix]
**Certificate**: I1 (Integration — strategy build + splice pipeline and `TranslationResult`)
**Location**: Track 8 `## Interfaces and Dependencies` ("In scope (modified): ... `GremlinToMatchStrategy` (register the union recogniser, and broaden the D7 idempotency scan ...)"); `GremlinToMatchStrategy.java:384-391` (`applyTranslation`), `:399-421` (`buildPlan`, returns one `InternalExecutionPlan`), `:439-454` (`replaceAllStepsWithBoundary`, constructs `YTDBMatchPlanStep`); `GremlinToMatchTranslator.java:74-100` (`TranslationResult` holds one `MatchPlanInputs`).
**Issue**: The translate→build→splice pipeline is single-plan end to end. `TranslationResult` carries exactly one `MatchPlanInputs`; `buildPlan` returns one `InternalExecutionPlan`; `replaceAllStepsWithBoundary` hard-codes `new YTDBMatchPlanStep(...)`. A union needs N child plans and a `MultiPlanMatchStep`, so each of these three methods (plus the carrier) needs a multi-plan branch: `TranslationResult` (or a sibling result type) must hold the N child `MatchPlanInputs`/plans, `buildPlan` must build (and cache-key) each child, and `replaceAllStepsWithBoundary` must construct the multi-plan step. Track 8's `GremlinToMatchStrategy` modified-scope names only "register the union recogniser" and the (already-done) scan broadening — it understates the change; the build and splice paths are the substantive edits. The track's Plan-of-Work item 3 ("child→full-plan path ... reconciling ... into `MultiPlanMatchStep`") and its "TranslationResult / the multi-plan carrier" scope entry acknowledge the carrier, but the strategy's build/splice methods that consume it are not called out.
**Proposed fix**: Expand Track 8's `## Interfaces` "In scope (modified)" for `GremlinToMatchStrategy` to name `applyTranslation` / `buildPlan` / `replaceAllStepsWithBoundary` as gaining a multi-plan branch, and pin the carrier shape in the `## Decision Log` (does `TranslationResult` grow an `Optional<List<MatchPlanInputs>>`/child-list field, or does a distinct `UnionTranslationResult` carry the children?). Note the recogniser can obtain each child plan by forking the prefix, stripping the child `EndStep`, and recursively invoking `GremlinStepWalker.production().walk(forkedChild)` to a child `TranslationResult`, since plan building itself stays in the strategy. Reference-accuracy caveat: the "these three methods are the only single-plan-assuming sites" claim rests on grep + reads of this file, not PSI.

### T3 [should-fix]
**Certificate**: E1 (Edge case — per-child positional-parameter installation through the base)
**Location**: Track 8 `## Plan of Work` item 3 ("reconciling per-child positional parameters and boundary aliases into `MultiPlanMatchStep`"); `AbstractMatchPlanStep.java:107` (single `inputParameters` field), `:184-198` (constructor takes one map), `:422-424` (`openArming` does `if (!inputParameters.isEmpty()) ctx.setInputParameters(inputParameters)` on `planContext()`).
**Issue**: The base installs one `inputParameters` map onto the current arming's plan context in `openArming`. Each union child is walked independently and mints its own positional-parameter slots (`0`, `1`, …) elided from its own fingerprint, so a single shared map cannot be installed on every child context — the slot numbering would collide and serve wrong parameter values (a correctness hazard, wrong-result multiset rather than a crash). `planContext()` in a multi-plan step returns the current child's context, so the map installed must be that child's. The track names "reconciling per-child positional parameters" abstractly but does not confront the base's single-map install as the concrete obstacle.
**Proposed fix**: At decomposition, pin the mechanism: pass an empty `inputParameters` to the base super-constructor and install each child's parameters into its own plan context when its stream opens (either bake them into each child `SelectExecutionPlan`'s context at build time, or install them in the multi-plan `startPlanStream()` hook keyed on the current plan index). Add a test asserting two union children with different `?`-slot values return the correct per-child multiset. Record the chosen mechanism in the `## Decision Log`.

### T4 [suggestion]
**Certificate**: C5 (Premise — D7 idempotency scan keys on the boundary base)
**Location**: Track 8 `## Interfaces and Dependencies` ("... broaden the D7 idempotency scan from `YTDBMatchPlanStep` to the Track 7 boundary base ..."); `GremlinToMatchStrategy.java:348-355` (`containsBoundaryStep` — `step instanceof AbstractMatchPlanStep<?, ?>`).
**Issue**: Track 7 already retargeted the idempotency scan: `containsBoundaryStep` tests `instanceof AbstractMatchPlanStep`, which already detects a `MultiPlanMatchStep` (it will subclass the same base). The track's "broaden the scan" sub-item is a no-op, matching the plan's Track 7 strategy-refresh note ("already retargeted the D7 idempotency `instanceof` scan to the base ... the decomposer drops it"). The stale sub-item risks the implementer re-editing an already-correct scan or double-checking a satisfied condition. Reference-accuracy caveat: confirmed by reading `containsBoundaryStep` directly, not PSI.
**Proposed fix**: Reduce Track 8's `GremlinToMatchStrategy` modified-scope to "register the union recogniser" and drop the scan-broadening clause (or annotate it "already satisfied by Track 7; no change"), aligning the track file with the plan's CONTINUE refresh note.

### T5 [suggestion]
**Certificate**: I2 (Integration — `GremlinPlanCache` single-plan value + RID bypass)
**Location**: Track 8 `## Plan of Work` item 4 and `## Decision Log` ("Union cache policy ... decide whether a union plan caches and, if so, how the fingerprint keys on the child shapes"); `GremlinPlanCache.java:70-88` (stores/copies one `InternalExecutionPlan`, honors `canBeCached()`), `GremlinToMatchStrategy.java:399-421` (`buildPlan`: `if (!translation.cacheEligible()) buildPlanUncached(...)`, else fingerprint→get/put), `GremlinToMatchTranslator.java:414` (`cacheEligible = !ctx.ridBearing()`), `GremlinPlanFingerprint.java:46` (`fingerprint(MatchPlanInputs)`).
**Issue**: The cache is single-plan by construction: the value is one `InternalExecutionPlan`, the key is one `GremlinPlanFingerprint` derived from one `MatchPlanInputs`, and the RID bypass is a boolean on the single translation. A union produces N child plans, so "cache the union plan" has no direct home in this shape — the realistic options are (a) cache each child plan independently under its own child fingerprint (children are themselves single-plan translations), (b) bypass caching for union whole, or (c) key a composite fingerprint over the ordered child fingerprints and store the child-plan list under a widened value type. The track correctly flags this as an open realization item but does not yet pick one; the cache's shape constrains the choice.
**Proposed fix**: Pin the policy in the `## Decision Log` as a decision record. Per-child caching (option a) reuses the existing single-plan `fingerprint` + value shape unchanged and inherits the RID bypass per child, so it is the lowest-risk fit; note that a RID-bearing child then bypasses only that child, not the whole union. Whichever is chosen, keep the acceptance line "no cross-shape fingerprint collision; RID-bearing shapes bypass" and add a test that a union with one RID-bearing child still returns the correct multiset.

## Evidence base

#### C1 Premise: `SelectExecutionPlan.start()` returns a fresh `ExecutionStream` per call — CONFIRMED
- **Track claim**: `## Interfaces` Signatures — "`SelectExecutionPlan.start()` (fresh `ExecutionStream` per call)."
- **Search performed**: Read `SelectExecutionPlan.java` (declaration); cross-read `AbstractMatchPlanStep.openArming`/`YTDBMatchPlanStep` hooks.
- **Code location**: `SelectExecutionPlan.java:84-87`.
- **Actual behavior**: `start()` returns `lastStep.start(ctx)` and does not reset the plan. Each child union plan is a distinct `SelectExecutionPlan` started once per arming, so "fresh stream per call" holds trivially for the union iteration pattern. Nuance: re-running the *same* plan instance requires a preceding `reset()` — the base already provides this via the REARMED→`rewindPlan(ctx)` (`plan.reset(ctx)`) path in `openArming` (`AbstractMatchPlanStep.java:427-429`, `YTDBMatchPlanStep.java:134-136`). The base Javadoc (`:66-69`) confirms a closed plan "cannot be cleanly restarted," so advance-on-drain must open the next (unstarted) child, never re-`start()` a drained one.
- **Verdict**: CONFIRMED.
- **Detail**: Reference-accuracy caveat — `start()` semantics read from the declaration, not PSI.

#### C2 Premise: `BranchStep.getGlobalChildren()` — CONFIRMED
- **Track claim**: union is "a `BranchStep` exposing N global children via `getGlobalChildren()`."
- **Search performed**: `javap -p` on the fork jar `gremlin-core-3.8.1-af9db90-SNAPSHOT`.
- **Code location**: `org.apache.tinkerpop.gremlin.process.traversal.step.branch.BranchStep` (fork).
- **Actual behavior**: `public List<Traversal.Admin<S, E>> getGlobalChildren()` returns `traversalPickOptions.values()` flat-mapped concatenated with `traversalOptions`; for a `UnionStep` the children live under `Pick.any` in `traversalPickOptions`. `UnionStep<S,E> extends BranchStep<S,E,Pick>`.
- **Verdict**: CONFIRMED.

#### C3 Integration: mid-traversal union children carry a trailing `EndStep` — CONFIRMED
- **Plan claim**: "mid-traversal union children (`g.V().union(out(), in())`) ... carry an `EndStep`" that the recogniser strips.
- **Search performed**: `javap -p -c` on `UnionStep` and `BranchStep` (fork bytecode).
- **Actual entry point**: `UnionStep.<init>` → `addChildOption(Pick.any, child)` → `UnionStep.addChildOption` delegates to `BranchStep.addChildOption(Object, Traversal.Admin)`.
- **Caller analysis**: `BranchStep.addChildOption` bytecode: after `integrateChild`, `new ComputerAwareStep$EndStep`, then `Traversal.Admin.addStep(...)` — appends a `ComputerAwareStep.EndStep` to each child option traversal. `getGlobalChildren()` returns those child traversals, each therefore ending in `ComputerAwareStep.EndStep`.
- **Breaking change risk**: none (read-only recognition).
- **Verdict**: MATCHES.
- **Detail**: The concrete strip target is `org.apache.tinkerpop.gremlin.process.traversal.step.util.ComputerAwareStep$EndStep`, not a top-level `EndStep` — worth naming in the recogniser at decomposition. Reference-accuracy caveat — bytecode inspection, not a running-traversal PSI dump; the runtime post-fold shape was cross-confirmed by Track 7 pre-split reviews.

#### C4 Premise: `hasVertexGraphStart` declines start-position `union` — CONFIRMED
- **Track claim**: "start-position `union` (no vertex `GraphStep` prefix) declines cleanly."
- **Search performed**: Read `GremlinToMatchStrategy.hasVertexGraphStart` + `applyOrDecline`; `javap` on `UnionStep`.
- **Code location**: `GremlinToMatchStrategy.java:364-367` (gate), `:248-250` (gate runs first in `applyOrDecline`).
- **Actual behavior**: `hasVertexGraphStart` returns `getStartStep() instanceof GraphStep && graphStep.returnsVertex()`. For `g.union(...)` the start step is the `UnionStep` (a `BranchStep`, not a `GraphStep`), so the gate returns false and `applyOrDecline` returns before walking. `g.V()….union(...)` starts with a vertex `GraphStep`, so it proceeds. (`UnionStep` also carries its own `isStart` flag, an independent signal.)
- **Verdict**: CONFIRMED.

#### C5 Premise: D7 idempotency scan keys on `AbstractMatchPlanStep` — CONFIRMED (→ T4)
- **Track claim**: `## Interfaces` — "broaden the D7 idempotency scan from `YTDBMatchPlanStep` to the Track 7 boundary base."
- **Search performed**: Read `GremlinToMatchStrategy.containsBoundaryStep`.
- **Code location**: `GremlinToMatchStrategy.java:348-355`.
- **Actual behavior**: `containsBoundaryStep` iterates `traversal.getSteps()` and returns true on `step instanceof AbstractMatchPlanStep<?, ?>` — already the base type. A `MultiPlanMatchStep extends AbstractMatchPlanStep` is detected with no further change.
- **Verdict**: CONFIRMED (already satisfied by Track 7; the track's sub-item is a no-op).
- **Detail**: Reference-accuracy caveat — scan read directly, not PSI.

#### C6 Premise: `walkChild`/`subWalk` yields a WHERE-predicate adapter, not a full plan — CONFIRMED
- **Track claim**: `## Decision Log` — "The existing `walkChild` yields a WHERE-predicate adapter, not a full plan ... Union needs each child sub-walked to a full `SelectExecutionPlan`."
- **Search performed**: Read `GremlinStepWalker.subWalk`; grep `walkChild`/`SubTraversalPredicateAdapter`.
- **Code location**: `GremlinStepWalker.java:319-338` (`subWalk` returns `SubTraversalPredicateAdapter`); doc at `:306-317` names `RecognitionContext#walkChild` as the seam.
- **Actual behavior**: `subWalk` builds a `SubTraversalPredicateAdapter` that buffers child contributions as a WHERE-predicate fragment (ACCEPTED/DECLINE outcome), not a compiled plan. There is no existing child→`SelectExecutionPlan` path; the top-level `walk()` produces a `TranslationResult` (`MatchPlanInputs`), and the plan is built later by the strategy. So union needs a new child→full-plan path (fork prefix, strip `EndStep`, run full `walk()` per child, build a plan each).
- **Verdict**: CONFIRMED.

#### C7 Premise: base four hooks + one-live-stream `processNextStart` support a subclass adding N-plan advance-on-drain — PARTIAL (→ T1)
- **Track claim**: `## Plan of Work` item 2 — `MultiPlanMatchStep` "extending the Track 7 boundary base ... advance on drain; one live stream."
- **Search performed**: Read `AbstractMatchPlanStep` in full (state machine, `processNextStart`, `openArming`, release methods, hook signatures) and `YTDBMatchPlanStep` (hook implementations).
- **Code location**: `AbstractMatchPlanStep.java:237-285`, `:394-444`, `:144-170`, `:451-491`, `:780-811`; `YTDBMatchPlanStep.java:126-146`.
- **Actual behavior**: The four hooks (`planContext`/`rewindPlan`/`startPlanStream`/`closePlan`) are `protected abstract` and their Javadoc already anticipates multi-plan ("a multi-plan form returns the context of the plan whose stream is currently live"; "for a multi-plan form, every plan it owns"), so the hooks genuinely support N plans. But the advance decision is not a seam: on drain `processNextStart` unconditionally sets `state = DRAINED`, releases the stream, and throws. Every lifecycle primitive (`state`/`State`, `openStream`, `shapedPayloads`, `openArming`, `openShapedPayloads`, `releaseStream`) is `private`, so a `processNextStart` override cannot reuse them, and the only public lever to re-enter the NEW/REARMED reopen (which rebuilds `shapedPayloads` per Step 2) is `reset()`, which also runs `AbstractStep.reset()` mid-`next()`.
- **Verdict**: PARTIAL.
- **Detail**: Hooks support multi-plan; the advance-on-drain path does not exist as a seam and cannot be built cleanly without modifying the base — which is outside Track 8's declared scope. See T1.

#### C8 Premise: `UnionStepRecogniser` and `MultiPlanMatchStep` are planned-new — CONFIRMED
- **Track claim**: `## Interfaces` "In scope (new): `UnionStepRecogniser`; `MultiPlanMatchStep`."
- **Search performed**: `find` for both `.java` names outside `target`/worktrees.
- **Code location**: NOT FOUND (as expected).
- **Actual behavior**: Neither class exists yet.
- **Verdict**: CONFIRMED — planned by this track.

#### C9 Premise: additive `MatchExecutionPlanner(MatchPlanInputs)` ctor and `GremlinPlanFingerprint.fingerprint(MatchPlanInputs)` exist — CONFIRMED
- **Track claim**: each child is built to a full `SelectExecutionPlan` (implies the D2 additive ctor) and cache keying via the fingerprint.
- **Search performed**: grep `public MatchExecutionPlanner`; grep `static.*fingerprint`.
- **Code location**: `MatchExecutionPlanner.java:500` (`public MatchExecutionPlanner(@Nonnull MatchPlanInputs inputs)`); `GremlinPlanFingerprint.java:46` (`static String fingerprint(@Nonnull MatchPlanInputs inputs)`).
- **Actual behavior**: Both exist; `buildPlanUncached` already routes `MatchPlanInputs` through the additive ctor. A per-child plan reuses the same path.
- **Verdict**: CONFIRMED.

#### I1 Integration: strategy build + splice pipeline and `TranslationResult` — MISMATCHES (→ T2)
- **Plan claim**: `MultiPlanMatchStep` concatenates N child plans; the strategy registers the union recogniser (its only stated strategy edit besides the already-done scan).
- **Actual entry point**: `GremlinToMatchStrategy.applyTranslation` (`:384-391`) → `buildPlan` (`:399-421`, returns one `InternalExecutionPlan`) → `replaceAllStepsWithBoundary` (`:439-454`, `new YTDBMatchPlanStep(...)`); `GremlinToMatchTranslator.TranslationResult` (`:74-100`) holds one `MatchPlanInputs`.
- **Caller analysis**: `translate()` returns a single `TranslationResult`; every downstream build/splice step assumes one plan and one `YTDBMatchPlanStep`. Reference-accuracy caveat — grep + read enumeration of this file, not PSI find-usages.
- **Breaking change risk**: union requires a multi-plan branch across all three methods plus the carrier; the strategy's modified-scope names only recogniser registration, so the substantive build/splice edits are unlisted.
- **Verdict**: MISMATCHES.

#### I2 Integration: `GremlinPlanCache` single-plan value + RID bypass — PARTIAL (→ T5)
- **Plan claim**: pin whether/how a union plan caches; RID-bearing shapes bypass (Track 5 R3).
- **Actual entry point**: `GremlinToMatchStrategy.buildPlan` (`:399-421`); `GremlinPlanCache.put/get` (`:54-110`); `GremlinPlanCache.putInternal` honors `internal.canBeCached()` (`:70-88`).
- **Caller analysis**: value type is one `InternalExecutionPlan`; key is one `GremlinPlanFingerprint` over one `MatchPlanInputs`; RID bypass is `cacheEligible = !ctx.ridBearing()` (`GremlinToMatchTranslator.java:414`), checked at `buildPlan:403`.
- **Breaking change risk**: no existing behavior breaks, but the single-plan value/key shape has no direct slot for a union's N plans — the track's cache policy is genuinely open.
- **Verdict**: PARTIAL — mechanism confirmed; union policy unresolved.

#### E1 Edge case: per-child positional-parameter installation through the base — PARTIAL (→ T3)
- **Trigger**: a union whose children carry different positional-parameter (`?`) values, e.g. `g.V().union(has('a', x), has('b', y))` after Track 4/5 predicate binding.
- **Code path trace**:
  1. `AbstractMatchPlanStep.openArming` @ `:411-424` — resolves `ctx = planContext()` (the current child's plan context for a multi-plan step) and does `if (!inputParameters.isEmpty()) ctx.setInputParameters(inputParameters)`.
  2. `inputParameters` is a single base field set once at construction (`:107,195`); a multi-plan step constructed with one merged map would install the same slot values on every child context.
- **Outcome**: with a merged map, child slot numbering collides and wrong values bind — a wrong-multiset correctness bug, not a crash. Correct behavior needs each child's own parameter map installed on its own context.
- **Track coverage**: partial — Plan item 3 says "reconciling per-child positional parameters" but does not confront the base's single-map install as the mechanism to change.
- **Verdict**: PARTIAL.
