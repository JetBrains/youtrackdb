<!--
MANIFEST
dimension: test-completeness
iteration: 1
target: track-1 (Direct RID fetch for SELECT FROM <class> WHERE @rid = / IN)
commit_range: 75e4d639fd..HEAD
verdict: CHANGES-REQUESTED
counts: {blocker: 0, should-fix: 2, suggestion: 3}
findings_total: 5
evidence_base: 6
cert_index: [C1, C2, C3, C4, C5, C6]
flags: []
index:
  - {id: TC1, sev: should-fix, anchor: "#tc1", loc: "SelectExecutionPlannerRidEqualityTest.java (new test needed)", cert: C1, basis: "LoaderExecutionStream.fetchNext returns (not continue) on RecordNotFoundException; @rid IN [dangling, existing] truncates → parity break vs scan"}
  - {id: TC2, sev: should-fix, anchor: "#tc2", loc: "SelectExecutionPlannerRidEqualityTest.java (no ORDER BY test)", cert: C2, basis: "Invariant 'Ordering untouched' claims a backing test; none exists. Multi-RID IN ... ORDER BY / LIMIT never asserted"}
  - {id: TC3, sev: suggestion, anchor: "#tc3", loc: "SelectExecutionPlanner.java:176 case String arm", cert: C3, basis: "toRecordIdCandidate case String / default arms uncovered (76.3% branch); @rid = '#c:p' string-literal test exercises case String"}
  - {id: TC4, sev: suggestion, anchor: "#tc4", loc: "SelectExecutionPlannerRidEqualityTest.java (no whole-list-drop test)", cert: C4, basis: "IN where ALL elements non-member converges on EmptyStep via members.isEmpty(); distinct emission branch from mixed-membership, untested"}
  - {id: TC5, sev: suggestion, anchor: "#tc5", loc: "SelectExecutionPlannerRidEqualityTest.java (no single-element IN / reversed-operand test)", cert: C5, basis: "IN with one element and x = @rid reversed ordering both route the new fast path through untested shapes"}
-->

## Findings

### TC1 [should-fix] `@rid IN [dangling-RID, existing-RID]` truncates the result — a parity break the tests never probe

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/SelectExecutionPlannerRidEqualityTest.java` (missing test)
**Production code**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/resultset/LoaderExecutionStream.java:44-73` (reached via `FetchFromRidsStep.internalStart`, `SelectExecutionPlanner.java:163`)

**Missing scenario**: an `@rid IN [...]` list where a candidate RID has a valid, in-class collection id but points at a non-existent (deleted or never-allocated) position — a dangling RID — placed **before** a live RID in the list. No test constructs a dangling RID.

**Why it matters**: the fast path membership-filters on collection id only (`SelectExecutionPlanner.java:142-146`), which a dangling RID passes because its collection is in the class's polymorphic set. The surviving RIDs go to `FetchFromRidsStep`, which streams them through `LoaderExecutionStream.fetchNext`. That method's loop does `db.load(...)` inside a `try`, and on `RecordNotFoundException` it executes `return;` (`LoaderExecutionStream.java:66-68`) — **not** `continue`. A `return` with `nextResult == null` ends the whole stream: every RID after the dangling one is silently dropped. So `@rid IN [<dangling>, <live>]` returns **zero** rows, while the scan-plus-filter it replaces returns the one live record (a scan simply never visits the dangling position). This is a direct violation of the track's own "Result-set parity" invariant, and the divergence is invisible to coverage — `LoaderExecutionStream` is pre-existing and already "covered", but this track is the first to route a class-target query into it for a caller-supplied, possibly-dangling RID list.

The single-RID case (`@rid = <dangling>`) does **not** diverge: both paths return empty, so parity holds there. The break is specific to a dangling RID that is not the last surviving candidate.

**Evidence**: input-domain row [RID position → dangling] × [IN list, dangling not last] = NO (see C1). The emission path at `SelectExecutionPlanner.java:149-164` has no per-RID existence check; existence is resolved lazily downstream where the truncation lives.

