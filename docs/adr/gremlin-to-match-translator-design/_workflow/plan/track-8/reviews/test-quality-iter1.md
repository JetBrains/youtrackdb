<!-- MANIFEST
findings: 13   severity: {blocker: 0, should-fix: 8, suggestion: 5}
index:
  - {id: TB1, sev: should-fix, loc: GremlinToMatchStrategyTest.java:771, anchor: "### TB1 ", cert: C1, basis: "multi-plan cacheEligible normalization (DR-U5) is asserted only through a factory that hardcodes false, so deleting the normalization keeps the test green"}
  - {id: TB2, sev: should-fix, loc: UnionTraversalEquivalenceTest.java:170, anchor: "### TB2 ", cert: C2, basis: "union limit fixture yields a one-row concatenation, so limit(2) never truncates and the Range arithmetic / early-stop is unfalsifiable"}
  - {id: TB3, sev: should-fix, loc: YTDBQueryMetricsStrategyTest.java:563, anchor: "### TB3 ", cert: C3, basis: "two metrics tests were converted to if/else on which source step the product happens to use; both branches self-fulfil and the named contract is no longer pinned"}
  - {id: TB4, sev: suggestion, loc: GremlinToMatchStrategyTest.java:899, anchor: "### TB4 ", cert: C4, basis: "test claims the second apply reuses the cached plan but only re-asserts contains(fingerprint), and identical children cannot show per-child fingerprints"}
  - {id: TB5, sev: suggestion, loc: MultiPlanMatchStepTest.java:383, anchor: "### TB5 ", cert: C5, basis: "rewindPlan context fidelity verified with reset(any()), so passing the coordinator context instead of the child's own would still pass"}
  - {id: TC1, sev: should-fix, loc: MultiPlanMatchStep.java:359, anchor: "### TC1 ", cert: C6, basis: "countConcatStream (count after limit/dedup) is a documented supported shape with zero test callers; only the push-down path is reached"}
  - {id: TC2, sev: suggestion, loc: MultiPlanMatchStep.java:281, anchor: "### TC2 ", cert: C7, basis: "no union test drives a zero-row child; all-empty arming and the sumChildCountStreams empty-child branch are unexercised"}
  - {id: TC3, sev: should-fix, loc: UnionStepRecogniser.java:87, anchor: "### TC3 ", cert: C8, basis: "the ResultShaping leg of the DR-U3 agreement gate is never isolated; the one mismatch test disagrees on all three legs at once"}
  - {id: TC4, sev: should-fix, loc: UnionTraversalEquivalenceTest.java:234, anchor: "### TC4 ", cert: C9, basis: "per-child positional parameters (technical T3) have no end-to-end test; only a mocked-context wiring test built by the test itself"}
  - {id: TC5, sev: should-fix, loc: MatchExecutionPlanner.java:801, anchor: "### TC5 ", cert: C10, basis: "the widened filtered-count short-circuit has no regression test on a populated class; the only filtered-count test runs where correct and false-positive answers coincide"}
  - {id: TC6, sev: suggestion, loc: YTDBQueryMetricsStep.java:98, anchor: "### TC6 ", cert: C11, basis: "the MultiPlanMatchStep branch of capturedExecutionPlan() has no test; no metrics test uses union"}
  - {id: TC7, sev: suggestion, loc: UnionStepRecogniser.java:57, anchor: "### TC7 ", cert: C12, basis: "union arity boundaries untested end to end: N=1, N>=3, zero children, and the null-fork-host sub-walk decline"}
  - {id: TC8, sev: should-fix, loc: MultiPlanMatchStep.java:423, anchor: "### TC8 ", cert: C13, basis: "SkipExecutionStream has no test caller; post-union skip / range(low>0,high) / unbounded-high shapes are never exercised"}
evidence_base: {section: "## Evidence base", certs: 15, matches: 13}
cert_index:
  - {id: C1,  verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2,  verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3,  verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4,  verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5,  verdict: CONFIRMED, anchor: "#### C5 "}
  - {id: C6,  verdict: CONFIRMED, anchor: "#### C6 "}
  - {id: C7,  verdict: PARTIAL,   anchor: "#### C7 "}
  - {id: C8,  verdict: CONFIRMED, anchor: "#### C8 "}
  - {id: C9,  verdict: CONFIRMED, anchor: "#### C9 "}
  - {id: C10, verdict: CONFIRMED, anchor: "#### C10 "}
  - {id: C11, verdict: CONFIRMED, anchor: "#### C11 "}
  - {id: C12, verdict: CONFIRMED, anchor: "#### C12 "}
  - {id: C13, verdict: CONFIRMED, anchor: "#### C13 "}
  - {id: C14, verdict: REFUTED,   anchor: "#### C14 "}
  - {id: C15, verdict: REFUTED,   anchor: "#### C15 "}
flags: [CONTRACT_OK, PSI_UNAVAILABLE]
-->

## Findings

### TB1 [should-fix] The DR-U5 "carrier is never cached" invariant is asserted through a factory that hardcodes the answer

