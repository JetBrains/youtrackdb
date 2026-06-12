# Track 3 Risk Review — Edge traversal (out / in / both / outE.inV / inE.outV / bothE.otherV)

Phase A iteration 1. Reviewer: risk-review sub-agent.

Tooling: mcp-steroid was not invoked in this session — fall back to grep / Read.
Each finding records the search method used; reference accuracy is therefore best-effort.
Where a finding depends on counting call sites or polymorphic dispatch resolution, the
finding text adds an explicit reference-accuracy caveat.

---

## Part 1: Evidence Certificates

### CRITICAL PATH EXPOSURE

#### Exposure: Step-recognition walker becomes the load-bearing entry point for Tracks 4–10
- **Track claim**: "the strategy currently declines any traversal whose `getSteps().size() > 1` … must be replaced with a step-recognition walker that classifies each step as recognized/unrecognized and declines the entire traversal only when at least one unrecognized step is present (D3 all-or-nothing). The walker becomes the load-bearing entry point for Tracks 4-10 as well."
- **Critical path trace**:
  1. Entry: `GremlinToMatchStrategy.apply(Traversal.Admin)` @
     `core/.../gremlin/translator/strategy/GremlinToMatchStrategy.java:146`
  2. → `translator.apply(traversal)` @ same file:175 (today resolves to
     `GremlinToMatchTranslator.translatePrefix`)
  3. → `translatePrefix` @
     `core/.../gremlin/translator/strategy/GremlinToMatchTranslator.java:137`
     where the size>1 hard-decline gate currently lives at line 156.
  4. After Track 3: the walker iterates `traversal.getSteps()` and for each step calls a
     classifier that decides: recognized → drive a builder; unrecognized → return
     `Optional.empty()`.
  5. → Builder calls reach `MatchPatternBuilder.addNode/addEdge` @
     `core/.../sql/executor/match/builder/MatchPatternBuilder.java`
  6. → `MatchExecutionPlanner(MatchPlanInputs)` ctor @
     `core/.../sql/executor/match/MatchExecutionPlanner.java:480` and
     `createExecutionPlan` (`buildPatterns` short-circuits because `pattern != null`).
  7. → Plan is wrapped by `YTDBMatchPlanStep` and executed.
- **Blast radius**: the walker is the unique gating decision point for every later
  Phase 1 track. A false-positive ("classify unrecognized step as recognized") would
  result in the strategy translating a traversal it cannot encode — producing wrong
  results without a user-visible error (see Strategy's top-level try/catch below).
  A false-negative ("decline a step the recognized set covers") loses an optimization
  opportunity but is silently safe. Cucumber baseline (~1900 scenarios across
  `core/YTDBGraphFeatureTest` + `embedded/EmbeddedGraphFeatureTest`) is the regression
  net; if a false-positive escapes to scenarios that previously declined whole and now
  translate, the blast radius equals "all such scenarios produce silently-wrong
  result rows".
- **Existing safeguards**:
  - The strategy's outer gates (idempotency, vertex-graph-start, hasContainers-empty,
    kill-switch) at `GremlinToMatchStrategy.java:153-163` filter most non-translatable
    shapes before the walker runs.
  - The strategy's top-level `try { … } catch (RuntimeException ex)` at
    `GremlinToMatchStrategy.java:174-186` catches any throw and declines back to
    native execution — which protects against crashes BUT also masks logic bugs
    (silent wrong results).
  - Pre-touch contract guard at `applyTranslation` (`GremlinToMatchStrategy.java:271-284`)
    rejects buggy `prefixStepCount > traversal.size()` outputs before any mutation.
  - Cucumber suite is the regression baseline (~1900 scenarios). A wrong-result
    false-positive would surface as a Cucumber failure if the scenario asserts on
    the result, not just on cardinality.
- **Residual risk**: HIGH — Cucumber asserts result equivalence but cannot certify
  that the *specific code path* that previously produced the result is the one
  exercised. A scenario that incidentally accepts the same result set under wrong
  translation will pass.  The classifier needs **per-class allow-listing**, not
  pattern-matching by step name, and unit tests for *every* step class the walker
  encounters during Cucumber.

#### Exposure: `MatchPatternBuilder.addEdge` contract is the foundation of every edge handler
- **Track claim**: "VertexStep with direction OUT/IN/BOTH and edge labels — produces a
  SQLMatchPathItem via SQLMatchPathItem.outPath/inPath/bothPath helpers wrapped by
  MatchPatternBuilder.addEdge."