**Refutation considered** (C1): could dedup into a `LinkedHashSet` reorder the dangling RID to last, hiding it? No — insertion order is preserved, and a test can force the dangling RID first. Could the membership filter reject a dangling RID? No — it checks collection id, which is valid for a deleted record's RID. Could `db.load` on a dangling RID throw something other than `RecordNotFoundException` and be caught elsewhere? The `catch` is specifically `RecordNotFoundException`; a deleted-then-position-reused case still parses to a valid collection id. Verdict: **CONFIRMED** as a real, untested parity divergence.

**Suggested test**:
```java
/**
 * A dangling RID (valid in-class collection, non-existent position) placed before a live RID in
 * an IN list must not truncate the result: parity with the old scan requires the live record to
 * be returned. Guards against LoaderExecutionStream returning (not continuing) on
 * RecordNotFoundException, which would drop every RID after the dangling one.
 */
@Test
public void ridInWithDanglingRidBeforeLive_stillReturnsLive() {
  var className = createClassInstance().getName();
  session.begin();
  var live = session.newInstance(className);
  live.setProperty("tag", "live");
  var liveRid = live.getIdentity();
  // Allocate then delete a record in the SAME class to get a dangling in-class RID.
  var doomed = session.newInstance(className);
  var danglingRid = doomed.getIdentity();
  session.commit();
  session.begin();
  session.delete(session.load(danglingRid));
  session.commit();

  // Dangling RID first, live RID second — the order that triggers truncation.
  var sql = "select from " + className + " where @rid in [" + danglingRid + ", " + liveRid + "]";
  try (var result = session.query(sql)) {
    Assert.assertTrue("the live record must survive a preceding dangling RID", result.hasNext());
    Assert.assertEquals("live", result.next().getProperty("tag"));
    Assert.assertFalse(result.hasNext());
  }
}
```
If this reproduces the truncation, the fix belongs in production (`continue` instead of `return`, or a pre-fetch existence check), not the test — but the test is what surfaces it. Flag to the implementer either way.

### TC2 [should-fix] "Ordering untouched" invariant claims a backing test that does not exist

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/SelectExecutionPlannerRidEqualityTest.java` (missing test)
**Production code**: `SelectExecutionPlanner.java:83-165` (`handleClassAsTargetWithRidEquality` never sets `info.orderApplied`); downstream assembler `handleProjectionsBlock`

**Missing scenario**: a multi-RID `@rid IN [...] ORDER BY <field>` (and/or `... LIMIT n`). The track's `## Invariants & Constraints` states "Ordering untouched … so ORDER BY / SKIP / LIMIT / GROUP BY / DISTINCT still assemble downstream" and prefixes the section with "Each invariant below is backed by a test." No test in the 10-method class uses `ORDER BY`, `SKIP`, `LIMIT`, `GROUP BY`, or `DISTINCT`.

**Why it matters**: `FetchFromRidsStep` streams RIDs in list order, which is arbitrary relative to any `ORDER BY`. The correctness of a multi-RID `IN ... ORDER BY` depends entirely on the downstream sort still running — precisely the invariant the handler preserves by leaving `orderApplied` false. A future edit that sets `orderApplied` (or that reorders the handler ahead of the ordering assembler) would silently return rows in fetch order, and nothing would catch it. The `IN` fast path is the only shape here where downstream ordering is observable (a single `=` fetch is trivially sorted), so this is where the invariant needs its test.

**Evidence**: input-domain row [downstream clause → ORDER BY over multi-RID IN] = NO (C2). The plan explicitly calls out "a real sort for a multi-RID `IN ... ORDER BY`" (`## Plan of Work`, Ordering-and-invariants paragraph) as the motivating case — and it is untested.

**Refutation considered** (C2): is ordering covered indirectly by criterion 8's remainder test? No — that asserts filter-step count, not sort order, and uses a single record. Is a single-RID sort enough? No — a one-element result is sorted by construction, so it cannot detect a broken downstream sort. Verdict: **CONFIRMED** — an asserted invariant with no test.

