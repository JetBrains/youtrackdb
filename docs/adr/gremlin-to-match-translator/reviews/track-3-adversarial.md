# Track 3 Adversarial Review — Edge traversal (out / in / both / outE.inV / inE.outV / bothE.otherV)

Phase A iteration 1. Reviewer: adversarial-review sub-agent (devil's advocate).

Tooling note: mcp-steroid was not invoked in this session — fall back to grep / Read.
Findings whose verdict hinges on counting call sites or polymorphic dispatch are
flagged with an explicit reference-accuracy caveat. Where a challenge overlaps
with the parallel risk review (`track-3-risk.md`), I cite the risk finding ID and
focus on the **adversarial angle** — the *decision being challenged* — rather than
duplicating the evidence base.

---

## Part 1: Challenge Certificates

### DECISION CHALLENGES

#### Challenge: Decision D3 — All-or-nothing translation (revised after Track 2)
- **Chosen approach**: Translate the entire traversal or decline it entirely;
  do not attempt to translate a profitable prefix and let suffix steps run
  natively.
- **Best rejected alternative**: D3 alternative (d) — "hybrid prefix kept but
  gated to a large recognized set via a config knob". I.e. keep the original
  prefix mechanism, but only fire the prefix cut once the recognized set is
  large enough to make mid-traversal cuts profitable.
- **Counterargument trace**:
  1. The plan's documented rationale for retiring the hybrid prefix
     (D3 "What changed" paragraph) is: "Phase 1 minimal scope is `g.V()` /
     `g.V(ids)` only; the hybrid mechanism only pays off if the recognized set
     is large enough that mid-traversal cuts produce useful plans."
  2. Track 3 expands the recognized set significantly — it now adds 6 step
     classes (`VertexStep` OUT/IN/BOTH × vertex/edge return, `EdgeVertexStep`,
     `EdgeOtherVertexStep`). After Track 3 lands, the recognized set is no
     longer "minimal".
  3. Tracks 4–10 each add more steps. By Track 10 the recognized set covers
     filtering, projection, ordering, aggregation, union — almost the entire
     LDBC-relevant Gremlin surface.
  4. The strategy's outer hasContainers-empty gate (`GremlinToMatchStrategy.java:160-162`)
     declines any traversal whose start step has folded predicates. Real LDBC
     queries virtually always have `g.V().has(...)` or `g.V().hasLabel(...)`
     somewhere, which `YTDBGraphStepStrategy` folds into the start step. Until
     Track 4 lands and lifts that gate by handling start-step hasContainers,
     **Track 3 in isolation provides almost zero real-world translation**:
     `g.V().out("knows")` works, but `g.V().has("name", "x").out("knows")`
     declines because the start step now carries hasContainers.
  5. Conversely, prefix translation (the rejected alternative) could deliver
     value in Track 3 alone: `g.V().has("name", "x").out("knows")` would
     translate the prefix `g.V().has(...)` to MATCH and let the native
     pipeline run `out("knows")` — a measurable LDBC win that Track 3
     all-or-nothing forfeits until Track 4.
  6. The plan's risk caveat already concedes this: "until [the recognized set]
     covers the LDBC-relevant shapes, most production traversals will decline."
- **Codebase evidence**:
  - `GremlinToMatchStrategy.java:160-162` declines on non-empty hasContainers.
  - `GremlinToMatchTranslator.java:145-147` re-asserts the same check.
  - `YTDBGraphStepStrategy.java:119-126` folds `HasStep` containers into the
    start step. Most real traversals therefore have non-empty hasContainers
    by the time `GremlinToMatchStrategy` sees them.
- **Survival test**: WEAK — the rationale survives because Phase 1's primary
  goal is correctness of the translator path, not maximum coverage of real
  workloads. But the *justification* that "all-or-nothing simplifies the
  walker" is not accurate any longer — the walker is already complex enough
  to track per-step recognition (Findings R6, R10 in risk review). Once the
  walker exists, the cost of *also* tracking "longest prefix" is incremental.
  The plan should explicitly acknowledge that under all-or-nothing, **Track 3
  alone does not deliver measurable LDBC value**; that arrival point shifts
  to Track 4. Rephrasing this as a deliberate sequencing trade-off (rather
  than implying Track 3 unlocks user-visible value) prevents misaligned
  expectations.

#### Challenge: Decision D4 — Strategy ordering after `YTDBGraphStepStrategy`
- **Chosen approach**: Run `GremlinToMatchStrategy` AFTER `YTDBGraphStepStrategy`
  so the start step's hasContainers are folded.
- **Best rejected alternative**: Run BEFORE `YTDBGraphStepStrategy`, see the
  raw `HasStep` chain, translate it directly to MATCH `where`, and never
  exercise the folded shape.
- **Counterargument trace**:
  1. Today's order: `YTDBGraphStepStrategy` runs first → folds `HasStep`
     into start step → translator sees folded `YTDBGraphStep` with
     non-empty `hasContainers` → declines (Track 4 will lift this).
  2. Inverted order: translator runs first → sees `g.V()` followed by raw
     `HasStep` → translates `HasStep` to MATCH `where` directly. The Track 4
     scope shifts from "decode folded hasContainers" to "translate raw
     `HasStep`" — arguably simpler.
  3. The plan rejects "before `YTDBGraphStepStrategy`" with the argument
     "we don't see `YTDBGraphStep` with absorbed has-containers and the
     step walker misses already-folded label predicates." But Track 3
     **does not need** the folded form — the walker can read `HasStep`
     directly.
- **Codebase evidence**:
  - `YTDBGraphStepStrategy.java:119-126` shows the absorption logic; running
    before would skip the absorption.
  - The translator's existing decline on `!getHasContainers().isEmpty()`
    explicitly avoids decoding the folded form. Inverting the order would
    let Track 3 decline on raw `HasStep` only when Track 4 hasn't landed
    yet — same behavior, different code path.
- **Survival test**: WEAK — the chosen ordering does survive (label folder
  still applies for declined traversals, count-strategy short-circuit fires
  before us). But the rationale **does not actually require** the absorption
  — Track 3 in isolation cannot use the absorbed has-containers (it declines
  on them) and Track 4 has to *decode* the absorption rather than translate
  raw `HasStep`. The chosen ordering is correct because of count-strategy
  ordering (rejected alternative would put us before the count short-circuit
  too unless we add another applyPrior), not because of absorption. The
  plan's stated rationale is misleading.

### SCOPE CHALLENGES

#### Challenge: Track 3 scope — "anonymous intermediate vertex aliases" delivered alongside edge translation
- **Chosen scope**: 5 steps covering simple direction handlers, outE+inV
  pairing, multi-label edge, anonymous alias generation, correctness tests.
- **Best rejected scope**: Split anonymous alias generation into its own
  Track 3.5 *before* edge translation, OR roll it into Track 6 (where
  `as(label)` is already handled).
- **Counterargument trace**:
  1. Anonymous alias generation is a **walker-utility** concern, not an
     edge-traversal concern. It lives in the walker's namespace and is
     invoked by every multi-hop edge handler.
  2. Track 6 introduces `as(label)` propagation. Anonymous aliases are the
     *fallback* when no `as(label)` is provided. The two are intertwined:
     the walker must check "is this step labeled? if not, use anonymous".
  3. Putting anonymous-alias generation in Track 3 means Track 3 hardcodes
     the *fallback* path while Track 6 implements the *primary* path. When
     Track 6 lands, it will likely refactor the walker to unify the two
     paths.
  4. Cheaper: do anonymous-alias-only first as a tiny pre-step (extends the
     translator with an `AnonymousAliasGenerator` utility), then Track 3
     consumes it without duplication, then Track 6 builds on the same
     utility.
- **Codebase evidence**:
  - `GremlinToMatchTranslator.java:93` — Track 2 already has a single
    boundary alias `$g2m_v0`. The "next" alias is the natural seed for
    `$g2m_anon_0`, suggesting a counter-driven generator.
  - Track 6's description (plan line 812-818) already references the
    anonymous-alias prefix, so the abstraction is implied by Track 6.
- **Survival test**: WEAK — the chosen scope survives, but the work is
  duplicated. Splitting anonymous-alias generation into a tiny shared
  utility step (1 step, 30-50 LOC) before Track 3 lets Track 3 focus on
  edge translation and lets Track 6 reuse the utility for `as(label)`
  handling without refactoring.

#### Challenge: Track 3 scope — boundary step extension for Edge output
- **Chosen scope**: Edge translation, with no mention of `BoundaryOutputType`
  extension or `YTDBMatchPlanStep.projectElement` updates.
- **Best rejected scope**: Either include `BoundaryOutputType.EDGE_ELEMENT`
  scaffolding in Track 3 OR explicitly defer `outE()`/`inE()` (edge-returning
  forms) to a later track that owns boundary-step extension.
- **Counterargument trace**:
  1. `g.V().outE("knows")` is a *terminal* edge-returning shape; the user
     iterates the result and gets edges, not vertices.
  2. The current `BoundaryOutputType` enum has only `ELEMENT` (vertex-only):
     `BoundaryOutputType.java:34` and `YTDBMatchPlanStep.projectElement`
     (`YTDBMatchPlanStep.java:278`) calls `row.getVertex(boundaryAlias)` —
     hardcoded to vertex.
  3. Track 3's Cucumber test scenarios at `Vertex.feature:155, 169, 181, 195,
     208, 242` exercise `g.V(id).outE()`, `g.V(id).inE()`, `g.V(id).bothE()`,
     `g.V(id).bothE("created")`, etc. These are TERMINAL edge-returning
     traversals that need an `EDGE_ELEMENT` boundary output type.
  4. If Track 3 produces `outE()` translations without extending the boundary
     step, every result row will have `null` under the boundary alias
     (because `getVertex(...)` on an edge-bound row returns null) and the
     translation produces wrong results.
- **Codebase evidence**:
  - `YTDBMatchPlanStep.java:262-265` — `project()` switch is exhaustive on
    `outputType`, only handles `ELEMENT`.
  - `YTDBMatchPlanStep.java:278-284` — `projectElement` casts to `Vertex`.
- **Survival test**: NO — the scope is incomplete. Either Track 3 adds
  `BoundaryOutputType.EDGE_ELEMENT` and an `Edge`-returning projection
  branch, OR the walker must decline `VertexStep` whose `returnsEdge() ==
  true` is the terminal step. Otherwise `g.V(id).outE("knows")` translates
  but produces null result rows.

### INVARIANT CHALLENGES

#### Violation scenario: Strategy declines whole traversal cleanly when any unrecognized step appears mid-chain (D3 invariant)
- **Invariant claim**: For any traversal that contains at least one
  unrecognized step, the strategy declines the whole traversal: no IR is
  constructed, no boundary step is inserted, and the step list is preserved
  verbatim.
- **Violation construction**:
  1. Start state: `g.V().out("knows").has("name", P.eq("marko"))` — a
     traversal with start step (recognized), `VertexStep` OUT (recognized
     after Track 3), `HasStep` (UNRECOGNIZED in Track 3 — Track 4's domain).
  2. Action sequence:
     - Walker starts from `getStartStep()` (recognized).
     - Walker advances; sees `VertexStep` OUT (recognized) and calls
       `MatchPatternBuilder.addEdge` to register the hop.
     - Walker advances; sees `HasStep` — UNRECOGNIZED.
     - Walker abandons translation, returns `Optional.empty()`.
     - Strategy's `apply()` returns without mutating the traversal.
  3. Intermediate state: the **builder** has been mutated mid-walk
     (`addEdge` called, but `build()` not called — see risk Finding R10).
     The builder is local to `translatePrefix`, so abandonment causes
     garbage collection. OK so far.
  4. Violation point: the strategy's `applyTranslation` was never invoked
     (because the translator returned empty), so no traversal mutation
     happened. Invariant preserved.
  5. Observable consequence: native pipeline runs the whole traversal —
     correct.

  ALTERNATE violation path:
  1. Start state: same as above.
  2. Action sequence:
     - Walker mis-classifies `HasStep` as recognized (e.g. a `instanceof
       HasContainerHolder` check that catches `HasStep`).
     - Walker calls `MatchPatternBuilder.addNode` to attach a where-clause
       it doesn't actually know how to build, and silently builds a
       no-op `WhereClause` instead.
     - Builder produces a valid IR, planner builds a plan, boundary step
       inserted.
  3. Intermediate state: traversal now has `YTDBMatchPlanStep` in place of
     all 3 original steps.
  4. Violation point: the plan ignores the `has("name", P.eq("marko"))`
     filter. Invariant: "no IR is constructed if step is unrecognized" is
     **violated** by mis-classification.
  5. Observable consequence: result includes vertices that don't match
     `has("name", "marko")`. Cucumber asserts result equivalence; this
     scenario fails Cucumber.
- **Feasibility**: CONSTRUCTIBLE — the misclassification path requires a
  bug in the walker's classifier. The `try/catch` at
  `GremlinToMatchStrategy.java:174-186` does NOT save us here because no
  exception is thrown; the walker silently produces a wrong-but-valid IR.
  Cucumber catches the divergence only if the test asserts on result
  values, not just count.

#### Violation scenario: anonymous alias `$g2m_anon_N` does not collide with user-provided labels
- **Invariant claim**: The translator's anonymous aliases are unique within
  the produced pattern AND do not collide with user-provided labels.
- **Violation construction**:
  1. Start state: `g.V().as("$g2m_anon_0").out("knows")` — a user who
     happened to label their start step with the translator's anonymous
     prefix.
  2. Action sequence:
     - Walker enters; start step's labels include `$g2m_anon_0`.
     - Walker generates anonymous alias for the target of `out("knows")`
       using counter starting at 0 → `$g2m_anon_0`.
     - Walker registers the start node with user label `$g2m_anon_0` AND
       the edge target with the same `$g2m_anon_0`.
     - `MatchPatternBuilder.addNode` MERGES on alias collision
       (`MatchPatternBuilder.java:71-93`).
  3. Intermediate state: pattern has ONE node aliased `$g2m_anon_0` instead
     of TWO. The pattern collapses two distinct concept-vertices into one.
  4. Violation point: the pattern is structurally wrong. The edge `out("knows")`
     now points from `$g2m_anon_0` to itself. Planner produces a plan that
     traverses self-loops only.
  5. Observable consequence: result is empty (or contains only self-loops).
- **Feasibility**: THEORETICAL — most users never use `$`-prefixed labels.
  But the test surface is real: a unit test or a paranoid user could write
  this. Risk Finding R3 already flags this; the adversarial angle is that
  the **invariant is asserted but not enforced** — there is no scan in the
  walker that detects the collision. Either the invariant must be
  weakened ("anonymous aliases must not collide with PRESENT user labels;
  the translator scans labels and declines on collision"), or a stronger
  scheme must be adopted (e.g. hashed/random suffix per traversal).

#### Violation scenario: identical result sets between Gremlin native and translated MATCH path
- **Invariant claim** (track description, line 35-36): "Cross-checked
  against equivalent SQL `MATCH` queries to confirm identical result sets
  and identical execution plan structure."
- **Violation construction**:
  1. Start state: A vertex `loop` with TWO self-loop edges of class `self`
     (the Cucumber `sink` graph at `Vertex.feature:417-427`).
  2. Action sequence:
     - Native Gremlin: `g.V().hasLabel("loops").both("self")` →
       `VertexStep` BOTH iterates each adjacent edge once, emitting one
       traverser per edge → produces TWO `v[loop]` traversers.
     - Translated MATCH: `MATCH {as: a}.both('self'){as: b} RETURN b` →
       runtime calls `SQLMatchPathItem.executeTraversal` →
       `traversePatternEdge` returns adjacent vertices via
       `SQLMethodCall.execute` which delegates to `SQLFunctionBoth`.
       But MATCH stores results via `Set<Identifiable>` (line 106 of
       `SQLMatchPathItem.executeTraversal` — see also `MatchEdgeTraverser`
       line 343 which "deduplicates vertices reachable via multiple paths
       in diamond/cyclic graphs" via `RidSet visited`).
  3. Intermediate state: `b` has only ONE entry (the `loop` vertex
     deduplicated).
  4. Violation point: result set has ONE row instead of TWO. Cucumber
     scenario `g_V_hasLabelXloopsX_bothXselfX` expects `[v[loop], v[loop]]`
     (multiset). MATCH produces `[v[loop]]`.
  5. Observable consequence: Cucumber FAILS for the self-loops scenario
     under translation.
- **Feasibility**: CONSTRUCTIBLE — the test exists in
  `Vertex.feature:417-427`. This is a fundamental Gremlin (multiset) vs
  MATCH (set) semantic divergence that cannot be papered over without
  changing MATCH's runtime.
- **Reference-accuracy caveat**: the dedup happens in
  `MatchEdgeTraverser.executeTraversal` via `RidSet visited`; I have
  Read the Javadoc but not traced every code path that emits result
  rows. PSI find-usages of `RidSet` would confirm whether deduplication
  is universal or limited to certain shapes. Recommend Track 3 reviewer
  iteration 2 verify with PSI.

#### Violation scenario: `g.V().out().out()` (chain without IDs) preserves rank-1 cardinality
- **Invariant claim** (implicit in "identical result sets" claim): a
  multi-hop traversal returns the same row count as the native pipeline.
- **Violation construction**:
  1. Start state: modern graph; `marko -- knows --> josh -- created -->
     ripple` and `marko -- created --> lop`. Total: marko has 3 outgoing
     paths (`marko->vadas`, `marko->josh->ripple`, `marko->lop`). Native
     `g.V(marko).out().out()` produces ONE result: `[ripple]` (the only
     two-hop neighbor). Native `g.V().out().out()` aggregates over all
     starts.
  2. Action sequence:
     - Native: each traverser is per-path; if `marko->josh->ripple` and
       no other 2-hop path exists, result is `[ripple]`.
     - Translated MATCH: `MATCH {as: a}.out(){as: b}.out(){as: c}
       RETURN c` — produces row per `(a, b, c)` triple. Without DISTINCT,
       rows are emitted per path. With deduplication at edge-traversal
       level (RidSet), only distinct (a, b, c) tuples are kept.
  3. Intermediate state: depends on graph topology. For modern graph
     `marko -> josh -> ripple` produces ONE (marko, josh, ripple) tuple;
     same as native. **OK for modern graph.**
  4. Violation point: graphs with multiple paths to the same target
     (diamond patterns, multi-edges) diverge. E.g., if there are two
     `marko --knows--> josh` edges (multi-edge) and one `josh --created-->
     ripple`, native produces TWO `[ripple]` traversers; MATCH dedups
     to ONE.
  5. Observable consequence: count divergence on multi-edge graphs.
     Modern graph doesn't have multi-edges, so the modern-graph Cucumber
     scenarios pass. Crew/grateful graphs need verification.
- **Feasibility**: THEORETICAL for the modern-graph Cucumber scenarios
  (these are likely PASS); CONSTRUCTIBLE for multi-edge graphs (LDBC,
  custom test data). The track description's claim is too strong — it
  should be qualified to "set-equivalence" or "multiset-equivalence on
  graphs without multi-edges or parallel paths".

### ASSUMPTION CHALLENGES

#### Assumption test: walker correctness can be testably-as-clean as the size>1 hard gate
- **Claim**: implicit in the "Gate replacement" paragraph — the walker
  replaces the size>1 gate with no loss in testability.
- **Stress scenario**:
  - The size>1 gate is THREE LINES (`if (traversal.getSteps().size() >
    1) return Optional.empty();`). It is INFALLIBLE.
  - The walker requires: a step classifier (10+ branches), an
    accumulator state (current alias, builder, projection), peek-ahead
    logic (outE+inV pairing), label-collision scan (Finding R3),
    multi-label encoding decisions (Finding R1), edge-alias decline
    rules (Finding R2). Tests must cover EACH branch.
- **Code evidence**:
  - `GremlinToMatchTranslator.java:156-158` — the existing 3-line gate.
  - Track 3 description mentions ~5 steps, each with its own test set.
  - Risk Finding R6 already suggests carving out a `StepClassifier`
    interface; agreed.
- **Verdict**: BREAKS — testability degrades from "3 lines, 1 test" to
  "10+ classifier branches, 5+ peek-ahead branches, 3+ collision/decline
  rules, all needing unit tests + integration tests + Cucumber re-run".
  This is unavoidable given Phase 1's goal, but the track should
  explicitly budget for the walker's test surface (suggest 30-50 unit
  tests across walker classifier, peek-ahead, collision detection,
  decline-mid-chain, anonymous-alias generation). Today's track-3.md
  verification list (line 32-36) is too informal for this scope.

#### Assumption test: Cucumber suite catches walker regressions
- **Claim**: implicit in the Phase 1 constraint "Full TinkerPop Cucumber
  suite (~1900 scenarios) must remain green with the strategy enabled."
- **Stress scenario**:
  - Track 2's episode noted: "the Cucumber suite (~1900 scenarios) cannot
    run in this worktree environment due to a classpath issue (predates
    this branch)."
  - Without local Cucumber runs, regressions only surface in CI on PR.
  - CI pipeline is slow; iterating on walker bugs round-tripping through
    CI is expensive.
- **Code evidence**:
  - Track 2's "Strategy refresh" comment in plan file line 622-636 (and
    the cross-track observation in the prompt summary).
- **Verdict**: BREAKS — Track 3 needs a local-runnable smoke test that
  approximates the Cucumber coverage without requiring the full suite.
  Recommend Track 3's first step extract a *subset* of the Cucumber
  Vertex.feature scenarios (the ones at `Vertex.feature:96-303` cover
  every Track 3 shape) into a JUnit-runnable parameterized test
  (`GremlinToMatchEdgeTraversalTest` or extension of the existing
  `GremlinToMatchSmokeTest`). This sidesteps the classpath issue and
  gives developers a fast local feedback loop.

#### Assumption test: `bothE().otherV()` semantics match Gremlin's "other endpoint" semantics
- **Claim**: track description line 16-17 — "`bothE(label).otherV()` —
  handled like the directional `bothE.inV` chain but with bidirectional
  edge."
- **Stress scenario**:
  - Gremlin's `bothE("knows").otherV()` from `marko` over an edge
    `marko -knows-> josh` returns `josh`. Over `vadas -knows-> marko`
    (incoming), `otherV` returns `vadas`. The "other" is *relative to
    the current traverser*.
  - MATCH's equivalent would need to encode "the endpoint of the edge
    that is NOT the current vertex". MATCH path-item `both('knows')`
    on a vertex returns BOTH endpoints — which on a self-loop returns
    the same vertex twice (or once after dedup), and on a non-self-loop
    returns the OTHER endpoint.
  - But MATCH does not have `otherV` semantics — it has `bothV` (both
    endpoints) and `bothE` (the edge). The `otherV` decision happens
    at runtime in Gremlin.
- **Code evidence**:
  - `SQLMethodCall.java:30` lists graph methods: "out, in, both, outE,
    inE, bothE, bothV, outV, inV". **`otherV` is NOT in the list.**
  - The track description claims `bothE.otherV` translates "like
    `bothE.inV` but with bidirectional edge" — but `inV` returns the
    "in" endpoint of the edge (always the same vertex regardless of
    which side you came from), which is NOT the same as `otherV`.
- **Verdict**: BREAKS — there is no MATCH primitive for "the other
  endpoint relative to the source vertex". The track's proposed
  translation `bothE.inV` is semantically wrong for non-self-loop
  edges where the source is the `inV` (not the `outV`).

  Concrete failure: edge `vadas -knows-> marko`, Gremlin from `marko`
  with `bothE("knows").otherV()` returns `vadas`. Translated as
  `MATCH {as: a, where: @rid = #marko}.both('knows'){as: b} RETURN b`
  — works, returns `vadas` (the "other" via dedup over `both` →
  unique result). BUT for an edge `marko -knows-> josh` and `marko
  -knows-> vadas` from `marko`, native `bothE.otherV` returns
  `[josh, vadas]` (multiset, two traversers), translated MATCH
  returns `[josh, vadas]` (two rows). OK for non-self-loop case.

  **For self-loop edges**, native `bothE("self").otherV()` from `loop`
  returns `[loop, loop]` (two traversers, one per edge — see
  `Vertex.feature:417-427`), MATCH returns `[loop]` (deduped). Same
  failure as `both("self")`.

- **Reference-accuracy caveat**: I have not traced
  `SQLFunctionBoth.execute` or `MatchEdgeTraverser.executeTraversal`
  fully — `otherV` semantic might map to a different primitive I missed.
  PSI find-usages of `Direction.OUT/IN/BOTH` across the function classes
  would confirm. Recommend Track 3 reviewer iteration 2 verify.

#### Assumption test: multi-label edge `IN [...]` filter exists in MATCH IR
- **Claim**: track description line 17-18 — "MATCH supports edge-label
  `IN` lists via the path-item filter mechanism."
- **Stress scenario**:
  - User writes `g.V(marko).out("knows", "created")` →
    walker tries to encode as a single MATCH path-item.
  - Programmatically, `SQLMatchPathItem.outPath(SQLIdentifier
    edgeName)` accepts ONE identifier
    (`SQLMatchPathItem.java:54-56`). The internal `graphPath` builds
    ONE `SQLBaseExpression` via `addParam` (`SQLMatchPathItem.java:43`).
  - The track's "via the path-item filter mechanism" suggests adding a
    `WHERE @class IN ['knows', 'created']` filter on the path item's
    `SQLMatchFilter`. But the path-item filter is the **target-vertex
    filter**, not the **edge filter**. `@class` on the target vertex
    filters destination *vertex* class, not edge class.
- **Code evidence**:
  - `MatchPatternBuilder.java:128-141` — `addEdge` builds a
    `SQLMatchFilter toFilter = SQLMatchFilter.fromGqlNode(toAlias, null)`
    and assigns `pathItem.setFilter(toFilter)`. The `toFilter` is the
    target-vertex filter.
  - `MatchExecutionPlanner.java:4132-4167` — `getEdgeClassName` reads
    `params.getFirst()` only. Even if `addEdge` were extended to attach
    multiple params via `SQLMethodCall.addParam`, the planner's edge
    cost estimation only sees the FIRST label.
  - SQL grammar at `YouTrackDBSql.jjt:3580-3712` accepts at most ONE
    `Identifier()` per path item — confirming there is NO grammar-level
    multi-label support.
- **Verdict**: BREAKS — the track description's encoding strategy is
  incorrect on TWO counts: (1) the path-item filter is a vertex filter,
  not an edge filter; (2) even programmatic multi-label
  `SQLMethodCall.addParam` calls would not feed the planner's cost
  model, which truncates to first label.

  Risk Finding R1 already flagged this with a different framing
  (additive: extend `MatchPatternBuilder`); the adversarial framing is
  that **the entire mental model of "MATCH IR supports multi-label
  edges" is wrong**. The recovery options remain (1) extend
  `MatchPatternBuilder` AND extend `MatchExecutionPlanner.getEdgeClassName`
  AND extend the schema-driven cardinality estimator to OR-combine
  per-label cardinalities; (2) decline multi-label edges in Phase 1
  (RECOMMENDED — defer to Phase 2 with proper multi-label support);
  (3) decompose into union (depends on Track 10).

  This finding is more invasive than R1 suggests: extending only
  `MatchPatternBuilder` is necessary but not sufficient — the planner's
  cost model also needs an upgrade or the resulting plans will be
  silently sub-optimal.

#### Assumption test: equivalence vs SQL `MATCH` is sufficient verification
- **Claim**: track description line 32-36 — "Cross-checked against
  equivalent SQL `MATCH` queries to confirm identical result sets and
  identical execution plan structure (same step types in the same
  order, regardless of parameter values)."
- **Stress scenario**:
  - The translator and SQL `MATCH` parser both produce
    `MatchPlanInputs` (translator directly, SQL parser via the new
    ctor). If the translator's encoding matches SQL's, plan structures
    will be identical. So plan-equivalence tests reduce to "input
    encoding matches".
  - But the test goal should be `Gremlin native = translated MATCH`,
    not `translated MATCH = SQL MATCH`. The latter only proves the
    translator faithfully encodes a MATCH-equivalent shape — it does
    NOT prove the MATCH shape is semantically equivalent to the
    Gremlin traversal.
  - Self-loop counting (above), multi-edge multiset semantics, edge
    alias preservation, path semantics — these are all places where
    Gremlin and MATCH diverge. SQL-MATCH-parity passes silently.
- **Code evidence**: see self-loop violation scenario above — SQL
  MATCH `both('self')` and Gremlin `both('self')` produce different
  cardinalities on self-loops; verifying "translated MATCH == SQL
  MATCH" doesn't catch this.
- **Verdict**: BREAKS — verification methodology is too weak. The
  correct invariant is "Gremlin native pipeline result == translated
  MATCH result" (i.e., assertion against the un-translated Gremlin
  baseline, not against SQL MATCH). This is what Cucumber actually
  asserts. The track description should be rewritten to make this
  explicit and downgrade SQL-MATCH-parity to a *secondary* sanity
  check.

#### Assumption test: `MatchExecutionPlanner.assignDefaultAliases` never runs on the translator's path
- **Claim** (track description line 21-29, citing CR4): "the
  `MatchExecutionPlanner.assignDefaultAliases` re-assignment never
  runs on the translator's path."
- **Stress scenario**:
  - Phase 1's translator only populates `matchExpressions` via the
    `MatchPlanInputs` ctor and never `notMatchExpressions`. CR4 is
    correct for Phase 1.
  - Track 5 will populate `notMatchExpressions`. `buildPatterns`
    short-circuits when `pattern != null` (line 4434-4436), but
    `notMatchExpressions` aliases — used by Track 5 — go through
    `assignDefaultAliases` UNDER the short-circuit, so they would NOT
    be auto-aliased.
  - Wait — re-read: `buildPatterns` at line 4437-4441 shows
    `assignDefaultAliases(allPatterns)` runs INSIDE the body, AFTER
    the early-return. So if `pattern != null`, the early return at
    4434-4436 fires and `assignDefaultAliases` is NEVER reached. This
    is correct for Track 3's path.
  - HOWEVER: Track 5 may populate `notMatchExpressions` with
    user-named aliases only (no anonymous fall-back). If a Track 5
    NOT pattern has anonymous intermediate hops, the translator must
    generate them itself — `assignDefaultAliases` won't.
- **Code evidence**:
  - `MatchExecutionPlanner.java:4433-4441`.
  - `Pattern.java:65-92` shows nodes are created from filter aliases;
    if alias is null, `getOrCreateNode` would NPE on `aliasToNode.put`.
- **Verdict**: HOLDS for Track 3, FRAGILE for downstream. Track 3's
  description correctly notes the short-circuit. The risk is that
  Tracks 5-10 inheriting Track 3's anonymous-alias generator must
  also feed `notMatchExpressions` properly. Surface this as a Track
  5/6/7/9 dependency note in the plan file.

  Reference-accuracy caveat: I read `Pattern.java` and
  `MatchExecutionPlanner.java` directly but did not PSI-trace every
  caller of `assignDefaultAliases`. PSI find-usages would confirm
  it is invoked from a single site.

### SIMPLIFICATION CHALLENGES

#### Challenge: Could the walker-and-decline-on-unrecognized be replaced by a "translate-and-validate" approach?
- **Chosen approach**: Walker classifies each step as recognized/unrecognized
  before calling builders. If any step is unrecognized, decline.
- **Best rejected alternative**: Try to translate every step optimistically;
  catch a hypothetical `UnrecognizedStepException`; decline on catch.
- **Counterargument trace**:
  1. Try-catch makes the decline path implicit. A new step type that
     accidentally doesn't throw produces wrong translation silently.
  2. Walker classifier makes the decline path explicit. A new step type
     defaults to UNRECOGNIZED via the switch-default branch.
- **Survival test**: YES — the walker classifier is the right approach.
  Java 21 sealed switch can make the default branch a compile-time
  exhaustiveness check (if step types were sealed; they aren't in
  TinkerPop). Pattern matching switch on `Step.getClass()` with a
  default branch that returns UNRECOGNIZED is robust.

#### Challenge: Could the existing `YTDBGraphMatchStepStrategy` infrastructure be reused for edge translation?
- **Chosen approach**: Build a brand-new walker.
- **Best rejected alternative**: Extend `YTDBGraphMatchStepStrategy`
  (the existing label-folding strategy) to also fold `out`/`in`/`both`
  into MATCH IR.
- **Counterargument trace**:
  1. `YTDBGraphMatchStepStrategy` already handles SOME translation
     today (label folding for `g.V().match(...)`). Track 3 explicitly
     routes around it (`applyPost`).
  2. If `YTDBGraphMatchStepStrategy` already has a partial walker,
     extending it would be cheaper than building a new one.
- **Codebase evidence**: Need to read `YTDBGraphMatchStepStrategy` to
  evaluate. Did not Read its full source for this review.
- **Survival test**: WEAK — without reading the existing strategy, I
  cannot conclusively rule out reuse. Recommend Track 3's iteration 2
  PSI-explore `YTDBGraphMatchStepStrategy` to see whether its
  label-folder logic is composable with edge-translation. If yes,
  reuse may save implementation effort. If no, build the new walker.

#### Challenge: Could anonymous-alias generation use a hash of the step identity instead of a counter?
- **Chosen approach**: Counter-based `$g2m_anon_0`, `$g2m_anon_1`, etc.
- **Best rejected alternative**: Hash-based, e.g.
  `$g2m_anon_<hex(step.hashCode())>` — guarantees uniqueness across
  separate translation sessions, is collision-resistant against user
  labels (very long hex strings).
- **Counterargument trace**:
  1. Counter-based aliases are stable across runs but vulnerable to
     user-label collision (Finding R3).
  2. Hash-based aliases are unstable (different hashCode each JVM)
     but collision-proof.
  3. Hash-based aliases would defeat plan caching (Phase 2) because
     the cache key would never match.
- **Survival test**: YES — counter-based survives. Plan caching
  (Phase 2) requires stable alias names; hash-based defeats it.
  Counter-based + collision detection (Finding R3) is the right
  choice.

---

## Part 2: Findings

### Finding A1 [should-fix]
**Certificate**: Decision D3 — All-or-nothing translation
**Target**: Decision D3
**Challenge**: D3's revised rationale ("Phase 1 is minimal scope, hybrid
doesn't pay off") was correct in Track 2's context but Track 3 expands the
recognized set significantly. After Track 3, the walker is already complex
enough to support a hybrid prefix; the simplification benefit erodes.
Coupled with the strategy's current decline-on-non-empty-hasContainers gate,
**Track 3 in isolation provides essentially no real-world LDBC translation**
because real queries virtually always have `has` folded into the start step.
The plan should explicitly acknowledge that user-visible value arrives at
Track 4 (when has-containers handling lands), not at Track 3.
**Evidence**: `GremlinToMatchStrategy.java:160-162` declines on non-empty
hasContainers; `YTDBGraphStepStrategy.java:119-126` folds HasStep into start
step. Most real traversals therefore decline regardless of edge-handling
sophistication.
**Proposed fix**: Add a paragraph to D3 acknowledging "Track 3 alone does
not deliver measurable LDBC value; arrival point is Track 4 after has-
container handling." Optionally reconsider hybrid prefix as alternative
(d) with a config knob, scoped to Phase 1 if Track 12 perf shows large
LDBC regressions.

### Finding A2 [blocker]
**Certificate**: Scope challenge — boundary step extension for Edge output
**Target**: Track 3 scope
**Challenge**: Track 3 plans to handle `outE()`, `inE()`, `bothE()` (edge-
returning terminal steps) but the existing `BoundaryOutputType` enum has
only `ELEMENT` and `YTDBMatchPlanStep.projectElement` calls
`row.getVertex(boundaryAlias)` — hardcoded to vertex. Without extending
both, every edge-returning translation produces null result rows.
**Evidence**: `BoundaryOutputType.java:34`, `YTDBMatchPlanStep.java:262-265`
(exhaustive switch on ELEMENT only), `YTDBMatchPlanStep.java:278-284`
(`getVertex` hardcoded). Cucumber scenarios at `Vertex.feature:155, 169,
181, 195, 208, 242` exercise these terminal edge-returning shapes.
**Proposed fix**: Either (a) include in Track 3 scope: extend
`BoundaryOutputType` with `EDGE_ELEMENT`, extend `projectElement` to
dispatch on output type, add Edge-returning projection branch via
`row.getEdge(...)`. OR (b) decline `VertexStep` whose `returnsEdge() ==
true` is the terminal step (and the bothE/outE/inE Cucumber scenarios)
in Phase 1; defer to a later track that owns boundary-step extension.
Add explicit step to track-3.md decomposition.

### Finding A3 [blocker]
**Certificate**: Violation scenario — multiset vs set semantic divergence
on self-loops
**Target**: Invariant — "identical result sets" between Gremlin native and
translated MATCH
**Challenge**: Gremlin `both/bothE/otherV` produce one traverser per edge;
MATCH deduplicates via `RidSet visited` in `MatchEdgeTraverser`. On
self-loop graphs (Cucumber `sink` graph at `Vertex.feature:417-427`),
native `g.V().hasLabel("loops").both("self")` returns `[v[loop], v[loop]]`
(two rows); translated MATCH returns `[v[loop]]` (deduped). Cucumber
scenario `g_V_hasLabelXloopsX_bothXselfX` FAILS under translation. The
track description's claim "identical result sets" is too strong; the
correct invariant is set-equivalence on graphs without parallel paths
or self-loops.
**Evidence**: `MatchEdgeTraverser.java:343` Javadoc — "visited: mutable
bitmap set of RIDs already emitted; used to deduplicate vertices reachable
via multiple paths in diamond/cyclic graphs". `SQLMatchPathItem.java:106`
— `Set<Identifiable> result = new HashSet<>()`. Cucumber scenario at
`Vertex.feature:417-427`.
**Proposed fix**: Track 3 must add a decline rule for traversals on graphs
with self-loops or multi-edges, OR weaken the invariant to set-equivalence,
OR (preferred) decline `both`/`bothE` traversals where the planner cannot
prove the absence of self-loops at translation time. Add explicit
decline-on-self-loop test in Track 3 verification list. Document the
multiset/set divergence as a Phase 1 limitation.

### Finding A4 [blocker]
**Certificate**: Assumption test — `bothE().otherV()` semantics
**Target**: Track 3 step coverage
**Challenge**: Track description proposes translating `bothE.otherV` "like
`bothE.inV`" — but `inV` is the fixed "in" endpoint of an edge regardless
of source, while `otherV` is the endpoint NOT equal to the source. They
coincide only in special cases. There is NO MATCH primitive for "other
endpoint relative to source" — `SQLMethodCall.graphMethods` lists only
`out, in, both, outE, inE, bothE, bothV, outV, inV` — no `otherV`. The
proposed translation `bothE.inV` is semantically wrong for the case where
the source is the edge's `inV` endpoint (incoming edge from source's
perspective).
**Evidence**: `SQLMethodCall.java:30` graphMethods enumeration. Track
description line 16-17.
**Proposed fix**: Either (a) translate `bothE("knows").otherV()` as
`MATCH ... both("knows") ...` directly (skipping the explicit edge
binding) — this works because MATCH `both` returns the OTHER endpoint
naturally for any non-self-loop edge. (b) Decline `bothE.otherV` in
Phase 1 and defer to a later track. Update track description to remove
the incorrect "like bothE.inV" framing.

