<!-- MANIFEST
findings: 9   severity: {blocker: 0, should-fix: 4, suggestion: 5}
index:
  - {id: TC1, sev: should-fix, loc: MatchExecutionPlanner.java:5798, anchor: "### TC1 ", cert: C1, basis: "dedup key packs (collectionId<<32)^position; colliding distinct RIDs are silently dropped and no test covers position >= 2^32"}
  - {id: TC2, sev: should-fix, loc: PromoteStaticRidsFromFiltersTest.java:703, anchor: "### TC2 ", cert: C2, basis: "AND-loop half of the step-5 fix has no unit test; a regression keeps rows and silently restores the O(class) scan"}
  - {id: TC3, sev: should-fix, loc: PromoteStaticRidsFromFiltersTest.java:760, anchor: "### TC3 ", cert: C3, basis: "no NOT case anywhere in the class; the newly reachable negated leaf could pin the excluded RID as the fetch target"}
  - {id: TB1, sev: should-fix, loc: YTDBQueryMetricsStrategyTest.java:375, anchor: "### TB1 ", cert: C4, basis: "the tree's only Gremlin-level index-usage assertion omits its mirror negative, so index-plus-scan passes"}
  - {id: TC4, sev: suggestion, loc: MultiPlanMatchStepTest.java:433, anchor: "### TC4 ", cert: C5, basis: "no CLOSED_UNSTARTED or failed-start re-arm case on the union step, where the cost is per child"}
  - {id: TC5, sev: suggestion, loc: AbstractMatchPlanStep.java:602, anchor: "### TC5 ", cert: C6, basis: "close() Javadoc justifies not gating REARMED_AFTER_CLOSE by a leak path no test drives"}
  - {id: TB2, sev: suggestion, loc: MatchStatementExecutionTest.java:2381, anchor: "### TB2 ", cert: C7, basis: "the sole guard for the sub-plan-free root-alias planner change asserts step tallies and no row"}
  - {id: TB3, sev: suggestion, loc: YTDBQueryMetricsStrategyTest.java:394, anchor: "### TB3 ", cert: C8, basis: "both kill-switch arms run in one method and neither output is captured or compared"}
  - {id: TC6, sev: suggestion, loc: SQLSuffixIdentifierTest.java:130, anchor: "### TC6 ", cert: C9, basis: "the $-branch precedence chain grew a fourth level; the ctx-var-over-projection pairing is unpinned"}
evidence_base: {section: "## Evidence base", certs: 12, matches: 9}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5, verdict: CONFIRMED, anchor: "#### C5 "}
  - {id: C6, verdict: CONFIRMED, anchor: "#### C6 "}
  - {id: C7, verdict: CONFIRMED, anchor: "#### C7 "}
  - {id: C8, verdict: CONFIRMED, anchor: "#### C8 "}
  - {id: C9, verdict: CONFIRMED, anchor: "#### C9 "}
  - {id: C10, verdict: REFUTED, anchor: "#### C10 "}
  - {id: C11, verdict: REFUTED, anchor: "#### C11 "}
  - {id: C12, verdict: REFUTED, anchor: "#### C12 "}
flags: [CONTRACT_OK]
-->

## Findings

### TC1 [should-fix] Dedup key for promoted static RIDs collides, and no test reaches the collision

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/PromoteStaticRidsFromFiltersTest.java`, method `toPromotedSqlRidList_dropsDuplicateRids` (line 648)

**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/MatchExecutionPlanner.java` lines 5785–5801 (`toPromotedSqlRidList`)

**Missing scenario**: two distinct RIDs whose packed dedup keys are equal. The key is
`(collectionId << 32) ^ position` stored in a `Set<Long>`, so `#0:4294967296` and `#1:0` both
map to `4294967296`. The one test that exercises the dedup uses `#25:7`, `#26:8`, `#25:7` —
positions of 7 and 8, which cannot set a bit above 31 and so cannot collide.

**Why it matters**: the collision drops a distinct RID from the pinned list, and the pinned list
is the fetch target. `createSelectStatement` emits `SELECT FROM [...]` over exactly those RIDs,
so the dropped record is never read and the query returns one row fewer with no error. The same
packing is wrong for any position at or above 2^32; generally `#0:<n*2^32>` collides with
`#n:0`. Sibling code in the same feature already has the correct shape:
`StartStepRecogniser.RidKey(int collectionId, long position)` (line 297) is a value record used
for precisely this dedup, and its Javadoc explains why an identity-keyed set is unsafe here.
The promotion path reimplemented the key by hand and lost that.

