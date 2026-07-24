<!-- MANIFEST
findings: 4   severity: {blocker: 1, should-fix: 3, suggestion: 0}
index:
  - {id: T1, sev: blocker,    loc: track-2.md:40 (Decision Log) / StartStepRecogniser gate, anchor: "### T1 ", cert: P7, basis: "Recogniser gates on YTDBGraphStep but strategy runs before its sole producer; g.V() always declines"}
  - {id: T2, sev: should-fix, loc: track-2.md:90,100,125 (Step 5 / registration), anchor: "### T2 ", cert: P5, basis: "YTDBGraphStepStrategy has no applyPrior() today and 5 strategies are registered, not 3"}
  - {id: T3, sev: should-fix, loc: track-2.md:88 (Plan of Work step 3), anchor: "### T3 ", cert: P6, basis: "isPolymorphic returns @Nullable Boolean; null (no-graph) case unaddressed -> NPE unbox risk"}
  - {id: T4, sev: should-fix, loc: track-2.md:86,96 (Step 1 / ctor), anchor: "### T4 ", cert: P2, basis: "MatchPlanInputs ctor must defensive-copy aliasFilters and assign three final fields groupBy/orderBy/unwind"}
evidence_base: {section: "## Evidence base", certs: 9, matches: 5}
cert_index:
  - {id: P1, verdict: CONFIRMED, anchor: "#### P1 "}
  - {id: P2, verdict: PARTIAL,   anchor: "#### P2 "}
  - {id: P3, verdict: CONFIRMED, anchor: "#### P3 "}
  - {id: P4, verdict: CONFIRMED, anchor: "#### P4 "}
  - {id: P5, verdict: PARTIAL,   anchor: "#### P5 "}
  - {id: P6, verdict: PARTIAL,   anchor: "#### P6 "}
  - {id: P7, verdict: WRONG,     anchor: "#### P7 "}
  - {id: P8, verdict: CONFIRMED, anchor: "#### P8 "}
  - {id: P9, verdict: CONFIRMED, anchor: "#### P9 "}
flags: [CONTRACT_OK]
-->
<!-- workflow-sha: e9377f7f133f5cd6ec3028936f28be2819e4ae96 -->
# Track 2 — Phase A technical review (iteration 1)

Independent decomposition review of Track 2 against the plan, the frozen design, and the live `core` codebase. One blocker: the `StartStepRecogniser` gate keys on the wrong runtime class given the strategy's own ordering decision, so the track as decomposed would decline every `g.V()`. Three should-fix items harden the strategy-ordering edit, the polymorphic-flag read, and the additive planner ctor. All named production classes resolve.

**Tooling note.** mcp-steroid PSI was reachable and the correct project was open (`/home/sandra-adamiec/IdeaProjects/youtrackdb`), but `steroid_execute_code` timed out on every invocation this session (IDE unresponsive within the ~60s MCP window). Existence and signature checks fell back to `find` + `grep` + direct `Read`. The one reference-accuracy question that PSI would have settled outright — "is `GraphStep` the concrete start-step class of a fresh `g.V()` before YTDB strategies run" — is instead established from the sole-construction-site fact (grep across all of `core/src/main`, single result), which does not depend on polymorphic-dispatch resolution. Caveat noted inline on T1.

## Findings