`multiPlanTranslation_isNotCacheEligible` passes whether or not the production normalization exists, so the invariant it names is unguarded.

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinToMatchStrategyTest.java`, method `multiPlanTranslation_isNotCacheEligible` (line 771)
- **Production code**: `GremlinToMatchTranslator.java`, the compact constructor's `if (multiPlan) { cacheEligible = false; }` (line 155), and the `multiPlan(...)` factory (line 164)
- **Issue**: The test builds its carrier through `fixtureMultiPlanTranslation` (line 85), which calls `TranslationResult.multiPlan(...)`. That factory passes the literal `false` for `cacheEligible` into the canonical constructor. The assertion `assertThat(translation.cacheEligible()).isFalse()` therefore reads back a value the fixture supplied, not a value the production code normalized.
- **Evidence**: C1
- **Missing behavior**: The compact constructor must coerce a caller-supplied `true` to `false` for any multi-plan carrier. This is the exact residual the Step 2b dim-review flagged as a suggestion ("`multiPlan(..., cacheEligible)` still accepts a `true` flag that the multi-plan strategy path ignores"), and Step 3 landed without closing it. Deleting line 155 leaves every test in the track green while re-opening the door to a single shared cache entry for an N-plan union.
- **Suggested fix**:
  ```java
  /** DR-U5: an N-plan carrier is never one cache entry, even when the caller asks for it. */
  @Test
  public void multiPlanCarrier_coercesCacheEligibleToFalse_whenCallerPassesTrue() {
    var child = MatchPlanInputs.builder(new Pattern()).build();
    var carrier =
        new GremlinToMatchTranslator.TranslationResult(
            null,
            List.of(child),
            List.of(Map.of()),
            List.of(true),
            List.of(),
            "v",
            BoundaryOutputType.ELEMENT,
            Vertex.class,
            Map.of(),
            /* cacheEligible */ true, // caller asks for caching …
            ResultShaping.NONE);

    assertThat(carrier.cacheEligible())
        .as("DR-U5: the N-plan carrier is never one GremlinPlanCache entry")
        .isFalse(); // … and the carrier refuses
    assertThat(carrier.childCacheEligible())
        .as("per-child eligibility is untouched by the carrier-level coercion")
        .containsExactly(true);
  }
  ```

### TB2 [should-fix] The post-union limit test never truncates anything

`unionThenLimit_returnsSamePrefixAsNative` runs `limit(2)` over a concatenation that holds one row, so no assertion in it depends on the limit behaving.

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionTraversalEquivalenceTest.java`, method `unionThenLimit_returnsSamePrefixAsNative` (line 170)
- **Production code**: `RangeGlobalStepRecogniser.recognizePostUnion` (`new PostConcatOp.Range(low, high - low)`) and `MultiPlanMatchStep.applyPostConcatOp` (line 342), which turns that record into `SkipExecutionStream` plus `ExecutionStream.limit(...)`
- **Issue**: The fixture is `seedKnowsChain()` — Alice→Bob→Carol — and the traversal is `g.V(alice).union(out("knows"), in("knows")).limit(2)`. From Alice, `out("knows")` yields `[Bob]` and `in("knows")` yields `[]`, so the concatenation has one row and `limit(2)` is a no-op. `unionWithRidBearingPrefix_returnsSameMultiset` (line 95) runs the same degenerate one-row union.
- **Evidence**: C2
- **Missing behavior**: That `limit(n)` truncates the concatenation to `n` rows, that the truncation is computed from `high - low` rather than `high`, and that a limited union stops the concatenator before opening the remaining children. A mutation of `Range(low, high - low)` to `Range(low, high)` passes today, as does deleting the `s.limit(range.limit())` call entirely.
- **Suggested fix**: reuse the anti-cartesian fixture (Alice→Bob, Alice→Carol, Bob→Dave) so the concatenation carries three rows, and pin cardinality plus membership rather than multiset equality — `limit` is order-sensitive and the harness deliberately does not pin order.
  ```java
  /**
   * limit(n) after union truncates the concatenation to n rows. Emission order is not pinned, so
   * the assertion is cardinality plus membership in the full union multiset, not native equality.
   */
  @Test
  public void unionThenLimit_truncatesConcatenationToLimit() {
    var aliceId = seedFanOut(); // Alice->Bob, Alice->Carol, Bob->Dave

    setTranslatorEnabled(true);
    var limited =
        sortedIds(graph.traversal().V(aliceId).union(__.out(), __.out().out()).limit(2).toList());
    var full =
        sortedIds(graph.traversal().V(aliceId).union(__.out(), __.out().out()).toList());

    assertThat(full).as("the untruncated union is 2 + 1 rows").hasSize(3);
    assertThat(limited).as("limit(2) truncates the concatenation").hasSize(2);
    assertThat(limited).isSubsetOf(full);
  }
  ```

### TB3 [should-fix] Two metrics tests were converted to self-fulfilling if/else branches