**Evidence**: input domain table for `toPromotedSqlRidList`, row "collection position / long /
boundaries 0, small, 2^32, negative (temporary RID) / tested: only small". See `#### C1`.

**Refutation considered**: reachability needs a cluster holding more than 4.29 billion records,
which is not a small database. It is not unreachable — `collectionPosition` is a `long`
throughout, `SQLInteger` promotes any literal past `Integer.MAX_VALUE` to a `Long`
(`SQLInteger.setValue`, line 36), and `RecordIdInternal` imposes no 32-bit ceiling on the
position. The failure is silent data loss rather than a throw, which is the class of defect this
track was created to stop shipping.

**Suggested test**:

```java
/**
 * Two RIDs whose packed dedup keys collide must both be promoted. The key is
 * (collectionId << 32) ^ position, so #0:4294967296 and #1:0 hash to the same long; a
 * Set<Long> keyed on it drops the second, and the pinned list is the fetch target, so the
 * query loses that record with no error.
 */
@Test
public void toPromotedSqlRidList_keepsDistinctRidsWithCollidingDedupKeys() {
  var where = parseWhere("SELECT FROM Comment WHERE @rid in [#0:4294967296, #1:0]");
  SQLInCondition inCond = where.findRidInList();
  assertThat(inCond).isNotNull();

  var promoted = MatchExecutionPlanner.toPromotedSqlRidList(inCond, ctx);

  assertThat(promoted).hasSize(2);
  assertThat(promoted.get(0).toRecordId((Result) null, CTX).toString())
      .isEqualTo("#0:4294967296");
  assertThat(promoted.get(1).toRecordId((Result) null, CTX).toString()).isEqualTo("#1:0");
}
```

The production fix that makes it pass is to key the set on a value record rather than a packed
long, matching `StartStepRecogniser.RidKey`.

### TC2 [should-fix] The AND-loop half of the step-5 fix has no test

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/PromoteStaticRidsFromFiltersTest.java`, the four new cases at lines 703–767

**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLWhereClause.java` lines 1115–1132 (`findRidConditionInExpression`)

**Missing scenario**: a code-assembled `SQLAndBlock` whose sub-blocks are bare conditions, one of
them `@rid IN [...]`. Step 5 changed two things in one method — it added the leaf branch, and it
rewrote the AND loop from `termExtractor.apply(sub)` plus recursion down to recursion alone. All
four new tests build a single bare leaf as the base expression, so they cover the first change
and none of them enters the AND loop with an unwrapped sub-term.

**Why it matters**: `MatchWhereBuilder.and(...)` returns an `SQLAndBlock` for two or more
operands (line 305), and its sub-blocks carry none of the `NotBlock` wrapping the grammar adds.
That is the shape `g.V(id1, id2).has("age", 30)` produces, and it is now the only path by which
the extractor reaches an AND sub-term. A regression in the AND loop keeps the query correct and
silently reverts the plan to the class scan with an `@rid` post-filter — the exact
correct-rows-wrong-plan failure that hid for 117 commits and that step 5 exists to remove.

**Evidence**: input domain table for `findRidConditionInExpression`, row "base expression /
SQLBooleanExpression / OrBlock(1), OrBlock(n), AndBlock of bare leaves, bare leaf, negated leaf /
tested: OrBlock(n) and bare leaf only". See `#### C2`.

**Refutation considered**: `PredicateTraversalEquivalenceTest.ridInAndHas_andCompose_onSameAlias`
(line 245) exercises `g.V(alice, bob).has("age", 30)` end to end, so the shape is not unexercised
— but that test asserts row equivalence against the native pipeline and makes no claim about the
plan. It passes whether or not the promotion fires. Nothing in the tree asserts the plan shape
for the AND-composed case, at either the unit or the Gremlin level.

**Suggested test**:

```java
/**
 * A code-assembled AND of two bare conditions still promotes its @rid IN term, whichever
 * position it holds in the block. MatchWhereBuilder.and(...) produces this shape for
 * g.V(id1, id2).has("age", 30): an SQLAndBlock whose sub-blocks carry none of the grammar's
 * NotBlock wrapping. The AND loop no longer applies the term extractor itself, so the leaf
 * branch reached through recursion is the only thing that sees these sub-terms.
 */
@Test
public void unwrappedAndBlockWithRidTerm_isPromoted() {
  var ridLeaf =
      unwrapToLeafClause(parseWhere("SELECT FROM Comment WHERE @rid in [#25:7]"))
          .getBaseExpression();
  var otherLeaf =
      unwrapToLeafClause(parseWhere("SELECT FROM Comment WHERE name = 'foo'"))
          .getBaseExpression();
  var and = new SQLAndBlock(-1);
  // RID term second, so a loop that stops after the first sub-term fails this test.
  and.setSubBlocks(List.of(otherLeaf, ridLeaf));
  var clause = new SQLWhereClause(-1);
  clause.setBaseExpression(and);

  Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
  aliasFilters.put("c", clause);
  Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

  MatchExecutionPlanner.promoteStaticRidsFromFilters(aliasFilters, aliasPinnedRids, ctx);

  assertPromotedRids(aliasPinnedRids, "c", "#25:7");
}
```