### Finding A5 [should-fix]
**Certificate**: Assumption test — equivalence vs SQL MATCH
**Target**: Verification methodology
**Challenge**: The track description specifies cross-checking against
equivalent SQL `MATCH` queries. But the relevant invariant is "Gremlin
native pipeline == translated MATCH", not "translated MATCH == SQL MATCH".
The latter only proves the translator faithfully encodes MATCH semantics —
it does NOT catch divergences between Gremlin and MATCH semantics
(self-loops, multi-edges, edge alias). Cucumber DOES assert against
Gremlin native, but Cucumber cannot run locally in this worktree (per
Track 2 episode).
**Evidence**: Track description line 32-36; Track 2 episode note about
local Cucumber.
**Proposed fix**: Reword the verification section: "Primary verification:
Gremlin native traversal result equals translated MATCH result, exercised
via JUnit tests over a real graph (Modern, Sink subset). Secondary
sanity check: translated MATCH plan-tree structure matches the SQL MATCH
parser's plan-tree structure for equivalent queries." Add a JUnit-runnable
edge-traversal test class as the primary regression net, since Cucumber
is not locally runnable.

### Finding A6 [should-fix]
**Certificate**: Decision D4 — Strategy ordering rationale
**Target**: Decision D4
**Challenge**: D4's stated rationale ("we receive `YTDBGraphStep` with
`hasContainers` already attached, which the translator reads as the root
selectivity") is misleading. Track 3 in isolation **cannot use** the
absorbed has-containers — it declines on them. The real reason for the
ordering is to avoid re-routing `g.V().count()` away from
`YTDBGraphCountStrategy`. The plan should rephrase the rationale to
reflect the actual driver, or it sets a false expectation that Track 3
benefits from the absorbed shape.
**Evidence**: `GremlinToMatchStrategy.java:160-162` — declines on non-empty
hasContainers.
**Proposed fix**: Update D4 rationale: "Run after `YTDBGraphCountStrategy`
to keep the count short-circuit's authority over `g.V().count()`. The
position relative to `YTDBGraphStepStrategy` is informational in Phase 1
(the translator declines on absorbed has-containers and would also
function in front of the absorption); Track 4 changes this — when
has-container handling lands, the translator benefits from the absorbed
shape. Position before `YTDBGraphMatchStepStrategy` keeps the label-folder
as fallback for declined traversals."