`cacheHitReplaySurfacesNullPlan` and `resetClearsPlanAndReIterationYieldsCorrectResults` now branch on which source step the product happens to install and assert a different contract in each branch, so neither contract is pinned.

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBQueryMetricsStrategyTest.java`, methods `cacheHitReplaySurfacesNullPlan` (line 527, branch at line 563) and `resetClearsPlanAndReIterationYieldsCorrectResults` (line 583, branch at line 596)
- **Production code**: `YTDBQueryMetricsStep.capturedExecutionPlan()` (line 91)
- **Issue**: The first test probes a *separate* traversal (`g().V().hasLabel("person")`, line 549) to decide whether the monitored replay will translate, then asserts `isNotNull()` or `isNull()` accordingly. The second switches on `matchStep.isPresent()`. `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` defaults to `true` (GlobalConfiguration.java:1020), so the `else` legs are dead in the default configuration, and the null-plan contract the first test is named for is no longer verified anywhere. A regression that silently stopped translating `g.V().hasLabel("person")` flips execution to the dead leg and the test still passes — the failure mode the branch was added to accommodate is the same one it now hides.
- **Evidence**: C3
- **Missing behavior**: Each contract needs a test that fails when its own path breaks. The translator kill-switch makes both paths reachable deterministically.
- **Suggested fix**:
  ```java
  @Test
  @LoadGraphWith(MODERN)
  public void cacheHitReplayUnderTranslator_keepsCompiledPlan() throws Exception {
    withTranslator(true, () -> {
      // … populate, then replay from the TX result cache …
      assertThat(firstStepOf(YTDBMatchPlanStep.class))
          .as("this test must exercise the MATCH boundary path")
          .isPresent();
      assertThat(listener.executionPlan)
          .as("the boundary owns the compiled plan, so a cache-hit replay still surfaces it")
          .isNotNull();
    });
  }

  @Test
  @LoadGraphWith(MODERN)
  public void cacheHitReplayWithoutTranslator_surfacesNullPlan() throws Exception {
    withTranslator(false, () -> {
      assertThat(firstStepOf(YTDBGraphStep.class))
          .as("this test must exercise the half-measure source path")
          .isPresent();
      assertThat(listener.executionPlan)
          .as("the result-cache view nulls the plan the half-measure source captured")
          .isNull();
    });
  }
  ```
  Apply the same split to `resetClearsPlanAndReIterationYieldsCorrectResults`, and rename the MATCH half — that branch asserts the plan is *kept* across `reset()`, which the current name contradicts.

### TB4 [suggestion] The per-child cache test does not assert the reuse or the per-child fingerprints it claims

`apply_multiPlanWithProductionBuilder_cachesEligibleChildren` proves one fingerprint landed in the cache; the two claims in its comments are unasserted.

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinToMatchStrategyTest.java`, method `apply_multiPlanWithProductionBuilder_cachesEligibleChildren` (line 899)
- **Production code**: `GremlinToMatchStrategy.buildChildPlans` (line 924 of the diff hunk), which routes each child through `planBuilder.buildPlan` and therefore through the single-plan `GremlinPlanCache` get/put
- **Issue**: Both children are the *same* `childInputs` instance (`List.of(childInputs, childInputs)`), so a single cache entry satisfies the assertion — "each eligible child uses the existing fingerprint/get/put path" (DR-U5) is indistinguishable from "one child does". The follow-up comment says the second apply "reuses the cached plan", but the only assertion after it re-checks `contains(fingerprint)`, which was already true before the second apply and stays true whether or not the cache was consulted.
- **Evidence**: C4
- **Missing behavior**: Two structurally different children each landing under their own fingerprint, and a second apply that reads rather than rebuilds.
- **Suggested fix**:
  ```java
  var childV = inputsFor("V");
  var childPerson = inputsFor("Person");
  var fpV = GremlinPlanFingerprint.fingerprint(childV);
  var fpPerson = GremlinPlanFingerprint.fingerprint(childPerson);
  var builds = new java.util.concurrent.atomic.AtomicInteger();
  var counting = new GremlinToMatchStrategy(
      t -> fixtureMultiPlanTranslation(List.of(childV, childPerson), List.of(Map.of(), Map.of())),
      (s, tr, start) -> { builds.incrementAndGet(); return productionBuilder.buildPlan(s, tr, start); });

  counting.apply(graph.traversal().V().asAdmin());
  assertThat(GremlinPlanCache.instance(session()).contains(fpV)).isTrue();
  assertThat(GremlinPlanCache.instance(session()).contains(fpPerson)).isTrue();
  var buildsAfterFirst = builds.get();

  counting.apply(graph.traversal().V().asAdmin());
  assertThat(builds.get())
      .as("the second apply serves both children from the cache without re-planning")
      .isEqualTo(buildsAfterFirst);
  ```

### TB5 [suggestion] Child rewind context fidelity is verified with a wildcard matcher

`reset_thenProcessNextStart_rewindsAndReRunsEveryChild` verifies `reset(any())`, which cannot distinguish the child's own context from the coordinator context.

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/MultiPlanMatchStepTest.java`, method `reset_thenProcessNextStart_rewindsAndReRunsEveryChild` (line 383)
- **Production code**: `MultiPlanMatchStep.rewindPlan` (line 218), which passes `childPlan.getContext()` with the stated intent that "passing the child's own context keeps the seam faithful to the single-plan path"
- **Issue**: The sibling iteration path is asserted precisely — `processNextStart_iteratesEachChildAgainstItsOwnContext` compares against `c1.ctx` by identity — but the rewind path uses `any()`. A change to `childPlan.reset(ctx)` (the coordinator) passes.
- **Evidence**: C5
- **Missing behavior**: Identity of the context handed to each child's `reset`.
- **Suggested fix**:
  ```java
  verify(c1.plan, times(1)).reset(c1.ctx);
  verify(c2.plan, times(1)).reset(c2.ctx);
  ```

### TC1 [should-fix] Post-union `count` after another reduction has no test at all

`union(...).limit(n).count()` and `union(...).dedup().count()` are documented supported shapes, and the entire stream-count implementation that serves them is unexecuted by any test.

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionTraversalEquivalenceTest.java` (no test covers the shape)
- **Production code**: `MultiPlanMatchStep.countConcatStream` (lines 359-406), reached from `applyPostConcatOp` (line 342) whenever `postConcatOps` is not exactly one `Count`
- **Missing scenario**: Every post-union test applies exactly one reduction. `unionThenCount_returnsSameTotalAsNative` (line 141) takes the lone-`Count` push-down branch (`sumChildCountStreams`, line 269). No test produces a `[Range, Count]` or `[Dedup, Count]` op list, so `countConcatStream` — 48 lines with its own `IllegalStateException("no counted row")`, its own double `upstream.close(ctx)` (once in the `finally` of `ensure`, again in `close`), and its own `ResultInternal` `"count"` column that the SCALAR projection must read — never runs.
- **Why it matters**: `PostConcatOp`'s own Javadoc names this shape ("Any preceding stream op disables that push-down so `union().limit(5).count()` counts at most five concatenated rows"). The two count paths build their result row independently; a mismatch between the column `countConcatStream` writes and the column the boundary's SCALAR projection reads returns `null` or `0` for every such query, and nothing in the suite would notice. The recogniser accepts the shape (`GremlinAggregateAssembler.configurePostUnionCount` declines only on a *second* `Count`), so users reach it.
- **Evidence**: C6
- **Refutation considered**: Not covered indirectly — `grep -rn "PostConcatOp\|countConcatStream" core/src/test/java` returns nothing, and commit `c6c66a62b9` added 274 lines to `MultiPlanMatchStep.java` with zero lines added to `MultiPlanMatchStepTest.java`. The only new tests in that commit are 53 lines in `UnionTraversalEquivalenceTest`, all single-reduction shapes.
- **Suggested test**:
  ```java
  /**
   * count() after limit() counts the truncated concatenation, not the child totals: the push-down
   * is disabled and the boundary counts rows off the concatenator instead.
   */
  @Test
  public void unionThenLimitThenCount_countsTruncatedConcatenation() {
    var aliceId = seedFanOut(); // union(out(), out().out()) is 2 + 1 = 3 rows
    assertEquivalent(
        "g.V(alice).union(out(), out().out()).limit(2).count()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(aliceId).union(__.out(), __.out().out()).limit(2).count(),
        true);

    setTranslatorEnabled(true);
    assertThat(graph.traversal().V(aliceId).union(__.out(), __.out().out()).limit(2).count().next())
        .as("stream-count path counts at most the limit, never the pushed-down child totals")
        .isEqualTo(2L);
  }

  /** dedup() before count() collapses cross-child duplicates before counting. */
  @Test
  public void unionThenDedupThenCount_countsDistinctConcatenation() {
    seedKnowsChain(); // union(out, in) is [Bob, Carol, Alice, Bob] — Bob twice
    assertEquivalent(
        "g.V().union(out(knows), in(knows)).dedup().count()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().union(__.out("knows"), __.in("knows")).dedup().count(),
        true);

    setTranslatorEnabled(true);
    assertThat(
            graph.traversal().V().union(__.out("knows"), __.in("knows")).dedup().count().next())
        .as("3 distinct vertices out of a 4-row concatenation")
        .isEqualTo(3L);
  }
  ```