A Gremlin-level sibling in `GremlinToMatchSmokeTest`, asserting that
`g.V(bob.id()).has("name", "Bob")` still yields a `FetchFromRidsStep` and no
`FetchFromClassExecutionStep`, would close the same gap at the boundary the three new by-id
plan-shape tests already cover.

### TC3 [should-fix] No test rejects a code-assembled negated RID condition

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/PromoteStaticRidsFromFiltersTest.java` (whole class; the negative cases end at line 767)

**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLWhereClause.java` lines 1131 (the new leaf branch) and 1297–1319 (`unwrapSingleElementTerm`, the `notBlock.negate` guard)

**Missing scenario**: a base expression that is a bare `SQLNotBlock` with `negate == true`
wrapping `@rid IN [...]` or `@rid = ...`. All 37 tests in the class use parsed clauses or
unwrapped leaves, and `unwrapToLeafClause` strips the pass-through `NotBlock` the grammar adds.
No test in the class contains a `NOT` at all.

**Why it matters**: the leaf branch made this input class newly reachable. Before the change a
bare `SQLNotBlock` fell through to `return null` without ever meeting the extractor; now it is
handed straight to `tryMatchRidInCondition`, and the only thing standing between it and
promotion is one `if (notBlock.negate) return null` in `unwrapSingleElementTerm`.
`MatchWhereBuilder.not` (line 421) builds exactly that node — `setSub` plus `setNegate(true)` —
so the shape is produced by the translator's own builder, not hypothetically. If that guard is
ever lost or refactored away, the planner pins the RID the query excludes as the fetch target
and `SELECT FROM [#X:Y]` returns precisely the record that must be filtered out.

**Evidence**: input domain table for `findRidConditionInExpression`, row "negated leaf / newly
reachable / tested: NO"; plus the grep over the test class returning zero `NOT` cases. See
`#### C3`.

**Refutation considered**: the guard predates this diff and was already load-bearing for a
negated term nested inside an AND block, where the old code applied the extractor directly. That
makes the guard exercised by production paths but not by any test — the class has no case that
would fail if the guard were removed, at either the old or the new entry point.

**Suggested test**:

```java
/**
 * A code-assembled NOT (@rid IN [...]) must not promote. MatchWhereBuilder.not builds an
 * SQLNotBlock with negate=true, and the leaf branch now hands that node straight to the term
 * extractor; only unwrapSingleElementTerm's negate guard stops the planner pinning the RID the
 * query excludes as its fetch target and returning exactly the record it must exclude.
 */
@Test
public void unwrappedNegatedRidList_isNotPromoted() {
  var ridLeaf =
      unwrapToLeafClause(parseWhere("SELECT FROM Comment WHERE @rid in [#25:7]"))
          .getBaseExpression();
  var negated = new SQLNotBlock(-1);
  negated.setSub(ridLeaf);
  negated.setNegate(true);
  var clause = new SQLWhereClause(-1);
  clause.setBaseExpression(negated);

  Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
  aliasFilters.put("c", clause);
  Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

  MatchExecutionPlanner.promoteStaticRidsFromFilters(aliasFilters, aliasPinnedRids, ctx);

  assertThat(aliasPinnedRids).isEmpty();
}
```

### TB1 [should-fix] The tree's only index-usage assertion never rules out a class scan

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBQueryMetricsStrategyTest.java`, method `indexedQuerySurfacesPlanWithFetchFromIndexStep` (line 329; the rendering assertion at lines 374–377)

**Issue**: every assertion in the scenario is a presence check. The structural half asserts that
the prefetch sub-plan contains a `FetchFromIndexStep`; the rendering half asserts
`+ PREFETCH` and `+ FETCH FROM INDEX` are present. Nothing asserts that the plan does not also
scan the class. Its two siblings both carry the mirror:
`planBackedScanSurfacesNonNullPlanWithoutFetchFromIndexStep` asserts
`doesNotContain("+ FETCH FROM INDEX")` (line 321), and
`byIdLookupSurfacesRidFetchPlanWhenTranslatedAndNoPlanWhenNative` asserts
`doesNotContain("+ FETCH FROM CLASS")`.

**Evidence**: falsifiability check — mutate the planner so the indexed alias's prefetch sub-plan
gains a `FetchFromClassExecutionStep` beside its index fetch (an index read narrowed to a
sub-predicate with the class still scanned for the rest). `containsStepOfType(..., index)` stays
true, `contains("+ PREFETCH")` stays true, `contains("+ FETCH FROM INDEX")` stays true. The test
passes on a plan that scans the class. See `#### C4`.