- **Critical path trace**:
  1. `addEdge(fromAlias, toAlias, dir, edgeLabel, edgeFilter, whileCondition, maxDepth)`
     exists @ `MatchPatternBuilder.java:109-149` (Track 1 already shipped it).
  2. Internally builds `SQLMatchPathItem` via `outPath/inPath/bothPath` @ same file
     :136-140 and `Pattern.addExpression` @ same file :147.
  3. `whileCondition`/`maxDepth` are explicitly **unsupported** in Track 1's API and
     throw `UnsupportedOperationException` (line 121-124). Track 3 must pass `null`
     for both, which the existing outE/inV handlers will do.
- **Blast radius**: if `addEdge`'s contract diverges from Track 3's expectations
  (e.g. it doesn't register the target alias, or the edgeFilter ends up in the
  wrong place), every multi-hop test in Track 3 fails the same way and the bug
  cascades to Tracks 4–10 which build on top of `addEdge`.
- **Existing safeguards**:
  - Track 1 ran a 3-iteration review and 86 GQL tests; the builder's behaviour for
    single-edge MATCH expressions is regression-covered.
  - Track 1's Javadoc explicitly documents that `addEdge` calls
    `Pattern.addExpression` which performs implicit `getOrCreateNode` for both
    endpoints (`MatchPatternBuilder.java:25-28`), so calling `addEdge` without a
    prior `addNode` for the target is intentional.
- **Residual risk**: MEDIUM — Track 1's tests focused on GQL's actual usage (single
  edge, fixed shape). Track 3 will exercise multi-hop chains, mixed direction, and
  the (gap) scenario where the **target alias's class** must be registered after
  `addEdge` so user filters take effect. The Javadoc at
  `MatchPatternBuilder.java:99-103` already says "Callers that want the filter to
  participate in plan-level selectivity inference should also call addNode for the
  target alias" — but Track 3's description does NOT call this out. Risk that a
  Track 3 implementer reads only the track description, calls `addEdge` without the
  follow-up `addNode`, and silently loses class inference on intermediate aliases.

#### Exposure: `bothE().otherV()` and the path item's lack of edge alias
- **Track claim**: "EdgeVertexStep (the inV()/outV() after an outE(label) / inE(label)) —
  composes with the preceding VertexStep of Edge class to form one SQLMatchPathItem
  with edge alias plus terminal vertex alias. Requires walker to peek ahead and pair
  the two steps."
- **Critical path trace**:
  1. TinkerPop's `g.V().outE("knows").inV()` produces `VertexStep<Edge>` (returnClass
     = Edge, direction = OUT) followed by `EdgeVertexStep` (direction = IN).
  2. The walker must collapse these two steps into one MATCH `out('knows')` path
     item.
  3. `SQLMatchPathItem`'s `outPath`/`inPath`/`bothPath` accepts a single
     `SQLIdentifier` edge label — there is no way to attach an edge alias to the
     path item itself. **`PatternEdge` (`core/.../match/PatternEdge.java:36`) has no
     alias field.**
  4. `g.V().outE("knows").as("e").inV()` therefore cannot encode "e" in the IR.
- **Blast radius**: any Cucumber scenario that names an edge alias and references it
  in a downstream `select("e")` / `path()` / `where(...)` cannot be correctly
  translated. Without explicit decline logic in the walker, the translator will
  produce a plan that returns no edge column under "e" — silent semantic divergence
  from the native pipeline.
- **Existing safeguards**: none — the IR genuinely cannot represent edge aliases.
  Track 3 must therefore add a decline rule: "VertexStep<Edge>.as(label) seen
  anywhere on the edge-step → unrecognized → whole traversal declines (D3)."
- **Residual risk**: HIGH — the track description does not flag this. Without an
  explicit decline, future tracks (Track 7 select / Track 11-retired path) will
  encounter the missing alias and either crash or produce wrong rows.

  Reference-accuracy caveat: confirmed `PatternEdge` has no alias field via direct
  Read of `core/.../match/PatternEdge.java`; could not confirm via PSI find-usages
  whether any downstream MATCH consumer reads an edge alias by other means. Recommend
  Track 3's review iteration 2 verify with PSI.

#### Exposure: Top-level try/catch hides walker bugs
- **Track claim**: implicit — the strategy already has a top-level
  `try { … } catch (RuntimeException ex) { logger.warn(…) }` at
  `GremlinToMatchStrategy.java:174-186`.
- **Critical path trace**:
  1. Walker bug throws (NPE, ClassCastException, IllegalState, etc.).
  2. `apply()` catches, logs WARN, returns without modifying the traversal.
  3. Native pipeline runs — user sees the right result, no error.
- **Blast radius**: a walker bug that consistently throws on a recognized-set step
  produces zero translation but no test failure for tests that only assert result
  equivalence. The Cucumber baseline counts pass-rate, not which path was taken.
- **Existing safeguards**:
  - WARN log captures the exception.
  - Phase-A tests in Tracks 4-10 will pin "translate succeeded" by asserting the
    resulting traversal contains a `YTDBMatchPlanStep` (existing test pattern in
    `GremlinToMatchStrategyTest.java`).