### TC2 [suggestion] No union test drives a child that yields zero rows

Every concatenation test in the track uses children that all produce rows, so the empty-child shapes are unexercised at the union level.

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/MultiPlanMatchStepTest.java` (concatenation tests, lines 90-160 region) and `UnionTraversalEquivalenceTest.java`
- **Production code**: `MultiPlanMatchStep.startPlanStream` producer (line 281) and `sumChildCountStreams`'s `if (!childStream.hasNext(childContext)) { continue; }` (line 305 region)
- **Missing scenario**: A union where the first child, a middle child, or every child yields nothing — `g.V().union(out("mentors"), out("knows"))` with no `mentors` edge is an everyday shape. `MultiPlanMatchStepTest` uses `ListStream.of()` (empty) only in the clone and `getPlans` tests, none of which iterate through `processNextStart`.
- **Why it matters**: An all-empty union must arm the base, project zero traversers, and still close every child; the push-down count path must contribute 0 for a child that emits no row rather than skipping the accumulation incorrectly.
- **Evidence**: C7
- **Refutation considered**: Partly refuted. The concatenator is the production `MultipleExecutionStream`, whose `hasNext` loop (`while (currentStream == null || !currentStream.hasNext(ctx))`) closes an exhausted sub-stream and pulls the next one, so "an empty child terminates the concatenation early" is correct by construction and not this track's risk. What remains untested is union-level: the all-children-empty arming, and the `continue` branch in `sumChildCountStreams`. That branch is itself close to unreachable after Step 2a (bare `count(*)` always emits a row via `GuaranteeEmptyCountStep`), which is why this stays a suggestion.
- **Suggested test**:
  ```java
  /** An empty leading child is skipped and the later children still deliver their rows. */
  @Test
  public void processNextStart_emptyFirstChild_stillEmitsLaterChildren() {
    var raw = rawVertex();
    var empty = child(ListStream.of());
    var populated = child(ListStream.of(vertexRow(raw)));

    var step = elementStep(empty, populated);

    assertThat(rawEntityOf(step.processNextStart().get())).isSameAs(raw);
    assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(step::processNextStart);
    verify(empty.plan, times(1)).start();
    verify(populated.plan, times(1)).start();
  }

  /** A union whose every child is empty emits nothing and still closes every child. */
  @Test
  public void processNextStart_allChildrenEmpty_emitsNothingAndClosesAll() {
    var c1 = child(ListStream.of());
    var c2 = child(ListStream.of());

    var step = elementStep(c1, c2);
    assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(step::processNextStart);
    step.close();

    verify(c1.plan, times(1)).close();
    verify(c2.plan, times(1)).close();
  }
  ```

### TC3 [should-fix] The `ResultShaping` leg of the agreement gate is never isolated

The one contract-mismatch test disagrees on all three legs at once, so removing the shaping comparison from the DR-U3 gate keeps every test green — and the shaping leg is precisely the case adversarial finding A2 was raised for.

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionTraversalEquivalenceTest.java`, method `unionProjectionContractMismatch_declines` (line 111)
- **Production code**: `UnionStepRecogniser.recognize`, the three-legged gate at lines 85-88 (`!agreedOutputType.equals(...) || !agreedReturnClass.equals(...) || !agreedShaping.equals(...)`)
- **Missing scenario**: `g.V().union(__.values("name"), __.values("age"))` — the two children agree on `BoundaryOutputType` (`SINGLE_VALUE`) and on return class, and differ only in `ResultShaping.presencePropertyKeys` (`["name"]` vs `["age"]`). The existing test pairs `values("name")` against `out()`, which differ on output type, return class, *and* shaping, so it fires on the first leg and says nothing about the third.
- **Why it matters**: The track's own Surprises log records this shape verbatim: "`union(values("name"), values("age"))` (same enum, different presence key) … mistranslate[s] under enum-only agreement". `ResultShaping` is a record whose `presencePropertyKeys` is a `List<String>` component (`ResultShaping.java`), so record equality does distinguish them today. If the shaping leg is dropped or weakened, the boundary applies the *first* child's presence key to every row of the second, and `values("age")` rows are silently emitted as `name` values or dropped by `dropOnAbsent` — a wrong-answer path with no test.
- **Evidence**: C8
- **Refutation considered**: Not covered indirectly. No `UnionStepRecogniserTest` exists (`grep -rn "UnionStepRecogniser" core/src/test/java` returns nothing), so the gate is only reachable through `UnionTraversalEquivalenceTest`, and no test there pairs two same-enum children.
- **Suggested test**:
  ```java
  /**
   * Same BoundaryOutputType (SINGLE_VALUE) and same return class, different ResultShaping presence
   * key: the third leg of the DR-U3 agreement gate must decline. Accepting would project child two's
   * rows under child one's presence key.
   */
  @Test
  public void unionShapingOnlyMismatch_declines() {
    seedKnowsChainWithAges();
    assertEquivalent(
        "g.V().union(values(name), values(age)) — same enum, different presence key",
        Recognition.DECLINED,
        () -> graph.traversal().V().union(__.values("name"), __.values("age")),
        false);
  }
  ```