**Missing behavior**: the track's `## Validation and Acceptance` makes this scenario the answer
to "whether the `+ PREFETCH` sub-plan contains a `FetchFromIndexStep`", and the step-3 episode
records that it is the only Gremlin-level index-usage assertion in the tree. An index-usage
answer that cannot distinguish "reads through the index" from "reads through the index and also
scans" does not carry that weight.

**Suggested fix**:

```java
    assertThat(containsStepOfType(prefetch.getSubSteps(), FetchFromClassExecutionStep.class))
        .as("an indexed alias is read through the index alone, never scanned as well")
        .isFalse();
    assertThat(listener.planPrettyInCallback)
        .as("the rendered plan names the prefetch block and the index fetch, and no class scan")
        .contains("+ PREFETCH")
        .contains("+ FETCH FROM INDEX")
        .doesNotContain("+ FETCH FROM CLASS");
```

### TC4 [suggestion] The union boundary has no never-started-close and no failed-start re-arm case

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/MultiPlanMatchStepTest.java`, the re-iteration block at lines 433–594

**Production code**:
`core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/AbstractMatchPlanStep.java`
lines 598–629 (`close()`'s `NEW` → `CLOSED_UNSTARTED` mapping) and 561–590 (`reset()`'s
`CLOSED_UNSTARTED` → `NEW` edge); `MultiPlanMatchStep.replaceClosedPlanWithCopy` (lines 265–305)

**Missing scenario**: two of the four state edges the step-2 work added are covered on the
single-plan boundary only. `YTDBMatchPlanStepTest` has
`closeBeforeAnyIteration_thenReset_startsTheOriginalPlanRatherThanACopy` and
`planStartThrows_thenCloseAndReset_reArmsWithACopyRatherThanTheClosedOriginal`;
`MultiPlanMatchStepTest` has neither.

**Why it matters**: DR-M2 states the cost of getting `CLOSED_UNSTARTED` wrong in terms of the
union — "deep-copying a pristine plan (once per child in the multi-plan step) and dropping the
original with nothing left to close it". The per-child multiplier is what makes the union the
worse case, and it is the case with no test. The track's own acceptance criterion says mocked and
single-plan coverage alone satisfies nothing for the re-arm work.

**Evidence**: method inventory of `MultiPlanMatchStepTest` (33 test methods, none naming
`closeBefore`, `Unstarted`, or `StartThrows`) against the matching inventory of
`YTDBMatchPlanStepTest`. See `#### C5`.

**Refutation considered**: the state machine itself lives in `AbstractMatchPlanStep`, so the
`CLOSED_UNSTARTED` edges are shared and a regression in them fails the single-plan tests. What
is subclass-specific is the consequence — `MultiPlanMatchStep.replaceClosedPlanWithCopy` loops
over every child, so a wrong route into it mints N copies and orphans N originals rather than
one. The gap is a blast-radius gap, not a logic-coverage gap, which is why this is a suggestion
rather than a should-fix.

**Suggested test**:

```java
/**
 * A close that arrives before the union ever iterated must not send the later re-arm down the
 * copy path. Treating it as an ordinary close would deep-copy every child's pristine plan and
 * drop every original with nothing left to close it — the per-child form of the hazard
 * closeBeforeAnyIteration_thenReset_startsTheOriginalPlanRatherThanACopy pins on the
 * single-plan boundary.
 */
@Test
public void closeBeforeAnyIteration_thenReset_startsTheOriginalChildPlans() {
  var raw1 = rawVertex();
  var raw2 = rawVertex();
  var c1 = child(ListStream.of(vertexRow(raw1)));
  var c2 = child(ListStream.of(vertexRow(raw2)));

  var step = elementStep(c1, c2);
  step.close();
  step.close(); // still idempotent from the never-started close
  verify(c1.plan, never()).close();
  verify(c2.plan, never()).close();

  step.reset();
  var pass = drainPayloads(step);

  assertThat(pass).as("the originals run, so both children still yield their rows").hasSize(2);
  assertThat(step.getPlans()).containsExactly(c1.plan, c2.plan);
  verify(c1.plan, never()).copy(any());
  verify(c2.plan, never()).copy(any());
  verify(c1.plan, never()).reset(any());
  verify(c2.plan, never()).reset(any());
}
```

