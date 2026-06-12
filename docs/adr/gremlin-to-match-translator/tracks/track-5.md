# Track 5: Logical filters — `and`, `or`, `not`, `where`

## Description

Implements TinkerPop's logical filter steps (which contain sub-traversals)
by descending into their global children. Three load-bearing prerequisites
land first: (a) the equivalence-test kitchen-sink split into per-recogniser
classes; (b) the non-polymorphic `@class = V` divergence audit + fix; (c) a
new `SubTraversalPredicateAdapter` abstraction that walks a sub-traversal
without mutating the outer `WalkerContext`, returning
`Optional<SQLBooleanExpression>` — the production `GremlinStepWalker` is
hard-wired for top-level traversals (`StartStepRecogniser` rejects
non-`YTDBGraphStep` start steps and only fires at outer `stepIndex == 0`)
and cannot be reused for sub-traversal recursion.

Behavior:

- **`AndStep` / `OrStep` (the residual `ConnectiveStrategy` form)** —
  `ConnectiveStrategy` is a `DecorationStrategy` that runs *before*
  `ProviderOptimizationStrategy` and **flattens** flat AND-of-has shapes
  (`g.V().and(__.has(a), __.has(b))` → `g.V().has(a).has(b)`); those land
  on the start step as folded `HasContainer`s and are already handled by
  Track 4's `StartStepRecogniser.collectFolded`. Track 5's AndStep
  recogniser therefore handles only the **residual** AndStep shapes —
  sub-traversals containing non-`HasStep` children (edge hops, nested
  logical filters, where-traversals). OrStep always survives intact (OR
  cannot fold into a sequential has-chain). Translation: each
  sub-traversal is walked via `SubTraversalPredicateAdapter`, returning
  `Optional<SQLBooleanExpression>`; the N subtrees are joined by
  `MatchWhereBuilder.and(...)` / `or(...)` and AND-merged into
  `ctx.aliasFilters[boundaryAlias]` via Track 4's
  `WHERE.andOptional(list.toArray(...))` flat-merge pattern. Validate-
  then-commit at sub-traversal granularity: validate every child sub-
  traversal recognises before any context mutation; on any child decline,
  the AndStep / OrStep recogniser declines (under D3 all-or-nothing the
  whole traversal then declines). Each recogniser step pins the actual
  arrival shape via empirical tests that build `g.V().and(...)` /
  `g.V().or(...)` traversals, run `applyStrategies()`, and assert the
  resulting `Step` types — protects against TinkerPop version drift and
  compensates for the unavailable PSI / gremlin-core jar inspection.
- **`NotStep`** with a sub-traversal that is a pure pattern match
  (one or more edge hops with optional filters) — translates to a new
  `SQLMatchExpression` added to `MatchPlanInputs.notMatchExpressions`
  (consistency review CR1; routed through the new ctor introduced in
  Track 2). The recogniser observes **two** planner constraints from
  `MatchExecutionPlanner.manageNotPatterns` — both load-bearing:
  1. `pattern.aliasToNode.get(exp.getOrigin().getAlias()) != null`
     (origin alias must already exist in the positive pattern). The
     recogniser threads `WalkerContext.boundaryAlias` as the NOT origin
     and pre-validates presence in `ctx.patternBuilder` declared
     aliases; on absence, declines cleanly under D3 (rather than
     letting the planner throw a `CommandExecutionException` that the
     strategy's outer try/catch then swallows).
  2. `exp.getOrigin().getFilter() == null` (origin cannot carry its
     own WHERE). The boundary alias's existing
     `aliasFilters[boundaryAlias]` entry stays on the positive pattern's
     map; the planner reads it independently when scanning the positive
     pattern. The recogniser MUST NOT copy the existing filter into
     `origin.setFilter(...)`.

  AST construction: introduce a new helper `MatchNotExpressionBuilder`
  in `match.builder/` (or extend `MatchPatternBuilder` with a
  detached-expression mode) — the existing `MatchPatternBuilder.addEdge`
  registers the expression into the **shared positive Pattern** via
  `pattern.addExpression(expr)` and cannot be reused for NOT-pattern
  AST without leaking aliases into `Pattern.aliasToNode`. The helper
  returns a standalone `SQLMatchExpression`.

  Alias minting: `MatchPlanInputs` requires non-null `pattern`, so
  `MatchExecutionPlanner.buildPatterns` short-circuits and
  `assignDefaultAliases` never runs on `notMatchExpressions`. The
  recogniser MUST mint an anon alias for every NOT-pattern intermediate
  vertex via `WalkerContext.anonAliasGenerator.next()`. Pin with a
  unit test asserting every alias in
  `MatchPlanInputs.notMatchExpressions` items is non-null.
- **`NotStep`** with a pure filter sub-traversal (no edge hops) —
  translates inline to `MatchWhereBuilder.not(...)` on the current
  node's `aliasFilters[boundaryAlias]`. Two sub-cases:
  - **`NotStep(PropertiesStep(key))`** — TinkerPop 3.8 desugars
    `hasNot(key)` to `NotStep(__.values(key))`, but `__.values(key)` is
    a `PropertiesStep` (a transformer that extracts property values),
    NOT a filter. The semantic is "no values extracted" → "property is
    absent / null". Translates to
    `MatchWhereBuilder.isNull(key)`-AND-merged into the current
    `aliasFilters[boundaryAlias]`. The `NOT(predicate)` form is wrong
    for this shape (there is no predicate to negate). The
    `MatchWhereBuilder.isNull` Javadoc already names this as the
    `hasNot(key)` target.
  - **`NotStep(<pure-filter sub-traversal>)`** — for the general pure-
    filter case, walk the sub-traversal via
    `SubTraversalPredicateAdapter`, then negate via
    `MatchWhereBuilder.not(...)`.
- **`WhereTraversalStep`** — same shape as `NotStep` but positive (the
  sub-traversal must yield ≥1 result for the row to pass).
  - **Pure-filter sub-traversal (no edge hops)**: translate inline as a
    where-clause AND-merged into the current node's
    `aliasFilters[boundaryAlias]` via `SubTraversalPredicateAdapter`.
    Safe — no row-multiset fan-out.
  - **Edge-hop sub-traversal**: **DECLINE under D3 all-or-nothing**.
    Extending the positive pattern via `MatchPatternBuilder.addEdge`
    produces a join (one row per match), which silently diverges from
    Gremlin's existence semantics (one row per upstream regardless of
    fan-out). The MATCH planner has no positive semi-join step
    (`FilterNotMatchPatternStep` is the anti-semi; `HashJoinMatchStep`
    is an inner-join). The fix is deferred until a positive semi-join
    planner step is introduced — same all-or-nothing carve-out pattern
    as Track 3's `both`/`bothE`. Add an explicit
    `V_where_out_Knows_declines` equivalence case pinning the carve-out.