### TC4 [should-fix] Per-child positional parameters have no end-to-end test

Technical finding T3 and the track's own Validation line both call for two union children with different `?`-slot values; the only coverage is a mocked wiring test that supplies both the parameter maps and the contexts it then reads back.

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionTraversalEquivalenceTest.java` (no test covers the shape); the mocked coverage is `GremlinToMatchStrategyTest.apply_multiPlanTranslation_buildsEveryChildAndSplicesMultiPlanBoundary` (line 522)
- **Production code**: `GremlinToMatchStrategy.buildChildPlans`'s `childPlan.getContext().setInputParameters(childParameters)` and `MultiPlanMatchStep`'s empty base map (`super(..., Map.of(), shaping)`)
- **Missing scenario**: `g.V().union(__.has("name", "Alice"), __.has("name", "Bob"))`. `HasStepRecogniser` binds literals as positional parameters through `ParamSink` (`HasStepRecogniser.java:131`, `ParamSink.bindParam`), so each child fork mints its own slot `0` with a different value — the classic collision the per-child-context design exists to prevent.
- **Why it matters**: The mocked test hands the strategy two `BasicCommandContext` objects it created itself and asserts `ctxA.getInputParameters()` equals the map it passed in. It verifies that `buildChildPlans` copies map A onto context A, and nothing about whether a real execution resolves each child's `?0` against its own value. If both children ended up sharing one context or one map, the mocked test still passes (its contexts are distinct by construction) while the query returns Bob's rows twice, or Alice's twice.
- **Evidence**: C9
- **Refutation considered**: Not covered indirectly. Every union child in `UnionTraversalEquivalenceTest` is `out()`, `in()`, `values(...)`, or `flatMap(...)` — none carries a bound literal, so no end-to-end union execution has ever had more than an empty parameter map on any child.
- **Suggested test**:
  ```java
  /**
   * Two children whose ?-slot 0 holds different values must each resolve their own slot: the union
   * returns Alice and Bob, never one of them twice. Pins technical T3 end to end.
   */
  @Test
  public void unionChildrenWithDistinctPositionalParams_resolveTheirOwnSlots() {
    seedKnowsChain(); // Alice, Bob, Carol
    assertEquivalent(
        "g.V().union(has(name,Alice), has(name,Bob)) — distinct ?0 per child",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().union(__.has("name", "Alice"), __.has("name", "Bob")),
        true);

    setTranslatorEnabled(true);
    var names =
        graph.traversal().V()
            .union(__.has("name", "Alice"), __.has("name", "Bob"))
            .values("name").toList();
    assertThat(names)
        .as("each child resolves its own slot 0; neither value leaks into the other child")
        .containsExactlyInAnyOrder("Alice", "Bob");
  }
  ```

### TC5 [should-fix] The widened filtered-count short-circuit has no regression test on a populated class

`tryHardwiredMatchCount` now runs on filtered patterns and gates on a string heuristic, and the only filtered-count test runs where the correct answer and the false-positive answer are both `0`.

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/MatchStatementExecutionTest.java`, method `testFilteredCountOnEmptyClassReturnsZeroRow` (line 4980); unit-level coverage in `HardwiredCountExactClassFilterTest.java`
- **Production code**: `MatchExecutionPlanner.tryHardwiredMatchCount` (line 757), which deleted the unconditional `if (aliasFilters.get(alias) != null) return false;` bail-out and now defers to `HardwiredCountOptimizations.isExactClassEqualsOnly` (line 801 → `HardwiredCountOptimizations.java:80`)
- **Missing scenario**: A single-node `MATCH … RETURN count(*)` with a *selective, non-`@class`* filter on a class that holds several records. `testFilteredCountOnEmptyClassReturnsZeroRow` uses `where:(name='nobody')` on a class with zero records, so a false positive (which would answer with the class size) and the correct filtered answer are both `0` — the test cannot detect the failure. At unit level `isExactClassEqualsOnly` is tested for the Gremlin-built AST, the SQL-parsed AST, a wrong class name, and `NOT (…)`; the two realistic false-positive shapes are absent: a multi-conjunct `@class = 'L' AND name = 'x'` (two sub-blocks, so `unwrapSingleEquals` must return `null`) and a non-`@class` left-hand side.
- **Why it matters**: `isExactClassEqualsOnly` decides by `bin.getLeft().toString().trim()` string comparison and `stripQuotes` on the right, and its `unwrapSingleEquals` peels single-conjunct `AndBlock` / `OrBlock` / non-negating `NotBlock` wrappers. A false positive routes a *filtered* count to `CountFromClassStep`, which counts by class name at execution time and ignores the filter entirely: `MATCH {class: Person, as: a, where: (name = 'Alice')} RETURN count(*)` would return the whole `Person` count. Before this track that was structurally impossible. This is the test I would add first.
- **Evidence**: C10
- **Refutation considered**: Not covered elsewhere. `grep -n "count(\*)"` across `MatchStatementExecutionTest` and `MatchStatementExecutionNewTest` finds only the four new tests plus grouped multi-node counts (line 4953) and `SELECT`-side counts; no pre-existing test issues a filtered single-node MATCH count against a populated class.
- **Suggested test**:
  ```java
  /**
   * A non-@class filter keeps a single-node MATCH count on the generic aggregate path: the answer is
   * the filtered count, not the class size. Guards against isExactClassEqualsOnly false-positiving on
   * a filter it must not fold into CountFromClassStep.
   */
  @Test
  public void testMatchCountWithNonClassFilter_countsOnlyMatchingRecords() {
    var className = "MatchFilteredCountV";
    session.execute("CREATE class " + className + " extends V").close();
    session.begin();
    session.execute("CREATE VERTEX " + className + " SET name = 'Alice'").close();
    session.execute("CREATE VERTEX " + className + " SET name = 'Bob'").close();
    session.execute("CREATE VERTEX " + className + " SET name = 'Carol'").close();
    session.commit();

    session.begin();
    var result =
        session.query(
            "MATCH {class: " + className + ", as: a, where: (name = 'Alice')} RETURN count(*) as cnt");
    assertEquals("filtered count must count matches, not the class size", 1L,
        (long) result.next().<Number>getProperty("cnt"));
    var plan = (SelectExecutionPlan) result.getExecutionPlan();
    assertFalse(
        "a non-@class filter must not fold into the class-size short-circuit",
        plan.prettyPrint(0, 2).contains("CALCULATE CLASS SIZE"));
    result.close();
    session.commit();
  }
  ```
  Add the two unit legs to `HardwiredCountExactClassFilterTest` as well:
  ```java
  @Test
  public void multiConjunctAndWithClassEquals_isRejected() throws Exception {
    assertThat(
            HardwiredCountOptimizations.isExactClassEqualsOnly(
                parseWhere("@class = 'Person' AND name = 'Alice'"), "Person"))
        .isFalse();
  }

  @Test
  public void nonClassLeftHandSide_isRejected() throws Exception {
    assertThat(
            HardwiredCountOptimizations.isExactClassEqualsOnly(
                parseWhere("name = 'Person'"), "Person"))
        .isFalse();
  }
  ```

