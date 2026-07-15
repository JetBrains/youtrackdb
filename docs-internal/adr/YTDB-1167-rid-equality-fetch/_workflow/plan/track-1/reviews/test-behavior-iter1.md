<!--MANIFEST
dimension: test-behavior
iteration: 1
verdict: PASS
findings_total: 3
findings_by_sev: {blocker: 0, should-fix: 0, suggestion: 3}
evidence_base: {certs: 3}
cert_index: [C1, C2, C3]
flags: []
index:
  - id: TB1
    sev: suggestion
    anchor: "### TB1"
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/SelectExecutionPlannerRidEqualityTest.java:388
    cert: C1
    basis: grep + Read (PSI on develop, not this worktree; symbols uniquely named, no polymorphic dispatch)
  - id: TB2
    sev: suggestion
    anchor: "### TB2"
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/SelectExecutionPlannerRidEqualityTest.java:527
    cert: C2
    basis: grep + Read (PSI on develop, not this worktree; symbols uniquely named, no polymorphic dispatch)
  - id: TB3
    sev: suggestion
    anchor: "### TB3"
    loc: core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/SelectExecutionPlannerRidEqualityTest.java:447
    cert: C3
    basis: grep + Read (PSI on develop, not this worktree; symbols uniquely named, no polymorphic dispatch)
-->

## Findings