### TC5 [suggestion] No test drives the leak path that `close()`'s Javadoc exists to defend

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/YTDBMatchPlanStepTest.java`, the re-iteration block at lines 797–1093

**Production code**:
`core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/AbstractMatchPlanStep.java`
lines 600–615 (the `REARMED_AFTER_CLOSE` paragraph) and 455–470 (the copy install ahead of the
session rebind)

**Missing scenario**: a re-arm that installs the copy and then throws before the guarded plan
start, followed by `close()`. `openArming()` calls `replaceClosedPlanWithCopy()` before
`planContext()` and before the try that wraps `startPlanStream()`, so a throw from the session
rebind in between leaves a live, unstarted copy. `close()` releases it only because the
`REARMED_AFTER_CLOSE` state deliberately falls through the idempotence gate.

**Why it matters**: that paragraph is the sole justification for not gating the state, and
"the transaction rebind, for one" is named as the throw that reaches it. Nothing in either
boundary suite arranges it. A future edit that adds `REARMED_AFTER_CLOSE` to the gate — a
plausible tidy-up, since the state reads as "not yet running" — leaks the copy's cursors and
every existing test stays green.

**Evidence**: the two tests that touch the state after a re-arm,
`closeAfterAReArmedPass_closesThePlanCopyThatRan` and
`getPlan_tracksThePlanThatProducedTheCurrentPass_acrossACloseThenReset`, both drive the pass to
completion first, so the step is `OPEN` by the time `close()` runs. See `#### C6`.

**Refutation considered**: the mirror case the Javadoc also names — a re-arm that was never
driven — needs no test, because no copy exists yet and the close lands on the already-closed
original as a no-op. Only the install-then-throw window carries a live object.

**Suggested test**:

```java
/**
 * A re-arm that installs its plan copy and then throws before the guarded start leaves a live,
 * unstarted copy that only close() can release. openArming() installs the copy before the
 * session rebind and outside the try that guards startPlanStream(), and close() therefore does
 * not gate on REARMED_AFTER_CLOSE. Gating on it would leak the copy's cursors.
 */
@Test
public void reArmThatThrowsAfterInstallingTheCopy_stillClosesThatCopy() {
  when(stream.hasNext(ctx)).thenReturn(false);
  var copiedPlan = stubPlanCopyDelivering();

  var step = elementStep("v");
  drainPayloads(step);
  step.close();

  // Fail the rebind that runs between the copy install and the plan start.
  doThrow(new IllegalStateException("rebind failed")).when(ctx).setDatabaseSession(any());

  step.reset();
  assertThatExceptionOfType(IllegalStateException.class)
      .isThrownBy(step::processNextStart)
      .withMessageContaining("rebind failed");

  step.close();
  verify(copiedPlan, times(1)).close();
}
```

### TB2 [suggestion] The guard for the sub-plan-free root alias asserts step tallies and no row

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/MatchStatementExecutionTest.java`, method `testPrefetchedRootAliasOfEdgePatternPublishesItsFetchOnce` (line 2381)

**Issue**: the test is the only coverage for the planner change that hands a prefetched root
alias the sub-plan-free `MatchFirstStep` constructor (`MatchExecutionPlanner.addStepsFor`, lines
5424–5455). It asserts a prefetch-step count, an empty `getSubSteps()` on the root, and a
whole-plan fetch tally, then calls `result.close()` without reading a row.

**Evidence**: behaviour trace — `session.query` constructs a `LocalResultSet`, whose constructor
calls `executionPlan.start()` (`LocalResultSet.java` lines 35–48), so `MatchFirstStep`'s
prefetch-cache lookup and its `assert executionPlan != null` do run. What does not run is the
`MatchStep` chain past the first row. A prefetch cache that is present but holds the wrong or an
empty row set produces a plan of the asserted shape and zero rows, and the test passes. Its two
siblings for the mirror-image case, `notPatternBuildRootKeepsItsScanForAPrefetchedOrigin` and
`hashJoinBranchBuildRootKeepsItsScanForAPrefetchedScanAlias`, were written in the same step and
both assert the exact row set. See `#### C7`.

**Missing behavior**: that the query the test names still returns its rows. Two Person aliases
joined by `Friend` is a small, fully determined set in this fixture.