- **Residual risk**: MEDIUM — Track 3 must add tests that assert the boundary step
  *was inserted* for each recognized shape, otherwise a silent throw masquerades as
  a passing test. The same trap will recur in every Track 4-10 step file.

### UNKNOWNS & ASSUMPTIONS

#### Assumption: planner's `assignDefaultAliases` does not run on the translator's path
- **Track claim** (in track-3.md description): "the
  `MatchExecutionPlanner.assignDefaultAliases` re-assignment never runs on the
  translator's path (consistency review CR4 — `buildPatterns` short-circuits when
  `pattern != null`, which is always the case for the `MatchPlanInputs` ctor)."
- **Evidence search**: grep `buildPatterns` on `MatchExecutionPlanner.java`
- **Code evidence**:
  - `MatchExecutionPlanner.java:480-509` (`MatchPlanInputs` ctor) sets
    `this.pattern = inputs.pattern();` at line 482.
  - `MatchExecutionPlanner.java:4434-4436` (`buildPatterns` body) returns
    immediately if `this.pattern != null`.
  - `assignDefaultAliases` is invoked at line 4441, inside `buildPatterns`, AFTER
    the `pattern != null` short-circuit.
- **Verdict**: VALIDATED — the translator's own anonymous prefix `$g2m_anon_N`
  cannot collide with `DEFAULT_ALIAS_PREFIX` because the planner's auto-assignment
  is never reached.
- **Detail**: The track is correct that the translator owns its own namespace.
  However, the track does not address the **other** consumers of
  `DEFAULT_ALIAS_PREFIX` — `ReturnMatchElementsStep.java:44`,
  `ReturnMatchPatternsStep.java:51`, `ReturnMatchPathElementsStep.java`. These
  steps **filter out** aliases starting with `DEFAULT_ALIAS_PREFIX` so that
  auto-generated intermediate nodes are not emitted as elements. The translator's
  `$g2m_anon_N` aliases will NOT be filtered out by these checks, so any future
  use of `RETURN $elements`/`RETURN $patterns` would emit anonymous intermediate
  vertices the user never asked for.

  Phase 1's recognized return paths only RETURN named projections (the boundary
  alias plus user-named labels), so the bug is dormant. But Track 7 (projections)
  and Track 9 (aggregations) need to **explicitly** check that anonymous aliases
  never reach a `RETURN $elements`/`$patterns` shape.

#### Assumption: anonymous alias prefix `$g2m_anon_N` cannot collide with user labels
- **Track claim**: "The translator's anonymous aliases only need to be locally unique
  and not collide with user-provided labels."
- **Evidence search**: grep `\$g2m` and `\$YOUTRACKDB_DEFAULT_ALIAS` and `setAlias`
  on `core/src/main/java`
- **Code evidence**:
  - `GremlinToMatchTranslator.java:93` already reserves `$g2m_v0` as the boundary
    alias.
  - User-provided labels in TinkerPop come from `Step.getLabels()`, which TinkerPop
    accepts as any non-null `String`. There is no rule against starting with `$`.
- **Verdict**: UNVALIDATED — TinkerPop does not validate label content, so a user
  who writes `g.V().as("$g2m_anon_0")` would collide with the translator's anonymous
  namespace.
- **Detail**: Most users will not name labels starting with `$`, but a paranoid
  test or copy-pasted production code might. The translator should:
  1. Detect collision: scan `traversal.getSteps()` for any `Step.getLabels()`
     entry that starts with `$g2m_anon_` and decline if found.
  2. Or: pick a prefix that's syntactically impossible as a TinkerPop label
     (none such exists — TinkerPop accepts any String).
  3. Or: accept the collision risk and document it as a known limitation.

  Recommend (1) — a single-line scan in the walker that runs before any IR
  construction. The track description should be updated to specify this.

#### Assumption: `$g2m_v0` (Track 2's boundary alias) does not collide with Track 3's anonymous aliases
- **Track claim**: implicit — Track 3 introduces `$g2m_anon_N`, Track 2 already
  uses `$g2m_v0`.
- **Evidence search**: Read `GremlinToMatchTranslator.java`
- **Code evidence**: `BOUNDARY_ALIAS = "$g2m_v0"` @ line 93.
- **Verdict**: VALIDATED if the track 3 generator uses a counter that starts at 0
  and a separator unique to the anonymous role (e.g. `$g2m_anon_0`, not
  `$g2m_v1`). Re-using the `$g2m_` prefix without a role discriminator would risk
  human error in later tracks.
