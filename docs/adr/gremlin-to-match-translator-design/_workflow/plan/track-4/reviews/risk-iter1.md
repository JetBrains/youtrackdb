<!-- MANIFEST
findings: 6   severity: {blocker: 0, should-fix: 5, suggestion: 1}
index:
  - {id: R1, sev: should-fix, loc: "track-4.md:59", anchor: "### R1 ", cert: A1, basis: "polymorphic-mode hasLabel semantics are an unresolved Phase-4 fork; the design doc's schema-polymorphism section is stale (deleted MatchClassFilters) and the subclass-inclusive claim is unvalidated against native"}
  - {id: R2, sev: should-fix, loc: "SQLMatchesCondition.java:166", anchor: "### R2 ", cert: E2, basis: "find-mode flag / new SQLEndsWithCondition must survive copy() and toGenericStatement(); plan is deep-copied on every clone()/cache-get, so a missed field is a silent wrong-result bug"}
  - {id: R3, sev: should-fix, loc: "track-4.md:49", anchor: "### R3 ", cert: A2, basis: "track inlines RIDs, design.md binds them as params — direct contradiction; wrong choice loses the RID fast path, right choice yields no D5 cache reuse for id-anchored shapes"}
  - {id: R4, sev: should-fix, loc: "track-4.md:47", anchor: "### R4 ", cert: E3, basis: "manageNotPatterns throw is caught by apply()'s RuntimeException net -> native decline, not the 'hard query failure' the track states; premise inaccuracy could mis-shape the mitigation"}
  - {id: R5, sev: suggestion, loc: "SQLContainsTextCondition.java:42", anchor: "### R5 ", cert: E1, basis: "collate transform on the shared node changes existing SQL CONTAINSTEXT semantics on ci-collated properties, not only Gremlin"}
  - {id: R6, sev: should-fix, loc: "track-4.md:49", anchor: "### R6 ", cert: E4, basis: "D5 correctness pivots on deterministic walk-to-slot ordering and structural/value classification; failures are silent wrong-plan/wrong-value and hard to test"}
evidence_base: {section: "## Evidence base", certs: 7, matches: 7}
cert_index:
  - {id: E1, verdict: EXPOSED, anchor: "#### E1 "}
  - {id: E2, verdict: EXPOSED, anchor: "#### E2 "}
  - {id: E3, verdict: SAFEGUARDED, anchor: "#### E3 "}
  - {id: E4, verdict: EXPOSED, anchor: "#### E4 "}
  - {id: A1, verdict: UNVALIDATED, anchor: "#### A1 "}
  - {id: A2, verdict: CONTRADICTED, anchor: "#### A2 "}
  - {id: T1, verdict: MIXED, anchor: "#### T1 "}
flags: [CONTRACT_OK]
-->

# Track 4 risk review — iteration 1 (reviewer-risk, Phase 3A)

Track 4 already passed the Phase A technical review; this pass is the risk lens
only. No blockers. The strategy's eager-plan-build-inside-a-`RuntimeException`-net
architecture caps the worst case at a native decline for most translator faults,
so the residual risks are correctness-silent (wrong results, no error) rather than
crash-or-abort. Six findings: five should-fix, one suggestion. The two highest-value
ones are unresolved-design pivots (polymorphic `hasLabel`, RID inline-vs-param) that
should be pinned at decomposition, before code, because their wrong branch surfaces
only at the Track 6 Cucumber gate.

## Findings

