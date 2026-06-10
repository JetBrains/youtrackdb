<!--MANIFEST
dimension: test-completeness
track: track-1
iteration: 1
target_range: f1cf786fbdc40e99c0a2c4b3f0ad2ab736d56eb7..HEAD
verdict: changes-requested
counts: {blocker: 1, should-fix: 4, suggestion: 2}
evidence_base: present
cert_index: [C1, C2, C3, C4, C5, C6, C7]
flags: []
index:
  - {id: TC1, sev: blocker,    anchor: "tc1-maxrecordsperentry-cap-boundary-has-no-test-because-it-is-unenforced", loc: "QueryResultCache.java:169-180; GlobalConfiguration.java:971-979; CachedEntry.java:265-268", cert: C1, basis: "psi+read"}
  - {id: TC2, sev: should-fix, anchor: "tc2-delta-builder-collapse-to-deleted-cells-untested", loc: "DeltaBuilder.java:159-192", cert: C2, basis: "read"}
  - {id: TC3, sev: should-fix, anchor: "tc3-collapsed-create-driven-out-of-where-cell-untested", loc: "DeltaBuilder.java:163-178", cert: C3, basis: "read"}
  - {id: TC4, sev: should-fix, anchor: "tc4-denylist-members-eval-and-math_random-untested", loc: "NonDeterministicQueryDetector.java:74-141", cert: C4, basis: "psi+read"}
  - {id: TC5, sev: should-fix, anchor: "tc5-order-by-tie-cmp-zero-merge-branch-untested", loc: "CachedResultSetView.java:239-248", cert: C5, basis: "read"}
  - {id: TC6, sev: suggestion, anchor: "tc6-inflightlookup-true-branch-uncovered", loc: "QueryResultCache.java:97-135", cert: C6, basis: "read+episode"}
  - {id: TC7, sev: suggestion, anchor: "tc7-empty-array-and-empty-map-param-boundary-untested", loc: "CacheKey.java:forArgs/forParams", cert: C7, basis: "read"}
-->

## Findings

### TC1 [blocker] maxRecordsPerEntry cap boundary has no test because it is unenforced

**File:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/QueryResultCacheTest.java` (no test); `CachedResultSetViewTest.java` (no test)
**Production code:** `QueryResultCache.java:169-180` (`put`), `GlobalConfiguration.java:971-979` (knob), `CachedEntry.java:265-268` (`sizeHint`)

**Missing scenario.** The per-entry record cap — one of the two bounded-memory knobs the review brief names as a boundary to test ("the per-entry record cap actually tested at the boundary") — has no test at, under, or over `maxRecordsPerEntry`. There is no test that populates an entry past the cap and asserts the documented overflow: entry removed, key routed to `nonCacheableKeys`, an overflow metric counted, the consumer still receiving every row.

**Why it matters.** The cap is not merely untested — it is **unenforced**. `QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY` is defined (`GlobalConfiguration.java:971`, default 10000) and documented as "When populating crosses this cap the entry overflows: it is removed from the cache and its key is marked non-cacheable." `CachedEntry.sizeHint()` (`CachedEntry.java:266`) carries the Javadoc "the LRU eviction cap and the per-entry record bound read this." Yet `grep` over the whole production tree finds the knob read **nowhere** and `sizeHint()` called **nowhere**: `QueryResultCache.put` enforces only `entries.size() > maxEntries` (the entry-count LRU), never a per-entry record count, and `CachedResultSetView.pullOneFromStream` (`CachedResultSetView.java:267-293`) appends to `entry.results` without bound. A RECORD or K0_NONE query over a class with > 10000 matching rows therefore caches an unbounded `List<Result>` per entry for the transaction's lifetime — the exact OOM the knob exists to prevent (D8). No test can cover the cap boundary because the enforcement code does not exist; the missing test is the signal that exposed the missing production path.

**Evidence.** Input-domain entry: `per-entry record count` / `int` / boundaries {0, maxRecordsPerEntry−1, maxRecordsPerEntry, maxRecordsPerEntry+1} / **NO** (knob never read; `sizeHint` never called).

**Refutation considered.** Could the cap be enforced elsewhere — in `CachedEntry` on append, or in the view? Read `CachedEntry` (no append-time check; `results.add` is unguarded at `CachedResultSetView.java:279`) and `QueryResultCache` (only the count LRU). Could a caller-side bound make it unreachable? No — `query()` row counts are unbounded by design (RECORD shape is precisely the un-paginated SELECT; LIMIT routes to K0_NONE). VERDICT: CONFIRMED — meaningful gap; the per-entry-cap boundary is uncoverable as written because the production cap is absent.

**Suggested test** (add to `QueryResultCacheTest`, once enforcement lands):
```java
@Test
public void entryOverflowingPerEntryRecordCapIsRemovedAndRoutedNonCacheable() {
  GlobalConfiguration.QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY.setValue(2);
  var metrics = new QueryCacheMetrics();
  var cache = new QueryResultCache(metrics);
  var k = key("select from OUser");
  // A stream/entry whose populate yields 3 rows (> cap of 2).
  var entry = recordEntryWithRows(3);   // helper: seed results to size 3
  cache.put(k, entry);
  // Driving the view to materialize past the cap must overflow:
  drainView(cache, k, entry);
  Assert.assertEquals("over-cap entry counts an overflow", 1, metrics.getOverflows());
  Assert.assertTrue("over-cap key routed out of cache", cache.isNonCacheable(k));
  Assert.assertEquals("over-cap entry removed", 0, cache.size());
  // and the consumer still received all 3 rows (boundary: cap+1).
}
```
(If the team's intent is to defer the cap to v2, the knob and `sizeHint()` should be removed or marked unused so the absence is not mistaken for a tested guarantee — but the brief lists this boundary as in-scope for Track 1.)

### TC2 [should-fix] DeltaBuilder collapse-to-DELETED cells untested

**File:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/DeltaBuilderTest.java`
**Production code:** `DeltaBuilder.java:159-192` (dispatch switch); collapse semantics at `FrontendTransactionImpl` lines 591-612 (per track file)

