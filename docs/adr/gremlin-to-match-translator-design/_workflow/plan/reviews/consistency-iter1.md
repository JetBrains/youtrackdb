<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 1, suggestion: 0}
index:
  - {id: CR1, sev: should-fix, loc: implementation-plan.md:113 / track-7.md (Component Map, Context, Plan of Work item 2, Interfaces), anchor: "### CR1 ", cert: C5, basis: "Plan names 3 YTDBMatchPlanStep construction sites; only GremlinToMatchStrategy:437 constructs it"}
evidence_base: {section: "## Evidence base", certs: 21, matches: 17}
cert_index:
  - {id: C1, verdict: MATCHES}
  - {id: C2, verdict: MATCHES}
  - {id: C3, verdict: MATCHES}
  - {id: C4, verdict: MATCHES}
  - {id: C5, verdict: MISMATCHES}
  - {id: C6, verdict: MATCHES}
  - {id: C7, verdict: MATCHES}
  - {id: C8, verdict: MATCHES}
  - {id: C9, verdict: MATCHES}
  - {id: C10, verdict: MATCHES}
  - {id: C11, verdict: MATCHES}
  - {id: C12, verdict: MATCHES}
  - {id: C13, verdict: MATCHES}
  - {id: C14, verdict: MATCHES}
  - {id: C15, verdict: MATCHES}
  - {id: C16, verdict: MATCHES}
  - {id: C17, verdict: MATCHES}
  - {id: C18, verdict: PARTIAL}
  - {id: C19, verdict: SUPPRESSED}
  - {id: C20, verdict: SUPPRESSED}
  - {id: C21, verdict: MATCHES}
flags: [CONTRACT_OK]
-->

# Consistency review — Tracks 7/8/9 (iteration 1)

Re-validation after the post-Track-6 inline replan that split the original Track 7 into Tracks 7/8/9. All four axes ran (design_gate=yes). Tracks 1-6 are `[x]` complete; Tracks 7/8/9 are `[ ]` pending, so their `## Purpose / Big Picture`, `## Plan of Work`, and `## Interfaces and Dependencies` claims were pre-screened as target-state and only `## Context and Orientation` plus current-state code references were checked as findings-eligible.

**Tooling note.** mcp-steroid's IDE was reachable (`steroid_list_projects` returned the open project matching the working tree), but every `steroid_execute_code` PSI query timed out — the cold-kotlinc-index condition the track files themselves document. All symbol verifications below therefore used grep + Read over main-tree source, plus `javap`/`unzip` over the resolved `io.youtrackdb/gremlin-core-3.8.1` fork jar. Each certificate records its tool. The one finding (CR1) rests on an exact-literal grep for `new YTDBMatchPlanStep`, which is complete for constructor call sites (no polymorphic dispatch on `new`), so the PSI gap does not weaken it; the reference-accuracy caveat is noted on the finding regardless.

## Findings