**Suggested test**:
```java
/**
 * A multi-RID IN with ORDER BY must return rows in sorted order, proving the handler leaves
 * info.orderApplied false so the downstream ORDER BY assembler still runs over the RID fetch.
 * Insert RIDs into the IN list in the opposite order to the sort key so a missing sort is visible.
 */
@Test
public void ridInWithOrderBy_sortsDownstream() {
  var className = createClassInstance().getName();
  session.begin();
  var d2 = session.newInstance(className); d2.setProperty("n", 2);
  var d0 = session.newInstance(className); d0.setProperty("n", 0);
  var d1 = session.newInstance(className); d1.setProperty("n", 1);
  var r2 = d2.getIdentity(); var r0 = d0.getIdentity(); var r1 = d1.getIdentity();
  session.commit();

  // List order 2,0,1 — a missing downstream sort would surface this order.
  var sql = "select from " + className + " where @rid in ["
      + r2 + ", " + r0 + ", " + r1 + "] order by n asc";
  try (var result = session.query(sql)) {
    Assert.assertEquals(0, (int) result.next().getProperty("n"));
    Assert.assertEquals(1, (int) result.next().getProperty("n"));
    Assert.assertEquals(2, (int) result.next().getProperty("n"));
    Assert.assertFalse(result.hasNext());
  }
}
```

### TC3 [suggestion] `case String` and `default` arms of `toRecordIdCandidate` are uncovered

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/SelectExecutionPlannerRidEqualityTest.java` (missing test)
**Production code**: `SelectExecutionPlanner.java:172-179` (`toRecordIdCandidate` switch, arms `case String s` and `default -> null`)

**Missing scenario**: `@rid = '#c:p'` where the RID value evaluates to a `String` rather than an `Identifiable`. The Surprises note and Step-1 episode both flag that the Identifiable-RID tests never reach the `case String` arm; changed-code coverage sits at 76.3% branch because of it.

**Why it matters**: this is defensive parity with `SQLRid.toRecordId`'s switch (`SQLRid.java:68-69`), which does handle a string RID. A `@rid = '#c:p'` string literal is a legitimate user query shape. If a future refactor breaks `RecordIdInternal.fromString(s, false)` parsing (e.g. wrong `changeable` flag, or a malformed-string exception not swallowed), no test would catch it. The gap is genuinely low-severity — the arm mirrors an already-tested switch elsewhere and the value type for the common `#c:p` and `:param` shapes is `Identifiable` — so accepting it as a defensive skip is defensible. But one string-literal test converts a 76.3% branch number into full arm coverage at trivial cost, and asserts the string-RID query shape actually works end to end.

**Evidence**: input-domain row [evaluated RID value type → String] = NO (C3). The `default -> null` arm (non-Identifiable, non-String) is harder to reach from SQL and is a reasonable defensive skip.

**Refutation considered** (C3): is `case String` reachable from real SQL? Yes — `@rid = '#c:p'` yields a String literal. Does the query still work if the string is a valid in-class RID? It should map to the same RID and fetch the record. Verdict: **CONFIRMED as a low-value but real gap** — worth one test, not a blocker.

**Suggested test**:
```java
/**
 * A quoted string RID literal (@rid = '#c:p') must map through the case String arm of
 * toRecordIdCandidate and fetch the record, exercising the string branch the Identifiable-RID
 * tests miss (raises changed-code branch coverage past the defensive-skip note).
 */
@Test
public void ridEqualsStringLiteral_compilesToRidFetch() {
  var className = createClassInstance().getName();
  session.begin();
  var doc = session.newInstance(className);
  doc.setProperty("tag", "s");
  var rid = doc.getIdentity();
  session.commit();

  var sql = "select from " + className + " where @rid = '" + rid + "'";
  var plan = explainPlan(sql);
  Assert.assertTrue("string RID literal must still use the RID fetch, plan was: " + plan,
      plan.contains("FETCH FROM RIDs"));
  try (var result = session.query(sql)) {
    Assert.assertTrue(result.hasNext());
    Assert.assertEquals("s", result.next().getProperty("tag"));
    Assert.assertFalse(result.hasNext());
  }
}
```