### T1 [blocker]
**Certificate**: P7 (Premise — `StartStepRecogniser` start-step gate) + P5 (strategy ordering)
**Location**: `track-2.md` Decision Log line 40 (`StartStep gates on stepIndex == 0 && step instanceof YTDBGraphStep`); realized in Concrete Step 4 (`StartStepRecogniser`) and Step 5 (ordering). Design Overview point 1 (design.md:42-46), D4.
**Issue**: The track's Decision Log states the start-step gate is `stepIndex == 0 && step instanceof YTDBGraphStep`, and D9 keys the registry on `step.getClass()`. But D4 / design Overview point 1 order the strategy to run **before** `YTDBGraphStepStrategy` — "each half-measure strategy lists `GremlinToMatchStrategy` in its own `applyPrior()`, so TinkerPop's topological sort runs us first." `YTDBGraphStep` is constructed in exactly one place: `YTDBGraphStepStrategy.rebuildTraversal` (`YTDBGraphStepStrategy.java:114`, `new YTDBGraphStep<>(graphStep)`) — verified as the sole `new YTDBGraphStep` site across `core/src/main`. A fresh `g.V()` traversal source (`YTDBGraphTraversalSource`, no `graphStepClass` override) starts with TinkerPop's plain `GraphStep`. Because the translator runs strictly before `YTDBGraphStepStrategy`, the `GraphStep -> YTDBGraphStep` rewrite has not happened yet at translator-application time, so `step.getClass()` at index 0 is `GraphStep`, not `YTDBGraphStep`. The gate `instanceof YTDBGraphStep` is false and — worse under D9's exact-class map — `map.get(GraphStep.class)` misses a registry keyed on `YTDBGraphStep.class`. Either way the recogniser declines, so **every `g.V()` / `g.V(id)` / `g.V(ids)` translation declines** and the track delivers nothing observable. (Reference-accuracy caveat: `GraphStep`-is-concrete was not PSI-confirmed this session because `steroid_execute_code` timed out; the ordering contradiction itself rests on the sole-construction-site grep, which is not subject to the polymorphic-miss failure mode.)
**Proposed fix**: Gate/key `StartStepRecogniser` on TinkerPop's `org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep` (the pre-YTDB-rewrite class the translator actually sees), not `YTDBGraphStep`. Correct the Decision Log line 40 wording and pin the class in Concrete Step 4. If instead the intent is for the translator to see the *rewritten* `YTDBGraphStep`, that requires the opposite ordering (translator in `YTDBGraphStepStrategy`'s `applyPost`, or `YTDBGraphStepStrategy` in the translator's `applyPrior`) — which contradicts D4 and the "half-measures become the fallback" model, so it is the class name, not the ordering, that should change. Add a walker-level test asserting a bare `g.V()` recognises (produces one `YTDBMatchPlanStep`) so this cannot regress silently.

### T2 [should-fix]
**Certificate**: P5 (strategy ordering + registration site)
**Location**: `track-2.md` Plan of Work step 5 / Concrete Step 5 ("reorder the three half-measure strategies' `applyPrior`"); Interfaces line 125 ("add `GremlinToMatchStrategy` to each `applyPrior()`") and line 124 ("strategy registration wiring").
**Issue**: The "add to each `applyPrior()`" phrasing understates the edit and mis-implies a uniform change across three symmetric sites. Two facts from the code: (1) `YTDBGraphCountStrategy` (line 114) and `YTDBGraphMatchStepStrategy` (line 147) each already have an `applyPrior()` returning `Collections.singleton(YTDBGraphStepStrategy.class)` — the edit there is to *widen* the returned set to also include `GremlinToMatchStrategy.class`. But `YTDBGraphStepStrategy` has **no** `applyPrior()` at all (it overrides only `apply()`), so Step 5 must *create* an `applyPrior()` on it returning `{GremlinToMatchStrategy.class}`. (2) The registration site `YTDBGraphImplAbstract` (lines 69-78) registers **five** strategies via `TraversalStrategies.GlobalCache.registerStrategies(...).addStrategies(...)` — the three named half-measures plus `YTDBGraphIoStepStrategy` and `YTDBQueryMetricsStrategy`. Step 5's "Register `GremlinToMatchStrategy`" must add it to that `addStrategies(...)` call; the two unnamed strategies do not need `applyPrior` edits (they do not gate on the translator), but the decomposition should say so explicitly so the implementer does not touch them or miss the registration list.
**Proposed fix**: In Concrete Step 5, spell out the three distinct edits: (a) create `applyPrior()` on `YTDBGraphStepStrategy` = `{GremlinToMatchStrategy.class}`; (b) widen the existing `applyPrior()` on `YTDBGraphCountStrategy` and `YTDBGraphMatchStepStrategy` to add `GremlinToMatchStrategy.class` (keep `YTDBGraphStepStrategy.class`); (c) add `GremlinToMatchStrategy.instance()` to the `addStrategies(...)` call in `YTDBGraphImplAbstract`. Note that `YTDBGraphIoStepStrategy` / `YTDBQueryMetricsStrategy` are intentionally untouched.