### Finding A7 [should-fix]
**Certificate**: Assumption test — walker testability vs hard-gate testability
**Target**: Walker design
**Challenge**: The size>1 hard-decline gate is THREE LINES and infallible.
The walker introduces a step classifier (10+ branches), peek-ahead state,
collision detection, decline-mid-chain logic — a 30-50× expansion in test
surface. The track description's verification list (lines 32-36) is
informal for this scope.
**Evidence**: Existing translator gate at
`GremlinToMatchTranslator.java:156-158`. Track description.
**Proposed fix**: Track 3's first step should establish the walker's
**stable interface** (sealed `Recognition` result, `WalkerContext`
record, `StepClassifier` enum) before any handler is implemented. This
also addresses risk Finding R6 (walker contract stability for Tracks
4-10). Add a test budget statement to track-3.md: "Walker classifier:
≥10 unit tests (one per step class). Peek-ahead: ≥5 unit tests (paired
+ unpaired + wrong-direction + bothE.otherV + outE alone). Collision
detection: ≥3 unit tests. Anonymous-alias generation: ≥2 unit tests
(counter resets per traversal, no leakage)."

### Finding A8 [should-fix]
**Certificate**: Assumption test — Cucumber runs locally to catch walker bugs
**Target**: Verification infrastructure
**Challenge**: Track 2's episode noted Cucumber cannot run locally in this
worktree due to a classpath issue. Track 3 introduces the walker — the
load-bearing entry point for 7 future tracks — without a fast feedback
loop. CI iteration is slow.
**Evidence**: Cross-track observation in prompt summary; Track 2 episode.
**Proposed fix**: Track 3's first step should extract the Cucumber Vertex
feature scenarios at `Vertex.feature:96-303` into a JUnit-runnable
parameterized test (`GremlinToMatchEdgeTraversalTest`). One scenario
per row in the parameterization; assert (a) translated equals native, (b)
boundary step inserted, (c) declined cases stay native. This scales to
Tracks 4-10 (each adds rows). Sidesteps the Cucumber classpath issue.