### R1 [should-fix]
**Certificate**: A1 (Assumption — polymorphic-mode `hasLabel` matches subclasses)
**Location**: track-4.md:59 (Validation) and :36/:44 (`~label` → `classEquals` gated on `ctx.polymorphic()==false`); design.md §"Schema polymorphism" (1545–1600); Track 3 `## Surprises & Discoveries` BC2 entry
**Issue**: The track pins `hasLabel(L)` non-polymorphic narrowing to `MatchWhereBuilder.classEquals` (exact `@class = 'L'`) and asserts that in *polymorphic* mode `hasLabel(L)` "still matches subclasses of L (via decline-to-native or a subclass-inclusive predicate)". That polymorphic branch is one of the two explicitly-open Phase-4 reconciliation items (schema-polymorphism BC2), and the design section it would lean on is stale: it still prescribes the `aliasClasses[a]=label` slot and the `MatchClassFilters` helper that Track 3's rework deleted, plus the chain-target narrowing Track 3 removed *because it replayed the BC2 undercount*. Worse, the subclass-inclusive claim is unvalidated against native: a TinkerPop `HasStep(~label=L)` filters on the element's leaf label (in YTDB, the leaf class), which is leaf-exact regardless of `polymorphicQuery`. If that is the real native behavior, `classEquals` is correct in *both* modes and a subclass-inclusive predicate would *over-match*. Likelihood the current wording is implemented literally: high (it is in the Validation section as an acceptance criterion). Impact: either the implementer emits a subclass-enumerating `@class IN [...]` (needs a schema read, must be D5-cache-invalidated on schema change, over-matches if native is leaf-exact) or blanket-declines polymorphic `hasLabel` — and since `polymorphic=true` is the default and `hasLabel` is the most common filter, a blanket decline leaves the feature inert for its most common shape. The mismatch surfaces at the Track 6 Cucumber re-run, far downstream, forcing rework.
**Proposed fix**: At decomposition, empirically pin native `hasLabel(L)` membership on subclassed data under both `polymorphic` values (run translator-off, observe), then choose one concrete branch and write it into the track. Add a polymorphic-vs-non-polymorphic equivalence test on a `Person`/`Employee` hierarchy as a Track-4 gate (not deferred to Track 6). If native is leaf-exact, drop the "subclass-inclusive" wording and use `classEquals` unconditionally; if it is hierarchy-aware, pin the exact predicate shape and its cache-invalidation path. Do not implement against the stale design §"Schema polymorphism".

### R2 [should-fix]
**Certificate**: E2 (Exposure — new/modified AST nodes deep-copied on every clone/cache-get)
**Location**: SQLMatchesCondition.java:166 (`copy()`), :276 (`splitForAggregation()`), :113 (`toGenericStatement()`); new `SQLEndsWithCondition`; track-4.md:46, :80; YTDBMatchPlanStep.java:424 (`plan.copy` in `clone()`); YqlExecutionPlanCache.java:138 (`result.copy(ctx)`)
**Issue**: D-TEXT-OPS adds a find-mode `boolean` to `SQLMatchesCondition` and a whole new `SQLEndsWithCondition` node. `SQLMatchesCondition.copy()` and `splitForAggregation()` re-create the node field-by-field by hand; a new `findMode` field that either method forgets to carry is silently dropped. The boundary step deep-copies its plan on every `clone()` (YTDBMatchPlanStep.clone → `plan.copy(isolatedCtx)`), and TinkerPop clones a traversal once per execution, so a lost `findMode` reverts the regex from `Matcher.find()` (TinkerPop partial-match) to `matches()` (whole-string) on essentially every run — wrong results, no exception. The same field must also appear in `toGenericStatement()` or the D5 fingerprint cannot distinguish find-mode from whole-string. `SQLEndsWithCondition` inherits the full ~25-method `SQLBooleanExpression` contract (`copy`, `toGenericStatement`, `evaluate`×2, `supportsBasicCalculation`, `splitForAggregation`, `equals`/`hashCode`, …); a missed `copy()` NPEs or empties the node on the first clone. Likelihood: high for a hand-written node in a generated-file package. Impact: silent wrong results for `regex`/`endingWith` predicates.
**Proposed fix**: Add a copy-round-trip unit test per new/modified node asserting `findMode` (and every field) survives `copy()` and `splitForAggregation()`, and a `toGenericStatement()` test asserting the comparison value renders as `PARAMETER_PLACEHOLDER` while the operator identity is preserved. Treat `copy()` propagation as the acceptance gate for the AST work, ahead of the equivalence tests.