- **`WherePredicateStep`** — fundamentally step-label-keyed
  (`where(P.eq("a"))` compares the current traverser to step-label
  "a"). Step-label resolution depends on `as(label)` propagation, which
  is Track 6's territory. **Carve out from Track 5: decline under D3
  with an explicit `V_where_predicate_step_label_declines` equivalence
  case.** The recogniser is added in Track 6 alongside `as(label)`.

Verification: tests for each combinator. Pure-filter `not(...)` and
`where(...)`; edge-hop pattern `not(out("knows"))`; AND/OR composition
with a mix of `__.has(...)` and `__.out(...)` children. Decline tests
for edge-hop `where(...)` and step-label-keyed `where(...)`.
NOT-with-pattern equivalence cases include both planner paths:
hash-anti-join (no `$matched.` references) and nested-loop fallback
(forced via shape eligibility — see `MatchExecutionPlanner.canUseHashJoin`).

**Plan corrections inherited from prior track's track-level review:**

- **Non-polymorphic `@class = V` semantic divergence (load-bearing
  for chain semantics).** `VertexStepRecogniser` and
  `StartStepRecogniser` write `@class = 'V'` to alias filters under
  non-polymorphic mode. In schemas where V is abstract and only
  subclasses (Person/Place/...) carry instances, the filter matches
  no rows so `g.V().out(label).hasLabel(X)` under
  `polymorphicQuery=false` empties the result set. Native Gremlin's
  `g.V()` always returns all vertices regardless of polymorphic flag —
  only `hasLabel(X)` semantics change. Fix:
  1. **Keep `aliasClasses[boundary] = "V"`** — the planner needs a
     class scan target via `createSelectStatement(targetClass, …)`
     (`MatchExecutionPlanner.java:4403-4417`); dropping the
     `aliasClasses` entry produces a malformed empty-FROM clause.
  2. **Drop the `aliasFilters[boundary] = @class='V'` write when
     `effectiveClass.equals(SchemaClass.VERTEX_CLASS_NAME)`** — the
     filter is vestigial when V is the polymorphic root and no
     non-V class was named upstream. Apply the guard at
     `StartStepRecogniser.java:152-153` (`!polymorphic &&
     !effectiveClass.equals(VERTEX_CLASS_NAME)`) and at
     `VertexStepRecogniser.java:142-146` (skip the write entirely
     when the chain target's class is V).
  3. **Audit + update existing tests** that assert the old
     `@class = 'V'` shape (recogniser unit tests, IR-level snapshot
     assertions) in the same commit. Inventory via grep before
     touching the recognisers.
  4. **Add chain-target polymorphic-false equivalence cases** on the
     seeded class hierarchy under both polymorphic modes:
     `V_out_Knows_polymorphicFalse_returnsAllSubclasses`,
     `V_has_name_Alice_out_Knows_polymorphicFalse`,
     `V_out_Knows_hasLabel_Person_polymorphicFalse_directInstancesOnly`
     (the deferred Track 4 suggestion).
- **`EdgeTraversalEquivalenceTest` per-recogniser split.** Current
  state: 30 case factories × 2 test methods = 70 case-runs (not the
  60 the inheritance text quoted — Track 4's review wrote it before
  cases consolidated). Split into per-recogniser equivalence classes
  (`StartStepEquivalenceTest`, `VertexStepEquivalenceTest`,
  `HasStepEquivalenceTest`, `HasLabelStepEquivalenceTest`,
  `HasIdStepEquivalenceTest`) extending a small `EquivalenceHarnessBase`
  that owns the seed fixture + `runCollecting` + `withStrategyEnabled`
  + `Expected` enum + `TraversalFactory` interface +
  `shape`/`shapePolymorphic` factories + parameter-name template.

  **Tiebreaker rule for cross-recogniser cases**: classify by the
  LAST non-`StartStep` recogniser-typed step under test. So
  `V_out_Knows_has_name_Bob` lives in `HasStepEquivalenceTest`,
  `V_out_Knows_hasLabel_Person` in `HasLabelStepEquivalenceTest`,
  `V_out_Knows_hasId_unknown` in `HasIdStepEquivalenceTest`, and pure
  `V_out_Knows` in `VertexStepEquivalenceTest`. The start-step
  recogniser is implicit and not counted unless the case exclusively
  tests start-step folding (`V_has_name_Alice`, `V_hasLabel_Person`).
  Verify post-split case count equals pre-split count (no silent
  drops).

  Land the split as the FIRST step of this track so subsequent
  logical-filter cases land in fresh, focused classes.

**Scope:** ~8 steps covering equivalence-test split, non-polymorphic
V-filter audit + fix, sub-traversal predicate adapter, AndStep + OrStep
recognisers, NotStep-with-filter (incl. `hasNot`/`IS NULL` desugar),
NotStep-with-pattern (new builder helper + alias minting), WhereTraversalStep
(restricted to pure-filter; edge-hop and step-label-keyed forms decline),
recogniser dispatch-order regression test + equivalence-class fleshing.
**Depends on:** Track 4.

## Progress

- [x] Review + decomposition
- [ ] Step implementation (4/8 complete)
- [ ] Track-level code review

## Base commit

`0f99bea3e0`

## Reviews completed

- [x] Technical: PASS at iteration 1 (10 findings: 2 blockers, 5 should-fix,
  3 suggestions; all blockers and should-fix absorbed into description
  amendments — NOT-pattern alias minting, `MatchNotExpressionBuilder`,
  WhereTraversalStep restriction, WherePredicateStep carve-out,
  filter-only sub-walker abstraction, V-filter guard wording, equivalence
  count correction; suggestions deferred or rolled into decomposition)
- [x] Risk: PASS at iteration 1 (9 findings: 4 should-fix, 5 suggestions;
  should-fix absorbed — `SubTraversalPredicateAdapter`, NOT origin-alias
  pre-validation, validate-then-commit at sub-traversal granularity,
  V-filter audit decomposition, equivalence-count correction; suggestions
  R6/R8/A9 noted but no track-shape change; R7/R9 rolled into Step 8)
- [x] Adversarial: PASS at iteration 1 (10 findings: 1 blocker, 6 should-fix,
  3 suggestions; blocker absorbed — sub-traversal walker dispatch via
  new adapter, no `YTDBStrategyUtil.isPolymorphic` re-resolution; should-
  fix absorbed — ConnectiveStrategy flattening note, `hasNot`-IS-NULL
  desugar, WherePredicateStep dependency on Track 6 carve-out, V-filter
  ambiguity resolved, NOT origin-filter null discipline, equivalence-
  split tiebreaker; suggestions A10 deferred — gate-verification
  iteration 2 skipped because all blocker/should-fix items are
  description amendments verifiable by the orchestrator against the
  finding text rather than via re-spawned reviewer)

## Steps

- [x] Step: Equivalence-test kitchen-sink split into per-recogniser classes
  - [x] Context: safe
  > **Risk:** medium — touches shared test infrastructure used by
  > Tracks 4–10 (per `risk-tagging.md` "Tests-only steps" rule —
  > medium because of the shared-fixture surface).
  >
  > **What was done:** Extracted `EquivalenceHarnessBase` as a shared
  > abstract parameterised base owning the seed fixture, kill-switch
  > flip, polymorphism-override plumbing, `Expected` enum,
  > `TraversalFactory` interface, `shape`/`shapePolymorphic`
  > factories, and the two shared assertions (result-multiset
  > equivalence + boundary-step engagement). Deleted the kitchen-sink
  > `EdgeTraversalEquivalenceTest` and routed its 30 case factories
  > into five focused subclasses by the tiebreaker rule:
  > `StartStepEquivalenceTest` (8 case-runs), `VertexStepEquivalenceTest`
  > (28), `HasStepEquivalenceTest` (16), `HasLabelStepEquivalenceTest`
  > (4), `HasIdStepEquivalenceTest` (4). All five classes keep
  > `@Category(SequentialTest.class)` and extend `GraphBaseTest`
  > (which extends `DbTestBase`) so the existing lifecycle is
  > preserved. Verified post-split count of 60 case-runs equals
  > pre-split count.
  >
  > **What was discovered:** The plan's "30 case factories × 2 test
  > methods = 70 case-runs" arithmetic is off — actual product is
  > 60 (28+16+8+4+4). Functionally irrelevant since the rule is
  > parity, but worth recording so subsequent tracks do not chase a
  > phantom 10-case discrepancy. Cases whose tail is not one of the
  > five recogniser-typed steps (e.g. `V_out_Knows_count`,
  > `V_out_Knows_hasNot_name_declines`) fall back via the tiebreaker
  > to the previous recogniser-typed step in the chain (a `VertexStep`
  > here), so they land in `VertexStepEquivalenceTest`. The harness
  > Javadoc spells this out explicitly so future cases (NotStep
  > family in later steps of this track) classify consistently.
  >
  > **Key files:**
  > - `EdgeTraversalEquivalenceTest.java` (deleted)
  > - `EquivalenceHarnessBase.java` (new)
  > - `StartStepEquivalenceTest.java`,
  >   `VertexStepEquivalenceTest.java`,
  >   `HasStepEquivalenceTest.java`,
  >   `HasLabelStepEquivalenceTest.java`,
  >   `HasIdStepEquivalenceTest.java` (new)
  >
  > **Critical context:** Subsequent track 5 steps add equivalence
  > cases into the appropriate per-recogniser bucket via the
  > tiebreaker rule. Cases whose tail is not a recogniser-typed
  > step fall back to the prior recogniser-typed step in the chain.
  > Commit: `c95afb35e8`.

- [x] Step: Non-polymorphic `@class = V` divergence audit + fix
  - [x] Context: safe
  > **Risk:** medium — multi-file logic in core; behaviour change
  > affects every chain-traversal under non-polymorphic mode (no HIGH
  > triggers — bounded blast radius, comprehensive equivalence pinning
  > available).
  >
  > **What was done:** Applied the V-filter divergence fix at both
  > sites. `StartStepRecogniser`: added
  > `!effectiveClass.equals(SchemaClass.VERTEX_CLASS_NAME)` to the
  > `classEq` guard so the `@class` write happens only when a
  > user-named class narrowed the start step. `VertexStepRecogniser`:
  > removed the `if (!ctx.polymorphic)` block that wrote
  > `@class = 'V'` on chain targets — the `aliasClasses` entry stays
  > at `"V"` via the `addNode` call so the planner still has a scan
  > target. Updated 7 test files: rewrote two
  > `VertexStepRecogniserTest` assertions to pin the absent
  > V-filter, rewrote two `GremlinToMatchTranslatorTest` assertions
  > for bare `g.V()` and multi-ID non-polymorphic shapes, and
  > updated stale comments in `HasStepRecogniserTest`,
  > `HasLabelStepRecogniserTest`, `GremlinStepWalkerTest`, and
  > `StartStepEquivalenceTest`. Updated matching production Javadoc
  > on `MatchClassFilters`, `HasStepRecogniser`,
  > `HasLabelStepRecogniser`, `StartStepRecogniser`,
  > `VertexStepRecogniser`. Added three equivalence cases per the
  > spec: two on `VertexStepEquivalenceTest`
  > (`V_out_Knows_polymorphicFalse_returnsAllSubclasses`,
  > `V_has_name_Alice_out_Knows_polymorphicFalse`) and one on
  > `HasLabelStepEquivalenceTest`
  > (`V_out_Knows_hasLabel_Person_polymorphicFalse_directInstancesOnly`).
  >
  > **What was discovered:** Pre-existing test failures on the base
  > commit are unrelated to this step:
  > `GraphStepStrategyTest.shouldFoldInHasContainers` asserts the
  > start step is `YTDBGraphStep`, but Track 4's start-step folding
  > now causes the translator strategy to claim
  > `g.V().has(name)` and emit a `YTDBMatchPlanStep` instead.
  > Four `SnapshotIsolationIndexes*` tests also fail on the
  > baseline. None are caused by this step's V-filter changes —
  > verified by stashing and re-running on the baseline.
  > **Cross-track impact (recorded):** The
  > `VertexStepRecogniser.ctx.polymorphic` branch is now dead code —
  > the recogniser no longer reads `ctx.polymorphic` for class-filter
  > decisions. `ctx.polymorphic` is still consumed by
  > `HasLabelStepRecogniser` and `StartStepRecogniser` and remains
  > the canonical channel for threading the polymorphism flag
  > through the walker.
  >
  > **What changed from the plan:** None.
  >
  > **Key files:**
  > - `StartStepRecogniser.java`, `VertexStepRecogniser.java`
  >   (production guards)
  > - `MatchClassFilters.java`, `HasStepRecogniser.java`,
  >   `HasLabelStepRecogniser.java` (Javadoc updates)
  > - `VertexStepRecogniserTest.java`,
  >   `GremlinToMatchTranslatorTest.java`,
  >   `HasStepRecogniserTest.java`,
  >   `HasLabelStepRecogniserTest.java`,
  >   `GremlinStepWalkerTest.java` (assertion / comment updates)
  > - `VertexStepEquivalenceTest.java`,
  >   `HasLabelStepEquivalenceTest.java`,
  >   `StartStepEquivalenceTest.java` (new equivalence cases)
  >
  > **Critical context:** `HasLabelStepRecogniser.mergeAliasFilter` /
  > `stripClassEqualsClauses` path is no longer the
  > production-arrival shape for chain targets (no
  > `VertexStepRecogniser`-supplied V predicate to strip). The strip
  > logic remains load-bearing for the
  > `hasLabel(X).hasLabel(Y)` override case and any future
  > recogniser that writes a stale `@class` predicate; existing unit
  > tests pre-seeding a synthetic V filter remain valid as
  > merge-contract pins. Future logical-filter recognisers writing
  > into `aliasFilters[boundary]` should expect either an absent
  > entry or a user-named class predicate, never a vestigial V
  > predicate. Commit: `ccf8f9bfbd`.

- [x] Step: `SubTraversalPredicateAdapter` — new abstraction
  - [x] Context: unavailable
  > **Risk:** high — architecture (introduces a new abstraction layer
  > the next four logical-filter recogniser steps build on; load-
  > bearing entry point analogous to Track 3's walker introduction).
  >
  > **What was done:** Introduced `SubTraversalPredicateAdapter` —
  > the package-private singleton helper that the next four logical-
  > filter recogniser steps will recurse through. The adapter walks a
  > sub-traversal's steps in a filter-only mode, reads `boundaryAlias`
  > and `polymorphic` from the outer `WalkerContext` without
  > mutating it, accumulates per-step `SQLBooleanExpression`s into a
  > local list, AND-merges via `MatchWhereBuilder.andOptional`, and
  > returns `Optional.empty()` on the first unrecognised step or empty
  > contribution (forcing D3 decline at the parent recogniser).
  > Recognises the leading `StartStep`, `HasStep` containers (delegated
  > to `GremlinPredicateAdapter` for properties; inline `T.label`
  > routed to a dedicated `translateLabelContainer`; `T.id` declined
  > because id constraints belong on `aliasRids`), and `YTDBHasLabelStep`
  > (single `Compare.eq` class and multi-class `Contains.within` under
  > non-polymorphic mode). The `AndStep` / `OrStep` / `NotStep` /
  > `WhereTraversalStep` dispatch hooks are present as stubs returning
  > `Optional.empty()` — the load-bearing seam future steps wire to
  > recurse through `toBooleanExpression`. Initial commit
  > `1c0f2b3dd8`; review fix `3463a0c876` follows.
  >
  > Iteration-1 dimensional review (5 agents: code-quality, bugs-
  > concurrency, test-behavior, test-completeness, test-structure)
  > flagged seven should-fix findings — all absorbed in the review-
  > fix commit:
  > 1. **CQ-1**: replaced the FQN reference to
  >    `SchemaClass.VERTEX_CLASS_NAME` with a normal import — matches
  >    sibling recognisers.
  > 2. **BC-1 / BC-2**: replaced `Optional.ofNullable` with
  >    `Optional.of` at the two andOptional call sites whose inputs
  >    are provably non-null, so a future invariant regression fails
  >    fast rather than silently returning a decline.
  > 3. **TC-1 / TC-3**: extended `translateLabelContainer` to accept
  >    `Contains.within` so inline `has(T.label, P.within(...))`
  >    sub-traversals — the multi-arg `__.hasLabel("Person", "Place")`
  >    shape that never sees the YTDB strategy on anonymous traversals
  >    — translate to `@class IN [...]` symmetric with the
  >    YTDBHasLabelStep branch.
  > 4. **TB-1 / TS-3**: expanded `recognise_doesNotMutateOuterContext`
  >    to snapshot every reachable mutable field on `WalkerContext`
  >    (aliasFilters, aliasRids, the three return-projection lists,
  >    outputType, returnClass, stepIndex) and compare maps/lists as
  >    whole — sizes alone would miss a replace-not-add regression.
  > 5. **TB-2**: added
  >    `decline_doesNotCommitPartialStateToOuterContext` exercising
  >    decline across vertex hop, polymorphic-mode hasLabel, hasId,
  >    and a dispatch-stub decline, asserting `aliasFilters` /
  >    `aliasRids` are untouched. Pins the parent recogniser's
  >    validate-then-commit contract.
  > 6. **TC-2**: pinned the V-divergence guard under non-polymorphic
  >    mode for inline `has(T.label, "V")`, explicit `hasLabel("V")`,
  >    and the mixed-list `hasLabel("Person", "V")` where any V in
  >    the collection forces a decline rather than emitting partial
  >    `@class IN ["Person", "V"]`.
  > 7. **TC-4**: pinned the current decline contract on the four
  >    logical-filter dispatch stubs (`__.and`, `__.or`, `__.not`,
  >    `__.where`) so an accidental partial wiring in a subsequent
  >    step surfaces here rather than via the equivalence harness.
  >
  > Also folded three suggestion-level items cheaply: TC-5 (null
  > guards on `sub` / `outerCtx` / `boundaryAlias`), TS-4 (Javadoc
  > on the empty-sub-traversal test accurately names the
  > IdentityStep), TS-5 (an `instanceof` guard before the
  > `SQLBinaryCondition` cast in the single-class hasLabel test).
  >
  > **Remaining deferred suggestions:** CQ-3 (three-valued
  > `null`-vs-`Optional.empty()` sentinel could be replaced with a
  > sealed result type), CQ-4 (lift the shared `render(...)` helper
  > into a test util shared with peer recogniser tests), CQ-5
  > (clearer inline note next to the four `@SuppressWarnings("unused")`),
  > BC-3 (pin that explicit `__.has(T.id, ...)` declines via the
  > token-keyed branch, not via accidental fallback), TB-3 (drop the
  > redundant size assertion in the multi-has test), TB-4 (assert the
  > hasId decline actually hits the T.id-key branch via a HasContainer
  > inspection), TC-6 (pin the unrecognised-`BiPredicate` decline
  > directly), TC-7 (variable-StartStep / mid-chain StartStep decline
  > pins), TC-8 (empty-HasContainers decline), TS-1 (`render` peer
  > consistency — covered by CQ-4), TS-2 (`outerCtx` helper peer
  > alignment).
  >
  > **What was discovered:** `__.hasLabel("Person")` produces a
  > `HasStep` with a `HasContainer(T.label, P.eq("Person"))` rather
  > than a `YTDBHasLabelStep` — the YTDB strategy that converts
  > T.label HasContainers into YTDBHasLabelStep only fires on top-
  > level traversals via `applyStrategies()`, not on anonymous
  > sub-traversals. The adapter therefore handles label narrowing
  > through `translateHasStep` → `translateLabelContainer`, and the
  > YTDBHasLabelStep dispatch is reserved for any pre-converted
  > sub-traversals that the parent recogniser hands in already
  > strategy-processed. Both label paths now share the same
  > Compare.eq + Contains.within + V-guard set.
  >
  > **What changed from the plan:** None at the track level — the
  > step description scoped only the adapter's introduction, and that
  > is delivered. The review-fix iteration added test coverage and
  > the `Contains.within` extension to `translateLabelContainer` (a
  > planned dispatch path the initial commit had not wired for the
  > HasStep path).
  >
  > **Cross-track impact:** none beyond the planned dependency —
  > Steps 4–7 of this track wire the dispatch stubs and will need to
  > update the `decline_nestedLogicalFilterStubs_currentlyAllDecline`
  > pin as each stub flips from decline to recognise.
  >
  > **Key files:**
  > - `SubTraversalPredicateAdapter.java` (new)
  > - `SubTraversalPredicateAdapterTest.java` (new — 18 tests after
  >   review fix; covered single-has, multi-has, hasLabel single /
  >   within / V-guard, inline T.label single / V-guard / polymorphic
  >   decline, vertex-hop / values / hasId / empty declines,
  >   dispatch-stub decline pin, null-input pins,
  >   recognise/decline no-mutation pins).
  >
  > **Critical context:** Subsequent steps wire the four
  > `translateXxx` dispatch stubs; the `decline_nestedLogicalFilterStubs`
  > pin will need to evolve into recognise tests as each stub fires.
  > Commits: `1c0f2b3dd8` (adapter), `3463a0c876` (review fix).

- [x] Step: AndStep + OrStep recognisers
  - [x] Context: unavailable
  > **Risk:** medium — multi-file logic in core; depends on the new
  > sub-traversal adapter (Step 3) but introduces no further
  > architectural surface.
  >
  > **What was done:** Added `AndStepRecogniser` and `OrStepRecogniser`
  > (package-private singletons) and registered both in
  > `GremlinStepWalker.PRODUCTION_RECOGNISERS` between `HasStepRecogniser`
  > and `NoOpBarrierRecogniser`. Each delegates to
  > `ConnectiveFilterFold.foldChildren(step.getLocalChildren(), ctx, op)`
  > and then to `ConnectiveFilterFold.mergeIntoBoundary(ctx, folded)` —
  > the shared helper introduced as part of the iteration-2 review fix
  > so the validate-then-commit walk and the AND-merge into
  > `ctx.aliasFilters[ctx.boundaryAlias]` live in one place. Wired the
  > two corresponding dispatch stubs on `SubTraversalPredicateAdapter`
  > (`translateAndStep` / `translateOrStep`) to delegate to the same
  > helper, so nested AND/OR inside a sub-traversal recurses through the
  > adapter and folds via the same code path. The `NotStep` and
  > `WhereTraversalStep` dispatch slots remain decline stubs reserved
  > for Steps 5–7.
  >
  > Initial commit `b9437e4175`; review-fix commit `2816cde3b6` extracted
  > `ConnectiveFilterFold` (CQ-1 DRY between the two recognisers and the
  > adapter), tightened tests with typed sub-block assertions
  > (TB-1/TB-2), broadened decline-state snapshots beyond `aliasFilters`
  > to also cover `aliasRids` / `returnItems` (TB-3), pinned the
  > `ConnectiveStrategy + InlineFilterStrategy` flattening of flat
  > AND-of-has (TB-4 — confirmed both strategies are needed; ConnectiveStrategy
  > alone leaves the AndStep intact), added merge-with-existing-class-filter
  > tests for both recognisers (TC-1), and added the mirror "OR survives
  > ConnectiveStrategy" empirical pin (TC-2).
  >
  > **Iteration-1 dimensional review (4 baseline agents: code-quality,
  > bugs-concurrency, test-behavior, test-completeness)** flagged 6
  > should-fix and several suggestions; all should-fix items absorbed
  > into the review-fix commit. Bugs-concurrency review returned no
  > findings — the validate-then-commit contract, the singleton/static
  > builder reuse, the fan-out shape, and the recursion depth bound were
  > all confirmed safe.
  >
  > **What was discovered:**
  >
  > 1. `ConnectiveStrategy` alone does NOT fully flatten flat AND-of-has —
  >    `InlineFilterStrategy` is the strategy that actually folds the
  >    children onto the start step. The empirical pin therefore applies
  >    both strategies and checks the post-state. ConnectiveStrategy
  >    only normalises the AND-tree shape; the inline happens later.
  > 2. `__.hasLabel("Person", "Place")` desugars to
  >    `has(T.label, P.within(...))` (HasStep with HasContainer), not
  >    YTDBHasLabelStep — anonymous sub-traversals do not run the
  >    YTDB strategy that converts T.label HasContainers. The adapter's
  >    `translateLabelContainer` (Step 3) had a Compare.eq-only gap that
  >    Step 4 closed by extending it to handle Contains.within
  >    symmetric with `translateHasLabelStep`.
  > 3. `ConnectiveFilterFold` is now the canonical entry point for any
  >    future recogniser that folds a list of sub-traversals through the
  >    adapter and AND-merges into the boundary alias filter — Steps 5–7
  >    should reuse `foldChildren` / `mergeIntoBoundary` where their
  >    semantics align (NotStep with filter sub-traversal in particular).
  >
  > **What changed from the plan:** None at the track level. The plan
  > called for `WHERE.andOptional(prior, foldedExpression)` inline in
  > each recogniser; the refactor moves that into `ConnectiveFilterFold.
  > mergeIntoBoundary` so the three call sites (And, Or, adapter dispatch)
  > stay in lockstep — pure structural improvement.
  >
  > **Cross-track impact:** none beyond the planned dependency. The
  > `decline_nestedLogicalFilterStubs_currentlyAllDecline` pin on
  > `SubTraversalPredicateAdapterTest` flipped to
  > `recognise_nestedAndOrSubTraversals_dispatchThroughAdapter` — Steps
  > 5–7 wiring the remaining two stubs (Not, Where) will update this
  > test analogously.
  >
  > **Key files:**
  > - `AndStepRecogniser.java` (new)
  > - `OrStepRecogniser.java` (new)
  > - `ConnectiveFilterFold.java` (new — shared helper)
  > - `SubTraversalPredicateAdapter.java` (modified — translateAndStep /
  >   translateOrStep dispatch hooks wired to ConnectiveFilterFold;
  >   translateLabelContainer extended for Contains.within)
  > - `GremlinStepWalker.java` (modified — registry adds And/Or
  >   recognisers between HasStepRecogniser and NoOpBarrierRecogniser)
  > - `AndStepRecogniserTest.java` (new — 7 tests covering AND-of-(OR,
  >   has), single-child lift, edge-hop decline with broadened state
  >   snapshot, non-AndStep decline, null-boundary decline, merge with
  >   existing class filter, ConnectiveStrategy+InlineFilterStrategy
  >   flattening empirical pin)
  > - `OrStepRecogniserTest.java` (new — 7 tests covering OR-of-two-has
  >   with typed leaf assertions, single-child lift, edge-hop decline
  >   with broadened state snapshot, non-OrStep decline, null-boundary
  >   decline, merge with existing class filter, ConnectiveStrategy
  >   preserving flat OR empirical pin)
  > - `SubTraversalPredicateAdapterTest.java` (modified — dispatch-stub
  >   pin updated to assert And/Or now recognise; decline-state pin
  >   uses an edge-hop-in-OR shape that still declines)
  >
  > **Remaining deferred suggestions:** TC-3 (deeper recursive nesting
  > pins ≥ depth 2 inside foldConnectiveChildren), TB-5 (null-boundary
  > tests also assert no aliasFilters write — currently only the boolean
  > return is pinned). Equivalence cases for AND/OR end-to-end will
  > land in Step 8's equivalence-class fleshing pass (Step 8 explicitly
  > scopes "flesh out per-recogniser equivalence classes with the cases
  > shipped in Steps 4–7").
  >
  > **Critical context:** Steps 5–7 should reuse
  > `ConnectiveFilterFold.foldChildren` where applicable (NotStep with
  > pure-filter sub-traversal is the most direct beneficiary — single
  > sub-traversal, negate the result via `MatchWhereBuilder.not`).
  > Commits: `b9437e4175` (initial), `2816cde3b6` (review fix).

- [ ] Step: NotStep with pure-filter sub-traversal (incl. `hasNot` desugar)
  > **Risk:** medium — multi-file logic in core; reuses the sub-
  > traversal adapter (no HIGH triggers).
  >
  > Add `NotFilterStepRecogniser` (package-private singleton)
  > registered in `PRODUCTION_RECOGNISERS` after
  > `OrStepRecogniser`. The recogniser handles two sub-cases via a
  > single `instanceof NotStep` gate:
  > 1. **`NotStep(PropertiesStep(key))` (TinkerPop's `hasNot(key)`
  >    desugar)** — when the inner traversal is a single-step
  >    `PropertiesStep` carrying exactly one property key, translate
  >    to `MatchWhereBuilder.isNull(key)` and AND-merge into
  >    `ctx.aliasFilters[ctx.boundaryAlias]`. The semantic is
  >    "property absent / null", not "negate predicate" — `__.values(key)`
  >    is a transformer, not a filter.
  > 2. **`NotStep(<pure-filter sub-traversal>)`** — for the general
  >    case, walk the sub-traversal via
  >    `SubTraversalPredicateAdapter`. If the adapter returns
  >    `Optional.empty()` (any unrecognised step in the sub-
  >    traversal — e.g. an edge hop, which signals the
  >    NOT-with-pattern path that Step 6 handles), decline. On
  >    success, negate via `MatchWhereBuilder.not(folded)` and AND-
  >    merge into `ctx.aliasFilters[ctx.boundaryAlias]`.
  >
  > Equivalence cases: `V_out_Knows_hasNot_name` (the `hasNot` desugar
  > path that Track 4 explicitly DECLINED — it now flips to
  > RECOGNIZED), `V_not_has_name_Alice` (single-filter NOT),
  > `V_not_and_has_name_Alice_has_age_gt_30` (compound filter NOT).

- [ ] Step: NotStep with pure-pattern sub-traversal — `MatchNotExpressionBuilder` + alias minting
  > **Risk:** high — architecture (new builder helper for detached
  > `SQLMatchExpression` AST, planner contract dependencies, alias-
  > minting discipline; multiple HIGH triggers fire — load-bearing
  > NOT-pattern construction with two planner constraints).
  >
  > Add `MatchNotExpressionBuilder` in `match.builder/`. The helper
  > returns a standalone `SQLMatchExpression` that does NOT register
  > into the shared positive `Pattern` (`MatchPatternBuilder.addEdge`
  > unconditionally registers via `pattern.addExpression(expr)` and
  > cannot be reused). API: `originAlias(String)`, `addEdge(toAlias,
  > Direction, edgeLabel)`, `build()`. The builder mints anon aliases
  > via a `Supplier<String>` injected at construction time (the
  > recogniser passes `ctx.anonAliasGenerator::next`); each
  > intermediate vertex MUST carry a non-null alias because
  > `MatchExecutionPlanner.buildPatterns` short-circuits when
  > `MatchPlanInputs` pre-supplies `pattern`, so `assignDefaultAliases`
  > never runs on `notMatchExpressions`.
  >
  > Add `NotPatternStepRecogniser` (package-private singleton)
  > registered after `NotFilterStepRecogniser`. Recognises
  > `NotStep(<pattern sub-traversal>)` where the sub-traversal
  > contains one or more edge hops (with optional inline filters).
  > Discipline:
  > 1. **Origin alias = parent's `boundaryAlias`** (NEVER mint a new
  >    alias for the NOT origin — must match the positive pattern).
  > 2. **Pre-validate origin alias presence** in
  >    `ctx.patternBuilder` (the boundary alias is registered by
  >    `StartStepRecogniser`'s `addNode` call — a missing entry
  >    indicates a recogniser bug). On absence, decline cleanly under
  >    D3 rather than letting the planner throw at `:738-742`.
  > 3. **Origin filter MUST remain null** (planner constraint at
  >    `:744-748` — "WHERE condition on the initial alias is not
  >    supported"). The boundary alias's existing
  >    `aliasFilters[boundary]` entry stays on the positive pattern's
  >    map; the recogniser MUST NOT copy it onto the NOT origin
  >    filter slot.
  > 4. **Mint anon aliases for every intermediate vertex** in the
  >    NOT pattern via `WalkerContext.anonAliasGenerator.next()`. Pin
  >    with a unit test asserting every alias in the produced
  >    `MatchPlanInputs.notMatchExpressions` items is non-null.
  > 5. **Validate-then-commit** — build the entire `SQLMatchExpression`
  >    via the new builder before pushing to
  >    `ctx.notMatchExpressions`; on any sub-traversal step the
  >    recogniser cannot translate, decline without mutation.
  >
  > Equivalence cases covering both planner execution paths
  > (`MatchExecutionPlanner.canUseHashJoin`):
  > - **Hash-anti-join eligible**: `V_not_out_Knows` (NOT pattern
  >   with no `$matched.` references, shared aliases via origin =
  >   boundary).
  > - **Nested-loop fallback**: `V_not_out_Knows_has_name_Bob` (NOT
  >   pattern with intermediate filter — verify the planner's hash-
  >   join eligibility check correctly routes to nested loop).
  > - **Decline cases**: `V_not_match_declines` (NOT containing an
  >   unrecognised step — `MatchStep`).
  > - **Filter-on-origin-stays-on-positive-pattern pin**:
  >   `V_has_name_Alice_not_out_Knows` (the `has("name", "Alice")`
  >   filter must end up on the positive `aliasFilters[$g2m_v0]`,
  >   NOT on the NOT origin filter; assert via plan-shape inspection
  >   in addition to result-multiset).

- [ ] Step: WhereTraversalStep (pure-filter only) + WherePredicateStep carve-out
  > **Risk:** medium — multi-file logic in core; decline-heavy step
  > with bounded behaviour-change surface.
  >
  > Add `WhereTraversalStepRecogniser` (package-private singleton)
  > registered after `NotPatternStepRecogniser`. Recognises
  > `WhereTraversalStep` where the contained sub-traversal is
  > pure-filter (no edge hops, no projections) — translate inline via
  > `SubTraversalPredicateAdapter` and AND-merge into
  > `ctx.aliasFilters[ctx.boundaryAlias]` (no negation, no
  > `MatchWhereBuilder.not`). Edge-hop forms decline: the existing
  > MATCH planner has no positive-semi-join step
  > (`FilterNotMatchPatternStep` is the anti-semi;
  > `HashJoinMatchStep` is an inner-join), and translating an edge-
  > hop `where(...)` as an additional `SQLMatchExpression` linked to
  > existing aliases produces a join — silently diverging from
  > Gremlin's existence semantics (one row per upstream vs. one row
  > per match). Same all-or-nothing carve-out pattern as Track 3's
  > `both`/`bothE`. Add explicit equivalence decline:
  > `V_where_out_Knows_declines` and a polymorphism variant.
  >
  > **WherePredicateStep carve-out**: also recognise
  > `WherePredicateStep` solely to issue a clean D3 decline. The step
  > is fundamentally step-label-keyed (`where(P.eq("a"))` compares the
  > current traverser to step-label "a"); step-label resolution
  > depends on Track 6's `as(label)` propagation. Add
  > `V_where_predicate_step_label_declines` equivalence pin. Document
  > in Javadoc that the recogniser will be retired / extended in
  > Track 6 alongside `as(label)` propagation. Do NOT add a
  > `MatchWhereBuilder.matchedAliasRid` builder primitive in this
  > track — defer to Track 6 when `$matched.<alias>` actually
  > resolves.

- [ ] Step: Recogniser dispatch-order regression test + equivalence-class fleshing
  > **Risk:** medium — touches shared test infrastructure used by
  > Tracks 4–10 (per `risk-tagging.md` "Tests-only steps" rule —
  > medium because of the shared-fixture surface).
  >
  > Add `RecogniserDispatchOrderTest` — one method per (existing
  > recogniser × Track-5 step type) pair, asserting `recognise()
  > returns false` for the wrong (recogniser, step) combination. With
  > 8 production recognisers (5 existing + And + Or + NotFilter +
  > NotPattern + WhereTraversal — actually 9) and 4 new step types,
  > that's a small matrix of ~30 trivial cases. Cheap insurance
  > against TinkerPop class-hierarchy drift (e.g. a future `AndStep
  > extends HasStep`-style change would otherwise silently shadow
  > the new recognisers via the existing `HasStepRecogniser`).
  >
  > Flesh out per-recogniser equivalence classes with the cases
  > shipped in Steps 4–7. Audit case-count parity vs the pre-Track-5
  > total (every case ships in exactly one class; no silent drops or
  > duplicates). Update test class Javadoc with the tiebreaker rule
  > so future tracks (6–10) classify their cases consistently.