**Missing scenario.** The brief names "collapse semantics (CREATE→DELETE and UPDATE→DELETE collapse to DELETED)" as a boundary. `DeltaBuilderTest.postPopulateDeleteSkipsAndDoesNotInject` covers a *genuine* DELETED op on a committed record (`committedRec` then `stage(DELETED)`). It does not cover an op that became DELETED by **collapse**: a post-populate CREATE then DELETE on the same RID (collapses to DELETED), or an UPDATE then DELETE. These reach the same `case RecordOperation.DELETED -> skipSet.add(rid)` arm, but the cache-membership and version-restamp state differ (a CREATE→DELETE RID was never committed, may or may not be in `cachedRids`), so the "always skip regardless of the other two facts" claim in the dispatch-table Javadoc (`DeltaBuilder.java:64,72-73`) is asserted only for the simple case.

**Why it matters.** A regression that made DELETED dispatch consult `cached_at_build` or `match_after` (e.g. injecting a re-created-then-deleted row) would pass every current test, because no test stages a collapsed DELETE. The collapse path is the load-bearing correctness floor the plan (D5/D21) repeatedly flags.

**Evidence.** Input-domain entry: `op.type=DELETED via collapse` / boundaries {CREATE→DELETE, UPDATE→DELETE, genuine DELETE} / genuine DELETE **YES** (`postPopulateDeleteSkips...`); collapsed forms **NO**.

**Refutation considered.** Is the collapse-to-DELETED itself covered in `TransactionMutationVersionTest`? That suite asserts the *version stamp* survives collapse, not the *delta dispatch* on a collapsed-DELETED op. Does the equivalence suite hit it? `TxResultCacheInvariantsTest` stages only single mutations per scenario (one CREATE, one UPDATE, one DELETE), never a collapse-to-DELETED. VERDICT: CONFIRMED.

**Suggested test:**
```java
@Test
public void collapsedCreateThenDeleteSkipsAndNeverInjects() {
  db.begin();
  var rec = newRec(5);                 // post-populate CREATE
  var rid = ridOf(rec);
  var entry = recordEntry(null, null, List.of()); // populate before the create
  stage(rec, RecordOperation.DELETED); // collapses CREATE->DELETE to DELETED
  assertEquals(RecordOperation.DELETED, tx().getRecordEntry(rid).type);
  var cursor = DeltaBuilder.buildForRecord(entry, tx(), ctx(null));
  assertTrue(cursor.shouldSkip(rid));
  assertEquals("a collapsed create+delete never injects", 0, cursor.injectSize());
  db.rollback();
}
```

### TC3 [should-fix] Collapsed-create-driven-out-of-WHERE dispatch cell untested