### TC6 [suggestion] The multi-plan branch of the metrics plan capture is untested

`capturedExecutionPlan()` gained a `MultiPlanMatchStep` branch in this track, and no test drives a union through the query-metrics listener.

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/GremlinToMatchSmokeTest.java`, method `queryMonitoringSurfacesMatchPlanUnderTranslation` (covers only the `YTDBMatchPlanStep` branch)
- **Production code**: `YTDBQueryMetricsStep.capturedExecutionPlan()` (line 91), specifically the `MultiPlanMatchStep` lookup and `plans.isEmpty() ? null : plans.getFirst()` at lines 98-104
- **Missing scenario**: A monitored `g.V().union(out(), in())` asserting `QueryDetails.getExecutionPlan()` is the first child plan rather than `null`.
- **Why it matters**: Without it, scan/index detectors that inspect step types see `null` for every union query, and the branch could be deleted with no test failure. The `plans.isEmpty()` guard is also unreachable — the `MultiPlanMatchStep` constructor rejects an empty plan list — so it is defensive-only.
- **Evidence**: C11
- **Refutation considered**: Not covered indirectly. `YTDBQueryMetricsStrategyTest` and `GremlinToMatchSmokeTest` are the only metrics tests touched by this track, and neither uses `union`. Diagnostics rather than query correctness, hence suggestion.
- **Suggested test**:
  ```java
  @Test
  public void queryMonitoringSurfacesFirstChildPlanForUnion() {
    seedKnowsChain();
    var listener = new RememberingListener();
    ((YTDBTransaction) graph.tx())
        .withQueryMonitoringMode(QueryMonitoringMode.EXACT).withQueryListener(listener);
    graph.tx().open();

    var q = graph.traversal().V().union(__.out("knows"), __.in("knows")).asAdmin();
    q.applyStrategies();
    var boundary = (MultiPlanMatchStep<?, ?>) q.getSteps().getFirst();
    q.toList();
    graph.tx().commit();

    assertThat(listener.executionPlan)
        .as("a union surfaces the first child's compiled plan, not null")
        .isSameAs(boundary.getPlans().getFirst());
  }
  ```

### TC7 [suggestion] Union arity boundaries are unexercised end to end

Every end-to-end union test uses exactly two children, and three decline legs in the recogniser have no test.

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionTraversalEquivalenceTest.java`
- **Production code**: `UnionStepRecogniser.recognize` — the `host == null` decline (line 57 region), the `globalChildren.isEmpty()` decline (line 62 region), and the N-child agreement loop (lines 70-96)
- **Missing scenario**: N = 1 (`g.V().union(__.out("knows"))`, the degenerate one-plan concatenation), N ≥ 3, a childless `g.V().union()`, and a union nested inside a sub-walk (`g.V().where(__.union(...))`), where `ctx.unionForkHost()` is `null` and the recogniser must decline. `MultiPlanMatchStepTest` reaches three plans only in the exception and close tests, never through a full concatenation.
- **Why it matters**: N = 1 is the boundary where `MultiPlanMatchStep` reduces to one plan and every "advance to the next child" branch is skipped; the null-host decline is the guard that keeps `union` out of sub-walks, where no fork host exists.
- **Evidence**: C12
- **Refutation considered**: The N = 1 and N = 3 paths are the same loop and the same `MultipleExecutionStream` already exercised at N = 2, so the marginal value is modest — hence suggestion. The null-host and empty-children declines are cheap to add and guard against accepting a shape the recogniser cannot fork.
- **Suggested test**:
  ```java
  /** Three children concatenate in declared order — the loop is not hard-wired to two. */
  @Test
  public void unionThreeChildren_concatenatesAllThree() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().union(out(knows), in(knows), out(knows).out(knows))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V()
            .union(__.out("knows"), __.in("knows"), __.out("knows").out("knows")),
        true);
  }

  /** A union inside a sub-walk has no fork host and must decline the whole traversal. */
  @Test
  public void unionInsideSubWalk_declines() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().where(union(out(knows), in(knows))) — no fork host on a sub-walk",
        Recognition.DECLINED,
        () -> graph.traversal().V().where(__.union(__.out("knows"), __.in("knows"))),
        false);
  }
  ```

### TC8 [should-fix] Post-union `skip` and bounded `range` are never exercised