### Finding A9 [suggestion]
**Certificate**: Simplification challenge — try-catch with implicit decline
**Target**: Walker design
**Challenge**: An optimistic translate-and-catch design would seem
simpler, but makes the decline path implicit. Walker classifier with
explicit RECOGNIZED/UNRECOGNIZED branches is correct.
**Evidence**: General software engineering principle.
**Proposed fix**: None — the chosen approach (explicit walker classifier)
is correct. Keep it.

### Finding A10 [suggestion]
**Certificate**: Simplification challenge — could `YTDBGraphMatchStepStrategy`
be extended instead of building a new walker?
**Target**: Walker scope
**Challenge**: `YTDBGraphMatchStepStrategy` already handles label folding
for `g.V().match(...)`. If its walker is composable with edge handling,
extending it would save effort.
**Evidence**: Did not Read `YTDBGraphMatchStepStrategy` source for this
review.
**Proposed fix**: Track 3's iteration 2 should PSI-explore
`YTDBGraphMatchStepStrategy` and decide: (a) reuse, (b) replace, (c) build
parallel. Document the decision in the track file.

### Finding A11 [suggestion]
**Certificate**: Simplification challenge — counter-based vs hash-based
anonymous alias
**Target**: Anonymous alias generation strategy
**Challenge**: Hash-based anonymous aliases would be collision-proof
against user labels but defeat plan caching (Phase 2 dependency).
**Evidence**: D5 plan-cache decision.
**Proposed fix**: None — counter-based is correct (Phase 2 cache
compatibility). Risk Finding R3 + adversarial Finding A12 (below) are
the right mitigations.