**File:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/DeltaBuilderTest.java`
**Production code:** `DeltaBuilder.java:163-178` (`CREATED` arm; `cached=true, match=false`)

**Missing scenario.** The dispatch table (`DeltaBuilder.java:56-65`) has a `CREATED true false → skip` cell: "collapsed update drove WHERE false; drop the cached row." `collapsedCreateAlreadyCachedSkipsAndReinjects` covers `CREATED true true` (the post-update row still matches, so skip + reinject). No test covers the twin where the collapsed update drives the cached row *out* of the WHERE clause, so the `if (matchAfter)` false branch at `DeltaBuilder.java:169` is never exercised for a cached CREATED op.

**Why it matters.** A cached row whose in-tx update should remove it from the result (WHERE no longer satisfied) must be skipped with **no** reinject. If `matchAfter` were mis-evaluated for the collapsed-create case, the row would be reinjected and the view would emit a row a fresh execution would not — a direct I10 violation. The symmetric UPDATED cell *is* tested (`postPopulateUpdateThatFailsWhereSkipsWithoutInject`), which makes the CREATED-side omission a clear asymmetry.

**Evidence.** Input-domain entry: `(op.type=CREATED, cached=true, matchAfter)` / boundaries {true, false} / `true` **YES**; `false` **NO**.

**Refutation considered.** Could the equivalence suite cover it? No — its CREATE scenarios add a matching record (`FIELD=99`) and never stage a cached-create-then-update-out-of-WHERE. VERDICT: CONFIRMED.

**Suggested test:**
```java
@Test
public void collapsedCreateDrivenOutOfWhereSkipsWithoutReinject() {
  db.begin();
  var rec = newRec(5);                 // CREATED, matches WHERE n > 0
  var rid = ridOf(rec);
  var where = parseWhere("SELECT FROM " + CLASS_NAME + " WHERE " + FIELD + " > 0");
  var entry = recordEntry(where, null, List.of(rec)); // treated as cached
  rec.setProperty(FIELD, -1);          // collapsed update drives it out of WHERE
  stage(rec, RecordOperation.UPDATED); // op stays CREATED, version re-stamped
  var cursor = DeltaBuilder.buildForRecord(entry, tx(), ctx(null));
  assertTrue("stale cached copy skipped", cursor.shouldSkip(rid));
  assertEquals("no reinject once it fails WHERE", 0, cursor.injectSize());
  db.rollback();
}
```

### TC4 [should-fix] Denylist members eval and math_random are untested

**File:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/NonDeterministicQueryDetectorTest.java`
**Production code:** `NonDeterministicQueryDetector.java:74-141`

**Missing scenario.** The brief asks for "each denylist member" to be tested. The denylist is `{sysdate, uuid, eval, math_random}` (`NonDeterministicQueryDetector.java:75`). The suite tests `sysdate` (top-level and nested), `uuid`, and zero-arg `date()`, but never `eval(...)` and never `math_random()`. Both are real registered functions: `SQLFunctionEval` is registered, and `math_random` resolves through the reflective `register("math_", Math.class)` factory (`CustomSQLFunctionFactory.java:25`) to `Math.random()`.

**Why it matters.** The detector is **fail-open** by design (`NonDeterministicQueryDetector.java:56-59`): a denylist member that fails to match silently caches a non-deterministic query, returning a stale value from a memoized result — a correctness violation invisible to coverage metrics (the `for`-loop in `isNonDeterministicFunction` is "covered" by the `sysdate` test, so the two unverified names hide behind line coverage). A typo or case mismatch in either `eval` or `math_random` would never be caught. The Track-1 plan defers the I5 enumeration-completeness test to Track 2, so per-member assertions are the only Track-1 guard.

**Evidence.** Input-domain entry: `denylisted function name` / boundaries {sysdate, uuid, eval, math_random, zero-arg date} / sysdate/uuid/date **YES**; eval/math_random **NO**.

**Refutation considered.** Are `eval`/`math_random` unreachable in this dialect? No — both are registered functions and parse as `SQLFunctionCall` nodes the walk visits. Does any other test parse them? `grep` for `eval`/`math_random` across the cache test package: only the production file. VERDICT: CONFIRMED.

**Suggested test:**
```java
@Test
public void evalIsNonDeterministic() {
  Assert.assertTrue(isNonDeterministic("select eval('1+1') from OUser"));
}
@Test
public void mathRandomIsNonDeterministic() {
  Assert.assertTrue(isNonDeterministic("select from OUser where age > math_random()"));
}
```

### TC5 [should-fix] ORDER BY tie (cmp == 0) merge branch untested