### TB1 [suggestion] Criterion 2 asserts IN-list row count but not identity, so a wrong-RID fetch passes

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/SelectExecutionPlannerRidEqualityTest.java`, method `ridInLiteralList_compilesToSingleRidFetch` (line 388)
- **Issue**: The correctness block asserts only `count == 2` — it never checks *which* records came back. The two records are inserted with distinct `n` values (0 and 1) precisely so identity is verifiable, but the test discards each `result.next()` without reading `n`. This is the one test in the ten that verifies cardinality without identity; every other result-returning test (C1, C4, C5, C6, C8, C9, C10) reads a distinguishing property. See the FALSIFIABILITY CHECK in cert C1: a mutation that fetched `[rid0, rid0]` (a bug in the `IN`-list element mapping in `handleClassAsTargetWithRidEquality`, `SelectExecutionPlanner.java:118-133`, where each list element is mapped through `toRecordIdCandidate`) still yields two rows, so this test would pass on that bug.
- **Missing behavior**: That both *distinct* listed RIDs were fetched — i.e. the returned `n` values are exactly `{0, 1}`, not `{0, 0}` or `{1, 1}`.
- **Suggested fix**:
  ```java
  try (var result = session.query(sql)) {
    var seen = new java.util.HashSet<Integer>();
    while (result.hasNext()) {
      seen.add((Integer) result.next().getProperty("n"));
    }
    Assert.assertEquals("both listed RIDs, and only those, must be fetched",
        java.util.Set.of(0, 1), seen);
  }
  ```

### TB2 [suggestion] Criterion 7 (empty IN []) has no positive plan-shape assertion

- **File**: `SelectExecutionPlannerRidEqualityTest.java`, method `ridInEmptyList_returnsEmptyNotScan` (line 527)
- **Issue**: The plan-shape assertion is negative only — `assertFalse(plan.contains("FETCH FROM CLASS"))`. It never positively asserts what the plan *is*. Per decision D4/D5 and the handler at `SelectExecutionPlanner.java:149-154`, `@rid IN []` must chain an `EmptyStep`, not a `FetchFromRidsStep` over an empty list (functionally equivalent for output, but the design commits to `EmptyStep`). The negative assertion plus the `!hasNext()` result check does make the test falsifiable against the failure it names — a fall-through scan would show `FETCH FROM CLASS` and return the two seeded rows — so the finding is a precision gap, not a false-confidence gap. Contrast C1/C2/C10, which pin the plan positively with `assertTrue(contains("FETCH FROM RIDs"))`.
- **Missing behavior**: That the empty `IN []` compiles to the intended empty-result step rather than any other plan that happens to return no rows.
- **Suggested fix**: `EmptyStep.prettyPrint` is inherited from `AbstractExecutionStep` and renders no distinctive marker, so a substring assertion on the plan is not available. Assert the two complements the design guarantees instead — the fetch fast path did not fire and the scan did not fire:
  ```java
  Assert.assertFalse(
      "@rid IN [] must not compile to a RID fetch over an empty list, plan was: " + plan,
      plan.contains("FETCH FROM RIDs"));
  // (the existing assertFalse on "FETCH FROM CLASS" already covers the scan)
  ```

### TB3 [suggestion] Criterion 4 (subclass) omits the "class scan absent" half of the plan-shape assertion

- **File**: `SelectExecutionPlannerRidEqualityTest.java`, method `ridEqualsSubclassUnderSuperclass_returnsRecord` (line 447)
- **Issue**: This plan-shape test asserts `FETCH FROM RIDs` is present but omits the matching `assertFalse(plan.contains("FETCH FROM CLASS"))` that C1, C2, C7, and C10 carry. Per the review focus, plan-shape tests should assert the RID fetch present AND the class scan absent. The identity block (single row, `tag == "sub"`) compensates on the correctness axis, so this is asymmetry against the sibling tests, not a false-confidence gap: a mutation that chained both a scan and a RID fetch (or fell back to a scan-plus-filter that still returned the sub record) would slip past the plan-shape half here, though the single-row assertion would catch a plan returning extra rows.
- **Missing behavior**: That the subclass query compiles to the RID fetch *instead of*, not *alongside*, a class scan.
- **Suggested fix**:
  ```java
  Assert.assertTrue(
      "subclass RID under a superclass target must still use the RID fetch, plan was: " + plan,
      plan.contains("FETCH FROM RIDs"));
  Assert.assertFalse(
      "the class scan must be gone once the RID fetch is chosen, plan was: " + plan,
      plan.contains("FETCH FROM CLASS"));
  ```

## Evidence base

Each cert records the Phase-3 falsifiability analysis for the criterion it covers. Criteria whose test survived the refutation check (the test would fail under the named mutation) are compressed to one line; the criteria that produced a finding are written in full.

#### C1 — Criterion 2 falsifiability (produced TB1)

- **Test**: `ridInLiteralList_compilesToSingleRidFetch` (line 388), criterion 2.
- **Behavior trace**: `session.query("select from C where @rid in [rid0, rid1]")` → `handleClassAsTargetWithRidEquality` (`SelectExecutionPlanner.java:83`) extracts the `IN` list via `extractAndRemoveRidInList`, evaluates it (`SelectExecutionPlanner.java:116`), maps each element through `toRecordIdCandidate` into a `LinkedHashSet` (lines 118-133), membership-filters, chains one `FetchFromRidsStep(members)` (line 163). Test asserts: `contains("FETCH FROM RIDs")`, `countOccurrences(plan, "FETCH FROM RIDs") == 1`, `!contains("FETCH FROM CLASS")`, and result `count == 2`.
- **Plan-shape axis**: PRECISE. `countOccurrences == 1` is a genuine "single fetch step, not one-per-RID" check; the RID list rendered on the following line by `FetchFromRidsStep.prettyPrint` (`FetchFromRidsStep.java:50-56`) contains no `FETCH FROM` substring, so no false double-count. A mutation chaining one fetch per RID fails the `== 1` assertion.
- **Result axis — MUTATION**: if the element-mapping loop mapped both list positions to `rid0` (or the dedup `LinkedHashSet` collapsed distinct RIDs), the query returns two rows both equal to `d0`. **ANALYSIS**: the test asserts only `count == 2` and never reads `n`, so it would PASS on that bug — false confidence on the result axis. This is the gap TB1 reports. Every other result-returning test reads a distinguishing property; C2 is the lone exception.
- **Verdict**: PLAUSIBLE (plan-shape axis solid; result-axis precision gap confirmed by reading the assertion block — a wrong-identity fetch is not caught).

#### C2 — Criterion 7 falsifiability (produced TB2)

- **Test**: `ridInEmptyList_returnsEmptyNotScan` (line 527), criterion 7.
- **Behavior trace**: `@rid IN []` → `extractAndRemoveRidInList` yields an empty candidate set → membership filter leaves `members` empty → `handleClassAsTargetWithRidEquality` chains `EmptyStep` and returns true (`SelectExecutionPlanner.java:149-154`). Two records are seeded so a fall-through scan would return them. Test asserts `!plan.contains("FETCH FROM CLASS")` and `!result.hasNext()`.
- **MUTATION**: if the empty-`IN` case fell through to the scan (returned false from the handler instead of chaining `EmptyStep`), the plan shows `FETCH FROM CLASS` and the query returns the two seeded rows. **ANALYSIS**: the test asserts `!contains("FETCH FROM CLASS")` (fails) and `!hasNext()` (fails) — so it FAILS, catching the named bug. The test is falsifiable against its stated failure mode.
- **Verdict**: PLAUSIBLE. The finding is a precision/symmetry gap: the assertion is negative-only and never positively pins the `EmptyStep`, so a plan that returns no rows via some *other* wrong step (not the designed `EmptyStep`, and not a class scan) would pass. Lower severity than C1 because the primary failure mode (scan fall-through) is caught.

#### C3 — Criterion 4 falsifiability (produced TB3)

- **Test**: `ridEqualsSubclassUnderSuperclass_returnsRecord` (line 447), criterion 4.
- **Behavior trace**: superclass target + subclass RID → membership filter keeps the RID because `resolveClassToCollectionIds(superClass)` returns the polymorphic set including the subclass collection (`SelectExecutionPlanner.java:139-147`) → one `FetchFromRidsStep`. Test asserts `contains("FETCH FROM RIDs")`, then single row with `tag == "sub"`.
- **Plan-shape axis — MUTATION**: a mutation that also chained a class scan, or fell back entirely to a scan-plus-filter that still located the sub record, would leave `FETCH FROM RIDs` present (mutation 1) or absent (mutation 2). **ANALYSIS**: mutation 2 (full fall-through) fails `assertTrue(contains("FETCH FROM RIDs"))` — caught. Mutation 1 (fetch plus scan) is not caught by the plan-shape half because there is no `assertFalse(contains("FETCH FROM CLASS"))`; the single-row correctness assertion catches it only if the extra scan changes the row set.
- **Result axis**: PRECISE — single row, exact `tag` value, `!hasNext()`.
- **Verdict**: PLAUSIBLE. Asymmetry against C1/C2/C7/C10, which all carry the `FETCH FROM CLASS`-absent assertion. Suggestion-tier because the correctness block backs the intent and the primary fall-through mutation is caught.

#### Criteria that survived refutation (compressed)

- **Criterion 1** `ridEqualsLiteral_compilesToRidFetch` (line 358): CONFIRMED behavior-driven. Asserts `FETCH FROM RIDs` present, `FETCH FROM CLASS` absent, and single-row identity (`tag == "a"`, `!hasNext()`). A scan-plus-filter mutation fails the class-absent assertion; a wrong-record fetch fails the `tag` assertion.
- **Criterion 3** `ridEqualsWrongClass_returnsEmpty` (line 426): CONFIRMED. Asserts an empty result (`!hasNext()`) for `SELECT FROM A WHERE @rid = <rid-of-B>`. A bare fetch without the membership guard (`SelectExecutionPlanner.java:139-147`) returns the B record; the assertion fails, catching it. The criterion is a result-contract (empty), which the test verifies directly.
- **Criterion 5** `ridInWithDuplicates_returnsSingleRow` (line 476): CONFIRMED — the cardinality-parity test the review focus calls out. Asserts exactly one row (`tag == "only"` then `!hasNext()`), not `>= 1`. A missing dedup (the `LinkedHashSet` at `SelectExecutionPlanner.java:117` removed) returns two rows; the second `assertFalse(hasNext())` fails, catching it.
- **Criterion 6** `ridInMixedMembership_returnsOnlyMembers` (line 498): CONFIRMED. Asserts only the member (`tag == "a"`, then `!hasNext()`), so an all-or-nothing membership implementation that returned both or neither is caught.
- **Criterion 8** `ridEqualsWithExtraPredicate_appliesRemainderExactlyOnce` (line 552): CONFIRMED — the "applied exactly once" test. `countOccurrences(plan, "FILTER ITEMS WHERE") == 1` catches double-application; the second query (`status = 'B'` → `!hasNext()`) is the load-bearing "remainder not dropped" check — if the remainder were dropped, the `status='B'` query would still return the RID-matching row and the assertion would fail. Both the neither-dropped and not-double-applied halves are falsifiable.
- **Criterion 9** `ridEqualsFieldReference_fallsThroughToScan` (line 592): CONFIRMED. Asserts `FETCH FROM CLASS` present AND `FETCH FROM RIDs` absent, plus the self-referencing row is returned. A mutation that wrongly treated `self` as early-calculable would fire the fetch path and fail the class-present / rids-absent assertions.
- **Criterion 10** `ridEqualsBoundParam_compilesToRidFetch` (line 624): CONFIRMED — binds `:rid` via a params map through both `explainPlanWithParams` and `session.query(sql, params)`. Asserts `FETCH FROM RIDs` present, `FETCH FROM CLASS` absent, and single-row identity (`tag == "p"`). The param is genuinely bound (not inlined), so this exercises the early-calculable-param path.