- **Detail**: track-3.md uses `$g2m_anon_N` as the example, which is good.
  Suggest making the prefix a constant in the translator (private static final
  String ANON_ALIAS_PREFIX = "$g2m_anon_") so future tracks reference it
  symbolically.

#### Assumption: the recognized walker can decline cleanly when an unrecognized step appears mid-chain
- **Track claim**: "all-or-nothing — any unrecognized step causes the whole
  traversal to decline (D3)".
- **Evidence search**: Read `GremlinToMatchStrategy.applyTranslation` and
  `MatchPatternBuilder.build`
- **Code evidence**:
  - `MatchPatternBuilder.build()` at `MatchPatternBuilder.java:158-163` is
    one-shot: it sets `built = true` and any further `addNode/addEdge/build()`
    throws `IllegalStateException`.
  - Track 1 episode confirms this: "**one-shot** — `build()` sets a `built` flag
    and any further `addNode`/`addEdge`/`build` throws `IllegalStateException`".
  - The walker iterates steps and decides "recognized" or "unrecognized"; if
    unrecognized is found mid-chain, the walker must **discard** the partially
    populated builder and return `Optional.empty()`.
- **Verdict**: VALIDATED — the one-shot contract is compatible with abort-and-
  decline because the builder is per-call (instantiated inside `translatePrefix`
  per Track 2) and abandoning it causes garbage collection.
- **Detail**: The risk is not abort itself but ordering. Some path-item walks
  populate the builder *before* checking the next step. If the walker calls
  `addEdge`, then sees an unrecognized step, the builder has work in it that's
  thrown away — fine, this is just memory. **But if the walker calls `build()`
  too early (e.g. in defensive validation), then later sees an unrecognized step,
  it cannot resume.** Track 3 must specify: walker calls `build()` exactly once,
  at the end of a successful walk, never speculatively.

#### Assumption: `out("knows", "follows")` can be encoded as a path-item with multi-label
- **Track claim**: "Multiple edge labels (`out("knows", "follows")`) → MATCH
  path-item with `IN [...]` edge filter; MATCH supports edge-label `IN` lists
  via the path-item filter mechanism."
- **Evidence search**: Read `SQLMatchPathItem.outPath/inPath/bothPath` and
  `SQLMethodCall.addParam`
- **Code evidence**:
  - `SQLMatchPathItem.graphPath` @ `SQLMatchPathItem.java:32-44` builds a
    `SQLMethodCall` whose `params` list contains exactly **one**
    `SQLBaseExpression(edgeName.getStringValue())`.
  - `SQLMethodCall.addParam` @ `SQLMethodCall.java:386` allows additional params,
    so the underlying method call is variadic and the SQL grammar accepts
    `.out('knows', 'follows')` (the existing `SQLFunctionMove.execute` accepts
    `String[] labels` at `SQLFunctionMove.java:48-57`).
  - But `MatchPatternBuilder.addEdge` only takes a **single** `String edgeLabel`
    (`MatchPatternBuilder.java:109-149`).
  - The `edgeFilter` (3rd arg) is set as the path item's target-vertex filter
    (`MatchPatternBuilder.java:99-103`), NOT as an edge predicate.
- **Verdict**: CONTRADICTED — neither claim in the track is implementable as
  written.
  - **Multi-label via `IN [...]` edge filter**: `addEdge`'s `edgeFilter` becomes
    the *target-vertex* filter, not an edge filter; an `@class IN ['knows',
    'follows']` predicate on the target vertex would not filter edges, it would
    filter the destination vertex by class.
  - **Multi-label via path-item filter**: MATCH does support `out().{class:
    'knows', as: …}` patterns with a class predicate on the edge if you write
    `outE().{...}.inV()`, but `MatchPatternBuilder.addEdge` does not expose this.
- **Detail**: Track 3 needs ONE of:
  1. Extend `MatchPatternBuilder.addEdge` to accept `String[] edgeLabels` and
     populate the underlying `SQLMethodCall.params` with multiple
     `SQLBaseExpression`s. The runtime evaluator (`SQLFunctionMove`) already
     handles the multi-label array.
  2. Decline `out("a", "b")` as unrecognized (whole traversal declines under D3).
  3. Generate N independent path items concatenated by union (this is Track 10's
     mechanism, depended on).

  Recommend (1): the simplest, mirrors what the SQL evaluator already supports.
  But it requires extending `MatchPatternBuilder` API — surface this as a Track 1
  follow-up rather than letting Track 3 hand-roll it.

#### Assumption: `g.V().out().in()` (mixed direction) translates without ambiguity
- **Track claim**: "mixed direction (out then in)" is in the verification list.
- **Evidence search**: Read `Pattern.addExpression` and `MatchPatternBuilder.addEdge`
- **Code evidence**:
  - `Pattern.addExpression` @ `Pattern.java:65-75` chains items left-to-right;
    each new item starts at the previous `nextNode`.
  - `MatchPatternBuilder.addEdge(fromAlias, toAlias, ...)` produces ONE
    `SQLMatchExpression` per call (origin = fromFilter, single item) at
    `MatchPatternBuilder.java:143-147` — each call creates a brand-new
    expression.