**File:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/CachedResultSetViewTest.java`
**Production code:** `CachedResultSetView.java:239-248` (`computeNextRecord` both-heads-present arm)

**Missing scenario.** The brief lists "ORDER BY tie-breaking in the sorted merge." Every RECORD-merge test (`createInjectIsSortedIntoCachedRows` {10,30}+{20}, `updateRepositionsRowViaSkipPlusInject`, `injectNeverPrecedesEarlierUnpulledStreamRow`) uses **distinct** ORDER BY key values, so `orderBy.compare(deltaHead, cacheHead, ctx)` is always strictly positive or negative — never zero. The documented "ties favour the inject side" path (`cmp <= 0` taking `delta.advanceInject()` when `cmp == 0`, `CachedResultSetView.java:244`) is never executed. The class Javadoc (`CachedResultSetView.java:46-48`) reasons about ties at length, but no test pins the behavior.

**Why it matters.** When a cache row and an inject row carry the same ORDER BY key (common: ORDER BY a low-cardinality field, or a post-populate CREATE with a duplicate sort key), the merge must emit both exactly once with no loss or duplication. A regression flipping the tie comparison to `cmp < 0` would drop or duplicate a row at equal keys and pass every current test. The `noOrderByEmitsAllRowsWithoutLoss` test exercises the `orderBy == null` branch, not the `cmp == 0` branch of a real comparator.

**Evidence.** Input-domain entry: `cmp(deltaHead, cacheHead)` / boundaries {<0, 0, >0} / <0,>0 **YES**; ==0 **NO**.

**Refutation considered.** Could the equivalence suite hit a tie? Its CREATE scenario injects `FIELD=99` (distinct from seeds 0..2) under ORDER BY ASC, so no tie. VERDICT: CONFIRMED.

**Suggested test:**
```java
@Test
public void injectWithEqualOrderByKeyEmitsBothExactlyOnce() {
  var orderBy = parseOrderBy("SELECT FROM " + CLASS_NAME + " ORDER BY " + FIELD + " ASC");
  var entry = recordEntry(orderBy, List.of(newRec(10), newRec(20)));
  var inject = List.<Result>of(resultOf(newRec(10))); // tie with the cached 10
  var view = new CachedResultSetView(entry, cursor(Set.of(), inject), db, tx(), null, ctx());
  var values = drainValues(view);
  assertEquals("tie must not drop or duplicate", List.of(10, 10, 20), values);
}
```

### TC6 [suggestion] inFlightLookup true-branch is uncovered

**File:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/QueryResultCacheTest.java`
**Production code:** `QueryResultCache.java:97-135` (`inFlightLookup` guard, `if (inFlightLookup) return null` at 127)