### R3 [should-fix]
**Certificate**: A2 (Assumption — RID argument binding)
**Location**: track-4.md:49 and :36 ("RIDs for `@rid IN` ... must not parameterize"); design.md §"Parameter binding" 1283–1286 ("RID arguments" listed as "Bound, out of the key"); MatchExecutionPlanner.java:4758 (`promoteStaticRidsFromFilters`), :4676 (additive-path promotion)
**Issue**: The track file (Plan of Work item 7) says structural tokens including RIDs stay inline and must not parameterize, "since a structural token bound as a param would serve a wrong plan". The design doc says the opposite: "RID arguments" are "Bound, out of the key ... high cardinality that would otherwise thrash the cache". This is a direct, load-bearing contradiction. `promoteStaticRidsFromFilters` promotes `@rid =`/`@rid IN` out of `aliasFilters` into `aliasPinnedRids` to drive root selection and the direct-RID fetch, and it skips anything not early-calculable — a `?` placeholder RID would not promote, so following the design (parameterize RIDs) silently loses the RID fast path and turns every `g.V(id)` into a class scan (severe regression). The track's (correct) inline choice has its own cost: `g.V(ids).has(k,v)` fingerprints vary per id set, so D5 provides no plan reuse for the most common id-anchored lookup and can fill with single-use cache entries. Likelihood the design wording misleads implementation or Phase-4 reconciliation: medium. Impact: either a fast-path regression or a mis-tuned cache.
**Proposed fix**: Reconcile design.md §"Parameter binding" to the track's inline-RID decision (move RIDs from "Bound" to "In the key / inline"). Add a test asserting an inlined-RID shape still takes the direct-RID fetch (not a class scan), and decide deliberately whether RID-bearing shapes bypass `GremlinPlanCache` (avoid single-use-entry thrash) or are cached with the RID in the key.