**Suggested fix**:

```java
    // The tally proves the plan's shape; the rows prove the prefetched root still feeds the
    // pattern. A cache that exists but holds the wrong rows keeps the shape and loses the answer.
    var pairs = result.stream()
        .map(r -> nameOf(r.getProperty("a")) + "->" + nameOf(r.getProperty("b")))
        .collect(Collectors.toSet());
    assertEquals(expectedFriendPairs(), pairs);
    result.close();
```

### TB3 [suggestion] The by-id scenario runs both kill-switch arms and compares neither output

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/scenarios/YTDBQueryMetricsStrategyTest.java`, method `byIdLookupSurfacesRidFetchPlanWhenTranslatedAndNoPlanWhenNative` (line 394)

**Issue**: the method executes `g().V(personId)` twice, once with the translator pinned on and
once with it pinned off, and discards both `toList()` results. Every assertion is about the
captured plan. The two arms sit in one method precisely because they are the same traversal under
two source paths, which makes the multiset comparison free.

**Evidence**: assertion precision check — the assertions are `notified`, `executionPlan`
non-null, four `containsStepOfType` calls, and two `planPrettyInCallback` substring checks. None
reads a returned vertex. A promotion that pinned the wrong RID, or that fetched the record twice,
satisfies all of them. Result-multiset equality between the translated and native paths is the
contract the branch holds everywhere, and this method is the only place in the suite where both
paths run side by side. See `#### C8`.

**Missing behavior**: that the translated arm and the native arm return the same vertex.

**Suggested fix**: capture each arm's ids and assert them equal.

```java
    final List<Object> translatedIds;
    try (var q = g().V(personId)) {
      translatedIds = q.toList().stream().map(Vertex::id).toList();
    } finally {
      restoreTranslator.run();
    }
    // ... native arm, same shape, into nativeIds ...
    assertThat(translatedIds)
        .as("the translated by-id path returns the same vertex as the native one")
        .isEqualTo(nativeIds);
    assertThat(translatedIds).containsExactly(personId);
```

### TC6 [suggestion] The `$`-branch precedence chain gained a level and one pairing is unpinned

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLSuffixIdentifierTest.java`, methods `dollarNameResolvesFromContextVariable` (line 72) and `dollarNameProjectionColumnWinsOverMetadataAndTemporaryProperty` (line 130)

**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLSuffixIdentifier.java`
lines 160–166 (the context-variable branch) and 188–190 (the new projection probe)

**Missing scenario**: a `$`-prefixed name that is both a live context variable and a projection
column. The class Javadoc now documents a four-level chain — context variable, then projection
column, then metadata, then temporary property — and the new test pins the lower three against
each other. The top pairing has no case.

