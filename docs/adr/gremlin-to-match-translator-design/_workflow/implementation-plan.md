<!-- workflow-sha: d2dfcc2d44fabd3ac76c5fd7620f1e6013675ad9 -->
# Gremlin-to-MATCH Translator

## Design Document
[design.md](design.md)

## High-level plan

### Goals

Run the pattern-matching subset of TinkerPop traversals through the existing
cost-based `MatchExecutionPlanner` instead of the native left-to-right Gremlin
pipeline, so recognized queries gain MATCH's optimizations — cost-based start
selection, index lookups, prefetch, hash anti-joins — without any SQL text
round-trip.

- Translate a recognized Gremlin step list into the same in-memory IR the SQL
  `MATCH` parser produces (`Pattern` + alias maps + projection metadata,
  packaged as a new `MatchPlanInputs` record) and feed it to the planner via a
  single **additive** constructor (D2). No SQL is generated.
- Stay strictly additive: any unrecognized step declines the **whole**
  traversal to the unchanged native pipeline (D3), so no existing query
  regresses. Coverage grows track by track.
- Factor MATCH IR construction into a shared `match/builder/` package consumed
  by both the translator and the GQL front-end; GQL's observable behavior is
  unchanged (D6).
- Cache plans across queries, keyed on value-independent traversal shape, with
  predicate values bound as positional parameters (D5).
- Unify the exact `count(*)` fast path inside the MATCH engine so SQL, GQL, and
  translated Gremlin class-counts share one snapshot-isolated short-circuit
  (see design §"Aggregation barrier semantics").

### Constraints

- **Multiset equality is the contract.** Translator-on and translator-off must
  return the same elements the same number of times for every recognized shape.
  Element order is explicitly **not** pinned — MATCH's planner reorders, and
  pinning order would erase the optimization. [Amended 2026-08-03: the green
  constraint below is superseded by an enumerated-baseline contract — the suite
  must complete, its failure set is a committed artifact, and no scenario
  regresses against it. Green stopped being reachable when Track 10's rebase
  restored three compliance executions the branch had never run; see
  `plan/track-10/core-compliance-failure-dispositions.md`.] The ~1900-scenario TinkerPop
  Cucumber suite must stay green.
- **Engine surface is preserved.** The only addition to the MATCH execution
  surface is one new public `MatchExecutionPlanner(MatchPlanInputs)` constructor
  (D2). Existing constructors, the IR classes, the execution steps, the grammar,
  and the evaluators are not modified — except the two new string-predicate AST
  nodes in D-TEXT-OPS, the count short-circuit refactor, and the MATCH
  introspection overrides Track 10 adds (`MatchPrefetchStep.getSubSteps()` and
  `MatchFirstStep.getSubSteps()`, each returning the nested sub-plan's steps as
  an immutable snapshot; `getSubExecutionPlans()` is deliberately left at its
  empty default so the counting index-usage helpers cannot double-count). Row
  output, caching eligibility, and execution semantics are unchanged. Two output
  surfaces do move, and the exception covers both: EXPLAIN result documents gain
  nested `subSteps` entries through `ExecutionStep.toResult`, and the
  pretty-printed plan string drops the `+ SET … AS <sub-plan>` block for a
  prefetched root alias, because the planner now builds that root with the
  sub-plan-free constructor instead of attaching a sub-plan nothing reads.
  See plan/track-10.md § Decision Log DR-M3.
- **Recognizers see post-fold shapes.** The strategy runs after TinkerPop's
  structural folders (`IncidentToAdjacentStrategy`, `ConnectiveStrategy`,
  `LazyBarrierStrategy`), so `outE(L).inV()` arrives folded to `out(L)`,
  `and(P,P)` arrives as `AndStep`, and injected `NoOpBarrierStep`s appear
  between recognized steps.