### R4 [should-fix]
**Certificate**: E3 (Exposure — `manageNotPatterns` throw under the strategy's safety net)
**Location**: track-4.md:47 and :83 ("throws a hard query failure (not a native decline)"); MatchExecutionPlanner.java:627 (`manageNotPatterns` in `createExecutionPlan`), :760/:766 (the two throw conditions); GremlinToMatchStrategy.java:208–225 (`catch (RuntimeException)` net), :376/:390 (eager `buildPlan`→`createExecutionPlan`); CommandExecutionException → CoreException → BaseException → RuntimeException
**Issue**: The track repeatedly frames the NOT decline conditions as guarding against a *hard query failure* from `manageNotPatterns`. In this pipeline the plan is built eagerly at strategy-application time — `apply()` → `applyTranslation` → `buildPlan` → `createExecutionPlan`, and `manageNotPatterns` is Phase 6 of `createExecutionPlan`. That whole call runs inside `apply()`'s `catch (RuntimeException)` net, and `CommandExecutionException` is a `RuntimeException`, so a mis-predicted NOT degrades to a clean native decline, not a user-facing failure. The premise is therefore inaccurate. That *lowers* the true blast radius, but two residual risks remain: (a) the inaccurate rationale can push the decomposer to over-engineer or mis-locate the decline logic, and (b) the graceful degradation depends on the plan staying eager-built at `apply()` time — a later refactor that defers plan build to stream-open (`processNextStart`) would move the throw outside the net and make it a genuine hard failure. Likelihood of a mid-implementation mis-design from the wrong premise: medium.
**Proposed fix**: Correct the track wording — a NOT shape that trips `manageNotPatterns` currently declines to native via the safety net, and the recogniser-side decline is defense-in-depth (avoid wasted plan-build; stay correct if plan-build ever goes lazy), not the sole barrier. Add a negative test asserting the two disqualifying NOT shapes (origin alias absent from the positive pattern; origin path-item carrying a WHERE) run on native with no exception surfaced.

### R5 [suggestion]
**Certificate**: E1 (Exposure — collate transform on the shared `SQLContainsTextCondition`)
**Location**: SQLContainsTextCondition.java:42/:63 (`indexOf` with no collate today); track-4.md:46/:80 (collate transform, "making SQL `CONTAINSTEXT` collation-aware too"); design.md 1085–1089; callers FetchFromIndexStep, SelectExecutionPlanner, QueryOperatorContainsText
**Issue**: `SQLContainsTextCondition` is the runtime for SQL/GQL `CONTAINSTEXT`, not a Gremlin-private node. Adding a field-collate transform to its `evaluate()` methods changes existing SQL `CONTAINSTEXT` results on `ci`-collated string properties (case-sensitive → case-insensitive); it is a no-op on the `default` collation. This is an observable behavior change to existing SQL queries whose blast radius is the SQL/GQL front-ends, not only translated Gremlin. Likelihood of surprising an existing SQL user with a `ci` property: low-to-medium; impact: a query silently returns more rows than before.
**Proposed fix**: Add an SQL-side regression test for `CONTAINSTEXT` on both a `default` and a `ci` property (pinning no-op vs case-insensitive), and record the change in design.md §"Observable behavior changes" so it is a documented, tested decision rather than an incidental side effect of the Gremlin work.

### R6 [should-fix]
**Certificate**: E4 (Exposure — `GremlinPlanCache` D5 correctness) and T1 (Testability)
**Location**: track-4.md:49 (D5 wiring), :66 (cache acceptance line); design.md §"Parameter binding" 1281–1321; YqlExecutionPlanCache.java:138 (copy-on-get pattern to mirror); GremlinToMatchStrategy.java:130–137 (planner internal `useCache` tied to a null `statement`)
**Issue**: D5 is a new cache layer whose correctness rests on two silent-failure invariants. First, slot ordering: on a cache hit the walk still runs to collect predicate values, and those values must land in exactly the positional-slot order the cached plan's `?` placeholders expect; any drift in recogniser dispatch order or AND-composition ordering desyncs slot→value and serves a cached plan the *wrong* values, with no error. Second, the structural/value split must be exact — a structural token (`classEquals` class name, `~label`, an inlined RID) leaking into a param serves a wrong plan; a high-cardinality value leaking into the key thrashes. There is no existing MATCH plan-cache-with-params path to inherit behavior from (the additive planner ctor runs `useCache=false` because it leaves `statement` null), so the wrapping cache, its copy-on-get, and its schema-change invalidation are all new surface. Both failure modes are wrong-result-without-exception, the hardest class to catch. Likelihood: medium; impact: high (silently wrong query results served from cache).
**Proposed fix**: Add a canonical D5 test that issues the same traversal shape with several distinct value sets and asserts (a) exactly one plan is compiled/cached and reused, (b) each value set yields the correct multiset (equivalence vs translator-off), and (c) a `CREATE CLASS`/`CREATE INDEX` flushes the Gremlin plan. Add a slot-order determinism assertion (same shape → same generic-statement fingerprint → same slot count/order) and a guard test that a structural token never renders as `PARAMETER_PLACEHOLDER`. The existing `EdgeTraversalEquivalenceTest`/`GraphBaseTest` harness (real graph, translator-on-vs-off, sorted-RID multiset compare) is the right base to extend.

## Evidence base

#### E1 Exposure: collate transform on shared `SQLContainsTextCondition`
- **Track claim**: track-4.md:46/:80 — add a collate transform to `SQLContainsTextCondition`, "making SQL `CONTAINSTEXT` collation-aware too".
- **Critical path trace**:
  1. `SQLContainsTextCondition.evaluate(Result, ctx)` @ SQLContainsTextCondition.java:46 → `((String) leftValue).indexOf((String) rightValue) > -1` @ :63 — no collation applied today.
  2. Reached from SQL/GQL execution via `FetchFromIndexStep`, `SelectExecutionPlanner`, and the `QueryOperatorContainsText` operator (grep of `core/src/main/java`, non-parser callers).
  3. Track 4 inserts a field-collate transform on both operands before `indexOf`.
- **Blast radius**: every existing SQL/GQL `CONTAINSTEXT` query on a `ci`-collated String property changes from case-sensitive to case-insensitive (no-op on `default`). Gremlin `containing` is the intended new caller, but the node is shared.
- **Existing safeguards**: none specific — the change is to the shared runtime evaluator; no flag gates SQL vs Gremlin use of the node.
- **Residual risk**: LOW-MEDIUM — behavior change is confined to `ci` properties and is arguably the intended consistency with `=`/`LIKE`, but it is an untested, undocumented SQL-side change unless a regression test and an observable-behavior-change note are added.

#### E2 Exposure: find-mode flag / new node through `copy()` and `toGenericStatement()`
- **Track claim**: track-4.md:46/:80 — find-mode flag on `SQLMatchesCondition`; new `SQLEndsWithCondition` node; both reachable programmatically (no grammar change).
- **Critical path trace**:
  1. `SQLMatchesCondition.copy()` @ :166 and `splitForAggregation()` @ :276 rebuild the node field-by-field; `toGenericStatement()` @ :113 renders the D5 fingerprint.
  2. `YTDBMatchPlanStep.clone()` @ YTDBMatchPlanStep.java:424 calls `plan.copy(isolatedCtx)`, which deep-copies the whole AST; TinkerPop clones a traversal once per execution (design §"Boundary-step lifecycle" 1892–1901).
  3. `YqlExecutionPlanCache.getInternal` @ :138 also copies on every get; a `GremlinPlanCache` mirroring it copies too.
- **Blast radius**: a `findMode` field dropped by `copy()`/`splitForAggregation()` silently reverts regex to whole-string `matches()`; a `toGenericStatement()` omission collides find-mode with whole-string in the fingerprint; a new-node `copy()` gap NPEs/empties on the first clone.
- **Existing safeguards**: the hand-copy pattern is visible in the sibling `SQLContainsTextCondition`/`SQLMatchesCondition` files as a template, but nothing enforces field completeness.
- **Residual risk**: MEDIUM-HIGH — near-certain to bite (copy runs on essentially every execution) if the field is missed, and the failure is silent wrong results.

#### E3 Exposure: `manageNotPatterns` throw under the strategy's `RuntimeException` net
- **Track claim**: track-4.md:47/:83 — NOT declines "when the first NOT alias is absent ... or when that alias would carry a WHERE filter — `MatchExecutionPlanner.manageNotPatterns` throws a hard query failure (not a native decline) in both cases."
- **Critical path trace**:
  1. `GremlinToMatchStrategy.apply` @ :208 wraps `applyOrDecline` in `try { } catch (RuntimeException e) { declineOnThrow(...) }` @ :217–225.
  2. `applyTranslation` @ :376 → `buildPlan` @ :390 → `new MatchExecutionPlanner(inputs).createExecutionPlan(ctx, false, useCache=false)` — eager, at `apply()` time.
  3. `createExecutionPlan` Phase 6 → `manageNotPatterns` @ MatchExecutionPlanner.java:627, which throws `CommandExecutionException` @ :761/:767.
  4. `CommandExecutionException extends CoreException extends BaseException extends RuntimeException` — so the throw is caught at :217 and degrades to a native decline.
- **Blast radius**: contrary to the claim, a mis-predicted NOT is a clean decline, not a user-facing failure — unless a future refactor defers plan build to `processNextStart` (outside the net), which would make it a real hard failure.
- **Existing safeguards**: the strategy's `RuntimeException` net (deliberate, documented at GremlinToMatchStrategy.java:84–117) plus eager plan build at `apply()` time.
- **Residual risk**: LOW for the shipped behavior; the risk is the inaccurate premise mis-shaping the mitigation and the fragility to a lazy-build refactor.

#### E4 Exposure: `GremlinPlanCache` (D5) slot ordering and structural/value classification
- **Track claim**: track-4.md:49 — bind predicate values as `SQLPositionalParameter` slots, key on the value-independent fingerprint, one plan per shape; structural tokens stay inline.
- **Critical path trace**:
  1. Recogniser/adapter call `RecognitionContext.bindParam(value)` → allocate slot + record value in a per-walk map.
  2. Built IR renders via `SQLPositionalParameter.toGenericStatement` → `PARAMETER_PLACEHOLDER`; the fingerprint is the generic statement (design §"Parameter binding" 1293–1300).
  3. Boundary step installs the per-walk map via `ctx.setInputParameters(map)` (CommandContext.java:106); each slot resolves via `SQLPositionalParameter.getValue(params)` at run time.
  4. On a cache hit the plan is reused but the walk still runs to collect values — the value order must match the cached plan's slot order.
- **Blast radius**: slot desync → cached plan served the wrong values (silent wrong result); structural token bound as param → wrong plan (silent); value leaked into key → cache thrash (perf). The layer, its copy-on-get, and its invalidation are all new (no existing params-cache path — additive ctor forces `useCache=false`, GremlinToMatchStrategy.java:130–137).
- **Existing safeguards**: `YqlExecutionPlanCache` provides a copy-on-get + schema-invalidation template to mirror; the equivalence harness can compare multisets.
- **Residual risk**: MEDIUM — the correctness invariants are silent-failure and untested by construction until D5-specific tests are added.

#### A1 Assumption: polymorphic-mode `hasLabel(L)` matches subclasses of L
- **Track claim**: track-4.md:59 — "in polymorphic mode it still matches subclasses of L (via decline-to-native or a subclass-inclusive predicate)".
- **Evidence search**: grep + Read of design.md §"Schema polymorphism" (1545–1600), Track 3 `## Surprises & Discoveries` (BC2 entry), `MatchWhereBuilder.classEquals` (:65–76, exact `@class =`), `WalkerContext.polymorphic` doc (:67–77). PSI find-usages of `classEquals` deferred — grep shows no production caller yet (Track 4 is first), consistent with the track. Reference-accuracy caveat: native `hasLabel` runtime membership was read from design/TinkerPop semantics, not executed.
- **Code evidence**: `classEquals` emits exact `@class = 'L'`; the design section prescribing subclass handling references the deleted `MatchClassFilters` and the chain-target narrowing Track 3 removed as BC2 — i.e., it is stale. Native `HasStep(~label)` filters on leaf label (leaf class in YTDB), which is leaf-exact regardless of `polymorphicQuery`.
- **Verdict**: UNVALIDATED
- **Detail**: whether polymorphic `hasLabel` is subclass-inclusive is unresolved (open Phase-4 item) and possibly contradicted by native leaf-exact label semantics. The branch must be pinned empirically before implementation; the design section must not be used as-is.

#### A2 Assumption: RID argument binding (inline vs positional parameter)
- **Track claim**: track-4.md:49 — RIDs stay inline and must not parameterize.
- **Evidence search**: Read of design.md §"Parameter binding" 1283–1286 (RID arguments listed as "Bound, out of the key") vs track-4.md:49; Read of `promoteStaticRidsFromFilters` (MatchExecutionPlanner.java:4758–4790) and the additive-path promotion at :4676.
- **Code evidence**: `promoteStaticRidsFromFilters` requires `filter.findRidEquality()` early-calculable (`isEarlyCalculated(ctx)` @ :4778) to promote into `aliasPinnedRids`; a `?` placeholder RID would not promote, forfeiting the direct-RID fetch and the collapsed root estimate.
- **Verdict**: CONTRADICTED (track vs design conflict; the track's inline choice is the performance-correct one, the design text is wrong/stale).
- **Detail**: following design.md (parameterize RIDs) regresses the RID fast path to a class scan; the track's inline choice is correct but yields no D5 reuse for id-anchored shapes and risks single-use cache entries.

#### T1 Testability: equivalence harness present; D5 silent-failure hard to test
- **Coverage target**: 85% line / 70% branch.
- **Difficulty assessment**: predicate/logical-filter translation is directly testable via the existing translator-on-vs-off multiset-equivalence harness. D5 caching is the hard part: slot desync and structural/value misclassification produce wrong results with no exception, so coverage of the happy path does not prove correctness.
- **Existing test infrastructure**: `EdgeTraversalEquivalenceTest` extends `GraphBaseTest` (real graph, translator flag toggled, boundary-step engagement + sorted-RID multiset compare) @ EdgeTraversalEquivalenceTest.java:1–60 — explicitly designed for Tracks 4–6 to extend. `GremlinPredicateAdapterTest`, `MatchWhereBuilderTest`, `MatchYqlExecutionPlanCacheTest`/`YqlExecutionPlanCacheTest` provide unit-level bases. The track already names the `SubTraversalPredicateAdapter.decline_doesNotCommitPartialStateToOuterContext` no-mutation pin (item 6).
- **Feasibility**: ACHIEVABLE for predicates/logical filters; DIFFICULT for D5 correctness without the dedicated slot-order / structural-token / invalidation tests in R6.
- **Detail**: the coverage numbers are reachable; the gap is behavioral (silent D5 wrong-result), addressed by R2 and R6's specific tests, not by line coverage.