### T3 [should-fix]
**Certificate**: P6 (`YTDBStrategyUtil.isPolymorphic` signature)
**Location**: `track-2.md` Plan of Work step 3 ("`StartStepRecogniser` ... pinning `WalkerContext.polymorphic` once (via `YTDBStrategyUtil.isPolymorphic`)"); design §"Schema polymorphism" (design.md:1557).
**Issue**: `YTDBStrategyUtil.isPolymorphic(Admin)` is declared `@Nullable Boolean` and returns `null` when the graph is not accessible from the traversal (`YTDBStrategyUtil.java:29-37`). The existing `YTDBGraphStepStrategy.apply` handles this by returning early on null (lines 32-36). The Plan of Work says the recogniser pins `WalkerContext.polymorphic` from this call but does not address the null case. If `polymorphic` is a primitive `boolean` field, auto-unboxing a null return NPEs inside `apply()`; if the recogniser proceeds with a bad default it diverges from the native path. Neither is covered by an acceptance line.
**Proposed fix**: In Concrete Step 4, specify that `StartStepRecogniser` declines the whole traversal (returns false, no `ctx` mutation — honouring the no-mutation-on-decline invariant) when `isPolymorphic` returns null, mirroring `YTDBGraphStepStrategy`'s early-return. Add a validation line: "a traversal whose graph is inaccessible declines (native pipeline handles it)."

### T4 [should-fix]
**Certificate**: P2 (`MatchExecutionPlanner` ctor / field contract)
**Location**: `track-2.md` Plan of Work step 1 / Concrete Step 1 ("`MatchExecutionPlanner(MatchPlanInputs)` ctor with full defensive copies (D2)").
**Issue**: The existing `(Pattern, aliasClasses, aliasFilters)` ctor (lines 398-415) does two things the "full defensive copies" phrasing must not lose: (1) it wraps `aliasFilters` in a mutable `new HashMap<>(...)` **because `detectNotInAntiJoin()` mutates the map to strip NOT IN conditions** (the comment at lines 411-412 is explicit) — a `MatchPlanInputs` ctor that passes an immutable `Map.of()` or the record's map through unchanged will throw `UnsupportedOperationException` at planning time for any traversal that hits that path; and (2) `groupBy`, `orderBy`, `unwind` are declared `final` (lines 284-286), so the new ctor **must** assign all three from the record (the record does carry them — design classDiagram lines 309-311), or the class will not compile. The single-RID / RID-IN shapes this track builds also populate `aliasRids`, which the existing `(Pattern,...)` ctors hardcode to `Map.of()`; the new ctor must accept and mutable-copy the record's `aliasRids` since `manageNotPatterns`/`estimateRootEntries` read it (lines 498, 551).
**Proposed fix**: In Concrete Step 1, state that the ctor copies `aliasClasses`, `aliasFilters`, and `aliasRids` into mutable `HashMap`s (not passthrough / not `Map.of()`), assigns the three `final` fields `groupBy`/`orderBy`/`unwind` from the record, and leaves `statement` null (T-null safety confirmed by P8). Add a unit test that plans a translated `g.V(ids)` (exercises `aliasFilters` @rid IN) to catch the immutable-map trap.

## Evidence base

#### P1 — Existing MATCH engine + Track 1 builder classes resolve
- **Track claim**: track relies on `MatchExecutionPlanner`, `SelectExecutionPlanner`, and Track 1's `MatchPatternBuilder` / `MatchWhereBuilder` / `MatchLiteralBuilder` (`match/builder/`).
- **Search performed**: `find core/src -name '<Class>.java'` (PSI `findClass` timed out; find fallback per prompt NAMED REFERENCES rule).
- **Code location**: `core/.../sql/executor/match/MatchExecutionPlanner.java`; `core/.../sql/executor/SelectExecutionPlanner.java`; `core/.../sql/executor/match/builder/{MatchPatternBuilder,MatchWhereBuilder,MatchLiteralBuilder}.java`.
- **Actual behavior**: All resolve to a single canonical `core/` path each (package matches reconstructed FQN). `.claude/worktrees/*` copies are unrelated checkouts, ignored.
- **Verdict**: CONFIRMED
- **Detail**: Reference-accuracy caveat: find-based, but each is a unique single match with matching package.

