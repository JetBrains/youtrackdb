<!-- MANIFEST
findings: 5   severity: {blocker: 1, should-fix: 4, suggestion: 0}
index:
  - {id: T1, sev: blocker,    loc: "core/.../translator/step/YTDBMatchPlanStep.java:88", anchor: "### T1 ", cert: C1, basis: "MultiPlanMatchStep cannot extend a final class; base machinery is all private, so extension gives no reusable surface. ADJUST note asserted the extension+ctor works and missed this."}
  - {id: T2, sev: should-fix, loc: "core/.../translator/strategy/GremlinStepWalker.java:160", anchor: "### T2 ", cert: C5, basis: "tail(n) arrives as TailGlobalStep OR TailGlobalStepPlaceholder; track names only TailGlobalStep, so the placeholder form declines silently unless both classes register."}
  - {id: T3, sev: should-fix, loc: "core/.../translator/step/YTDBMatchPlanStep.java:524", anchor: "### T3 ", cert: C4, basis: "Adding BoundaryOutputType.LIST breaks the exhaustive projectOrSkip switch; fold/unfold/tail are stream-level transforms that do not fit the per-row projection model and need a distinct post-process stage."}
  - {id: T4, sev: should-fix, loc: "core/.../translator/strategy/WalkerContext.java:569", anchor: "### T4 ", cert: C6, basis: "Union needs each child walked to a full SelectExecutionPlan; the existing walkChild sub-walk yields a WHERE-predicate adapter, not a plan. Per-child params/boundary alias reconciliation unaddressed."}
  - {id: T5, sev: should-fix, loc: "core/.../translator/step/YTDBMatchPlanStep.java:465", anchor: "### T5 ", cert: C10, basis: "MultiPlanMatchStep must close ALL child plans (incl. un-run) and clone-copy each against an isolated context; track mentions only the current stream and omits clone, risking cursor leaks and shared-context races."}
evidence_base: {section: "## Evidence base", certs: 10, matches: 5}
cert_index:
  - {id: C1,  verdict: WRONG,     anchor: "#### C1 "}
  - {id: C2,  verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3,  verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4,  verdict: PARTIAL,   anchor: "#### C4 "}
  - {id: C5,  verdict: PARTIAL,   anchor: "#### C5 "}
  - {id: C6,  verdict: PARTIAL,   anchor: "#### C6 "}
  - {id: C7,  verdict: CONFIRMED, anchor: "#### C7 "}
  - {id: C8,  verdict: CONFIRMED, anchor: "#### C8 "}
  - {id: C9,  verdict: CONFIRMED, anchor: "#### C9 "}
  - {id: C10, verdict: MISMATCHES, anchor: "#### C10 "}
flags: [CONTRACT_OK]
-->

# Track 7 technical review — iteration 1

Tooling note: `mcp-steroid` was reachable and the project was open, but `steroid_execute_code` timed out on every call this session (repeated ~60s MCP HTTP cancellations on the kotlinc cycle, including a trivial warmup), so PSI find-class / find-usages was unavailable. Evidence below is direct `Read` of source, `javap -p` of the resolved `io.youtrackdb` gremlin-core fork jar (`3.8.1-af9db90-SNAPSHOT`, the version `pom.xml` resolves), and `find`/`grep`. Every existence and shape claim is a single unambiguous match in a known package or bytecode signature, and no finding depends on a "no callers" find-usages result, so reference-accuracy is not at risk for these findings.

ADJUST-gate validation (the Pre-Flight ADJUST this review was asked to check): claim 1 (route the three new list-shaping flags through `ResultShaping`, not new `WalkerContext` fields) HOLDS — C2, C3. Claim 3 (Plan-of-Work item 4 + Interfaces text still describe the superseded individual-flag model; decomposition rewrites it) HOLDS as a documentation task. Claim 2 (`MultiPlanMatchStep extends YTDBMatchPlanStep` inheriting the single-`ResultShaping` constructor) DOES NOT HOLD — see T1.

## Findings