`SkipExecutionStream` has no test caller anywhere, and three branches of `recognizePostUnion` are unreached.

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/UnionTraversalEquivalenceTest.java` (no test covers the shapes)
- **Production code**: `MultiPlanMatchStep.SkipExecutionStream` (lines 423-448), instantiated at line 348 when `range.skip() > 0`; `RangeGlobalStepRecogniser.recognizePostUnion` — the `low == 0` unbounded-high early accept, the `low > 0` unbounded-high `Range(low, -1)`, and the `high < low` clamp
- **Missing scenario**: `union(...).skip(n)`, `union(...).range(low, high)` with `low > 0`, and `union(...).range(low, -1)`. The only post-union range test is `unionThenLimit_returnsSamePrefixAsNative`, which supplies `low = 0` and never truncates (TB2).
- **Why it matters**: `SkipExecutionStream.hasNext` drains the skipped prefix inside the `hasNext` probe and `next` re-enters `hasNext` before delegating; an off-by-one there drops or duplicates a row at the skip boundary, and the `high - low` conversion at the recogniser turns a `range(low, high)` into a `Range(skip, limit)` pair that no test reads back. This is the one place where union's row set can be silently wrong without any child plan being wrong.
- **Evidence**: C13
- **Refutation considered**: Not covered indirectly. `grep -rn "SkipExecutionStream\|PostConcatOp" core/src/test/java` returns nothing, and the single-plan `SQLSkip` path that `OrderRangeStepRecogniserTest` covers is a different code path — `recognizePostUnion` returns before the `SQLSkip` / `SQLLimit` construction.
- **Suggested test**:
  ```java
  /**
   * skip(n) and range(low, high) after a union slice the concatenation. Order is not pinned, so the
   * assertions are cardinality plus membership against the full 3-row union.
   */
  @Test
  public void unionThenSkipAndRange_sliceTheConcatenation() {
    var aliceId = seedFanOut(); // union(out(), out().out()) is 3 rows
    setTranslatorEnabled(true);
    var full = sortedIds(graph.traversal().V(aliceId).union(__.out(), __.out().out()).toList());
    assertThat(full).hasSize(3);

    var skipped =
        sortedIds(graph.traversal().V(aliceId).union(__.out(), __.out().out()).skip(1).toList());
    assertThat(skipped).as("skip(1) drops exactly one row").hasSize(2);
    assertThat(skipped).isSubsetOf(full);

    var ranged =
        sortedIds(graph.traversal().V(aliceId).union(__.out(), __.out().out()).range(1, 3).toList());
    assertThat(ranged).as("range(1,3) yields high - low = 2 rows after skipping 1").hasSize(2);
    assertThat(ranged).isSubsetOf(full);

    var openEnded =
        sortedIds(graph.traversal().V(aliceId).union(__.out(), __.out().out()).range(1, -1).toList());
    assertThat(openEnded).as("unbounded high after low is skip-only").hasSize(2);
  }
  ```

## Evidence base

#### C1 CONFIRMED — multi-plan cache-eligibility normalization is unfalsifiable
MUTATION: delete `cacheEligible = false;` (GremlinToMatchTranslator.java:155). `multiPlanTranslation_isNotCacheEligible` reads back the `false` that `multiPlan(...)` passed in, so the test still passes; no other test constructs a multi-plan carrier with `cacheEligible = true`.

#### C2 CONFIRMED — the post-union limit test cannot fail on a limit regression
MUTATION: change `new PostConcatOp.Range(low, high - low)` to `Range(low, high)`, or drop `s.limit(range.limit())` entirely. The fixture (`seedKnowsChain`, Alice→Bob→Carol, start `V(alice)`) gives `out("knows") = [Bob]` and `in("knows") = []`, so the concatenation is one row and `limit(2)` never binds; both mutations keep the test green.

#### C3 CONFIRMED — the metrics tests pass on either branch
MUTATION: disable translation for `g.V().hasLabel("person")` (or break it). `matchBoundary` flips to `false`, the `else` leg runs, and both tests still pass. `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` defaults to `true` (GlobalConfiguration.java:1020), so the `else` leg is dead under the default configuration and neither contract is pinned.

#### C4 CONFIRMED — cache reuse and per-child fingerprints are unasserted
MUTATION: make `buildChildPlans` skip the cache `put` on every child after the first, or make the second apply rebuild from scratch. `contains(fingerprint)` stays true in both cases because the two children share one `childInputs` instance and the first apply already populated the entry.

#### C5 CONFIRMED — rewind context identity is unasserted
MUTATION: change `childPlan.reset(childPlan.getContext())` to `childPlan.reset(ctx)` (the coordinator). `verify(c1.plan, times(1)).reset(any())` matches either argument. Low impact today because `SelectExecutionPlan.reset` ignores its argument, which is why this is a suggestion rather than a should-fix.

#### C6 CONFIRMED — countConcatStream has no test caller
`grep -rn "PostConcatOp\|countConcatStream\|SkipExecutionStream\|sumChildCountStreams" core/src/test/java` returns no match. `git show --stat c6c66a62b9` adds 274 lines to `MultiPlanMatchStep.java` and 0 to `MultiPlanMatchStepTest.java`; its only test additions are 53 lines in `UnionTraversalEquivalenceTest`, all single-reduction shapes, so `applyPostConcatOp` never receives a two-element op list.

#### C7 PARTIAL — the empty-child hazard is mostly correct by construction
CLAIM: no union test drives a zero-row child, so an empty child could terminate the concatenation early.
REFUTATION CHECK:
- Is the boundary unreachable? No. `union(out("a"), out("b"))` with no `a` edges is an everyday shape.
- Is the behavior trivially correct? Largely yes. The concatenator is the production `MultipleExecutionStream` (`sql/executor/resultset/MultipleExecutionStream.java`), whose `hasNext` is `while (currentStream == null || !currentStream.hasNext(ctx)) { close; if (!producer.hasNext) return false; currentStream = producer.next(ctx); }`. An empty sub-stream is closed and skipped, and the loop continues to the next child. The early-termination bug class this track could have introduced is therefore prevented by a pre-existing, separately-tested class.
- Is it covered indirectly? Partly. `MultiPlanMatchStepTest` builds empty `ListStream.of()` children only in the clone / `getPlans` tests, which never call `processNextStart`. No equivalence fixture produces a zero-row child.
- Residual: the union-level all-children-empty arming (base opens, projects nothing, closes all) and `sumChildCountStreams`' `if (!childStream.hasNext) continue;`. The latter is near-unreachable after Step 2a, since bare `count(*)` always emits a row through `GuaranteeEmptyCountStep`.
VERDICT: LOW VALUE for the early-termination class (refuted by the concatenator loop); CONFIRMED but low-severity for the all-empty arming. Reported as a suggestion.

#### C8 CONFIRMED — the shaping leg of the agreement gate is unfalsifiable
MUTATION: delete `|| !agreedShaping.equals(childResult.shaping())` from UnionStepRecogniser.java:87. `unionProjectionContractMismatch_declines` pairs `values("name")` with `out()`, which already disagree on `outputType` and `returnClass`, so the first two legs still fire and the test passes. No test pairs two children that agree on enum and return class but differ in `ResultShaping` — the exact `union(values("name"), values("age"))` case the track's Surprises log records from adversarial A2. `ResultShaping` is a record with a `List<String> presencePropertyKeys` component, so record equality does distinguish the two shapings when the leg is present.

#### C9 CONFIRMED — per-child positional parameters are only wiring-tested
MUTATION: make `buildChildPlans` install `translation.childInputParameters().getFirst()` onto every child (a shared-map regression). `apply_multiPlanTranslation_buildsEveryChildAndSplicesMultiPlanBoundary` would catch that specific mutation because it asserts both contexts, but no test executes a union whose children actually bind different literals: every union child in `UnionTraversalEquivalenceTest` is `out()` / `in()` / `values()` / `flatMap()`, all with empty parameter maps. `HasStepRecogniser.java:131` (`ParamSink paramSink = ctx::bindParam`) confirms `has(key, literal)` is the shape that mints a positional slot.

#### C10 CONFIRMED — the widened filtered-count path has no populated-class regression test
The deleted guard (`MatchExecutionPlanner`, old lines 761-768: `if (aliasFilters != null && aliasFilters.get(alias) != null) return false;`) made a filtered single-node count structurally unable to reach `CountFromClassStep`. It is now gated by the string heuristic `isExactClassEqualsOnly` (`getLeft().toString().trim()` compared to `"@class"`, `stripQuotes` on the right, `unwrapSingleEquals` peeling single-conjunct wrappers). `grep -n "count(\*)"` across `MatchStatementExecutionTest` and `MatchStatementExecutionNewTest` shows the only filtered single-node count is `testFilteredCountOnEmptyClassReturnsZeroRow` on a zero-record class, where the correct answer and the class-size answer are both `0`. A false positive therefore returns the whole class size for a filtered count with no test failing.

#### C11 CONFIRMED — the multi-plan metrics branch has no test
`YTDBQueryMetricsStep.capturedExecutionPlan()` lines 98-104 are reached only when the traversal's first assignable step is a `MultiPlanMatchStep`. `GremlinToMatchSmokeTest.queryMonitoringSurfacesMatchPlanUnderTranslation` uses `g.V().hasLabel("Person")` (single-plan), and `YTDBQueryMetricsStrategyTest` uses no union. Deleting the branch leaves the suite green.

#### C12 CONFIRMED — union arity boundaries are untested end to end
Every one of the twelve tests in `UnionTraversalEquivalenceTest` passes exactly two children to `union(...)`. `MultiPlanMatchStepTest` reaches three plans only in `processNextStart_firstChildThrows_…` and `close_multipleChildCloseFailures_…`, neither of which drains a three-child concatenation. `grep -rn "UnionStepRecogniser" core/src/test/java` returns nothing, so the `host == null` and `globalChildren.isEmpty()` declines have no direct unit coverage either.

#### C13 CONFIRMED — SkipExecutionStream has no test caller
`grep -rn "SkipExecutionStream" core/src/test/java` returns no match, and `applyPostConcatOp` constructs it only when `range.skip() > 0`. The single post-union range test supplies `low = 0`, so the branch is never taken. The `low > 0` unbounded-high (`Range(low, -1)`) and `high < low` clamp branches of `recognizePostUnion` are likewise unreached.

#### C14 REFUTED — `scalarCount` degenerate row shapes
CLAIM: `MultiPlanMatchStep.scalarCount` (line 329) returns `0L` silently when the row carries no non-boundary property or when that property is not a `Number`, and no test covers either shape, so a malformed child count row would sum to zero without notice.
REFUTATION CHECK:
- Could the boundary be unreachable due to caller validation? Yes. `scalarCount` runs only on the push-down path, and `buildChildPlans` reaches it only after `PostConcatSupport.rewriteToCountStar` has replaced the child's RETURN with exactly one `MatchProjectionBuilder.countStar()` item and cleared GROUP BY / DISTINCT / ORDER / SKIP / LIMIT. The row therefore always carries exactly one numeric column.
- Could the behavior be trivially correct? The `return 0L` fall-throughs are defensive branches for a row shape the rewrite forbids.
- Covered indirectly? The happy path runs in `unionThenCount_returnsSameTotalAsNative`.
VERDICT: LOW VALUE — correct by construction; not reported as a finding.

#### C15 REFUTED — `PostConcatOp.Range` skip validation
CLAIM: `Range`'s compact constructor throws `IllegalArgumentException` for `skip < 0` and no test exercises it.
REFUTATION CHECK:
- Reachable from production? No. The only construction site is `RangeGlobalStepRecogniser.recognizePostUnion`, which declines (`if (low < 0) return Outcome.DECLINE;`) before constructing a `Range`. Both `Range` construction sites pass a non-negative `low`.
- Worth a direct unit test? Testing an unreachable guard adds a test that pins an implementation detail rather than a behavior.
VERDICT: LOW VALUE — not reported as a finding.