### TC4 [suggestion] `@rid IN [...]` where ALL elements are non-member — the whole-list-drop → EmptyStep branch — is untested

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/SelectExecutionPlannerRidEqualityTest.java` (missing test)
**Production code**: `SelectExecutionPlanner.java:149-154` (`members.isEmpty()` → `EmptyStep`)

**Missing scenario**: an `IN` list where every RID belongs to a sibling class (all non-members), e.g. `SELECT FROM A WHERE @rid IN [<rid-of-B>, <rid-of-B2>]`. Criterion 6 tests a **mixed** list (one member, one non-member → member survives, `FetchFromRidsStep`). Criterion 7 tests the **empty** `IN []` list. Neither tests a **non-empty list that filters down to empty**.

**Why it matters**: these reach different emission branches. Mixed-membership lands in the `members` non-empty branch (`FetchFromRidsStep`); empty `IN []` produces an empty `candidates` set before the filter; but an all-non-member list produces a **non-empty** `candidates` set that the **membership filter** empties, then `members.isEmpty()` chains `EmptyStep`. That third path — candidates present, all filtered out — is the exact branch that guards against a wrong-class multi-RID query leaking rows or falling through to a scan. It is distinct from both tested branches and currently exercised by neither.

**Evidence**: input-domain row [IN list membership → all non-member] = NO (C4). Emission branch coverage: `FetchFromRidsStep` (criterion 2/5/6), `EmptyStep` via empty candidates (criterion 7), `EmptyStep` via emptied-by-filter (none).

**Refutation considered** (C4): is the all-non-member case indirectly covered by the mixed test? No — the mixed test has a surviving member, so it never reaches `members.isEmpty()`. Is it covered by criterion 3 (`@rid = <wrong-class>`)? That is the singleton path (empty after filter), which does exercise `members.isEmpty()`, so the branch is not strictly uncovered — but the **IN, multi-element, all-non-member** shape is the one the D4 risk note ("an all-or-nothing implementation would err") calls out, and asserting empty for it (not a scan of A's rows) closes the gap the plan itself raised. Verdict: **CONFIRMED as low-value** — the raw branch is hit by criterion 3, but the multi-element IN whole-drop shape is worth one explicit test.

**Suggested test**:
```java
/**
 * An IN list whose RIDs are ALL from a sibling class must return empty (EmptyStep), not fall
 * through to a scan of the target class. Distinct from the mixed-membership case (a member
 * survives) and the empty-list case (no candidates before the filter): here candidates are
 * present but the membership filter empties them.
 */
@Test
public void ridInAllNonMembers_returnsEmpty() {
  var classA = createClassInstance().getName();
  var classB = createClassInstance().getName();
  session.begin();
  var b1 = session.newInstance(classB); b1.setProperty("tag", "b1");
  var b2 = session.newInstance(classB); b2.setProperty("tag", "b2");
  var rb1 = b1.getIdentity(); var rb2 = b2.getIdentity();
  // A record in classA a fall-through scan would wrongly return.
  session.newInstance(classA).setProperty("tag", "a");
  session.commit();

  var sql = "select from " + classA + " where @rid in [" + rb1 + ", " + rb2 + "]";
  var plan = explainPlan(sql);
  Assert.assertFalse("all-non-member IN must not scan classA, plan was: " + plan,
      plan.contains("FETCH FROM CLASS"));
  try (var result = session.query(sql)) {
    Assert.assertFalse("no sibling-class RID may leak through the class target", result.hasNext());
  }
}
```

### TC5 [suggestion] Single-element `IN` and reversed-operand `x = @rid` route the fast path through untested shapes

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/SelectExecutionPlannerRidEqualityTest.java` (missing tests)
**Production code**: `tryExtractRidInListFromTerm` (`SQLWhereClause.java:1093-1144`); reversed operand in `tryExtractRidFromTerm` (`SQLWhereClause.java:1186-1187`)