### CR1 [should-fix]
**Certificate**: C5
**Location**: `implementation-plan.md:113` (Component Map bullet); `plan/track-7.md` `## Context and Orientation` (reference-accuracy note), `## Plan of Work` item 2, `## Interfaces and Dependencies` "In scope (modified)". Code: `GremlinToMatchStrategy.java:437`.
**Issue**: The plan names three YTDBMatchPlanStep "construction sites" to rewire onto the extracted boundary base — `GremlinToMatchTranslator`, `GremlinStepWalker` (`buildResult`), and `GremlinToMatchStrategy`. Only `GremlinToMatchStrategy` constructs the boundary step. The other two are upstream of step construction and do not touch the class the base is extracted from.
**Evidence**: The sole `new YTDBMatchPlanStep(...)` in the main tree is `GremlinToMatchStrategy.replaceAllStepsWithBoundary` at line 437. A whole-tree reference scan shows `GremlinStepWalker.java` does not reference the `YTDBMatchPlanStep` type at all — its `buildResult` (line 375) returns a `GremlinToMatchTranslator.TranslationResult` record, not a step — and `GremlinToMatchTranslator.java` references `YTDBMatchPlanStep` only in class Javadoc, returning the walker's `TranslationResult` from `translate` (line 52). So `GremlinToMatchTranslator` and `GremlinStepWalker` produce the `TranslationResult` that feeds step construction; they never build the boundary step. Track 7's own Context note already flags this enumeration as grep/read-derived and defers PSI re-verification to decomposition, which mitigates but does not correct the claim as written (the Component Map bullet states it unhedged).
**Proposed fix**: Correct the current-state description so the sole boundary-step construction site is `GremlinToMatchStrategy` (`replaceAllStepsWithBoundary`, line 437), and describe `GremlinToMatchTranslator`/`GremlinStepWalker.buildResult` as the upstream `TranslationResult` producers rather than construction sites. Whether the base-extraction rewire should still touch those two files is a scope question tied to the deferred base-shape choice (abstract superclass vs composed row-projector) — resolve it when pinning the base shape at decomposition.
**Classification**: design-decision
**Justification**: Multiple plausible fix renderings — the current-state correction is unambiguous, but the target-state rewire footprint (Plan of Work item 2, Interfaces "In scope (modified)") depends on the base-shape realization choice the track defers, so the orchestrator cannot pick a single rewrite of all occurrences without a design call ("multiple plausible fix renderings" trigger; when in doubt, escalate).

## Evidence base

### Plan ↔ Code

#### C1 [Ref] YTDBMatchPlanStep class shape (Track 7 Context + Signatures)
- **Document claim**: `public final class YTDBMatchPlanStep<S, E extends Element> extends AbstractStep<S, E> implements AutoCloseable` (`YTDBMatchPlanStep.java:88`), private lifecycle (`plan` field, lazily-`start()`ed `ExecutionStream`, private `State` enum), private projection surface (`projectOrSkip`), single `ResultShaping` field.
- **Search performed**: Read `YTDBMatchPlanStep.java` (grep/Read; PSI timed out).
- **Code location**: `core/.../gremlin/translator/step/YTDBMatchPlanStep.java:88`.
- **Actual signature/role**: Line 88 exactly `public final class YTDBMatchPlanStep<S, E extends Element> extends AbstractStep<S, E>` / line 89 `implements AutoCloseable`. `private InternalExecutionPlan plan` (line 106, non-final by design so `clone()` installs the copy), `private enum State` (line 138), `private ExecutionStream openStream` opened lazily in `openArming().plan.start()` (line 378), `private Object projectOrSkip(Result row)` (line 523), single `private final ResultShaping shaping` (line 119).
- **Verdict**: MATCHES