### T1 [blocker]
**Certificate**: C1 (Premise — `YTDBMatchPlanStep` class shape)
**Location**: Track `## Plan of Work` item 2 and `## Context and Orientation`; plan Component Map bullet "YTDBMatchPlanStep / MultiPlanMatchStep" and D8; the Pre-Flight ADJUST note. Source: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/YTDBMatchPlanStep.java:88`.
**Issue**: The track (item 2, verbatim: "`MultiPlanMatchStep` extending `YTDBMatchPlanStep`"), the plan Component Map, D8, and the current-state ADJUST note ("`MultiPlanMatchStep extends YTDBMatchPlanStep` inherits the post-refactor single-`ResultShaping` constructor") all assume `MultiPlanMatchStep` subclasses the concrete boundary step. `YTDBMatchPlanStep` is declared `public final class YTDBMatchPlanStep<S, E extends Element>` (line 88). A `final` class cannot be extended — the track will not compile as written. Two further problems survive even if `final` is removed: (a) Java constructors are not inherited, so the ADJUST's "inherits the constructor" is imprecise — a subclass must declare its own constructor and call `super(...)`; and (b) the entire reusable surface is `private` — the `plan` field, the `State` machine, `openStream`, `openArming()`, `releaseStream()`, `projectOrSkip()`, `projectMap/SingleValue/Scalar()`, `emitAccumulatedGroupMap()`, and `convertValue()` are all private (lines 106-119, 138-164, 340-432, 523-696). A subclass that overrides `processNextStart()` to iterate a `List<SelectExecutionPlan>` inherits nothing usable and would have to duplicate the row-projection + shaping logic that Track 7 is simultaneously extending with LIST/unfold/reverse/tail. This is exactly the point the ADJUST gate missed.
**Proposed fix**: Replace the "extends the concrete step" instruction. Extract the shared row-projection + shaping + single-stream lifecycle into a reuse point that both boundary steps share — either an `abstract` base (e.g. `AbstractMatchPlanStep` holding projection/shaping/State with a `protected` "advance to next stream" hook, with `YTDBMatchPlanStep` = one plan and `MultiPlanMatchStep` = plan list as concrete subclasses), or a composed `MatchRowProjector` component both steps delegate to. This matches the plan Component Map's sibling framing ("YTDBMatchPlanStep + MultiPlanMatchStep") and lets union reuse the LIST/unfold/reverse/tail shaping rather than fork it. Update the track text, the D8 wording, and the ADJUST line accordingly during Move 1/decomposition.

### T2 [should-fix]
**Certificate**: C5 (Premise/Edge — tail placeholder dispatch)
**Location**: Track `## Plan of Work` item 3, `## Interfaces and Dependencies` Signatures, and the mermaid `fold/unfold/reverse/tail` node. Source: `GremlinStepWalker.java:160-161`; fork jar `TailGlobalStep` / `TailGlobalStepPlaceholder` / `TailGlobalStepContract`.
**Issue**: `tail(n)` can reach the strategy as either `TailGlobalStep` or `TailGlobalStepPlaceholder` — both implement `TailGlobalStepContract`, which exposes `Long getLimit()`. D9 dispatch keys on `step.getClass()`, so a recogniser registered under only `TailGlobalStep.class` (what the track names) silently declines the placeholder form. Track 6 already hit and solved the identical shape for `range`: `RangeGlobalStepRecogniser` matches on the `RangeGlobalStepContract` interface (`RangeGlobalStepRecogniser.java:25`) and is registered under BOTH `RangeGlobalStep.class` and `RangeGlobalStepPlaceholder.class` (`GremlinStepWalker.java:160-161`). `fold`/`unfold`/`reverse` have no placeholder in the fork jar (single concrete final classes), so only `tail` carries this hazard.
**Proposed fix**: Decompose the tail recogniser to mirror range: register it under both `TailGlobalStep.class` and `TailGlobalStepPlaceholder.class`, match internally on `TailGlobalStepContract`, and read `getLimit()`. Add the null / parameterized-GValue guard range already uses (`TailGlobalStepPlaceholder` is a `GValueHolder`; `getLimit()` may be null for a parameterized `tail(GValue)`) — decline rather than NPE. Add `TailGlobalStepPlaceholder` to the track's Signatures/Interfaces text.