#### P2 — `MatchExecutionPlanner` ctors, field mutability, and the additive-ctor contract
- **Track claim**: add one additive `MatchExecutionPlanner(MatchPlanInputs)` ctor with full defensive copies, leaving the three existing ctors untouched (D2).
- **Search performed**: `Read` MatchExecutionPlanner.java lines 248-454; `grep` ctors/fields.
- **Code location**: MatchExecutionPlanner.java:284-287 (`final` groupBy/orderBy/unwind), 385-415 (two `(Pattern,...)` ctors), 411-413 (defensive-copy comment), 424-454 (`(SQLMatchStatement)` ctor).
- **Actual behavior**: Three existing public ctors: `(Pattern, Map)`, `(Pattern, Map, Map)`, `(SQLMatchStatement)`. `aliasFilters` is defensively wrapped `new HashMap<>()` because `detectNotInAntiJoin` mutates it. `groupBy`/`orderBy`/`unwind` are `final`. `aliasRids` hardcoded to `Map.of()` in the `(Pattern,...)` path.
- **Verdict**: PARTIAL
- **Detail**: Feeds T4 — the "full defensive copies" phrasing must preserve the mutable-copy-for-mutation contract and assign the three final fields.

#### P3 — `createExecutionPlan(context, enableProfiling, useCache)` signature
- **Track claim**: signatures section names `MatchExecutionPlanner.createExecutionPlan(ctx, prof, useCache)`; the ctor routes the record through it.
- **Search performed**: `Read` lines 472-636.
- **Code location**: MatchExecutionPlanner.java:472-473.
- **Actual behavior**: `public InternalExecutionPlan createExecutionPlan(CommandContext context, boolean enableProfiling, boolean useCache)`. Matches the track's stated signature exactly. `buildPatterns` is called unconditionally (line 490) but no-ops when `pattern` is pre-set (line 4584 `if (this.pattern != null) return;`), so a record-built pattern flows straight through.
- **Verdict**: CONFIRMED

#### P4 — `handleProjectionsBlock` is called internally by the planner (D2 no-double-append)
- **Track claim**: the strategy must NOT call `handleProjectionsBlock`; the planner already calls it inside `createExecutionPlan`.
- **Search performed**: `grep handleProjectionsBlock`.
- **Code location**: MatchExecutionPlanner.java:623 (`SelectExecutionPlanner.handleProjectionsBlock(result, info, ...)` in the custom-RETURN branch).
- **Actual behavior**: Confirmed — the planner invokes it internally in the `else` (custom projections) branch. Track 2's `g.V()` uses the `returnElements`-style built-in return path (lines 560-596) rather than the projection branch, so this only matters from Track 5 on, but the D2 no-double-append invariant holds as stated.
- **Verdict**: CONFIRMED

#### P5 — Half-measure strategy `applyPrior` bodies + registration site
- **Track claim**: reorder the three half-measure strategies' `applyPrior()` so the translator runs first; register the strategy (D4).
- **Search performed**: `Read` all three strategies + `grep` registration in `YTDBGraphImplAbstract`.
- **Code location**: YTDBGraphStepStrategy.java:21-40,167-169 (no `applyPrior`); YTDBGraphCountStrategy.java:113-116 (`applyPrior` = `{YTDBGraphStepStrategy.class}`); YTDBGraphMatchStepStrategy.java:146-149 (same); YTDBGraphImplAbstract.java:69-78 (`addStrategies(` of 5 strategies).
- **Actual behavior**: `YTDBGraphStepStrategy` has no `applyPrior` (needs one created). The other two have a singleton `applyPrior` to widen. Registration lists 5 strategies (3 half-measures + `YTDBGraphIoStepStrategy` + `YTDBQueryMetricsStrategy`).
- **Verdict**: PARTIAL
- **Detail**: Feeds T2 — the edit is three distinct changes, not one uniform "add to each `applyPrior`", plus the registration-list add.