**Missing scenario**: (a) a single-element `@rid IN [#c:p]` — the degenerate list; (b) the reversed operand ordering `WHERE #c:p = @rid` / `WHERE :param = @rid`.

**Why it matters**: (a) A single-element `IN` is the boundary between the `=` fast path and the multi-element `IN` path — it exercises the `IN` extractor and the scalar-vs-collection normalization at the emission site with a one-element collection, a common real query shape (`IN` with a computed one-element set). (b) The equality primitive explicitly tries both operand orders (`tryExtractRidValue(cond.left, cond.right)` then the swap at line 1187), and the plan claims "both orderings" are supported — but every equality test in the class writes `@rid = <value>`, never `<value> = @rid`. The reversed branch (line 1187) is pre-existing and reached by the EXPAND path, so this is low-value, but the new fast path is the first plain-SELECT consumer and a one-line reversed-operand assertion confirms the fast path fires for it rather than silently falling through to a scan.

**Evidence**: input-domain rows [IN cardinality → single element] = NO (C5); [equality operand order → value = @rid] = NO (C5).

**Refutation considered** (C5): is single-element IN covered by the two-element criterion 2? No — the normalization of a one-element collection is a distinct boundary (single vs plural). Is reversed ordering covered elsewhere? The branch is hit by pre-existing EXPAND tests, so it is not strictly uncovered — hence suggestion, not should-fix. Verdict: **CONFIRMED as low-value** — both are cheap boundary tests, neither is a correctness risk on its own.

**Suggested test**:
```java
/** A single-element IN must compile to the same RID fetch as the two-element case. */
@Test
public void ridInSingleElement_compilesToRidFetch() {
  var className = createClassInstance().getName();
  session.begin();
  var doc = session.newInstance(className); doc.setProperty("tag", "one");
  var rid = doc.getIdentity();
  session.commit();
  var sql = "select from " + className + " where @rid in [" + rid + "]";
  var plan = explainPlan(sql);
  Assert.assertTrue(plan.contains("FETCH FROM RIDs"));
  Assert.assertFalse(plan.contains("FETCH FROM CLASS"));
  try (var result = session.query(sql)) {
    Assert.assertEquals("one", result.next().getProperty("tag"));
    Assert.assertFalse(result.hasNext());
  }
}

/** Reversed operand order (<literal> = @rid) must fire the fast path, not fall through. */
@Test
public void reversedOperandRidEquals_compilesToRidFetch() {
  var className = createClassInstance().getName();
  session.begin();
  var doc = session.newInstance(className); doc.setProperty("tag", "r");
  var rid = doc.getIdentity();
  session.commit();
  var plan = explainPlan("select from " + className + " where " + rid + " = @rid");
  Assert.assertTrue("reversed <literal> = @rid must still use the RID fetch, plan was: " + plan,
      plan.contains("FETCH FROM RIDs"));
}
```

## Evidence base

#### C1 — `@rid IN [dangling, live]` truncation (CONFIRMED, drives TC1)
`LoaderExecutionStream.fetchNext` (`LoaderExecutionStream.java:44-73`): the `while (iterator.hasNext())` loop loads each RID; on `RecordNotFoundException` it runs `return;` (line 67), ending the stream with `nextResult == null` and abandoning the remaining RIDs — a truncation, not a skip. Reached from the fast path via `FetchFromRidsStep.internalStart` → `ExecutionStream.loadIterator` (`FetchFromRidsStep.java:45`, `ExecutionStream.java:59`). The membership filter (`SelectExecutionPlanner.java:142-146`) admits a dangling RID because its collection id is valid and in-class. Old scan path never emits a non-existent record (a cluster scan visits only live positions), so it returns the live record — the new path returns empty. Single-RID dangling case does not diverge (both empty). `LoaderExecutionStream` is unchanged by this track (`git diff 75e4d639fd..HEAD` shows no hunk), so the truncation is pre-existing behavior newly reached for caller-supplied RID lists. Survived refutation: dedup preserves insertion order (dangling can be forced first); the catch is specifically `RecordNotFoundException`.