- **Absent vs null-valued properties must stay distinct.** YTDB's record layer
  separates *absent* from *present-with-null*; the query-layer accessor
  collapses them. Filter (`IS DEFINED`) and projection (`hasProperty`) paths
  must compensate to match native Gremlin set membership (design §"Track 5
  commitment", §"Phase 1 dependency").
- **Custom TinkerPop fork** under the `io.youtrackdb` group ID shadows upstream
  `org.apache.tinkerpop` symbols — recognizers key on the fork's `Step`
  classes.
- 85% line / 70% branch coverage on changed code; JDK 21; `./mvnw` build.

### Architecture Notes

#### Component Map

```mermaid
flowchart TB
    subgraph TPside["TinkerPop side (new)"]
        Strat["GremlinToMatchStrategy\n(ProviderOptimizationStrategy)"]
        Walker["GremlinStepWalker"]
        Reg["StepRecogniser registry\nMap&lt;Class,Recogniser&gt;"]
        Ctx["WalkerContext"]
        Cache["GremlinPlanCache"]
        Boundary["boundary base (T7)\n← YTDBMatchPlanStep\n+ MultiPlanMatchStep (T8)"]
    end
    subgraph Builders["Shared MATCH IR builders (new pkg)"]
        PB["MatchPatternBuilder"]
        WB["MatchWhereBuilder"]
        LB["MatchLiteralBuilder"]
    end
    subgraph Engine["Existing MATCH engine (preserved)"]
        MEP["MatchExecutionPlanner\n(+1 additive ctor)"]
        SEP["SelectExecutionPlanner\n(count short-circuit shared)"]
    end
    Half["YTDB half-measure strategies\n(reordered fallback)"]
    GQL["GqlMatchStatement\n(refactored onto builders)"]
    Metrics["YTDBQueryMetricsStep\n(plan capture, T10)"]

    Strat --> Walker --> Reg --> Ctx
    Strat --> Cache
    Reg --> PB & WB & LB
    Walker --> MEP
    MEP --> SEP
    Strat --> Boundary
    Strat -. declines .-> Half
    GQL --> PB & WB & LB
    Boundary -. read by .-> Metrics
```

- **GremlinToMatchStrategy** — entry point; idempotent; walks the step list,
  decides yes/no for the whole traversal (D3), and on yes replaces every step
  with one boundary step. Registered before the three half-measure strategies
  via their `applyPrior()` (D4).
- **GremlinStepWalker + StepRecogniser registry** — index-driven walk;
  `Map<Class<? extends Step>, StepRecogniser>` keyed on the step's runtime
  class (D9). A recognizer may consume N steps in one claim (D10).
- **WalkerContext** — per-walk accumulator: pattern builder, alias maps,
  anonymous-alias generators, bound-parameter map, return/order/limit metadata,
  boundary output type.
- **Shared MATCH IR builders** — language-agnostic IR assembly; both the
  translator and the refactored `GqlMatchStatement` consume them (D6).
- **Boundary base / YTDBMatchPlanStep / MultiPlanMatchStep** — a shared boundary
  base extracted in Track 7 carries the row-projection + `ResultShaping`
  machinery bridging YTDB's `ExecutionStream` back to TinkerPop traversers; the
  single-plan `YTDBMatchPlanStep` and the N-plan `MultiPlanMatchStep` (Track 8,
  `union`, D8) both reuse it, plus an ordered list-shaping post-process that
  Track 11's terminators drive. Mechanism detail in `plan/track-7.md`
  `## Context and Orientation`.
- **Existing engine** — preserved; reached through one additive constructor
  (D2). The count short-circuit is factored to a shared helper the planner
  invokes (design §"Aggregation barrier semantics").
- **YTDBQueryMetricsStep** — a read-only observer, not a component the plan
  builds. Its `capturedExecutionPlan()` read `YTDBGraphStep` until translation
  removed that step, and now reads the boundary step. Track 10 settles the
  three contracts the shift exposed: `reset()` from `CLOSED`, `g.V(rid)` plan
  capture, and boundary sub-step introspection.

#### D1: Integration via `ProviderOptimizationStrategy`
- **Alternatives considered**: a custom `GraphTraversalSource` step; rewriting
  the Gremlin compiler; intercepting at `GraphStep` only.
- **Rationale**: a `ProviderOptimizationStrategy` is TinkerPop's sanctioned
  provider hook — it runs after structural folding, sees the whole step list,
  and can replace it wholesale. It composes with the existing YTDB strategies
  via the standard `applyPrior()`/`applyPost()` ordering contract.
- **Risks/Caveats**: ordering relative to the half-measure strategies and the
  structural folders must be explicit; mis-ordering changes what shapes the
  recognizers see. Handled by D4.
- **Implemented in**: Track 2

#### D2: Planner entry via additive `(MatchPlanInputs)` ctor; planner owns the projection block
- **Alternatives considered**: generate SQL `MATCH` text and re-parse;
  call `SelectExecutionPlanner.handleProjectionsBlock` from the strategy.
- **Rationale**: building the IR directly skips a parse round-trip and keeps the
  translator type-safe. The planner already calls `handleProjectionsBlock`
  internally inside `createExecutionPlan`; the strategy must **not** call it too
  (the consistency review caught a double-append). One additive constructor
  leaves the three existing ones untouched.
- **Risks/Caveats**: `MatchPlanInputs` must carry every field the planner reads
  post-parse; a missing field surfaces as a planning-time gap. Mitigated by the
  reused-steps audit (design §"Reused execution steps").
- **Implemented in**: Track 2
- **Full design**: design.md §"Overview", §"Workflow"

#### D3: All-or-nothing translation, no hybrid prefix
- **Alternatives considered**: hybrid — translate the longest recognized prefix
  and let an unrecognized suffix run natively over the boundary's output.
- **Rationale**: the hybrid required cross-boundary output-type negotiation,
  cross-boundary label propagation, and special-casing `path()` — each a bag of
  edge cases with no proportional benefit at Phase 1's small recognized set.
  All-or-nothing removes the boundary as a splice point: one unrecognized step
  declines the whole traversal and the native pipeline handles it verbatim.
- **Risks/Caveats**: a single unsupported step forfeits MATCH for the whole
  query. Accepted — coverage grows track by track; every declined shape is at
  least as well-served as before.
- **Implemented in**: Track 2 (decline logic); enforced by every recognizer
- **Full design**: design.md §"Overview"

#### D4: Strategy ordering — translator first, half-measure strategies as fallback
- **Alternatives considered**: remove the three half-measure strategies;
  declare ordering with `applyPost()` on the translator.
- **Rationale**: each half-measure strategy lists `GremlinToMatchStrategy` in
  its own `applyPrior()`, so TinkerPop's topological sort runs the translator
  first; the translator declares empty prior/post. On decline the original step
  list is preserved verbatim and the half-measures see it next, keeping today's
  behavior for shapes the translator does not yet cover.
- **Risks/Caveats**: `YTDBGraphCountStrategy` must stay (reordered, not removed)
  to serve multi-label and non-polymorphic counts the short-circuit declines.
- **Implemented in**: Track 2

#### D5: Plan cache in Phase 1, keyed on traversal shape, values bound at execution
- **Alternatives considered**: no cache in Phase 1; cache keyed on full
  bytecode including predicate values.
- **Rationale**: predicate values are bound as `SQLPositionalParameter` slots
  and elided from the key (mirroring YQL's `?` handling), so one plan serves
  every parameter value without cache thrash. The key is the value-independent
  generic-statement fingerprint; the cache spares only the expensive planner
  pass.
- **Risks/Caveats**: structural-vs-value classification must be correct — a
  value leaking into the key thrashes the cache; a structural token bound as a
  param serves a wrong plan. Schema changes reuse the YQL invalidation hook.
- **Implemented in**: Track 5
- **Full design**: design.md §"Parameter binding"

#### D6: Shared MATCH IR builder package; GQL refactor in Phase 1
- **Alternatives considered**: translator-private IR helpers; defer the GQL
  refactor to a later phase.
- **Rationale**: one builder package (`match/builder/`) serves both front-ends
  from day one, so the IR-construction contract is exercised by GQL's existing
  tests immediately. The GQL refactor is strictly behavior-preserving — its
  public API and test assertions are unchanged.
- **Risks/Caveats**: a behavior drift in the GQL refactor would surface as a GQL
  test failure; the builders must cover both today's GQL needs and the
  translator's full needs (chains, edges, predicates).
- **Implemented in**: Track 1
- **Full design**: design.md §"GQL refactor and shared builders evolution"

#### D7: Strategy idempotency
- **Alternatives considered**: rely on TinkerPop to apply strategies once; a
  per-traversal "translated" flag.
- **Rationale**: strategy chains can re-apply (clone for sub-traversal reuse,
  test harness re-application, lazy first-iteration apply). A single early scan
  of the whole step list for any boundary step returns immediately if found —
  O(N) over a single-digit step count, negligible cost, absolute safety. The
  scan keys on the Track 7 boundary base (D8 revised) so it detects both
  `YTDBMatchPlanStep` and the `union` `MultiPlanMatchStep`.
- **Risks/Caveats**: the scan must cover the entire list, not just the start
  step, because a wrapping source can place steps before a translated boundary.
- **Implemented in**: Track 2 (against `YTDBMatchPlanStep`); broadened to the
  boundary base in Track 7 with the base extraction.

#### D8: Union enters the recognized set; `optional` deferred to Phase 2 (revised after Track 6)
- **Original decision**: translate `union` by building one `SelectExecutionPlan`
  per child and concatenating their streams in a `MultiPlanMatchStep` that
  `extends YTDBMatchPlanStep`; all children must agree on output type or the
  union declines whole; `optional` deferred to Phase 2. Alternatives at the
  time: MATCH `splitDisjointPatterns` cartesian product (wrong semantics);
  ship `optional` in Phase 1.
- **What changed**: Track 7 Phase A reviews (technical / risk / adversarial,
  iter 1) found two realization gaps. (1) `YTDBMatchPlanStep` is a
  `public final class` with private lifecycle and projection machinery, so
  `MultiPlanMatchStep extends YTDBMatchPlanStep` cannot compile, and even
  de-finalized a subclass would inherit nothing reusable (constructors are not
  inherited). (2) The strategy declines any traversal whose start step is not a
  vertex `GraphStep` (`hasVertexGraphStart`), so start-position `union` declines;
  mid-traversal union children are prefix-relative sub-traversals that carry an
  `EndStep`, and the planned "sub-walk each child against the registry → one
  `SelectExecutionPlan`" accounted for neither, nor for a child→full-plan path
  (the existing `walkChild` yields a WHERE-predicate adapter, not a plan).
- **Revised decision**: the concatenation semantics and the all-children-agree-
  on-output-type decline gate are unchanged. The realization splits into two
  tracks. Track 7 (foundation) extracts a shared **boundary base** — an abstract
  superclass or a composed row-projector — from `YTDBMatchPlanStep` so both the
  single-plan step and a new `MultiPlanMatchStep` reuse projection + shaping.
  Track 8 (union) forks the traversal prefix into each child, strips the child
  `EndStep`, and builds one full `SelectExecutionPlan` per child;
  `MultiPlanMatchStep` iterates them with one live stream at a time, closing all
  child plans (including un-run ones) and cloning each against an isolated
  context, and stops advancing on the first child exception.
- **Alternatives considered**: de-finalize `YTDBMatchPlanStep` and subclass it
  directly (rejected — private machinery leaves the subclass nothing reusable
  and constructors are not inherited); keep the merged single-track plan
  (rejected — the base refactor plus prefix-forking union pushes the footprint
  past the split ceiling, user-approved split 2026-07-27).
- **Rationale**: only the realization is corrected, to compile and to handle the
  prefix / `EndStep` reality; the user-visible union semantics are untouched.
- **Risks/Caveats**: the base extraction touches a class every prior track's
  boundary depends on — a behavior-neutral refactor guarded by the projection /
  aggregate / equivalence suites and the full Cucumber re-run in Track 11. Union
  cache policy (single-plan cache value today) must be pinned in Track 8.
- **Implemented in**: Track 7 (boundary base), Track 8 (union) — revised from the
  original single Track 7.
- **Full design**: design.md §"Union semantics divergence"

#### D9: Type-keyed recognizer dispatch via `Map<Class<? extends Step>, StepRecogniser>`
- **Alternatives considered**: an ordered `instanceof` chain of recognizers.
- **Rationale**: `map.get(step.getClass())` on the concrete runtime class gives
  O(1) dispatch and **safe failure** on unknown subclasses — a future
  `BespokeHasStep extends HasStep` returns `null` and declines cleanly rather
  than misrouting through a parent recognizer. One map entry per `Step` class;
  each recognizer handles every variant internally (so `NotStep` is one
  recognizer branching on `hasEdgeHops`). Parallel tracks register disjoint keys
  and cannot collide; a duplicate-key assertion catches the rare clash.
- **Risks/Caveats**: a registered recognizer that branches internally still
  needs the no-mutation-on-decline discipline (per-recognizer unit invariant).
- **Implemented in**: Track 2 (walker + registry); per-class entries added by
  Tracks 2–8 and Track 11
- **Full design**: design.md §"Recogniser dispatch"

#### D10: Walker supports multi-step claims via index-driven iteration
- **Alternatives considered**: single-step-per-recognizer for-each loop with a
  look-back buffer.
- **Rationale**: non-adjacent edge filtering (`outE(L).has(...).inV()`) needs a
  recognizer to consume several steps in one claim. The walker loop is
  index-driven (`ctx.stepIndex`), so a recognizer advances the index by N
  instead of the default `++`. This is the first multi-step claim; it lands when
  edge filtering does.
- **Risks/Caveats**: a recognizer that mis-counts consumed steps corrupts the
  walk; the no-mutation-on-decline contract bounds the blast radius.
- **Implemented in**: Track 3
- **Full design**: design.md §"Edge filtering in non-adjacent chains"

#### D11: Unify the exact class-`count(*)` fast path in the MATCH engine
- **Alternatives considered**: keep declining class-count to the
  `YTDBGraphCountStrategy` front-end strategy (the earlier approach,
  motivated by preserving an O(1) non-snapshot-isolated count);
  per-front-end count optimizers (SQL / GQL / Gremlin each own one).
- **Rationale**: since YTDB-609 (#791) the class-count fast path stopped
  being O(1) / non-SI — `countClass(name, true)` is now a snapshot-isolated
  per-record-visibility scan, so the original "declining preserves O(1)"
  rationale no longer holds. Factoring `handleHardwiredCountOnClass*` out of
  `SelectExecutionPlanner` into a shared helper the `MatchExecutionPlanner`
  also invokes gives SQL, GQL, and translated Gremlin one exact SI count
  path; the dedicated `CountFromClassStep` / `CountFromIndexWithKeyStep` still
  win a constant factor (no record-body deserialization). This is the one
  engine-surface change beyond the additive ctor (D2).
- **Risks/Caveats**: `CountFromClassStep.canBeCached()==false`, so these MATCH
  plans are not cached (SELECT already behaves so). Multi-label and
  non-polymorphic counts decline to the reordered `YTDBGraphCountStrategy`
  fallback (D4). The genuine O(1) `approximateCountClass` stays detached —
  an opt-in count mode is Phase 2.
- **Implemented in**: Track 6
- **Full design**: design.md §"Aggregation barrier semantics"

#### D-IS-DEFINED: Adopt existing YTDB SQL `IS DEFINED` / `IS NOT DEFINED` operators
- **Alternatives considered**: map `has(key)`/`hasNot(key)` to `IS NULL`/`IS NOT
  NULL`; add new presence operators from scratch (the earlier design draft).
- **Rationale**: `IS NULL` over-matches — TP `hasNot(key)` is false for a
  property stored with literal `null` (the wrapper reports `isPresent()==true`),
  while `IS NULL` matches it. The grammar already has
  `SQLIsDefinedCondition`/`SQLIsNotDefinedCondition` (audit during PR #1038),
  routing through the `isDefinedFor` entity-presence primitive. Phase 1 only
  adds `MatchWhereBuilder.isDefined`/`isNotDefined` factories wrapping the
  existing AST nodes — no grammar, AST, or evaluator change.
- **Risks/Caveats**: presence predicates are not index-aware
  (`isIndexAware()==false`) — full-scan filters, as documented.
- **Implemented in**: Track 1
- **Full design**: design.md §"Phase 1 dependency: `IS DEFINED` / `IS NOT DEFINED` operators"

#### D-TEXT-OPS: Phase 1 string predicates — range prefix, collation-aware suffix/substring, case-sensitive regex
- **Alternatives considered**: `SQLLikeOperator` for prefix/suffix; decline all
  string predicates to native.
- **Rationale**: `LIKE` is unconditionally case-insensitive, rewrites literal
  `%`/`?` into wildcards, and is never index-aware — wrong on three axes.
  `startingWith` becomes the half-open range `field >= p AND field < p⁺` (index-
  aware, collation-respecting); `endingWith` needs a new `SQLEndsWithCondition`
  AST node; `regex` needs a find-mode flag on `SQLMatchesCondition`. Declining
  any one string predicate would decline the whole traversal under D3, so all
  translate.
- **Risks/Caveats**: suffix/substring/regex are full-scan (`isIndexAware()==
  false`); `regex` stays case-sensitive because collate-transforming a pattern
  changes its meaning. Adding the collate transform to `SQLContainsTextCondition`
  also makes SQL `CONTAINSTEXT` collation-aware (no-op on `default`).
- **Implemented in**: Track 4
- **Full design**: design.md §"Predicate translation"

### Invariants
- A recognized traversal contains exactly one boundary step —
  `YTDBMatchPlanStep` (single-plan) or `MultiPlanMatchStep` (union, its sibling
  under the Track 7 boundary base, D8) — after `applyStrategies()`; a declined
  traversal preserves the original step list verbatim. (Boundary-step engagement
  assertion — Tracks 2–11 tests.)
- No-mutation-on-decline: a recognizer that returns `false` leaves
  `WalkerContext` unmutated (per-recognizer unit invariant).
- The strategy is idempotent: re-applying on a traversal already containing a
  boundary step (`YTDBMatchPlanStep` or `MultiPlanMatchStep`) is a no-op.
- Translator-on and translator-off produce equal result multisets for every
  `RECOGNIZED` shape (`EdgeTraversalEquivalenceTest`).
- `GqlMatchStatement` observable behavior is unchanged after the builder
  refactor (its existing tests pass with the same assertions).

### Integration Points
- `GremlinToMatchStrategy` registered in the provider optimization chain; named
  in each half-measure strategy's `applyPrior()` (D4).
- `MatchExecutionPlanner(MatchPlanInputs)` additive ctor → existing
  `createExecutionPlan` pipeline (D2).
- Shared count short-circuit factored from
  `SelectExecutionPlanner.handleHardwiredCountOnClass*`, invoked by
  `MatchExecutionPlanner` after `buildPatterns`.
- `GremlinPlanCache` reuses the YQL plan-cache schema-change invalidation hook.
- `YTDBQueryMetricsStep.capturedExecutionPlan()` reads the compiled plan off the
  boundary step (`YTDBMatchPlanStep`, then `MultiPlanMatchStep`'s first child
  plan), falling back to `YTDBGraphStep.getLastExecutionPlan()` for untranslated
  traversals; a change to what translates changes what the monitoring layer
  sees (Track 10).

### Non-Goals
Phase 2+ (the translator declines these under D3; native pipeline handles them):
`optional(...)`; OR over edge-bearing sub-traversals; variable-depth
`repeat()/times()`; stateful side-effects (`sack`/`store`/`aggregate`); lambda
steps; `subgraph`; path manipulation (`simplePath`/`cyclicPath`/advanced
`path()`); `choose()`; custom DSL steps; edge-returning terminals and
user-facing edge aliases; edge property extraction; multi-label edges
(`out("a","b")`); mid-traversal list-shaping; singleton-collection equality on
schema-less fields; `profile()`; **positional suffixes after a `union` that are
not immediately followed by `count()`** — `union(...).limit(n)`,
`union(...).range(a, b)`, `union(...).skip(n)`. Full table: design.md §"Out of
scope (Phase 2+)".

**The post-union positional suffix, and the way out of it (Track 9 step 7).**
Gremlin's `union` is a `BranchStep` that interleaves its arms per incoming
traverser, while `MultiPlanMatchStep` concatenates the child plans branch-major
through a `MultipleExecutionStream`. Both emit the same rows; only the order
differs, so any suffix that selects by position sees a different prefix on each
arm. `count()`, `dedup()` and the other reductions to cardinality or to a
distinct set are order-free, which is why `union(...).count()` and
`union(...).limit(3).count()` still translate and why the decline is narrowed to
the positional case rather than dropping the range recogniser outright.

Translating the positional case needs the boundary step to reproduce native's
arrival order, and the obstacle is structural rather than incidental: each union
child is its own compiled MATCH plan over the whole start set, so there is no
per-traverser interleaving point to imitate. The exit is to make each child plan
carry the identity of the start row its results belong to and emit them ordered
by it, then replace the concatenation in `MultiPlanMatchStep` with a k-way merge
on that key. That is a change to the child-plan output contract and to the
boundary step together, it overlaps the index-ordered MATCH work, and it is
sized as its own track rather than as a suffix to this plan. Relaxing the
acceptance criterion to unordered multisets is **not** an alternative: the
TinkerPop compliance suite asserts specific prefixes, so the project would
trade a wrong answer for a failed conformance run.

## Checklist

- [x] Track 1: Shared MATCH IR builders + GQL adoption + `IS DEFINED` / `IS NOT DEFINED` builder factories
  > Foundation track: creates the shared `match/builder/` package consumed by
  > both GQL and the upcoming Gremlin translator, and exposes
  > `MatchWhereBuilder.isDefined` / `isNotDefined` factories wrapping the
  > pre-existing `SQLIsDefinedCondition` / `SQLIsNotDefinedCondition` AST nodes
  > (D-IS-DEFINED) — wiring only, no grammar / parser / evaluator changes.
  > **Scope:** ~9 files covering three builder classes, `GqlMatchPatternAssembler`,
  > the behavior-preserving GQL refactor, the two presence-operator factories,
  > builder unit tests, and prettyPrint plan regression tests.
  > **Size:** ~7 in-scope files — below the ~12-file floor, kept standalone by
  > justification: the `match/builder/` package is foundational and is adopted
  > by GQL independently of the translator, so it stands alone as an
  > independently reviewable, independently mergeable PR.

- [x] Track 2: Strategy skeleton + boundary step + minimal `g.V()` / `g.V(ids)` translation
  > Wires `GremlinToMatchStrategy` into the optimization chain and establishes
  > the end-to-end pipeline with the simplest recognized traversal. Lands the
  > cross-cutting scaffolding every later track extends: the `MatchPlanInputs`
  > record + the single additive `MatchExecutionPlanner` ctor (D2), the
  > `GremlinStepWalker` + `WalkerContext` + `StepRecogniser` registry (D9) +
  > `StartStepRecogniser`, idempotency (D7), the
  > anonymous-alias generator, and the `YTDBMatchPlanStep` boundary; registers
  > the strategy and reorders the three half-measure strategies' `applyPrior()`
  > (D4).
  > **Scope:** ~19 files covering the record + planner ctor, strategy skeleton
  > with structural gating + ordering + idempotency, walker + context +
  > recogniser registry + start recogniser, anon-alias generator,
  > boundary step, registration + half-measure edits, minimal `g.V()` /
  > `g.V(ids)` translator, and strategy / boundary tests + a Cucumber
  > smoke check.
  > **Depends on:** Track 1.
  >
  > **Strategy refresh:** CONTINUE — Track 2's discoveries are absorbed by
  > Track 3's plan with no scope, dependency, or ordering change to Tracks 3–6:
  > `useCache=false` binds the shared planner ctor, `WalkerContext.polymorphic`
  > now exists so Track 3 only adds the chain-target read, the throw-safety net
  > rethrows `Error`/`AssertionError`, `clone()` isolation already covers
  > multi-node patterns, and the anon-alias generator deferred from Track 2
  > lands here. (Phase A reviews then corrected two plan premises — see the
  > Track 3 entry below and `plan/track-3.md` `## Surprises & Discoveries`:
  > chain-target `@class` narrowing is dropped entirely because even
  > `polymorphic=false`-gating replays BC2, and edge filtering needs an
  > edge-as-node builder extension the plan had assumed away.)

- [x] Track 3: Edge traversal — `out` / `in` / `both`, folded `outE.inV` etc., plus non-adjacent edge filtering
  > Extends the recognized set with edge-traversal patterns and non-adjacent
  > edge filtering (`outE(L).has(...).inV()`). The walker gained multi-step
  > recognition through a walker-owned step cursor (D10). Bare chain-hop
  > targets root at `V` polymorphically — no `@class` narrowing (BC2). Edge
  > filtering uses the edge-as-node form (`outE(L){as $e, where}.inV()`) via a
  > new builder/assembler capability (the executor already supports it;
  > `MatchPatternBuilder.addEdge` cannot filter edges). `WalkerContext.polymorphic`
  > and the `ELEMENT` boundary already landed in Track 2; Track 3 re-pins the
  > boundary to the last hop's target and builds the anonymous-alias generator
  > + reserved-`$` scan deferred from Track 2.
  >
  > **Track episode:** Edge traversal on a walker-owned step-cursor
  > architecture; completed as-is over an unreviewed 14-commit post-review
  > rework — see `plan/track-3.md` `## Episodes` § Track completion. (3 steps, 0 failed)
  >
  > **Track file:** `plan/track-3.md`
  >
  > **Strategy refresh:** ADJUST — Track 4's track file reconciled to Track 3's
  > post-review rework before decomposition: folded-`hasLabel` narrowing via
  > `MatchWhereBuilder.classEquals` (`MatchClassFilters` deleted), the
  > already-landed absent-property `neq` semantics (`k IS DEFINED AND k <> v`),
  > the `Outcome recognize(StepCursor, RecognitionContext)` recogniser contract,
  > and the new `WalkerContext` shape. Scope, dependencies, and ordering for
  > Tracks 4–6 unchanged.

- [x] Track 4: Filtering — predicates (`has` / `hasLabel` / `hasId`, `P` / `Text` / `TextP`)
  > Translates the Gremlin predicate surface into MATCH WHERE clauses: the
  > full `P` / `Text` / `TextP` predicate algebra (string operators per
  > D-TEXT-OPS), `has` / `hasLabel` / `hasId`, and the bare presence form
  > `has(key)` (`IS DEFINED`, D-IS-DEFINED). Comparison values render as
  > inline literals here; Track 5 flips them to positional parameters when it
  > lands the plan cache. The logical filters and the cache split off to
  > Track 5 (adversarial A1). Detail in plan/track-4.md.
  >
  > **Track episode:** Full predicate adapter, HasStep / presence recognisers,
  > D-TEXT-OPS SQL layer; Phase C A1 pin reversed `eq(null)` to bare `IS NULL`
  > — see `plan/track-4.md` `## Episodes` § Track completion. (3 steps, 0 failed)
  >
  > **Track file:** `plan/track-4.md`

- [x] Track 5: Logical filters + plan cache — `and` / `or` / `not` / `where`, sub-walker, `GremlinPlanCache` (D5)
  > Splits off from Track 4 at decomposition (adversarial A1: the merged
  > predicate + logical surface realized past the ~25-file split ceiling with
  > a clean seam, user-approved 2026-07-15). Adds the step-level logical
  > filters (`and` / `or` / `not` / `where`) and the `hasNot(key)` negation,
  > each composed by a sub-walker (`SubTraversalPredicateAdapter`) that runs
  > the child sub-traversal against the same registry; a single `NotStep`
  > recogniser owns `NotStep.class` (A2). Lands the `GremlinPlanCache` (D5):
  > predicate values bind as positional parameters so one plan serves every
  > value, keyed on the post-walk generic-statement fingerprint (A3), with
  > RID-bearing shapes bypassing the cache (R3). Detail in plan/track-5.md.
  >
  > **Track episode:** Sub-walker seam, combinator recognisers, D5 plan cache
  > with positional parameters; Phase C PASS (0 blockers). See
  > `plan/track-5.md` `## Episodes` § Track completion. (5 steps + 2
  > post-step refactors, 0 failed)
  >
  > **Track file:** `plan/track-5.md`

- [x] Track 6: Result shaping — labels + dedup, projections, order/pagination, aggregations
  > Merges the four result-producing step families — labels + dedup,
  > projections, order / pagination, and aggregations — pinning the boundary
  > output type per terminal step. The load-bearing cases are absent-vs-null
  > (`EntityImpl.hasProperty(key)`) and the empty-input aggregate divergence,
  > handled by the `dropOnAbsent` / `dropNullRows` boundary flags, with a shared
  > `ByModulatorTranslator` serving the `by(...)` modulator across the families.
  > Detail in plan/track-6.md.
  > **Scope:** ~20 files covering as-label + dedup walker extensions,
  > `GremlinProjectionAssembler` + projection recognisers, the shared
  > `ByModulatorTranslator`, `Order` / `Range` recognisers, aggregate
  > recognisers + the shared count short-circuit helper (`MatchExecutionPlanner`
  > + `SelectExecutionPlanner` edits), and parity / projection / absent-vs-null /
  > aggregate-equivalence / empty-result tests.
  > **Depends on:** Track 4, new Track 5 (the sub-walker its `by`-value
  > accumulators reuse), and Track 1 (`hasProperty` primitive / presence check).
  >
  > **Track episode:** Four result-producing step families on per-terminal
  > boundary output types; shared `ByModulatorTranslator`, count short-circuit
  > to `CountFromClassStep`, absent-vs-null via `dropOnAbsent` / `dropNullRows`.
  > Phase C deeper re-audit plus two further re-review passes fixed a plan-cache
  > count disclosure, dedup-after-projection, aggregate-clause-order, and
  > concurrent-DDL cache staleness; the seven shaping flags folded into
  > `ResultShaping`. See `plan/track-6.md` `## Episodes`. (7 steps + 6b +
  > post-completion tail, 0 failed)
  >
  > **Track file:** `plan/track-6.md`
  >
  > **Strategy refresh:** ADJUST — Track 7's three list-shaping post-process
  > flags (`unfoldOutput` / `reverseOutput` / `tailLimit`) re-pointed to Track 6's
  > `ResultShaping` record (extend the record via `withX` rather than adding
  > individual `WalkerContext` fields), and `MultiPlanMatchStep` inherits the
  > post-refactor single-`ResultShaping` `YTDBMatchPlanStep` constructor. Scope,
  > dependencies, and ordering unchanged; applied in the Phase A track-file write.
  > (Both premises were overturned by the 2026-07-27 split — see D8 and Tracks
  > 7/8/9: `MultiPlanMatchStep` extends the shared boundary base Track 7
  > extracts, not `YTDBMatchPlanStep`, and the list-shaping post-process is an
  > ordered `List` because order-less flags cannot encode `reverse().unfold()`
  > vs `unfold().reverse()`.)

- [x] Track 7: Boundary base extraction + ordered list-shaping infrastructure
  > Foundation refactor for the last feature slice: the original Track 7's Phase
  > A reviews found `MultiPlanMatchStep extends YTDBMatchPlanStep` does not
  > compile (`YTDBMatchPlanStep` is `final` with private machinery), so this
  > track extracts a shared boundary base — abstract superclass or composed
  > row-projector — from `YTDBMatchPlanStep` that both the single-plan step and
  > the upcoming `MultiPlanMatchStep` (Track 8) reuse for row projection +
  > `ResultShaping` (D8 revised). It also introduces the ordered list-shaping
  > post-process (declared-order fold / unfold / reverse / tail — order-less
  > flags cannot encode `reverse().unfold()` vs `unfold().reverse()`) that
  > Track 9 drives [Track 11 after the 2026-08-03 split], all behavior-neutral.
  > Detail in plan/track-7.md.
  >
  > **Track episode:** Extracted the `AbstractMatchPlanStep` base and added the
  > `ListShapingOp` ordered list-shaping carrier — behavior-neutral
  > infrastructure for Track 8 (multi-plan advance-on-drain over the base) and
  > Track 9 [Track 11 after the 2026-08-03 split] (fold/tail ops registered into
  > the carrier) — see `plan/track-7.md`
  > `## Episodes` § Track completion. (2 steps, 0 failed)
  >
  > **Track file:** `plan/track-7.md`
  >
  > **Strategy refresh:** CONTINUE — Track 7 delivered the `AbstractMatchPlanStep`
  > base (four plan-seam hooks + `resetLifecycleForClone()`) that Track 8's
  > `MultiPlanMatchStep` extends, and already retargeted the D7 idempotency
  > `instanceof` scan to the base. Track 8's `## Interfaces and Dependencies`
  > "broaden the scan" sub-item is therefore a no-op the decomposer drops,
  > keeping only union-recogniser registration on `GremlinToMatchStrategy`. No
  > scope, dependency, or ordering change to Tracks 8–9.

- [x] Track 8: Union via `MultiPlanMatchStep`
  > Adds `union(...)` (D8): a `UnionStepRecogniser` that forks the traversal
  > prefix into each global child, strips the child `EndStep`, and builds one
  > full `SelectExecutionPlan` per child; and a `MultiPlanMatchStep` (subclass of
  > the Track 7 boundary base) that concatenates the plans with one live
  > `ExecutionStream` at a time, closes every child plan including un-run ones,
  > clones each against an isolated context, and stops advancing on the first
  > child exception. All children must agree on output type or the union declines
  > whole; start-position union declines under the vertex-`GraphStep` start gate.
  > Pins the union plan-cache policy. Detail in plan/track-8.md.
  >
  > **Track episode:** delivered as planned, plus a post-concatenation pipeline
  > for `count` / `limit` / `dedup` (`PostConcatStreams`) and per-child plan-cache
  > reuse with the carrier itself uncached (DR-U5). 38 files, 5,814 insertions —
  > past the ~4,000-line review-burden threshold, recorded as a retroactive-split
  > signal for future planning. Phase C ran three iterations over 42 findings: 30
  > cleared, zero blockers and zero should-fix remaining, twelve suggestions
  > carried forward. Both blockers were silent-wrong-results defects originating
  > in unrostered follow-up commits that never received a step-level review. The
  > first blocker's fix introduced a performance regression (the post-union gate
  > landed after the child fork); the gate check caught it and the repair moved
  > the check ahead of the fork, leaving the decline path cheaper than before.
  > DR-U7 records the accepted constant-factor cost on non-polymorphic bare
  > `count()`. The coverage gate was not run in any iteration — see the track
  > file for the reasoning and the resulting unmeasured thresholds. Full detail
  > in plan/track-8.md.
  >
  > **Strategy refresh:** CONTINUE — Track 8's discoveries are absorbed by the
  > remaining plan with no scope, dependency, or ordering change. The
  > false-pre-existing-failure discovery produced Track 10 itself, so it is
  > absorbed by construction. Track 9's [Track 11's after the 2026-08-03 split]
  > newly-absorbed post-union relaxation was
  > checked against the as-built union machinery and holds: `MultiPlanMatchStep`
  > passes `shaping` to the Track 7 boundary base, so the list-shaping
  > post-process applies once over the whole concatenation, and Track 8's
  > `PostConcatOp` (a sealed type permitting `Count` / `Range` / `Dedup` only) is
  > a separate concat-time mechanism rather than a competing one. The sizing
  > signal (38 files / 5,814 insertions past the review-burden threshold) and the
  > unrostered-follow-up-commit review gap are carried forward as forward risks
  > for Track 9 [split 2026-08-03; the terminators are Track 11], whose Cucumber
  > triage bucket its own Scope line marks unsized
  > until the first run; neither warrants a plan edit now.

- [x] Track 10: Query-metrics regression remediation — enumerated `core` baseline, no regression against the track base
  > **Runs before Track 9** despite the higher number (inline replan, 2026-08-01).
  > Four `YTDBQueryMetricsStrategyTest` scenarios have failed since 2026-07-16;
  > `./mvnw -pl core test` has been red on this branch for 117 commits, hidden
  > because PR #1038 is a draft and every CI check reports `skipping`. Two
  > culprits: `6e657ce2b1` (Track 4 era) removed `YTDBGraphStep` from translated
  > traversals, which is the only step `YTDBQueryMetricsStep.capturedExecutionPlan()`
  > read; Track 8's `3d476357cc` then repaired three scenarios and broke a fourth.
  >
  > **Track episode:** Repaired 483 compliance and unit failures and caused one,
  > against a stale pre-rebase inventory that made the track look like the culprit;
  > the green-`core` title was unreachable and was corrected, and a dropped
  > per-alias-filter defect was localized and deferred to Track 9 — see
  > `plan/track-10.md` `## Episodes` § Track completion. (6 steps, 0 failed)
  >
  > **Track file:** `plan/track-10.md`
  >
  > **Strategy refresh:** ADJUST — Track 9's shape holds (terminators, the Cucumber
  > gate, the JMH harness, the dropped-filter fix all survive), but four of its
  > claims were
  > amended. Plan-of-Work item 6 pointed at Track 10's superseded step-0 inventory
  > and asserted it covered the Cucumber runner; it covers neither, so item 6 now
  > reads the dispositions file and the track derives the first Cucumber baseline
  > itself. [Amended 2026-08-03: after the split, Track 9 publishes that baseline
  > and Track 11 item 6 reads it — not the dispositions file, which has no
  > `gremlin-feature-compliance-tests` row at all.] Item 1's triage bucket gained its enumerated process-compliance half:
  > 21 deferred failures, 9 of the dropped-filter signature and 12 separate defects.
  > `## Context and Orientation` absorbed the boundary base's growth to seven
  > lifecycle states, and the Decision Log absorbed the recompute-the-inventory-
  > with-the-base-SHA rule. Track 10's own "`core` Cucumber is inert" discovery was
  > checked against HEAD and does not hold — develop's `9b9dfa20fd` gives the runner
  > its own unfiltered surefire execution — so the runner list stayed as written.
  > **[Withdrawn 2026-08-03.]** The wiring claim is right and the conclusion drawn
  > from it was not: the runner reports zero scenarios for a different reason, and
  > the suite does not complete at all with the translator on. See
  > `plan/track-9.md` § Clarifications.
  > Forward risk, now realized rather than carried: Tracks 8 and 10 both landed 38
  > files past the ~4,000-line review-burden threshold, and Phase A's technical
  > review took Track 9 from ~18–26 to **~22–30 files** — already above the ~20–25
  > split-candidate bound, before the Cucumber triage bucket is sized. The Phase A
  > adversarial review weighs sizing against that figure; if it or decomposition
  > confirms the track is oversized, the response is ESCALATE rather than a
  > silently oversized third consecutive over-threshold track.
  >
  > **That ESCALATE fired the same day.** Phase A's risk review found the track's
  > headline gate unevaluable — the `core` feature suite does not complete with
  > the translator on — which put an unsized engine-level diagnosis in front of a
  > shared-planner correctness fix in front of a four-recogniser feature. The
  > 2026-08-03 inline replan split it into Track 9 (suite completion, baseline,
  > dropped per-alias filter) and Track 11 (terminators, seam, decline gates, JMH
  > harness), which share no file.
- [x] Track 9: Cucumber suite completion + the dropped per-alias filter
  > Delivers the measured baseline the branch does not currently have: the `core`
  > TinkerPop feature suite does not complete with the translator on, while the
  > same command with the kill-switch off returns 1930 scenarios green. This track
  > localizes that and records each runner's failure set as a committed artifact.
  > It also fixes the dropped per-alias filter, which answers
  > `g.V(marko).out().has(name, vadas)` with 3 rows where native returns 1 and
  > translates rather than declining — detail in plan/track-9.md.
  >
  > **Track episode:** `core` reaches 1930 / 0 / 14 with the translator on,
  > matching its own off arm, from a suite that did not terminate at track start;
  > four silent-wrong-answer defects closed, three of them by declining the shape
  > — see `plan/track-9.md` `## Episodes` § Track completion. (10 steps, 0 failed)
  >
  > **Track file:** `plan/track-9.md`

- [ ] Track 11: List-shaping terminators + JMH harness
  > **Runs after Track 9** (inline replan, 2026-08-03 — the two halves of the
  > original final track). Completes the Phase 1 recognised set: the four
  > list-shaping terminators (`fold` / `unfold` / `reverse` / `tail`) as last-step
  > recognisers registering ordered ops into the Track 7 post-process carrier,
  > with mid-traversal use and both child paths declining under D3. Then the full
  > Cucumber suite with no regression against Track 9's post-fix baseline and a
  > Gremlin-on-vs-off JMH harness exercised in-track — detail in
  > plan/track-11.md, whose Decision Log carries the mechanism rationale (DR-T1
  > through DR-T3).
  > **Scope:** ~14–20 files — the `RecognitionContext` append seam plus its
  > `supportsListShaping()` decline channel (`WalkerContext`,
  > `SubTraversalPredicateAdapter`), four recognisers, four `ListShapingOp`
  > implementations, the `POST_UNION_RECOGNISERS` relaxation and the two child
  > gates (`UnionStepRecogniser`, `RecognitionContext.walkChild`), the
  > `ListShapingOp` javadoc correction and three other stale javadoc sites,
  > composition / boundary / decline / re-arm / clone tests, the per-step scenario
  > catalogue, and three new JMH classes in `jmh-ldbc` with their in-track execution test. "Mirrored" was dropped by A7 — the repository holds no Gremlin JMH benchmark to adapt, so the new class measures its own named shapes on a translator-on-vs-off axis.
  > The LDBC SF 1 baseline numbers are Hetzner-scoped and not a gate.
  > **Depends on:** Tracks 7, 8, and 9.
  >
  > **Correction from Track 9 Phase C (2026-08-04): the no-regression reference
  > this track reads is `core`-only.** Track 9's acceptance was measured at its
  > final tree on `core` alone — 1930 scenarios, 0 failures, 14 skipped, on both
  > the translator-on and translator-off arms — and the `embedded` re-measurement
  > was stopped by user decision because CI covers that runner's on arm. So the
  > two-runner baseline described above does not exist as a measured artifact, and
  > no baseline file was published; the figures live in Track 9's completion
  > episode and its `## Surprises & Discoveries`. Two consequences for this track:
  > compare against the `core` figure rather than looking for a four-arm artifact,
  > and treat `embedded`'s translator-off arm as unverified at Track 9's final
  > tree rather than as a green reference. Track 9's test-harness backlog also
  > moved — see plan/track-11.md item 10's 2026-08-04 amendment, which records the
  > declined-path pin as discharged and re-measures the consolidation's scale.


## Implementation state

Tracks 1–8 and Track 10 are executed and complete; Tracks 9 and 11 remain, in that order (the 2026-08-03 inline replan split the original final track — see the Track 9 entry's `plan/track-9.md` `## Decision Log` DR-S1). Track 1 delivered the shared `match/builder/` package, the behavior-preserving `GqlMatchStatement` refactor (via `GqlMatchPatternAssembler`), and the `IS DEFINED` / `IS NOT DEFINED` presence factories. Track 2 delivered the `GremlinToMatchStrategy`, `GremlinStepWalker` + `StepRecogniser` registry, and `YTDBMatchPlanStep`. Track 3 delivered edge traversal. Track 4 delivered the predicate surface. Track 5 delivered logical filters, the sub-walker, and `GremlinPlanCache` (D5). Track 6 delivered result shaping. Track 7 extracted `AbstractMatchPlanStep` and the ordered list-shaping post-process carrier. Track 8 delivered `MultiPlanMatchStep`, multi-plan `TranslationResult` + strategy splice, `UnionStepRecogniser` behind `UnionForkHost`, and union `cacheEligible=false` (D8 code path). Track 10 delivered the query-metrics regression remediation and, in place of the green `core` run its title originally promised, an enumerated baseline: 483 repaired failures against one caused, and 21 deferred with per-class dispositions. The green goal stopped being reachable when the 2026-08-02 rebase restored three TinkerPop compliance executions the branch had never run. Track 9 now owns suite completion plus the dropped per-alias filter — the `core` feature suite does not terminate with the translator on, and no Cucumber baseline exists for this branch. Track 11 owns the list-shaping terminators and the JMH harness, measured against the baseline Track 9 publishes.

| Track | Code | Notes |
|---|---|---|
| 1 | done | shared builders + GQL adoption + `IS DEFINED` / `IS NOT DEFINED` factories |
| 2 | done | strategy + walker / registry + boundary step + `g.V()` / `g.V(ids)` translation |
| 3 | done | edge traversal — direction handlers, folded edge chains, non-adjacent edge filtering |
| 4 | done | predicate surface — full `P` / `Text` / `TextP` algebra incl. D-TEXT-OPS, `has` / `hasLabel` / `hasId`, `has(key)` presence |
| 5 | done | logical filters (`and` / `or` / `not` / `where`) + `hasNot(key)` + sub-walker + `GremlinPlanCache` (D5) |
| 6 | done | result shaping — labels / dedup, projections, order / pagination, aggregations; shared `ByModulatorTranslator` + count short-circuit |
| 7 | done | `AbstractMatchPlanStep` + ordered list-shaping post-process carrier |
| 8 | done | `MultiPlanMatchStep` + multi-plan carrier/splice + `UnionStepRecogniser` / `UnionForkHost` |
| 10 | done | query-metrics regression remediation — enumerated `core` baseline; 483 failures repaired, 21 deferred to Track 9 with dispositions |
| 9 | not started | Cucumber suite completion (does not terminate with the translator on) + the dropped per-alias filter + the measured baseline |
| 11 | not started | list-shaping terminators (`fold`/`unfold`/`reverse`/`tail`) as ordered `ListShapingOp`s + union/combinator decline gates + JMH harness |

Decision conformance: D6 and D-IS-DEFINED are satisfied by Track 1; Track 2 decisions (decline, class-keyed dispatch, boundary lifecycle, idempotency, translator-first) by Track 2; D10 by Track 3; D5 by Track 5; D11 by Track 6. D8 (union via `MultiPlanMatchStep`) is implemented in code across Tracks 7–8 and complete. D-TEXT-OPS by Track 4. D3 is *all-or-nothing decline*, not the terminators — it is enforced by every recogniser, including Track 11's four, whose mid-traversal and child-path declines are the split's new D3 surface.

Track 1 deferral: `MatchWhereBuilder.endsWith` / `matchesRegex` are not built in this track. Their AST backing (`SQLEndsWithCondition`, `SQLMatchesCondition` find-mode) is introduced by Track 4's D-TEXT-OPS work; the baseline-backed `containsText` (`SQLContainsTextCondition`) and `startsWith` (half-open range) ship in Track 1. See plan/track-1.md § Decision Log.