### T3 [should-fix]
**Certificate**: C4 (Premise — BoundaryOutputType.LIST + exhaustive switch), C7
**Location**: Track `## Plan of Work` items 3-4, `## Interfaces and Dependencies` (`BoundaryOutputType` enum LIST). Source: `YTDBMatchPlanStep.java:523-530` (`projectOrSkip`), `:272-273`/`:311-327` (`accumulateMap`/`emitAccumulatedGroupMap` drain path); `BoundaryOutputType.java`.
**Issue**: Two mechanics the track glosses. (1) `projectOrSkip` is an arrow `switch (outputType)` over the four current cases with no `default` (lines 524-529) — a compile-checked exhaustive switch. Adding `LIST` to `BoundaryOutputType` makes it non-exhaustive → the module will not compile until a `case LIST` arm is added. (2) `fold()` (drain the whole stream into one `List<E>` traverser; empty input → empty list) is a barrier terminator, not a per-row projection. The existing barrier — group/groupCount via `accumulateMap` — is handled by an early `if (shaping.accumulateMap()) return emitAccumulatedGroupMap(ctx);` branch BEFORE the per-row loop (line 272), and its output type is never fed through `projectOrSkip`. `LIST`/fold needs the same treatment: an early drain branch, not a `projectOrSkip` case. The same mismatch applies to the other three post-processors — `unfold` is a flat-map (one row → N traversers, needs a pending-emission queue across `processNextStart` calls), `tail` needs a cross-row ring buffer emitted at end-of-stream (barrier-like), and only `reverse` is a genuine per-row value transform. `ResultShaping` is documented as per-row "row-projection shaping" (its Javadoc), so folding stream-level transforms into it is fine as a config bundle but the processing stage in `processNextStart` is where the real work lands.
**Proposed fix**: In decomposition, specify: (a) add the `case LIST` arm to `projectOrSkip` (even if it throws, since fold drains via an early branch); (b) add a stream-level post-process stage in `processNextStart` distinct from per-row projection — drain-to-list for fold, a flat-map pending queue for unfold, a bounded `ArrayDeque` ring buffer flushed at exhaustion for tail, per-row value transform for reverse — applied in the design's declared order. Note that `tailLimit` as an `int` needs a sentinel for "no tail" (e.g. `-1`) since `int` has no absent state; confirm the `ResultShaping` extension picks one.