**Why it matters**: the projection probe was inserted into a branch that already had a
precedence order, and the ordering claim is now load-bearing in the class Javadoc. A future edit
that moves the probe above the context-variable check (a plausible reading of "the projection is
the row's own data") would silently change which value a `LET`-bound name resolves to, and no
test would notice.

**Evidence**: the `$`-branch source order at lines 160–200 against the six `dollarName*` test
methods; five cover a single source each, one covers projection-over-metadata-and-temporary,
none covers context-variable-over-projection. See `#### C9`.

**Refutation considered**: a collision is unlikely by construction — `StartStepRecogniser`'s
Javadoc records that `$g2m_` is chosen to be distinct from GQL's `$c` prefix and from
`MatchExecutionPlanner.DEFAULT_ALIAS_PREFIX`, and user `LET` names are unlikely to land on any of
them. The gap is a documented-contract gap rather than a live defect, which is why it stays a
suggestion.

**Suggested test**:

```java
/**
 * A context variable shadows a same-named projection column: the ctx lookup runs before the
 * per-record dispatch, so the projection probe never sees the name. Pins the top of the
 * $-branch precedence chain, which the projection-over-metadata test cannot reach.
 */
@Test
public void dollarNameContextVariableWinsOverProjectionColumn() {
  var ctx = new BasicCommandContext(session);
  ctx.setVariable("$g2m_v0", "from-context");
  var record = new ResultInternal(session);
  record.setProperty("$g2m_v0", "from-projection");

  assertThat(resolve("$g2m_v0", record, ctx)).isEqualTo("from-context");
}
```

## Evidence base

#### C1 dedup-key collision in `toPromotedSqlRidList` — CONFIRMED

Survived. `MatchExecutionPlanner.java:5798` keys a `Set<Long>` on
`(collection.getValue().longValue() << 32) ^ position.getValue().longValue()`; cluster ids
occupy bits 32–46 after the shift, so any position with a bit at or above 32 aliases onto a
different cluster's key, and `#0:4294967296` / `#1:0` is the minimal witness. `SQLInteger`
(line 36) stores a `Long` for literals past `Integer.MAX_VALUE`, and
`StartStepRecogniser.RidKey(int collectionId, long position)` (line 297) is the correct key
already present in this feature. The one dedup test uses positions 7 and 8.

#### C2 AND-loop rewrite unexercised by any unit test — CONFIRMED

Survived. The diff replaced `termExtractor.apply(sub)` plus recursion with recursion alone in the
`SQLAndBlock` loop (`SQLWhereClause.java:1117–1128`). All four new tests set a single bare leaf
as `baseExpression` via `unwrapToLeafClause`, so none enters the loop.
`MatchWhereBuilder.and` (line 305) returns an `SQLAndBlock` for two or more operands.

#### C3 no `NOT` case anywhere in `PromoteStaticRidsFromFiltersTest` — CONFIRMED

Survived. Grep over the class's 37 test methods returns no `NOT` case, parsed or assembled.
`unwrapToLeafClause` explicitly strips the grammar's pass-through `NotBlock` before building the
leaf clause (its own comment says so). `MatchWhereBuilder.not` (line 421) builds
`SQLNotBlock` with `setNegate(true)`, and the leaf branch now routes such a node to
`tryMatchRidInCondition`, where `unwrapSingleElementTerm`'s `notBlock.negate` check (line 1313)
is the only rejection.

#### C4 index scenario admits an index-plus-scan plan — CONFIRMED

Survived. Mutation: add a `FetchFromClassExecutionStep` to the indexed alias's prefetch sub-plan
alongside the index fetch. `indexedQuerySurfacesPlanWithFetchFromIndexStep` asserts
`listener.executionPlan` non-null, `containsStepOfType(planStepsInCallback, FetchFromIndexStep)`,
`prefetch != null`, `containsStepOfType(prefetch.getSubSteps(), FetchFromIndexStep)`,
`planPrettyInCallback` contains `+ PREFETCH` and `+ FETCH FROM INDEX`. Every one of those holds
under the mutation, so the test passes. Sibling `planBackedScanSurfacesNonNullPlanWithoutFetchFromIndexStep`
carries `doesNotContain("+ FETCH FROM INDEX")` at line 321 and
`byIdLookupSurfacesRidFetchPlanWhenTranslatedAndNoPlanWhenNative` carries
`doesNotContain("+ FETCH FROM CLASS")`, so the asymmetry is local to this one scenario.

#### C5 union boundary missing two lifecycle cases — CONFIRMED

Survived. `MultiPlanMatchStepTest`'s 33 test methods include no name matching `closeBefore`,
`Unstarted`, or `StartThrows`; `YTDBMatchPlanStepTest` carries both
`closeBeforeAnyIteration_thenReset_startsTheOriginalPlanRatherThanACopy` and
`planStartThrows_thenCloseAndReset_reArmsWithACopyRatherThanTheClosedOriginal`. DR-M2 states the
per-child cost of the `CLOSED_UNSTARTED` mistake in terms of the multi-plan step specifically.

#### C6 install-then-throw window untested — CONFIRMED

Survived. `AbstractMatchPlanStep.openArming()` calls `replaceClosedPlanWithCopy()` at line 453,
`planContext()` at line 455, and the session rebind after that, all outside the try that wraps
`startPlanStream()`. `close()`'s Javadoc (lines 600–615) names this window as the reason
`REARMED_AFTER_CLOSE` is not gated. The two tests that close after a re-arm both complete the
pass first, leaving the step `OPEN`, so neither observes `close()` in the `REARMED_AFTER_CLOSE`
state with a live copy installed.

#### C7 prefetched-root test reads no row — CONFIRMED

Survived, narrowed. `LocalResultSet`'s constructor calls `executionPlan.start()`
(`LocalResultSet.java:35–48`), so the plan's step chain is started and
`MatchFirstStep.internalStart`'s `assert executionPlan != null` does run at `session.query(...)`
time. That refutes the stronger claim "the plan is never executed" and leaves the weaker one:
`testPrefetchedRootAliasOfEdgePatternPublishesItsFetchOnce` never pulls a row, so a prefetch
cache present but holding the wrong rows keeps the asserted shape and passes. The two
`HashJoinPlannerIntegrationTest` tests written in the same step both assert the enumerated row
set.

#### C8 by-id scenario compares neither arm's output — CONFIRMED

Survived. The method body between lines 394 and 458 contains two `q.toList()` calls whose results
are not assigned. The assertions read `listener.notified`, `listener.executionPlan`,
`containsStepOfType`, `findStepOfType`, and `listener.planPrettyInCallback` only. The method is
the only place in the class where the same traversal runs under both kill-switch settings.

#### C9 `$`-branch top precedence pairing unpinned — CONFIRMED

Survived, low value. Source order in `SQLSuffixIdentifier.execute(Result, CommandContext)` is
context variable (line 160), projection column (line 188), metadata (line 192), temporary
property (line 195). The six `dollarName*` tests cover each source alone plus
projection-over-metadata-and-temporary; the context-variable-over-projection pairing has no case.
Reachability is thin because `$g2m_` and `DEFAULT_ALIAS_PREFIX` are namespaced away from user
`LET` names, so the gap is against the documented contract rather than a live defect.

#### C10 the three new `GremlinToMatchSmokeTest` by-id tests assert no rows — REFUTED

The claim: `translatedSingleIdLookupFetchesByRid`, `translatedMultiIdLookupFetchesByRid`, and
`translatedHasIdLookupFetchesByRid` all route through `capturedTranslatedPlan`, which runs
`admin.toList()` and returns only `listener.executionPlan`, so a promotion that pinned the wrong
RID would satisfy every assertion. The duplicate-RID regression the track records is exactly a
result-multiset defect that plan-shape assertions cannot see, which made the claim look strong.

Refuted by the sibling coverage in the same class and in the equivalence suite.
`GremlinToMatchSmokeTest.translatesSingleIdLookup` (line 201) asserts the returned vertex's id
and its `name` property; `translatesMultiIdLookupDistinctIds` (line 257) asserts the returned id
set and name set; `translatedSingleIdLookup_nonExistentRid_returnsEmpty` and
`translatedMultiIdLookup_mixedExistingAndMissing_returnsOnlyExisting` cover the partial and empty
cases. All four assert `countBoundarySteps == 1` first, so they run on the translated path.
For the `hasId` family,
`PredicateTraversalEquivalenceTest.hasIdSingle_matchesNative`, `hasIdMulti_matchesNative`, and
`hasIdDuplicate_isSetMembership_matchesNative` (lines 179, 192, 212) compare the translated
multiset against the native pipeline, and the last of those is the test that caught the
duplicate-RID regression. The three new tests are deliberately plan-shape-only guards layered on
top of existing row-level coverage, which is the right split. No finding.

#### C11 `MatchPrefetchStep.getSubSteps()` dereferences a possibly-null sub-plan — REFUTED

The claim: the new override is `List.copyOf(prefetchExecutionPlan.getSteps())` with no null
guard, while `canBeCached()` two lines above still tests `prefetchExecutionPlan == null`. The
inconsistency suggests null is a live value, in which case `EXPLAIN` on such a plan would move
from an empty `subSteps` list to a `NullPointerException`, and no test covers it.

Refuted on reachability. The constructor carries
`assert MatchAssertions.checkNotNull(prefetchExecPlan, "prefetch execution plan")`
(`MatchPrefetchStep.java:74`), and the only construction site is
`MatchExecutionPlanner.addPrefetchSteps`, which always builds a sub-plan before constructing the
step. The null test in `canBeCached()` is defensive legacy from before the assert. A test for an
unconstructible state has no value. No finding.

#### C12 the leaf branch widens what the search accepts, not only where it looks — REFUTED

The claim: `findRidConditionInExpression` previously returned `null` for any top-level expression
that was neither an `SQLOrBlock` nor an `SQLAndBlock`, and now applies the term extractor to it.
That could admit shapes the old code rejected by construction — a multi-branch disjunction
reached by some other route, or a term whose extractor unwrapping is looser than the tree walk.
Admitting one branch of a disjunction as the fetch target would drop the other branch's rows.

Refuted by reading both halves. The `SQLOrBlock` arm still returns `null` for `subBlocks.size()
!= 1` before anything else runs (line 1112), so a real disjunction never reaches the leaf branch
at the top level or through the AND loop. `unwrapSingleElementTerm` (lines 1297–1319) rejects a
multi-element `OrBlock` or `AndBlock` and a negated `NotBlock`, and both extractors run through
it. `unwrappedNonRidCondition_isNotPromoted` (line 760) pins the non-RID case. The widening is
confined to where the search looks. The residual — that the negate guard is now the sole
rejection for a newly reachable input class and has no test — is reported separately as TC3.