- **Verdict**: VALIDATED with caveat — each `addEdge` call is its own expression,
  so mixed direction works (each direction = one expression in `matchExpressions`),
  but the translator must NOT chain them implicitly. Track 3's walker for
  `g.V().out("a").in("b")` will issue:
  - `addEdge($g2m_v0, $g2m_anon_0, OUT, "a", null, null, null)`
  - `addEdge($g2m_anon_0, $g2m_anon_1, IN, "b", null, null, null)`
  
  Each one is a separate expression. The planner's `splitDisjointPatterns` will
  see them as connected (both reference `$g2m_anon_0`) and treat them as one
  pattern, which is correct.
- **Detail**: The risk surfaces if Track 3 tries to use a single call to encode
  the chain. Each hop must be its own `addEdge` call to keep the expression
  granularity correct.

### PERFORMANCE IMPLICATIONS

#### Exposure: walker visits every step on every traversal that passes the outer gates
- **Track claim**: implicit — the walker is the new gating mechanism.
- **Critical path trace**: `apply` runs on every traversal post `applyStrategies`.
  Today's gate is O(1) (size > 1 short-circuit). Tomorrow's gate is O(N) where N
  = number of steps; for each step the classifier must inspect step type, labels,
  hasContainers.
- **Blast radius**: cumulative slowdown across Cucumber's ~1900 scenarios is
  small (most traversals are short), but production LDBC workloads can have
  long step lists (10-30 steps). Adding a per-step type check introduces CPU
  overhead — each step's `getClass()` + `instanceof` chain.
- **Existing safeguards**: outer gates already filter by start step and
  hasContainers before the walker runs.
- **Residual risk**: LOW — a per-step instanceof chain over ~10 step classes is
  cheap (sub-microsecond). Track 12's perf baseline measures end-to-end and will
  catch regressions if any. Recommend keeping the classifier as a switch on
  `Class<?>` (using Java 21 pattern matching), not a long if/else chain.

### TESTABILITY & COVERAGE

#### Testability: step-recognition walker classifier
- **Coverage target**: 85% line / 70% branch
- **Difficulty assessment**: Walker has many decline branches (one per
  unrecognized step type). Coverage requires fixtures for each step class:
  `VertexStep`, `EdgeVertexStep`, `HasStep`, `OptionalStep`, `OrderGlobalStep`,
  `RangeGlobalStep`, etc.
- **Existing test infrastructure**:
  - `GremlinToMatchStrategyTest.java` (27 tests, lines 1-120 reviewed) provides
    fixture-translator harness and `GraphBaseTest` real-graph harness.
  - `GremlinToMatchTranslatorTest.java` (18 tests) exercises `translatePrefix`
    directly with synthetic traversals built via `GraphTraversalSource.V().…`.
- **Feasibility**: ACHIEVABLE — both test harnesses scale to the new step
  classes. Track 3 should add (a) walker-level unit tests that exercise the
  decline branch for every Track 4-10 step type (so when Track 4 lands, the
  decline tests *invert* into accept tests), and (b) end-to-end tests that
  assert boundary-step insertion and result equivalence.

#### Testability: path-item composition for outE+inV pairing
- **Coverage target**: 85% line / 70% branch
- **Difficulty assessment**: The peek-ahead logic creates a state machine that's
  hard to unit-test without driving real traversals.
- **Existing test infrastructure**: `GraphBaseTest` (real YTDBGraph) supports
  building traversals with chained steps.
- **Feasibility**: ACHIEVABLE — but requires care. The state machine's
  branches (saw outE / didn't see inV next / saw inV with wrong direction /
  saw bothE.otherV / saw outE.otherV which is unrecognized) are 5+ branches.
  Each must have a deterministic test.

### ROLLBACK & RECOVERY

#### Exposure: rolling back Track 3 once Tracks 4–10 depend on the walker
- **Track claim**: implicit — the walker is the load-bearing entry for 7 future
  tracks.
- **Critical path trace**: if Track 3's walker has a deep correctness bug,
  reverting Track 3's commits restores the size>1 hard-decline gate. But Track 4
  builds on Track 3's walker, so Track 4 cannot land without Track 3.
- **Blast radius**: a walker bug discovered late forces re-doing Track 3 from
  scratch; Tracks 4-N would need to be reverted and re-applied on top of the new
  walker.