### Finding A12 [should-fix]
**Certificate**: Violation scenario — anonymous alias collision with
user label
**Target**: Invariant — anonymous aliases unique within pattern
**Challenge**: TinkerPop accepts any String as a step label.
`g.V().as("$g2m_anon_0").out("knows")` would collide with the
translator's anonymous namespace. `MatchPatternBuilder.addNode` MERGES
on collision (`MatchPatternBuilder.java:71-93`), collapsing two
distinct concept-vertices into one. Result: empty or self-loop-only
result.
**Evidence**: `MatchPatternBuilder.java:71-93` (merge-not-replace);
`GremlinToMatchTranslator.java:93` (existing reservation of `$g2m_v0`).
**Proposed fix**: Walker pre-flight scan: before generating anonymous
aliases, scan `traversal.getSteps()` for any `Step.getLabels()` entry
that startsWith `$g2m_` (or matches the `$g2m_anon_` / `$g2m_v` prefix
specifically). Decline the whole traversal on collision. Add unit test
for the collision case. (Same conclusion as risk Finding R3, raised
here from the invariant-violation angle for completeness.)

---

## Summary

12 findings.

- **blocker**: 3 (A2 boundary step Edge output, A3 multiset/set self-loop
  divergence, A4 bothE.otherV semantic miscoded)
- **should-fix**: 6 (A1 D3 LDBC value qualification, A5 verification
  methodology too weak, A6 D4 rationale misleading, A7 walker
  testability budget, A8 Cucumber-unrunnable mitigation, A12 anonymous
  alias collision invariant)
- **suggestion**: 3 (A9 walker classifier explicitness, A10 reuse
  YTDBGraphMatchStepStrategy?, A11 counter-based anonymous alias)
- **skip**: 0 — Track 3 is essential, but its scope and verification
  methodology need substantial corrections before implementation.

### Cross-cutting recommendation

Three of four blockers (A2, A3, A4) and two should-fixes (A5, A8) are
**verification-methodology** failures: the track relies on "translated
MATCH = SQL MATCH" parity instead of "translated MATCH = Gremlin native"
parity, plus Cucumber as a safety net that cannot run locally. The
single highest-leverage fix is to land a JUnit-runnable parameterized
edge-traversal test class (Finding A8) before any walker code, populated
from `Vertex.feature:96-303`. That test class will independently
surface A2 (null result rows on edge output), A3 (self-loop count
divergence), and A4 (bothE.otherV mistranslation) as concrete failures
the implementer can iterate on locally.