#### C2 — ORDER BY invariant unbacked (CONFIRMED, drives TC2)
`handleClassAsTargetWithRidEquality` (`SelectExecutionPlanner.java:83-165`) never assigns `info.orderApplied`, so the downstream projections block still sorts — the mechanism the track's "Ordering untouched" invariant and `## Plan of Work` motivate with "a real sort for a multi-RID `IN ... ORDER BY`". The 10 test methods (read in full) use no `ORDER BY`/`LIMIT`/`SKIP`/`GROUP BY`/`DISTINCT`. Single-RID results are sorted by construction, so only a multi-RID IN can detect a broken downstream sort. Survived refutation: criterion 8 asserts filter count on a single record, not order.

#### C3 — `case String` arm uncovered (CONFIRMED low-value, drives TC3)
`toRecordIdCandidate` (`SelectExecutionPlanner.java:172-179`) has `case String s -> RecordIdInternal.fromString(s, false)` and `default -> null`. Track Surprises note + Step-1 episode: Identifiable-RID tests never reach `case String`; changed-code branch coverage 76.3%. `@rid = '#c:p'` yields a String literal → `case String`. Mirrors `SQLRid.toRecordId` (`SQLRid.java:68-69`), which is exercised elsewhere, so this is defensive parity — a defensible skip, but one test closes it. `default` arm (non-Identifiable, non-String) is harder to reach from SQL; reasonable skip.

#### C4 — all-non-member IN whole-drop branch (CONFIRMED low-value, drives TC4)
Emission at `SelectExecutionPlanner.java:149-164`: three reachable outcomes — non-empty `members` → `FetchFromRidsStep` (tested: criteria 2/5/6); empty `candidates` (empty `IN []`) → `EmptyStep` (tested: criterion 7); non-empty `candidates` emptied by the membership filter → `EmptyStep` (tested only via the singleton criterion 3, not via a multi-element IN). The D4 risk note explicitly warns an all-or-nothing implementation would err on this shape. Raw branch is covered by criterion 3, so low-value — but the multi-element all-non-member IN shape is the one the plan flags and is not directly tested.

#### C5 — single-element IN and reversed operand (CONFIRMED low-value, drives TC5)
Single-element `@rid IN [#c:p]` exercises `tryExtractRidInListFromTerm` (`SQLWhereClause.java:1093`) with a one-element collection and the scalar-vs-collection normalization at the emission site — a boundary distinct from the two-element criterion 2. Reversed operand `<value> = @rid` is handled by the swap at `SQLWhereClause.java:1187` (pre-existing, reached by EXPAND tests), but no test in the new class writes the reversed form, though the plan claims "both orderings". Both are cheap boundary tests, neither a standalone correctness risk.

#### C6 — `@rid = x AND @rid IN [...]` interaction is acceptable as-is (REFUTED as a gap)
The D4 risk note documents `@rid = x AND @rid IN [...]`: the extractor takes the first matching AND term (equality is tried before IN at `SelectExecutionPlanner.java:99-102`), the second RID predicate stays as a post-filter, and "both extraction orders converge on the same rows, differing only in intermediate cardinality." The remainder wiring (D5) chains the leftover IN as a FilterStep, so correctness is preserved by construction regardless of which predicate is extracted. Criterion 8 already tests the remainder-applied-exactly-once mechanism with a non-RID predicate (`status = 'A'`), which is the load-bearing behavior. A dedicated `@rid = x AND @rid IN [...]` test would assert only that two convergent orderings converge — a tautology given the post-filter. Verdict: **not a meaningful gap**; the interaction is correct by construction and the mechanism it relies on is tested. No `NOT IN` / `NOT @rid =` test is needed either: `SQLNotInCondition` / negated `SQLNotBlock` are distinct node types that never reach either extractor (`SQLWhereClause.java:1111-1116`, `1180`), so the complement falls through to the scan unoptimized — node-type discrimination enforces this, and criterion 9 (fall-through) covers the "unoptimized path still works" contract. Reported here only to record that these focus-area candidates were checked and dismissed.