- **Existing safeguards**:
  - Phase A risk review (this document) catches design-level issues before
    implementation.
  - Per-step decomposition (Track 3 expects ~5 steps) means each commit can be
    reverted individually.
  - The kill-switch `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` provides a
    runtime escape valve while a fix is in flight.
- **Residual risk**: MEDIUM — the risk shifts from "rollback is hard" to "the
  walker's contract must be right the first time". Mitigation: Track 3 should
  define the walker's interface (e.g. `WalkerStepClassifier`,
  `WalkerVisitor`) explicitly so Tracks 4-10 plug in via that interface
  without re-shaping the walker.

---

## Part 2: Findings

### Finding R1 [blocker]
**Certificate**: Assumption — multi-edge label encoding (CONTRADICTED)
**Location**: track-3.md "Multiple edge labels (`out("knows", "follows")`) → MATCH
path-item with `IN [...]` edge filter"; `MatchPatternBuilder.addEdge` @
`core/.../sql/executor/match/builder/MatchPatternBuilder.java:109-149`
**Issue**: As documented, the multi-label encoding is not implementable. The
`edgeFilter` arg of `addEdge` becomes the target-vertex filter, not an edge
predicate; the underlying `SQLMatchPathItem.outPath` accepts a single
`SQLIdentifier`, not a list. The runtime evaluator (`SQLFunctionMove`) supports
multi-label, but the IR builder does not surface that capability. Without a
fix, Track 3 either (a) implements the wrong semantics silently or (b) cannot
land the multi-label test case.

Likelihood: HIGH (the test case is in the verification list, the implementation
will hit the gap immediately). Impact: BLOCKER — every multi-label Cucumber
scenario fails or produces wrong results.
**Proposed fix**: Pick one explicitly:
1. Extend `MatchPatternBuilder.addEdge` to accept `List<String> edgeLabels`
   (overload or replace single-label form) and populate
   `SQLMethodCall.params` with multiple `SQLBaseExpression`s — surfaced as a
   small Track 1 follow-up step before Track 3's multi-label step.
2. Decline `out(>1 label)` as unrecognized for Phase 1 and document. Mark the
   "multi-label edge" line in the track verification list as Phase 2.
3. Use Track 10's union mechanism — but Track 10 lands AFTER Track 3, so this
   would invert track ordering.

Recommend (1). Update Track 3 description and add a step "extend addEdge for
multi-label" before "multi-label edge translation" step.

### Finding R2 [blocker]
**Certificate**: Exposure — `bothE().otherV()` and the path item's lack of edge
alias
**Location**: track-3.md "EdgeVertexStep (the inV()/outV() after an outE(label) /
inE(label)) — composes with the preceding VertexStep of Edge class to form one
SQLMatchPathItem with edge alias plus terminal vertex alias"; `PatternEdge` @
`core/.../sql/executor/match/PatternEdge.java:36`
**Issue**: `PatternEdge` has no alias field; `SQLMatchPathItem` does not
support edge alias. Therefore `g.V().outE("knows").as("e").inV()` cannot be
encoded — the "e" label is lost. Any downstream `select("e")` /
`path()` / `where("e", …)` would silently produce wrong results (Track 7
projection over a label that's not in the IR is supposed to decline, but
without explicit detection in Track 3 the IR just doesn't carry the label and
Track 7 has no way to tell).

Likelihood: HIGH (any Cucumber scenario that names an edge alias triggers this).
Impact: BLOCKER for test correctness — silent wrong results.
**Proposed fix**: Add a decline rule to the walker's `outE`/`inE`/`bothE`
handler: "if the `VertexStep<Edge>` has any non-empty `getLabels()`, the
traversal is unrecognized and the whole traversal declines (D3)." Document
this as a Phase 1 limitation in track-3.md description; defer "edge alias" to
Phase 2 with a follow-up note that requires extending `PatternEdge` and
`SQLMatchPathItem`.

### Finding R3 [should-fix]
**Certificate**: Assumption — anonymous alias prefix `$g2m_anon_N` cannot
collide with user labels (UNVALIDATED)
**Location**: track-3.md "anonymous intermediate vertex aliases — generated by
the translator under a private prefix (e.g. `$g2m_anon_N`) chosen to be unique
within the produced pattern"
**Issue**: TinkerPop accepts any `String` as a step label. A user who writes
`g.V().as("$g2m_anon_0").out()` would collide with the translator's anonymous
namespace. The track description does not specify how to detect or handle
collision.