#### C2 [Ref] ResultShaping — 7-flag immutable record with withX + NONE (Track 7 Signatures)
- **Document claim**: `ResultShaping` is a "7-flag immutable record with `withX` builders + `NONE`".
- **Search performed**: Read `ResultShaping.java`.
- **Code location**: `core/.../gremlin/translator/step/ResultShaping.java:31-93`.
- **Actual signature/role**: `record ResultShaping(boolean dropNullRows, boolean dropOnAbsent, List<String> presencePropertyKeys, boolean wrapMapValuesInLists, boolean accumulateMap, boolean unwrapSingletonMap, boolean elementMapTokens)` — 7 components (6 boolean + 1 List; the class's own Javadoc also calls them "seven ... flags"), 7 `withX` methods, `public static final ResultShaping NONE`.
- **Verdict**: MATCHES

#### C3 [Ref] BoundaryOutputType enum — no LIST yet (Track 9 target-state)
- **Document claim**: Track 9 adds `BoundaryOutputType.LIST`, which "breaks the compile-exhaustive `projectOrSkip` switch, which must gain a `LIST` case".
- **Search performed**: Read `BoundaryOutputType.java` + `YTDBMatchPlanStep.projectOrSkip`.
- **Code location**: `core/.../gremlin/translator/step/BoundaryOutputType.java:23-47`; `YTDBMatchPlanStep.java:523-530`.
- **Actual signature/role**: Enum has exactly `ELEMENT, MAP, SINGLE_VALUE, SCALAR` — no `LIST` (correctly absent; target-state). `projectOrSkip` is `switch (outputType)` over those four cases with no `default`, so adding a `LIST` constant does break exhaustiveness exactly as Track 9 claims.
- **Verdict**: MATCHES (LIST correctly target-state)

#### C4 [Ref] projectOrSkip exhaustive switch + group accumulateMap drain branch (Track 9)
- **Document claim**: `fold`/`unfold`/`tail` need a drain / flat-map / ring-buffer stage "like the existing group `accumulateMap` branch", not per-row `projectOrSkip` cases.
- **Search performed**: Read `YTDBMatchPlanStep.java`.
- **Code location**: `YTDBMatchPlanStep.java:272` (`shaping.accumulateMap()` branch), `:311-327` (`emitAccumulatedGroupMap` drain).
- **Actual signature/role**: `processNextStart` dispatches to `emitAccumulatedGroupMap` when `shaping.accumulateMap()`, which drains the whole stream into one `LinkedHashMap` and emits a single traverser — a barrier/drain stage separate from the per-row `projectOrSkip` switch. Confirms the precedent Track 9 cites.
- **Verdict**: MATCHES

#### C5 [Ref] YTDBMatchPlanStep construction sites (Track 7 Component Map / Context / Plan of Work / Interfaces)
- **Document claim**: The base extraction touches "its construction sites (`GremlinToMatchTranslator`, `GremlinStepWalker`, `GremlinToMatchStrategy`)".
- **Search performed**: `grep -rn 'new YTDBMatchPlanStep'` and reference scan `grep -rln 'YTDBMatchPlanStep'` over `core/src/main` (excluding worktrees); Read of `buildResult`/`translate` (PSI find-usages of the constructor timed out).
- **Code location**: `GremlinToMatchStrategy.java:437` (sole `new YTDBMatchPlanStep`).
- **Actual signature/role**: Only `GremlinToMatchStrategy.replaceAllStepsWithBoundary` constructs the step. `GremlinStepWalker.java` does not reference the type (its `buildResult`, line 375, returns a `TranslationResult` record); `GremlinToMatchTranslator.java` references it only in Javadoc (returns the walker's `TranslationResult` from `translate`, line 52).
- **Verdict**: MISMATCHES
- **Detail**: Two of the three named "construction sites" produce the upstream `TranslationResult`, not the boundary step. Reference-accuracy caveat: verdict rests on exact-literal grep (complete for `new` call sites) rather than PSI. Feeds CR1.

#### C6 [Ref] GremlinToMatchStrategy.hasVertexGraphStart start gate (Track 8 Context + Signatures)
- **Document claim**: The strategy "declines any traversal whose start step is not a vertex `GraphStep` (`hasVertexGraphStart`)".
- **Search performed**: grep + Read `GremlinToMatchStrategy.java`.
- **Code location**: `GremlinToMatchStrategy.java:357-360`, gate called at `:244`.
- **Actual signature/role**: `private static boolean hasVertexGraphStart(...)` returns `getStartStep() instanceof GraphStep<?,?> graphStep && graphStep.returnsVertex()`. Accepts `g.V()`, declines `g.E()` and non-GraphStep starts.
- **Verdict**: MATCHES

#### C7 [Ref/Invariant] D7 idempotency scan keys on YTDBMatchPlanStep (Track 8 Interfaces; plan D7)
- **Document claim**: Track 8 must "broaden the D7 idempotency scan from `YTDBMatchPlanStep` to the Track 7 boundary base"; the scan currently keys on `YTDBMatchPlanStep`.
- **Search performed**: grep + Read `GremlinToMatchStrategy.java`.
- **Code location**: `GremlinToMatchStrategy.java:341-348` (`containsBoundaryStep`).
- **Actual signature/role**: Scans the full step list; `if (step instanceof YTDBMatchPlanStep<?, ?>) return true`. Keys on `YTDBMatchPlanStep` today; broadening to a base is target-state.
- **Verdict**: MATCHES (current state)

#### C8 [Ref] TranslationResult holds one MatchPlanInputs (Track 8 Context)
- **Document claim**: "`TranslationResult` holds one `MatchPlanInputs`".
- **Search performed**: Read `GremlinToMatchTranslator.java`.
- **Code location**: `GremlinToMatchTranslator.java:74-100`.
- **Actual signature/role**: `record TranslationResult(MatchPlanInputs inputs, String boundaryAlias, BoundaryOutputType outputType, Class<? extends Element> returnClass, Map inputParameters, boolean cacheEligible, ResultShaping shaping)` — a single `inputs`.
- **Verdict**: MATCHES

#### C9 [Ref] walkChild yields a WHERE-predicate adapter (Track 8 Context + Decision Log)
- **Document claim**: "the existing `walkChild` yields a WHERE-predicate adapter, not a full plan".
- **Search performed**: grep `walkChild` across translator package + Read `RecognitionContext`/`WalkerContext`.
- **Code location**: `RecognitionContext.java:296`, `WalkerContext.java:569`.
- **Actual signature/role**: `SubTraversalPredicateAdapter walkChild(Traversal.Admin<?, ?> child)` — returns a predicate adapter, not a `SelectExecutionPlan`. Callers: `ConnectiveStepSupport`, `TraversalFilterStepRecogniser`, `NotStepRecogniser`.
- **Verdict**: MATCHES

#### C10 [Ref] buildResult / buildPlan are single-plan (Track 8 Context)
- **Document claim**: "`buildResult` / `buildPlan` are single-plan".
- **Search performed**: grep + Read.
- **Code location**: `GremlinStepWalker.java:375` (`buildResult` → one `TranslationResult`); `GremlinToMatchStrategy.java:392` (`buildPlan` → one `InternalExecutionPlan`).
- **Actual signature/role**: `buildResult` snapshots one context into one `TranslationResult`; `buildPlan` builds one plan (cache get/put or uncached) from one `TranslationResult.inputs()`.
- **Verdict**: MATCHES

#### C11 [Ref] GremlinPlanCache holds a single-plan value (Track 8 Context + Decision Log)
- **Document claim**: "The plan cache holds a single-plan value today".
- **Search performed**: Read `GremlinPlanCache.java`.
- **Code location**: `GremlinPlanCache.java:32-33`.
- **Actual signature/role**: `public final class GremlinPlanCache extends AbstractMetadataUpdateCache<String, InternalExecutionPlan>` — value is a single `InternalExecutionPlan`.
- **Verdict**: MATCHES

#### C12 [Ref] splitDisjointPatterns joins by cartesian product (Track 8 Context; design §Union semantics divergence)
- **Document claim**: MATCH's `splitDisjointPatterns` joins disconnected patterns by cartesian product, so union cannot ride it.
- **Search performed**: `grep -rn splitDisjointPatterns` over core main.
- **Code location**: `MatchExecutionPlanner.java:4552` (definition), `:570` (call), `:295` (state comment).
- **Actual signature/role**: `private void splitDisjointPatterns()` exists on the planner; cartesian semantics also cross-documented in the yql-internals book. Existence and role confirmed; the "cartesian vs concatenation" divergence is a behavior claim I did not execute.
- **Verdict**: MATCHES (existence + role)

#### C13 [Ref] TinkerPop fork step classes exist (Tracks 8/9 Signatures)
- **Document claim**: `UnionStep`, `BranchStep.getGlobalChildren()`, `TailGlobalStep`, `TailGlobalStepPlaceholder`, `UnfoldStep`, `ReverseStep`, `FoldStep`, `RangeGlobalStep(Placeholder)` are fork classes/APIs the target recognisers key on.
- **Search performed**: `unzip -l` over `io.youtrackdb/gremlin-core-3.8.1-af9db90-SNAPSHOT.jar`.
- **Code location**: fork jar `org/apache/tinkerpop/gremlin/process/traversal/step/{branch,filter,map}/`.
- **Actual signature/role**: All present — `UnionStep`, `BranchStep`, `TailGlobalStep`, `TailGlobalStepContract`, `TailGlobalStepPlaceholder`, `RangeGlobalStep`, `RangeGlobalStepPlaceholder`, `UnfoldStep`, `ReverseStep`, `FoldStep`. (These are target-state fork symbols not yet referenced in core source, correctly.)
- **Verdict**: MATCHES

#### C14 [Ref] TailGlobalStepContract.getLimit() with both implementors (Track 9 Context + Signatures)
- **Document claim**: `tail(n)` arrives as `TailGlobalStep` or `TailGlobalStepPlaceholder`, both implementing `TailGlobalStepContract.getLimit()`, so the recogniser keys on the interface.
- **Search performed**: `javap` over the fork jar.
- **Code location**: fork jar `.../step/filter/TailGlobalStepContract`.
- **Actual signature/role**: `interface TailGlobalStepContract<S>` declares `public abstract Long getLimit()` (+ `getLimitAsGValue()`). `TailGlobalStep implements TailGlobalStepContract<S>`; `TailGlobalStepPlaceholder implements TailGlobalStepContract<S>, GValueHolder`.
- **Verdict**: MATCHES

#### C15 [Ref] Track 6 range placeholder-registration precedent (Track 9 Context)
- **Document claim**: "Track 6 already solved the identical shape for `range` by registering `RangeGlobalStep` and its placeholder".
- **Search performed**: grep + Read `RangeGlobalStepRecogniser` + `GremlinStepWalker`.
- **Code location**: `RangeGlobalStepRecogniser.java:25` (`instanceof RangeGlobalStepContract`); `GremlinStepWalker.java:160-161`.
- **Actual signature/role**: The recogniser keys on `RangeGlobalStepContract`; the walker registry has both `Map.entry(RangeGlobalStep.class, INSTANCE)` and `Map.entry(RangeGlobalStepPlaceholder.class, INSTANCE)`. Exactly the precedent Track 9 cites.
- **Verdict**: MATCHES

#### C16 [Ref] QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED default true (Track 9 Context; plan implementation-state)
- **Document claim**: The strategy is "on by default since Track 2 via `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED`".
- **Search performed**: grep + Read `GlobalConfiguration.java`.
- **Code location**: `core/.../api/config/GlobalConfiguration.java:1011-1020`.
- **Actual signature/role**: `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED(..., Boolean.class, true)` — "True by default"; read by `GremlinToMatchStrategy.java:331`.
- **Verdict**: MATCHES

#### C17 [Ref] jmh-ldbc module + Cucumber runners exist (Track 9 Signatures)
- **Document claim**: JMH mirror template = `jmh-ldbc` module; Cucumber runners `YTDBGraphFeatureTest` (core), `EmbeddedGraphFeatureTest` (embedded).
- **Search performed**: `ls`/`find` + grep root `pom.xml`.
- **Code location**: root `pom.xml:54` `<module>jmh-ldbc</module>` (artifactId `youtrackdb-jmh-ldbc`); `core/.../gremlin/gremlintest/YTDBGraphFeatureTest.java`; `embedded/.../shade/EmbeddedGraphFeatureTest.java`.
- **Actual signature/role**: Module and both runner classes present.
- **Verdict**: MATCHES

#### C18 [Ref] Union child sub-traversals carry an EndStep (Track 8 Context)
- **Document claim**: mid-traversal union children are prefix-relative sub-traversals that carry an `EndStep`; the recogniser strips it.
- **Search performed**: `unzip`/grep for `EndStep` in the fork jar + core references.
- **Code location**: fork jar has `ComputerAwareStep$EndStep`, `MatchStep$MatchEndStep`, `WhereTraversalStep$WhereEndStep`, `RepeatStep$RepeatEndStep`; core references are `WhereTraversalStep.WhereEndStep`.
- **Actual signature/role**: No positive evidence located for a generic union-child `EndStep` in this session; this is a TinkerPop traversal-structure behavior claim, not a single-FQN existence claim.
- **Verdict**: PARTIAL
- **Detail**: Not raised as a finding — the claim is target-state (Track 8's recogniser does not exist yet), Track 8's Context explicitly defers PSI re-verification of child-`EndStep` presence, `SelectExecutionPlan.start()` fresh-stream semantics, and `BranchStep.getGlobalChildren()` to decomposition, and PSI was unavailable this session. Flagged for the decomposition PSI check the track already mandates.

### Design ↔ Code / Design ↔ Plan

#### C19 [Ref] design "MultiPlanMatchStep extends YTDBMatchPlanStep (Track 6)" vs plan D8-revised boundary base (Track 8)
- **Document claim**: Frozen `design.md` (Class Design diagram line 291; §Boundary-step lifecycle line 1860; §MultiPlanMatchStep line 1920) states `MultiPlanMatchStep extends YTDBMatchPlanStep`, delivered in "Track 6". Plan D8 (revised after Track 6) instead extracts a shared boundary base both steps extend, with union in Track 8.
- **Search performed**: `find`/grep for `MultiPlanMatchStep` (0 code references — class does not exist yet).
- **Code location**: n/a (target-state).
- **Actual signature/role**: `MultiPlanMatchStep` is absent from the codebase (correctly — Track 8 target-state). `YTDBMatchPlanStep` is `final`, so the frozen `extends` shape cannot compile — the exact realization gap D8-revised records.
- **Verdict**: SUPPRESSED
- **Detail**: This is the central intended replan divergence. Per the review rules ("a revised Decision Record diverging from the frozen design is expected, not a finding") and the spawn instruction, not emitted. The Phase-4 `design-final.md` reconciles it.

#### C20 [Ref] design order-less post-process flags vs plan/Track 7 ordered carrier
- **Document claim**: Frozen `design.md` §Boundary step output types (line 543) describes order-less post-process flags `unfoldOutput` / `reverseOutput` / `tailLimit: int?`. Track 7 replaces them with an ordered `List` carrier (order-less booleans cannot encode `reverse().unfold()` vs `unfold().reverse()`).
- **Search performed**: Read design §Boundary step output types + §List-shaping terminators; Read `ResultShaping.java` (no `unfoldOutput`/`reverseOutput`/`tailLimit` fields today — none of the terminators are implemented).
- **Code location**: n/a (both are target-state carriers).
- **Actual signature/role**: Neither the flags nor the ordered carrier exist in code yet; both are target-state. Track 7's Decision Log records the flags→ordered-carrier change (pre-split adversarial A2).
- **Verdict**: SUPPRESSED
- **Detail**: Intended replan divergence; the ordered-carrier rationale is captured in Track 7's Decision Log + Context. Not a finding; design-final reconciles.

### Gaps

#### C21 [Ref] Orphan-construct + design-coverage sweep
- **Document claim**: (gap axis) plan parts with no design coverage; design parts no track covers; codebase constructs the plan should reference but doesn't.
- **Search performed**: cross-read design section headers vs Tracks 7/8/9 scope; grep for translator constructs.
- **Code location**: n/a.
- **Actual signature/role**: Track 7's ordered list-shaping carrier maps to design §List-shaping terminators + §Boundary step output types (frozen as order-less flags, superseded per C20). Track 8's union maps to design §Union semantics divergence + §MultiPlanMatchStep. Track 9's terminators + Cucumber/JMH hardening map to design §List-shaping terminators + §Test strategy. No plan element lacks design coverage beyond the intended replan lag; no design element for Tracks 7/8/9 is uncovered by a track; no orphan codebase construct (the ServiceLoader/`ProviderOptimizationStrategy` SPI is already referenced via D1/D4).
- **Verdict**: MATCHES (no gap finding)