#### P6 — `YTDBStrategyUtil.isPolymorphic` returns `@Nullable Boolean`
- **Track claim**: `StartStepRecogniser` pins `WalkerContext.polymorphic` via `YTDBStrategyUtil.isPolymorphic`.
- **Search performed**: `Read` YTDBStrategyUtil.java:26-50.
- **Code location**: YTDBStrategyUtil.java:28-37.
- **Actual behavior**: `@Nullable public static Boolean isPolymorphic(Admin<?,?>)` — returns `null` when `traversal.getGraph()` is empty; otherwise a config-derived / default `Boolean`. Existing `YTDBGraphStepStrategy.apply` early-returns on null.
- **Verdict**: PARTIAL
- **Detail**: Feeds T3 — null (no-graph) case unhandled in the Plan of Work; NPE-unbox / divergence risk.

#### P7 — `YTDBGraphStep` is produced only by `YTDBGraphStepStrategy`, which runs after the translator
- **Track claim**: `StartStep gates on stepIndex == 0 && step instanceof YTDBGraphStep` (Decision Log line 40).
- **Search performed**: `grep -rn "new YTDBGraphStep" core/src/main`; `grep "class YTDBGraphStep"`; `Read` YTDBGraphStepStrategy.rebuildTraversal + design Overview point 1 + registration/traversal-source wiring.
- **Code location**: YTDBGraphStepStrategy.java:114 (sole `new YTDBGraphStep<>(graphStep)`); YTDBGraphStep.java:34 (`extends GraphStep`); design.md:42-46 (translator runs before `YTDBGraphStepStrategy`); YTDBGraph.java:81-82 (`YTDBGraphTraversalSource`, no `graphStepClass` override).
- **Actual behavior**: The `GraphStep -> YTDBGraphStep` rewrite happens inside `YTDBGraphStepStrategy.apply()`, which by D4 runs strictly after `GremlinToMatchStrategy`. So at translator time the start step is TinkerPop `GraphStep`, never `YTDBGraphStep`. Gating/keying on `YTDBGraphStep` never matches.
- **Verdict**: WRONG
- **Detail**: Produces T1 (blocker). Reference-accuracy caveat: rests on the sole-construction-site grep (single result across `core/src/main`), not on PSI find-usages, which timed out; the fact is a class-instantiation-site count, not a polymorphic-dispatch question, so the grep is authoritative here.

#### P8 — Null `statement` is safe under `useCache=false` (deferred-cache scope-down)
- **Track claim**: Decision Log — the additive ctor leaves `statement` null; planner runs with `useCache=false`, no cache key.
- **Search performed**: `Read` createExecutionPlan lines 472-636; `grep statement`.
- **Code location**: MatchExecutionPlanner.java:478-479, 627-632 (only two `statement` dereferences, both guarded by `useCache &&` as first conjunct).
- **Actual behavior**: `statement.executinPlanCanBeCached(...)` / `.getOriginalStatement()` are reached only when `useCache` is true; short-circuit evaluation means a null `statement` with `useCache=false` is never dereferenced. The `GremlinPlanCache` (D5) and prefix-size gate deferrals in the Decision Log are consistent with this.
- **Verdict**: CONFIRMED
- **Detail**: The scope-down (defer `GremlinPlanCache`, run `useCache=false`) is feasible as written.

#### P9 — Planned new classes do not yet exist; all named new symbols are declared as this track's creations
- **Track claim**: In-scope (new): `MatchPlanInputs`, `GremlinToMatchStrategy`, `GremlinStepWalker`, `WalkerContext`, `StepRecogniser`, `StartStepRecogniser`, `AnonAliasGenerator`, `GremlinPlanCache`, `YTDBMatchPlanStep`.
- **Search performed**: `find core/src -name '<Class>.java'` for each.
- **Code location**: NOT FOUND (none exist) — expected for planned-new classes.
- **Actual behavior**: No matches in `core/src` for any of the nine. The track's `## Interfaces and Dependencies` explicitly marks them "In scope (new)", so per the NAMED REFERENCES planned-class rule these are CONFIRMED-as-planned, not blockers.
- **Verdict**: CONFIRMED
- **Detail**: `AnonAliasGenerator` and `GremlinPlanCache` are named as new but scoped-down/deferred in the Decision Log (single-constant alias held in `StartStepRecogniser`; cache deferred). Consistent — a deferred new class is not a missing reference.