Likelihood: LOW (most users don't use `$`-prefixed labels). Impact: HIGH if it
happens — alias collision in the IR causes silent wrong results.
**Proposed fix**: Add a pre-flight scan in the walker: "before generating any
anonymous alias, scan `traversal.getSteps()` for any `Step.getLabels()` entry
that startsWith `$g2m_anon_` or equals `$g2m_v0`; if found, decline the whole
traversal." This is one extra step in the walker's outer gate. Add a unit test
for the collision case.

### Finding R4 [should-fix]
**Certificate**: Exposure — top-level try/catch hides walker bugs
**Location**: `GremlinToMatchStrategy.java:174-186` (existing); track-3.md
verification section
**Issue**: The strategy's `try { … } catch (RuntimeException) { logger.warn }`
swallows any walker bug and lets the traversal run native — producing the
right result with no test failure. Cucumber asserts result equivalence, not
which path was taken. Track 3's tests may pass while the walker silently
throws on every recognized shape.

Likelihood: MEDIUM (anyone refactoring the walker may introduce a NPE).
Impact: HIGH — every Track 4-10 lands on top of an effectively non-functional
walker without anyone noticing until perf-baseline (Track 12).
**Proposed fix**: Track 3's tests must include an assertion *per recognized
shape* that "the resulting traversal contains a `YTDBMatchPlanStep`" (i.e.
translation actually happened). The harness in `GremlinToMatchStrategyTest.java`
already supports this pattern. Add a checklist line to the track-3.md
verification section: "Every recognized-shape test asserts boundary step
insertion, not just result equivalence." Optionally: add a development-mode
config flag that re-throws instead of WARN, so CI runs catch silent throws.

### Finding R5 [should-fix]
**Certificate**: Exposure — `MatchPatternBuilder.addEdge` contract — class
inference on intermediate aliases
**Location**: track-3.md edge handlers; `MatchPatternBuilder.java:99-103`
(Javadoc note about needing follow-up `addNode`)
**Issue**: When the walker translates `g.V().out("knows").has("Person",
"name", "x")` (assume Track 4 supports `has` later), the intermediate
alias `$g2m_anon_0` represents the target of `out("knows")`. The user's
`has` filter on it must reach the planner via `aliasFilters`. But
`MatchPatternBuilder.addEdge` does NOT register the target alias in
`aliasClasses` / `aliasFilters` (only `addNode` does). Without the
follow-up `addNode($g2m_anon_0, "Person", whereClause, false)`, the
filter is attached to the path item's `SQLMatchFilter` (target-vertex
filter on the path) but **not** in `aliasFilters` for the planner's
selectivity inference.

Phase 1 prior-track episode for Track 1 already noted: "call addNode
after addEdge to register target class/filters; one-shot builder". Track
3 description does not surface this.

Likelihood: HIGH (the moment Track 4 lands `has` and Track 3 chains
`addEdge` then `has`-derived `addNode`, this is the path). Impact:
MEDIUM (selectivity inference suboptimal, plan still correct but slower).
**Proposed fix**: Track 3 description amended to surface: "After every
`addEdge(from, to, …)` for an intermediate or terminal alias, the
walker MUST also call `addNode(to, className, where, optional)` so the
target alias reaches `aliasClasses` and `aliasFilters`. Pass `null` for
className/where if not yet known; later `has` handlers in Track 4
update the alias via `addNode`'s merge-not-replace contract."
Add a unit test that verifies a simple chain produces the expected
`aliasClasses` and `aliasFilters` content.

### Finding R6 [should-fix]
**Certificate**: Exposure — step-recognition walker becomes load-bearing for
7 future tracks
**Location**: track-3.md "Gate replacement" paragraph
**Issue**: Track 3 introduces a walker contract used by Tracks 4-10 but the
description does not pin the walker's interface. Tracks 4-10 may shape the
walker differently if Track 3 leaves the contract implicit; risk of churning
the walker's API across tracks.