**Missing scenario.** The test-class Javadoc claims to cover "the two re-entrancy guards' lookup-level boolean," but no test ever observes `inFlightLookup == true`. The Step 3 episode states plainly: "The lookup-level `inFlightLookup` guard cannot be unit-tested in isolation (nothing inside `lookup` re-enters the cache)." So the `return null` early-out at line 127 is unreachable by any current test and by the wired path (the session's `cacheCodeDepth` guard short-circuits *before* `lookup` is re-entered).

**Why it matters.** This is the weaker of the two guards (the brief notes both `cacheCodeDepth > 0` bypass and the `inFlightLookup` path). Because the field is package-visible only through `lookup`, the branch is dead under the current wiring. A small white-box test could pin it, or — if it is genuinely redundant against `cacheCodeDepth` — the team should note it as belt-and-suspenders rather than leave a Javadoc claim the suite does not back.

**Evidence.** Input-domain entry: `inFlightLookup state at lookup` / boundaries {false, true} / false **YES**; true **NO**.

**Refutation considered.** Is the guard exercised by any integration test? `TxResultCacheWiringTest.flagOn_reentrantQueryBypassesCache` exercises `cacheCodeDepth`, not `inFlightLookup` — the depth guard bypasses before `lookup` runs, so `inFlightLookup` stays false. VERDICT: CONFIRMED (low value: the branch is correct-by-construction and arguably redundant, hence suggestion not should-fix).

### TC7 [suggestion] Empty-array and empty-map param boundary untested

**File:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/CacheKeyTest.java`
**Production code:** `CacheKey.forArgs` (`args == null || args.length == 0`), `CacheKey.forParams` (`params == null || params.isEmpty()`)

**Missing scenario.** `forArgs`/`forParams` each branch on null-or-empty, routing both to the shared `Collections.emptyMap()`. `CacheKeyTest` exercises the `null` arm (`forArgs(stmt, null)`) but never an explicit empty `Object[]{}` or an empty `Map`. The two inputs take the same branch, so the empty-collection sub-case (length 0 / `isEmpty()`) is asserted only transitively.

**Why it matters.** Low risk — same code path as null — but a caller that passes `new Object[0]` or `Map.of()` must produce a key equal to the no-arg key, and an explicit test would pin that two callers (`query(sql)` vs `query(sql, new Object[0])`) collide on one entry. Cheap to add; closes the documented empty-param boundary the class Javadoc describes.

**Evidence.** Input-domain entry: `params` / boundaries {null, empty, 1-elem, n-elem} / null/1/n **YES**; empty **NO**.

**Refutation considered.** Behavior is trivially correct (both reach `emptyMap()`). VERDICT: LOW VALUE — reported as suggestion.

## Evidence base

#### C1 — maxRecordsPerEntry is defined and documented but never enforced or read
CONFIRMED-as-issue. `grep -rn "maxRecordsPerEntry|MAX_RECORDS_PER_ENTRY|sizeHint"` over `core/src/main` returns only the knob definition (`GlobalConfiguration.java:971-979`) and `CachedEntry.sizeHint()` (`CachedEntry.java:266`); no read site, no caller. `QueryResultCache.put` (read in full, lines 169-180) enforces only `entries.size() > maxEntries`. `CachedResultSetView.pullOneFromStream` (lines 267-293) appends unbounded. PSI project confirmed open and matching the working tree (`steroid_list_projects` → `design.md` at repo root), so the no-caller claim is reference-accurate, not a grep artifact.

#### C2 — collapse-to-DELETED dispatch cells covered only for genuine DELETE
CONFIRMED-as-issue. `DeltaBuilder.java:159-192` switch read in full; the `DELETED` arm is reached by `postPopulateDeleteSkipsAndDoesNotInject` (genuine committed-record delete via `committedRec` + `stage(DELETED)`). No test stages CREATE→DELETE or UPDATE→DELETE collapse. `TransactionMutationVersionTest` asserts the version stamp across collapse, not the delta dispatch.

#### C3 — CREATED cached=true match=false arm untested
CONFIRMED-as-issue. `DeltaBuilder.java:163-178`: the `if (matchAfter)` false branch in the `cachedAtBuild` sub-case is never reached. `collapsedCreateAlreadyCachedSkipsAndReinjects` (DeltaBuilderTest:304) drives only the `matchAfter == true` path; the symmetric UPDATED-false cell *is* tested (`postPopulateUpdateThatFailsWhereSkipsWithoutInject`:239), establishing the asymmetry.

#### C4 — eval and math_random denylist members never parsed in any test
CONFIRMED-as-issue. `NonDeterministicQueryDetector.java:75` denylist `{sysdate, uuid, eval, math_random}`. `NonDeterministicQueryDetectorTest` (read in full) tests sysdate/uuid/date only. `math_random` resolves via `CustomSQLFunctionFactory.java:25` `register("math_", Math.class)` → `Math.random()` (verified by grep); `SQLFunctionEval` is a registered function. Both parse as `SQLFunctionCall` nodes the walk visits. `grep` for `eval`/`math_random` in the cache test package: production file only.

#### C5 — sorted-merge tie (cmp == 0) branch never executed
CONFIRMED-as-issue. `CachedResultSetView.java:239-248` both-heads arm; the `cmp <= 0` inject-favoured path is reachable only at equal ORDER BY keys. All RECORD-merge tests use distinct key values {10,20,30}/{1,2,9}; `grep` of `CachedResultSetViewTest` confirms no equal-key inject case. `noOrderByEmitsAllRowsWithoutLoss` exercises the `orderBy == null` branch (cmp forced to -1), a different code path.

#### C6 — inFlightLookup true-branch dead under current wiring
CONFIRMED-as-issue (low value). `QueryResultCache.java:127` early-out. Step 3 episode (track-1.md:448-451) explicitly records the branch cannot be unit-tested in isolation. The session bypasses on `cacheCodeDepth > 0` before `lookup` re-entry (`DatabaseSessionEmbedded.serveThroughCache`, diff lines 238-242), so `inFlightLookup` never becomes true on the wired path.

#### C7 — empty-collection param sub-case asserted only via the null arm
CONFIRMED-as-issue (low value). `CacheKey.forArgs`/`forParams` null-or-empty guards (read at CacheKey.java:60-108). `CacheKeyTest` (read in full) passes `null` and non-empty arrays/maps, never `new Object[0]` or `Map.of()`.