### T4 [should-fix]
**Certificate**: C6 (Premise/Integration — union child walk)
**Location**: Track `## Plan of Work` item 1 ("sub-walk each child against the registry, build one `SelectExecutionPlan` per child"). Source: `WalkerContext.java:569-578` (`walkChild` → `SubTraversalPredicateAdapter`); `NotStepRecogniser.java:58-63`, `ConnectiveStepSupport.java:35-41` (existing sub-walk usage); fork `BranchStep.getGlobalChildren()`.
**Issue**: The existing sub-walk machinery does not produce what union needs. `RecognitionContext.walkChild(child)` returns a `SubTraversalPredicateAdapter` — a WHERE predicate that the and/or/not/where recognisers fold into the SAME pattern via `getLocalChildren()`. Union is categorically different: each child is a `getGlobalChildren()` branch that must become its OWN complete `MatchPlanInputs` → `SelectExecutionPlan` (an independent query whose rows are concatenated), not a predicate fragment. The track's "sub-walk each child against the registry, build one `SelectExecutionPlan` per child" glosses this distinct capability — there is no existing child-to-plan walk entry (the top-level walk builds one plan for the whole traversal). Three further union specifics are unaddressed: (a) positional parameters — each child walk allocates its own `0,1,2…` slots (`WalkerContext.bindParam`), but the base boundary step carries ONE `inputParameters` map (`YTDBMatchPlanStep:111`), so `MultiPlanMatchStep` needs per-child param maps or an offset/merge scheme; (b) boundary alias — each child (fresh `WalkerContext`) mints its own boundary alias, yet the "one shared `BoundaryOutputType`" projection looks rows up by a single `boundaryAlias`, so per-child aliases must be reconciled to a common output column; (c) D5 fingerprinting must cover all children for the plan cache.
**Proposed fix**: Decomposition should specify a child-to-plan walk path (run the walker's top-level entry per branch, each with its own fresh `WalkerContext`, then compile each to a plan), and state how per-child positional parameters and boundary aliases are carried into / reconciled by `MultiPlanMatchStep`. This dovetails with the T1 shared-projection refactor: if projection is shared, `MultiPlanMatchStep` applies the common `BoundaryOutputType` projection to every child's rows.

### T5 [should-fix]
**Certificate**: C10 (Integration — MultiPlanMatchStep lifecycle vs single-plan base)
**Location**: Track `## Plan of Work` item 2 and `## Validation and Acceptance` ("an exception in plan N does not start plan N+1"). Source: `YTDBMatchPlanStep.java:447-517` (`reset`/`close`/`clone`), `:287-304` (iteration-failure path), `:484-517` (clone isolation + its documented invariant).
**Issue**: The track specifies only "exception in `plans[N]` closes the current stream and re-throws without starting `plans[N+1..]`" and says nothing about `close()` or `clone()` for the plan list. The base's lifecycle is built for ONE plan: `close()` closes the single `plan` (line 479), the iteration-failure path releases the single stream + plan (lines 297-303), and `clone()` deep-copies the single `plan` against an isolated child `BasicCommandContext` — the documented per-execution isolation point (lines 484-517). For a `List<SelectExecutionPlan>`, `MultiPlanMatchStep` must: close ALL child plans on `close()` and on iteration-failure, not just the currently open one (later plans may be un-started but still hold compiled state); and `clone()` must deep-copy EVERY child plan against its own isolated child context, or concurrent/re-run executions race on shared per-run state. The base clone-isolation invariant is called out explicitly (lines 494-503): the shared parent context must stay free of per-run variables, and "a later track that seeds alias or LET variables onto the plan's context at BUILD time would break it." Union builds several multi-alias MATCH patterns — exactly the shape most likely to seed alias/LET bindings — so this invariant is at elevated risk and must be verified for the union child plans.
**Proposed fix**: Decomposition should specify `MultiPlanMatchStep.close()` and the iteration-failure path iterate and close every child plan (with `addSuppressed` accumulation, mirroring the base), and specify `MultiPlanMatchStep.clone()` deep-copying each child plan against its own isolated child context. Add a check (test + note) that each child plan build seeds no per-run variables onto a shared parent context, preserving the base's clone-isolation invariant.

## Evidence base

#### C1 Premise: `MultiPlanMatchStep extends YTDBMatchPlanStep` (single-ResultShaping ctor inheritance)
- **Track claim**: Plan of Work item 2 — "`MultiPlanMatchStep` extending `YTDBMatchPlanStep`"; ADJUST note — "`MultiPlanMatchStep extends YTDBMatchPlanStep` inherits the post-refactor single-`ResultShaping` constructor."
- **Search performed**: `Read` of `YTDBMatchPlanStep.java` (full); `find` confirmed no `MultiPlanMatchStep.java` exists (planned new).
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/YTDBMatchPlanStep.java:88`
- **Actual behavior**: `public final class YTDBMatchPlanStep<S, E extends Element> extends AbstractStep<S, E> implements AutoCloseable`. Two public constructors (5-arg :177, 7-arg :200; the 7-arg takes `Map<Object,Object> inputParameters` + `ResultShaping shaping`; the old 13-param ctor is gone, matching the ADJUST's refactor claim). All fields (`plan`, `returnClass`, `boundaryAlias`, `outputType`, `inputParameters`, `shaping`, `presenceKeySet`, `openStream`, `armingGraph`, `state`) are `private`; the `State` enum, `processNextStart` helpers (`openArming`, `releaseStream`, `releaseStreamAndClosePlan`), and the projection helpers are `private` (`projectElement` is package-private).
- **Verdict**: WRONG
- **Detail**: A `final` class cannot be subclassed; the track/plan/ADJUST all assume it can. Even de-final'd, constructors are not inherited and the private surface leaves a subclass nothing to reuse. → T1.

#### C2 Premise: three new list-shaping flags route through `ResultShaping` (ADJUST claim 1)
- **Track claim**: ADJUST — extend the `ResultShaping` record via `withX` rather than adding individual `WalkerContext` fields.
- **Search performed**: `Read` of `ResultShaping.java` (full).
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/ResultShaping.java:31-93`
- **Actual behavior**: `public record ResultShaping(boolean dropNullRows, boolean dropOnAbsent, List<String> presencePropertyKeys, boolean wrapMapValuesInLists, boolean accumulateMap, boolean unwrapSingletonMap, boolean elementMapTokens)` with `NONE` default (:44), a compact ctor doing `List.copyOf` (:48), and seven `withX` builders. Extensible by adding record components + `withX` methods.
- **Verdict**: CONFIRMED
- **Detail**: The record is the right seam; extending it touches `NONE`, all seven existing `withX` (each reconstructs the full record), the compact ctor, and both `YTDBMatchPlanStep` ctors (still compile — 5-arg delegates with `NONE`). Routine, expected work. `tailLimit` int needs a sentinel (noted in T3).

#### C3 Premise: `WalkerContext` carries a single `ResultShaping`, no individual flag fields (ADJUST claim 1)
- **Track claim**: individual `WalkerContext`/`YTDBMatchPlanStep` flag fields were removed; a single `ResultShaping` field remains.
- **Search performed**: `Read` of `WalkerContext.java` (full).
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/WalkerContext.java:126, 548-556`
- **Actual behavior**: One field `ResultShaping shaping = ResultShaping.NONE;` (:126), an `@Override setResultShaping(ResultShaping)` (:547-550), and a `shaping()` reader (:554). No `unfoldOutput`/`reverseOutput`/`tailLimit` fields. Recognisers pin via `ctx.setResultShaping(...)`.
- **Verdict**: CONFIRMED
- **Detail**: The new list-shaping recognisers pin their flags by building a `ResultShaping` off `NONE`/current and calling `setResultShaping`. ADJUST claim 1 holds. → supports T1's "ADJUST partially validated" framing.

#### C4 Premise: `BoundaryOutputType.LIST` is new and the projection switch is exhaustive
- **Track claim**: introduce `BoundaryOutputType.LIST`; boundary "gains the `LIST` materialization."
- **Search performed**: `Read` of `BoundaryOutputType.java` (full) and `YTDBMatchPlanStep.projectOrSkip`.
- **Code location**: `BoundaryOutputType.java:23-47`; `YTDBMatchPlanStep.java:523-530`
- **Actual behavior**: Enum has exactly `ELEMENT`, `MAP`, `SINGLE_VALUE`, `SCALAR` — no `LIST` (planned new, CONFIRMED as planned). `projectOrSkip` is `return switch (outputType) { case ELEMENT -> …; case MAP -> …; case SINGLE_VALUE -> …; case SCALAR -> …; };` — an arrow switch expression with no `default`, so it is compile-time exhaustive. The group barrier is handled by an early `if (shaping.accumulateMap())` branch (`:272`) that bypasses `projectOrSkip` entirely.
- **Verdict**: PARTIAL
- **Detail**: Adding `LIST` breaks the exhaustive switch until a `case LIST` arm is added; fold/unfold/tail are barrier/flat-map transforms needing an early stream-level stage, not a per-row `projectOrSkip` case. → T3.

#### C5 Premise/Edge: `tail(n)` placeholder dispatch — both classes implement `TailGlobalStepContract.getLimit()`
- **Track claim**: `tail(n)` recognised via a `TailGlobalStep` recogniser → `tailLimit`.
- **Search performed**: `javap -p` on fork jar (`gremlin-core-3.8.1-af9db90-SNAPSHOT`); `Read` of `RangeGlobalStepRecogniser.java`; `grep` of `GremlinStepWalker.java` registry entries.
- **Code location**: fork `org.apache.tinkerpop.gremlin.process.traversal.step.filter.{TailGlobalStep, TailGlobalStepPlaceholder, TailGlobalStepContract}`; `GremlinStepWalker.java:160-161`; `RangeGlobalStepRecogniser.java:25, 36-38`
- **Actual behavior**: `TailGlobalStepContract<S>` declares `public abstract Long getLimit()`; both `TailGlobalStep` (`final`, `private final long limit`) and `TailGlobalStepPlaceholder` (`final`, `GValueHolder`, `private GValue<Long> limit`) implement it. Precedent: `RangeGlobalStepRecogniser` does `if (!(step instanceof RangeGlobalStepContract<?> range)) return DECLINE;` and is registered under both `RangeGlobalStep.class` and `RangeGlobalStepPlaceholder.class`. Range also null-checks `getLowRange()/getHighRange()` and declines on null. `fold`/`unfold`/`reverse` have no placeholder class in the jar.
- **Verdict**: PARTIAL
- **Detail**: Track names only `TailGlobalStep`; the placeholder form declines silently unless both classes register and the recogniser matches on the Contract + reads `getLimit()` with a null guard. → T2.

#### C6 Premise/Integration: union child walk needs a plan, existing sub-walk yields a predicate
- **Track claim**: item 1 — "sub-walk each child against the registry, build one `SelectExecutionPlan` per child."
- **Search performed**: `Read` of `WalkerContext.walkChild`; `grep` of `NotStepRecogniser` / `ConnectiveStepSupport` child usage; `javap` of `BranchStep`.
- **Code location**: `WalkerContext.java:569-578`; `NotStepRecogniser.java:58-63`; `ConnectiveStepSupport.java:35-41`; fork `BranchStep.getGlobalChildren()` / `getLocalChildren()`
- **Actual behavior**: `walkChild(child)` returns a `SubTraversalPredicateAdapter` via `GremlinStepWalker.subWalk(child, this, recognisers)` — a WHERE predicate folded into the same pattern. and/or/not/where read `getLocalChildren()` and compose predicates. `UnionStep extends BranchStep`; its N branches are `getGlobalChildren()` (confirmed accessor). No existing entry walks a child into a standalone `MatchPlanInputs`/plan.
- **Verdict**: PARTIAL
- **Detail**: Union needs a child-to-plan walk (each branch → its own plan), distinct from the predicate sub-walker; per-child positional params and boundary aliases must be reconciled into `MultiPlanMatchStep`. → T4.

#### C7 Premise: `FoldStep` / `UnfoldStep` / `ReverseStep` exist; `flatMap`/`map` are protected (mirror-only)
- **Track claim**: `unfold()` "mirroring `UnfoldStep.flatMap`"; `reverse()` "mirroring `ReverseStep.map`"; `fold()` → drain to list.
- **Search performed**: `javap -p` on fork jar.
- **Code location**: fork `…step.map.{FoldStep, UnfoldStep, ReverseStep}`
- **Actual behavior**: `FoldStep` (`final`, extends `ReducingBarrierStep`, has `isListFold()`); `UnfoldStep` (`final`, extends `FlatMapStep`, `protected Iterator<E> flatMap(Traverser.Admin<S>)`); `ReverseStep` (`final`, extends `ScalarMapStep`, `protected E map(Traverser.Admin<S>)`). All three concrete final classes; no placeholder variants.
- **Verdict**: CONFIRMED
- **Detail**: `flatMap`/`map` are `protected` — not callable, so "mirroring" correctly means re-implementing the semantics. Bare `fold()` is the list-fold (`isListFold()` true), the only form the recogniser should accept. Supports T3.

#### C8 Premise: Cucumber runners exist
- **Track claim**: `YTDBGraphFeatureTest` (core), `EmbeddedGraphFeatureTest` (embedded).
- **Search performed**: `find`.
- **Code location**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/YTDBGraphFeatureTest.java`; `embedded/src/test/java/com/jetbrains/youtrackdb/shade/EmbeddedGraphFeatureTest.java`
- **Actual behavior**: Both files present.
- **Verdict**: CONFIRMED

#### C9 Premise: LDBC JMH benchmark module exists as a mirror template
- **Track claim**: "a JMH suite mirroring the existing LDBC SQL benchmarks."
- **Search performed**: `find` for benchmark modules/classes.
- **Code location**: `jmh-ldbc/` module (`jmh-ldbc/pom.xml`; `jmh-ldbc/src/main/java/com/jetbrains/youtrackdb/benchmarks/ldbc/` — `LdbcSingleThreadICBenchmark`, `LdbcQuerySql`, `LdbcBenchmarkState`, etc.)
- **Actual behavior**: A full JMH LDBC benchmark suite exists; `LdbcQuerySql` shows the SQL/YQL path the Gremlin-on-vs-off mirror parallels.
- **Verdict**: CONFIRMED
- **Detail**: The mirror has a concrete template. The new benchmarks run equivalent traversals through the Gremlin API with the translator on and off.

#### C10 Integration: MultiPlanMatchStep lifecycle vs single-plan base
- **Plan claim**: `MultiPlanMatchStep` iterates plans in order, one live stream; exception in plan N does not start plan N+1.
- **Actual entry point**: `YTDBMatchPlanStep.close()` (:465-482), iteration-failure path (:287-304), `clone()` (:484-517), `reset()` (:447-453).
- **Caller analysis**: TinkerPop drives `processNextStart`/`close`/`clone`/`reset`; the base handles exactly one `plan`. `clone()` is the documented per-execution isolation point (deep-copies the single plan against an isolated child `BasicCommandContext`), with an explicit invariant (:494-503) that the parent context must carry no per-run variables.
- **Breaking change risk**: `MultiPlanMatchStep` must extend close/clone/failure semantics to the whole plan list (close all, incl. un-run; clone-copy each against its own isolated context) or leak cursors / race on shared per-run state; union's multi-alias child patterns elevate the clone-isolation-invariant risk.
- **Verdict**: MISMATCHES
- **Detail**: Track addresses only the current-stream exception case; close/clone across the list and the isolation invariant are unaddressed. → T5.