Likelihood: HIGH (the description itself says the walker is "load-bearing for
Tracks 4-10"). Impact: MEDIUM — re-shaping the walker mid-Phase-1 forces
test rewrites.
**Proposed fix**: Track 3 should explicitly carve out a stable interface
before Track 4 lands. Suggested structure:
- A `StepClassifier` enum / sealed type with `RECOGNIZED_VERTEX_STEP`,
  `RECOGNIZED_EDGE_VERTEX_STEP`, `UNRECOGNIZED`, etc.
- A `WalkerContext` record holding the current "node-under-construction"
  alias, the builder, the projection assembler.
- A switch on step class (Java 21 pattern matching) returning a
  `Recognition` result.

Adding a "Walker contract" sub-section to track-3.md with the interface
sketch makes Tracks 4-10's plug-in points obvious and stable.

### Finding R7 [suggestion]
**Certificate**: Assumption — anonymous alias prefix is consistent with Track 2
**Location**: `GremlinToMatchTranslator.java:93` (`BOUNDARY_ALIAS = "$g2m_v0"`);
track-3.md anonymous alias prefix `$g2m_anon_N`
**Issue**: Track 2 uses `$g2m_v0` as a hard-coded boundary alias. Track 3
introduces a separate anonymous-alias namespace `$g2m_anon_N`. They use the
same root prefix `$g2m_` but different role discriminators (`v` vs `anon`).
The naming is correct but easy to confuse — a future track may use `$g2m_v1`
as an "anonymous next vertex" by mistake, colliding with the Track 2 boundary
naming if extended.

Likelihood: LOW. Impact: LOW.
**Proposed fix**: Move both to private static final constants in the
translator and document the role separation:
- `BOUNDARY_ALIAS_PREFIX = "$g2m_v"` for explicit boundary-emitted aliases
  (numbered v0, v1, v2 for tracks that emit multiple result columns).
- `ANONYMOUS_ALIAS_PREFIX = "$g2m_anon_"` for intermediate hops the user
  cannot reference.

### Finding R8 [suggestion]
**Certificate**: Assumption — anonymous aliases bypass `RETURN $elements`
filter
**Location**: `ReturnMatchElementsStep.java:44`,
`ReturnMatchPatternsStep.java:51` (uses `DEFAULT_ALIAS_PREFIX` to filter)
**Issue**: The MATCH return-elements / return-patterns steps filter out
aliases that startsWith `MatchExecutionPlanner.DEFAULT_ALIAS_PREFIX` so that
auto-generated intermediates aren't emitted. The translator's `$g2m_anon_*`
aliases are not auto-generated by the planner (the planner's
`assignDefaultAliases` doesn't run on the `MatchPlanInputs` ctor path), so
they will NOT be filtered by these checks. Phase 1 doesn't use `RETURN
$elements` / `RETURN $patterns`, but Track 7 (projections) and Track 9
(aggregations) may.

Likelihood: LOW for Phase 1; MEDIUM for Phase 2. Impact: MEDIUM if it
surfaces — anonymous intermediates leak into result rows.
**Proposed fix**: Add a note to track-3.md (and propagate to Tracks 7, 9):
"If the recognized return shape includes `$elements` / `$patterns`, the
ReturnMatch{Elements,Patterns,PathElements}Step filter on
`DEFAULT_ALIAS_PREFIX` does not catch translator-generated `$g2m_anon_`
aliases. The walker must explicitly project only user-named aliases, never
anonymous ones." Optionally extend the filter to also recognize
`$g2m_anon_` — small addition to those return steps.

### Finding R9 [suggestion]
**Certificate**: Exposure — walker performance per-traversal
**Location**: track-3.md walker section
**Issue**: The walker iterates every step on every translatable traversal.
Per-step `instanceof` chain over ~10 step classes is cheap but cumulative
across long step lists.
**Proposed fix**: Use Java 21 pattern-matching `switch` on `Step` for the
classifier (single dispatch table) rather than a chain of `instanceof`. Track
12's perf baseline will catch any regression but pattern-matching switch is
the canonical readable choice for a 10-class dispatch.

### Finding R10 [suggestion]
**Certificate**: Assumption — builder one-shot contract incompatible with
mid-walk speculative `build()`
**Location**: `MatchPatternBuilder.build()` @ `MatchPatternBuilder.java:158-163`
**Issue**: If a Track 3 implementer defensively calls `build()` early for
inspection, it cannot resume. The walker must call `build()` exactly once at
the end of a successful walk.
**Proposed fix**: Add a Javadoc note to the walker's contract: "MatchPatternBuilder
is one-shot; the walker must call `build()` only after every step has been
classified as recognized." Add a unit test that drives a partial walk and
verifies abandoning the builder is safe.

### Finding R11 [suggestion]
**Certificate**: Testability — fixture for each recognized shape
**Location**: track-3.md verification list
**Issue**: The verification list is informal. Tracks 4-10 will each need to
add tests for "translate succeeded" *and* "decline cleanly when an
unrecognized step is mixed in".
**Proposed fix**: Track 3 should establish a parameterized test pattern
("for each shape, assert YTDBMatchPlanStep present + equivalent result vs
SQL MATCH"). A reusable JUnit Parameterized harness in
`GremlinToMatchStrategyTest.java` or a sibling test class would let
Tracks 4-10 simply add a fixture row for each new shape. Minor — but
saves duplication and makes the recognized set's growth visible.

---

## Summary

11 findings.

- **blocker**: 2 (R1 multi-label encoding contradicted, R2 edge alias gap)
- **should-fix**: 4 (R3 anon-alias collision, R4 try/catch hides bugs, R5
  intermediate alias class registration, R6 walker contract stability)
- **suggestion**: 5 (R7 alias prefix consistency, R8 anonymous alias leak via
  return-elements filter, R9 pattern-matching switch, R10 builder one-shot
  walker compatibility, R11 reusable test harness)
- **skip**: 0 — Track 3 is essential.
