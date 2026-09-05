package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies the direct-RID-fetch fast path for {@code SELECT FROM <class> WHERE @rid = / IN},
 * driven end-to-end through {@code session.query(...)} so the full planner is exercised.
 *
 * <p>Each test maps to one acceptance criterion: an early-calculable {@code @rid} equality or
 * {@code IN} list under a class target must compile to a {@code FetchFromRidsStep} (an O(1) fetch)
 * instead of a {@code FetchFromClassExecutionStep} (a full scan) plus a RID post-filter, while
 * preserving the class-membership and cardinality semantics the scan gave for free. Plan shape is
 * asserted via {@code EXPLAIN}'s {@code executionPlanAsString}: {@code FetchFromRidsStep} renders
 * as "FETCH FROM RIDs" and {@code FetchFromClassExecutionStep} as "FETCH FROM CLASS".
 *
 * <p>Correlated {@code @rid = $parent.$current...} (IC1-style LET subqueries) compiles to
 * {@code FetchFromCorrelatedRidStep} ("FETCH FROM CORRELATED RID"). Negative cases lock the
 * intentional non-optimizations: correlated {@code IN}, OR of RID equalities, and scalar equality
 * against a multi-element collection.
 */
public class SelectExecutionPlannerRidEqualityTest extends TestUtilsFixture {

  /**
   * Criterion 1: {@code @rid = <literal>} under a class target compiles to a direct RID fetch.
   * The EXPLAIN plan must show "FETCH FROM RIDs" and must NOT show a class scan — the whole point
   * of the optimization is to skip the scan for a RID that already names the exact record.
   */
  @Test
  public void ridEqualsLiteral_compilesToRidFetch() {
    var className = createClassInstance().getName();
    session.begin();
    var doc = session.newInstance(className);
    doc.setProperty("tag", "a");
    var rid = doc.getIdentity();
    session.commit();

    var plan = explainPlan("select from " + className + " where @rid = " + rid);
    Assert.assertTrue(
        "class-target @rid = <literal> must compile to FetchFromRidsStep, plan was: " + plan,
        plan.contains("FETCH FROM RIDs"));
    Assert.assertFalse(
        "the class scan must be gone once the RID fetch is chosen, plan was: " + plan,
        plan.contains("FETCH FROM CLASS"));

    // Correctness: the optimized query must still return the targeted record.
    try (var result = session.query("select from " + className + " where @rid = " + rid)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals("a", result.next().getProperty("tag"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * Criterion 2: {@code @rid IN [<literals>]} under a class target compiles to a single
   * {@code FetchFromRidsStep} over the listed RIDs (not a scan-plus-filter).
   */
  @Test
  public void ridInLiteralList_compilesToSingleRidFetch() {
    var className = createClassInstance().getName();
    session.begin();
    var d0 = session.newInstance(className);
    d0.setProperty("n", 0);
    var d1 = session.newInstance(className);
    d1.setProperty("n", 1);
    var rid0 = d0.getIdentity();
    var rid1 = d1.getIdentity();
    session.commit();

    var sql = "select from " + className + " where @rid in [" + rid0 + ", " + rid1 + "]";
    var plan = explainPlan(sql);
    Assert.assertTrue(
        "class-target @rid IN [...] must compile to FetchFromRidsStep, plan was: " + plan,
        plan.contains("FETCH FROM RIDs"));
    // Exactly one fetch step: the IN list is unified into a single fetch, not one per RID.
    Assert.assertEquals(
        "the IN list must be gathered into exactly one FetchFromRidsStep, plan was: " + plan,
        1,
        countOccurrences(plan, "FETCH FROM RIDs"));
    Assert.assertFalse(plan.contains("FETCH FROM CLASS"));

    // Read the distinguishing `n` values, not just the count: assert both DISTINCT listed RIDs
    // were fetched. A count-only check would pass on a mutation that fetched [rid0, rid0].
    try (var result = session.query(sql)) {
      var seen = new HashSet<Integer>();
      while (result.hasNext()) {
        seen.add((Integer) result.next().getProperty("n"));
      }
      Assert.assertEquals("both listed RIDs, and only those, must be fetched",
          Set.of(0, 1), seen);
    }
  }

  /**
   * Criterion 3: a RID whose collection lies outside the target class's polymorphic set must yield
   * an empty result — the class-membership guard rejects it at plan time, so
   * {@code SELECT FROM A WHERE @rid = <rid-of-B>} returns nothing (never the B record).
   */
  @Test
  public void ridEqualsWrongClass_returnsEmpty() {
    var classA = createClassInstance().getName();
    var classB = createClassInstance().getName();
    session.begin();
    var docB = session.newInstance(classB);
    docB.setProperty("tag", "b");
    var ridB = docB.getIdentity();
    session.commit();

    var sql = "select from " + classA + " where @rid = " + ridB;
    var plan = explainPlan(sql);
    Assert.assertFalse(
        "a wrong-class @rid must compile to EmptyStep, not fall through to a class scan, plan: "
            + plan,
        plan.contains("FETCH FROM CLASS"));
    try (var result = session.query(sql)) {
      Assert.assertFalse(
          "a RID from a sibling class must never leak through the class target", result.hasNext());
    }
  }

  /**
   * Criterion 4: a subclass record's RID under a superclass target must be returned — the
   * superclass's polymorphic collection set includes its subclasses, so membership holds.
   */
  @Test
  public void ridEqualsSubclassUnderSuperclass_returnsRecord() {
    var superClass = createClassInstance();
    var subClass = createChildClassInstance(superClass);
    session.begin();
    var subDoc = session.newInstance(subClass.getName());
    subDoc.setProperty("tag", "sub");
    var subRid = subDoc.getIdentity();
    session.commit();

    var sql = "select from " + superClass.getName() + " where @rid = " + subRid;
    var plan = explainPlan(sql);
    Assert.assertTrue(
        "subclass RID under a superclass target must still use the RID fetch, plan was: " + plan,
        plan.contains("FETCH FROM RIDs"));
    Assert.assertFalse(
        "the class scan must be gone once the RID fetch is chosen, plan was: " + plan,
        plan.contains("FETCH FROM CLASS"));

    try (var result = session.query(sql)) {
      Assert.assertTrue("subclass record must be visible under the superclass target",
          result.hasNext());
      Assert.assertEquals("sub", result.next().getProperty("tag"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * Criterion 5: a duplicate RID in an {@code IN} list must return the matching record exactly
   * once — cardinality parity with the old scan-plus-filter, which the pre-fetch dedup preserves
   * (the fetch step itself does no dedup).
   */
  @Test
  public void ridInWithDuplicates_returnsSingleRow() {
    var className = createClassInstance().getName();
    session.begin();
    var doc = session.newInstance(className);
    doc.setProperty("tag", "only");
    var rid = doc.getIdentity();
    session.commit();

    var sql = "select from " + className + " where @rid in [" + rid + ", " + rid + "]";
    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals("only", result.next().getProperty("tag"));
      Assert.assertFalse(
          "a duplicate RID in the IN list must not duplicate the returned row", result.hasNext());
    }
  }

  /**
   * Criterion 6: an {@code IN} list mixing a member and a non-member RID must return only the
   * member — the membership filter drops the non-member and fetches the member (not all-or-nothing).
   */
  @Test
  public void ridInMixedMembership_returnsOnlyMembers() {
    var classA = createClassInstance().getName();
    var classB = createClassInstance().getName();
    session.begin();
    var docA = session.newInstance(classA);
    docA.setProperty("tag", "a");
    var ridA = docA.getIdentity();
    var docB = session.newInstance(classB);
    docB.setProperty("tag", "b");
    var ridB = docB.getIdentity();
    session.commit();

    // ridA belongs to classA (member); ridB belongs to classB (non-member for classA).
    var sql = "select from " + classA + " where @rid in [" + ridA + ", " + ridB + "]";
    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals("a", result.next().getProperty("tag"));
      Assert.assertFalse(
          "the non-member RID must be dropped, leaving only the member row", result.hasNext());
    }
  }

  /**
   * Criterion 7: an empty {@code IN []} list must yield an empty result, not a full-class scan.
   * The empty candidate set chains an EmptyStep — a fall-through to a scan would wrongly return
   * every record in the class.
   */
  @Test
  public void ridInEmptyList_returnsEmptyNotScan() {
    var className = createClassInstance().getName();
    session.begin();
    // Two records that a fall-through scan would incorrectly return.
    session.newInstance(className).setProperty("tag", "x");
    session.newInstance(className).setProperty("tag", "y");
    session.commit();

    var sql = "select from " + className + " where @rid in []";
    var plan = explainPlan(sql);
    Assert.assertFalse(
        "@rid IN [] must not fall through to a full class scan, plan was: " + plan,
        plan.contains("FETCH FROM CLASS"));
    // An empty IN chains an EmptyStep, not a RID fetch over an empty list. EmptyStep renders no
    // distinctive marker, so assert both complements: neither a scan nor a RID fetch.
    Assert.assertFalse(
        "@rid IN [] must not compile to a RID fetch over an empty list, plan was: " + plan,
        plan.contains("FETCH FROM RIDs"));

    try (var result = session.query(sql)) {
      Assert.assertFalse("@rid IN [] must produce no rows", result.hasNext());
    }
  }

  /**
   * Criterion 8: a predicate accompanying the RID equality
   * ({@code @rid = <literal> AND <other>}) must be applied exactly once — neither dropped (which
   * would return a non-matching row) nor double-applied. The remainder is wired as a single
   * post-fetch FilterStep, so EXPLAIN must show exactly one "FILTER ITEMS WHERE".
   */
  @Test
  public void ridEqualsWithExtraPredicate_appliesRemainderExactlyOnce() {
    var className = createClassInstance().getName();
    session.begin();
    var doc = session.newInstance(className);
    doc.setProperty("status", "A");
    var rid = doc.getIdentity();
    session.commit();

    // The record matches the RID but its status is 'A'. Match on status='A' returns the row;
    // status='B' returns nothing — proving the remainder is applied (not dropped).
    var matchSql = "select from " + className + " where @rid = " + rid + " and status = 'A'";
    var plan = explainPlan(matchSql);
    Assert.assertTrue(
        "the RID equality must still drive a RID fetch, plan was: " + plan,
        plan.contains("FETCH FROM RIDs"));
    Assert.assertEquals(
        "the remaining predicate must be chained as exactly one FilterStep, plan was: " + plan,
        1,
        countOccurrences(plan, "FILTER ITEMS WHERE"));

    try (var result = session.query(matchSql)) {
      Assert.assertTrue("matching status must keep the row", result.hasNext());
      Assert.assertEquals("A", result.next().getProperty("status"));
      Assert.assertFalse(result.hasNext());
    }

    var noMatchSql = "select from " + className + " where @rid = " + rid + " and status = 'B'";
    try (var result = session.query(noMatchSql)) {
      Assert.assertFalse(
          "a non-matching remainder predicate must exclude the row (remainder not dropped)",
          result.hasNext());
    }
  }

  /**
   * Criterion 9: a non-early-calculable RID value (a field reference here) must fall through to
   * the class scan with no behavior change — the value cannot be resolved at plan time, so neither
   * the membership check nor the fetch-by-RID is possible. EXPLAIN must show "FETCH FROM CLASS".
   */
  @Test
  public void ridEqualsFieldReference_fallsThroughToScan() {
    var className = createClassInstance().getName();
    session.begin();
    var doc = session.newInstance(className);
    // self ends up equal to the record's own @rid, so @rid = self matches this row.
    var rid = doc.getIdentity();
    doc.setProperty("self", rid);
    session.commit();

    var sql = "select from " + className + " where @rid = self";
    var plan = explainPlan(sql);
    Assert.assertTrue(
        "a field-reference RID value is not early-calculable and must fall through to the "
            + "class scan, plan was: " + plan,
        plan.contains("FETCH FROM CLASS"));
    Assert.assertFalse(
        "the RID fetch fast path must not fire for a non-early-calc value, plan was: " + plan,
        plan.contains("FETCH FROM RIDs"));

    // Correctness: the scan-plus-filter must still return the self-referencing row.
    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals(rid, result.next().getProperty("self"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * Criterion 10: {@code @rid = :param} binds an early-calculable parameter (parameters are
   * available at plan time), so the planner must compile it to a {@code FetchFromRidsStep}.
   */
  @Test
  public void ridEqualsBoundParam_compilesToRidFetch() {
    var className = createClassInstance().getName();
    session.begin();
    var doc = session.newInstance(className);
    doc.setProperty("tag", "p");
    var rid = doc.getIdentity();
    session.commit();

    Map<Object, Object> params = new HashMap<>();
    params.put("rid", rid);

    var explainPlan = explainPlanWithParams("select from " + className + " where @rid = :rid",
        params);
    Assert.assertTrue(
        "@rid = :param must compile to FetchFromRidsStep (params are early-calculable), "
            + "plan was: " + explainPlan,
        explainPlan.contains("FETCH FROM RIDs"));
    Assert.assertFalse(explainPlan.contains("FETCH FROM CLASS"));

    try (var result = session.query("select from " + className + " where @rid = :rid", params)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals("p", result.next().getProperty("tag"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * A dangling RID (valid in-class collection, non-existent position) placed before a live RID in
   * an IN list must not truncate the result: parity with the old scan requires the live record to
   * be returned. The fast path opts into skip-missing on its FetchFromRidsStep so a dangling RID is
   * skipped rather than terminating the fetch and dropping every RID after it.
   */
  @Test
  public void ridInWithDanglingRidBeforeLive_stillReturnsLive() {
    var className = createClassInstance().getName();
    session.begin();
    var live = session.newInstance(className);
    live.setProperty("tag", "live");
    var liveRid = live.getIdentity();
    // Allocate a second record in the SAME class, then delete it to get a dangling in-class RID.
    var doomed = session.newInstance(className);
    var danglingRid = doomed.getIdentity();
    session.commit();
    // Delete via SQL so the dangling RID keeps a valid in-class collection id at a freed position.
    // (A SQL DELETE avoids the "record not bound to current session" trap that session.delete(
    // session.load(rid)) hits on a record committed before the deleting transaction.)
    session.begin();
    session.execute("delete from " + className + " where @rid = " + danglingRid).close();
    session.commit();

    // Dangling RID first, live RID second — the order that would truncate without skip-missing.
    var sql = "select from " + className + " where @rid in [" + danglingRid + ", " + liveRid + "]";
    try (var result = session.query(sql)) {
      Assert.assertTrue("the live record must survive a preceding dangling RID", result.hasNext());
      Assert.assertEquals("live", result.next().getProperty("tag"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * A multi-RID IN with ORDER BY must return rows in sorted order, proving the handler leaves
   * info.orderApplied false so the downstream ORDER BY assembler still runs over the RID fetch.
   * The IN list is written in the opposite order to the sort key, so a missing downstream sort
   * would surface as fetch-order output.
   */
  @Test
  public void ridInWithOrderBy_sortsDownstream() {
    var className = createClassInstance().getName();
    session.begin();
    var d2 = session.newInstance(className);
    d2.setProperty("n", 2);
    var d0 = session.newInstance(className);
    d0.setProperty("n", 0);
    var d1 = session.newInstance(className);
    d1.setProperty("n", 1);
    var r2 = d2.getIdentity();
    var r0 = d0.getIdentity();
    var r1 = d1.getIdentity();
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

  /**
   * A quoted string RID literal ({@code @rid = '#c:p'}) must map through the case-String arm of
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
    Assert.assertFalse(plan.contains("FETCH FROM CLASS"));
    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals("s", result.next().getProperty("tag"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * A malformed RID string ({@code @rid = 'garbage'}) must return an empty result, not throw —
   * parity with the old scan-plus-filter, which swallows the conversion failure in
   * QueryOperatorEquals. toRecordIdCandidate drops the unparseable string (yields null), leaving
   * no candidate, so the fast path chains an EmptyStep.
   */
  @Test
  public void ridEqualsMalformedStringLiteral_returnsEmptyNoThrow() {
    var className = createClassInstance().getName();
    session.begin();
    // A real record a broken parse must NOT return, and a scan must NOT be reached.
    session.newInstance(className).setProperty("tag", "real");
    session.commit();

    var sql = "select from " + className + " where @rid = 'garbage'";
    var plan = explainPlan(sql);
    Assert.assertFalse(
        "an unparseable @rid must compile to EmptyStep, not a class scan, plan was: " + plan,
        plan.contains("FETCH FROM CLASS"));
    try (var result = session.query(sql)) {
      Assert.assertFalse(
          "a malformed RID string must yield an empty result rather than throwing",
          result.hasNext());
    }
  }

  /**
   * A syntactically valid RID string whose collection id is below {@link Short#MIN_VALUE} makes
   * {@code RecordIdInternal.fromString} throw a {@code DatabaseException}. The plan-time
   * {@code toRecordIdCandidate} path catches every {@code RuntimeException} and drops the
   * candidate, so the fast path chains {@code EmptyStep} and returns empty — parity with scan
   * plus filter, which also yields no rows.
   */
  @Test
  public void ridEqualsOutOfRangeCollectionString_returnsEmptyNoThrow() {
    var className = createClassInstance().getName();
    session.begin();
    // A real record a broken parse must NOT return, and a scan must NOT be reached.
    session.newInstance(className).setProperty("tag", "real");
    session.commit();

    var sql = "select from " + className + " where @rid = '#-40000:0'";
    var plan = explainPlan(sql);
    Assert.assertFalse(
        "an out-of-range @rid must compile to EmptyStep, not a class scan, plan was: " + plan,
        plan.contains("FETCH FROM CLASS"));
    try (var result = session.query(sql)) {
      Assert.assertFalse(
          "an out-of-range RID string must yield an empty result rather than throwing",
          result.hasNext());
    }
  }

  /**
   * Regression: in a multi-statement script the planner builds every statement's plan up front, so
   * a {@code LET $r = <rid>} is only DECLARED (not yet bound) when a later
   * {@code SELECT ... WHERE @rid = $r} is planned. The fast path must not resolve {@code $r} at
   * plan time — it reads null there and would wrongly chain EmptyStep, returning zero rows. It must
   * fall through to the scan, which evaluates the predicate per row at execution, after the LET has
   * run. Verifies the record is returned, not a silently-empty result.
   */
  @Test
  public void ridEqualsScriptVariable_returnsRecordViaScan() {
    var className = createClassInstance().getName();
    session.begin();
    var doc = session.newInstance(className);
    doc.setProperty("tag", "sv");
    var rid = doc.getIdentity();
    session.commit();

    // The script runs in a fresh transaction: the correct plan for a not-yet-bound LET variable
    // is the class scan, which needs an active tx to iterate (session.computeScript, unlike
    // session.query, does not auto-open one); the buggy fast path returned empty without a tx.
    // Running inside a tx isolates the behavior under test.
    session.begin();
    try {
      var script = "LET $r = " + rid + ";\n"
          + "SELECT FROM " + className + " WHERE @rid = $r;";
      try (var result = session.computeScript("sql", script)) {
        Assert.assertTrue(
            "a script LET-bound @rid must return the record, not a silently-empty result",
            result.hasNext());
        Assert.assertEquals("sv", result.next().getProperty("tag"));
        Assert.assertFalse(result.hasNext());
      }
    } finally {
      session.commit();
    }
  }

  /** IN-list variant of {@link #ridEqualsScriptVariable_returnsRecordViaScan}. */
  @Test
  public void ridInScriptVariableList_returnsRecordsViaScan() {
    var className = createClassInstance().getName();
    session.begin();
    var d0 = session.newInstance(className);
    d0.setProperty("tag", "a");
    var d1 = session.newInstance(className);
    d1.setProperty("tag", "b");
    var rid0 = d0.getIdentity();
    var rid1 = d1.getIdentity();
    session.commit();

    // See ridEqualsScriptVariable_returnsRecordViaScan: the fall-through scan needs an active tx.
    session.begin();
    try {
      var script = "LET $r = [" + rid0 + ", " + rid1 + "];\n"
          + "SELECT FROM " + className + " WHERE @rid IN $r;";
      try (var result = session.computeScript("sql", script)) {
        Set<String> seen = new HashSet<>();
        while (result.hasNext()) {
          seen.add(result.next().getProperty("tag"));
        }
        Assert.assertEquals(
            "a script LET-bound @rid IN list must return all matching records", Set.of("a", "b"),
            seen);
      }
    } finally {
      session.commit();
    }
  }

  /**
   * A nonexistent class name with an {@code @rid} predicate must still throw a class-resolution
   * error, not silently return an empty result. When the class cannot be resolved the fast path
   * falls through (returns false) rather than chaining EmptyStep, so the query reaches the same
   * class-existence check every other class-target query does. An {@code @rid} predicate must not
   * flip a typo'd class into a masked empty result.
   */
  @Test
  public void ridEqualsNonexistentClass_throwsClassNotPresent() {
    var missingClass = "NoSuchClass" + System.nanoTime();
    var sql = "select from " + missingClass + " where @rid = #12:0";
    try (var result = session.query(sql)) {
      result.hasNext();
      Assert.fail("querying a nonexistent class with an @rid predicate must throw, not return "
          + "empty");
    } catch (CommandExecutionException e) {
      Assert.assertTrue(
          "the error must name the missing class, message was: " + e.getMessage(),
          e.getMessage().contains(missingClass));
    }
  }

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
    var b1 = session.newInstance(classB);
    b1.setProperty("tag", "b1");
    var b2 = session.newInstance(classB);
    b2.setProperty("tag", "b2");
    var rb1 = b1.getIdentity();
    var rb2 = b2.getIdentity();
    // A record in classA a fall-through scan would wrongly return.
    session.newInstance(classA).setProperty("tag", "a");
    session.commit();

    var sql = "select from " + classA + " where @rid in [" + rb1 + ", " + rb2 + "]";
    var plan = explainPlan(sql);
    Assert.assertFalse("all-non-member IN must not scan classA, plan was: " + plan,
        plan.contains("FETCH FROM CLASS"));
    try (var result = session.query(sql)) {
      Assert.assertFalse("no sibling-class RID may leak through the class target",
          result.hasNext());
    }
  }

  /**
   * A single-element IN ({@code @rid IN [#c:p]}) must compile to the same RID fetch as the
   * two-element case — the boundary between the {@code =} fast path and the multi-element IN path,
   * exercising the one-element-collection normalization at the emission site.
   */
  @Test
  public void ridInSingleElement_compilesToRidFetch() {
    var className = createClassInstance().getName();
    session.begin();
    var doc = session.newInstance(className);
    doc.setProperty("tag", "one");
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

  /**
   * Reversed operand order ({@code <literal> = @rid}) must fire the fast path, not fall through —
   * the equality extractor tries both operand orders, and the plan claims both orderings are
   * supported. Every other equality test writes {@code @rid = <value>}; this pins the reversed form.
   */
  @Test
  public void reversedOperandRidEquals_compilesToRidFetch() {
    var className = createClassInstance().getName();
    session.begin();
    var doc = session.newInstance(className);
    doc.setProperty("tag", "r");
    var rid = doc.getIdentity();
    session.commit();

    var sql = "select from " + className + " where " + rid + " = @rid";
    var plan = explainPlan(sql);
    Assert.assertTrue("reversed <literal> = @rid must still use the RID fetch, plan was: " + plan,
        plan.contains("FETCH FROM RIDs"));
    Assert.assertFalse(plan.contains("FETCH FROM CLASS"));
    try (var result = session.query(sql)) {
      Assert.assertTrue("reversed operand must return the targeted record", result.hasNext());
      Assert.assertEquals("r", result.next().getProperty("tag"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * A scalar {@code @rid = :param} where the param binds to a 2-or-more-element RID collection must
   * return empty and fall through to the class scan, NOT expand into a multi-RID fetch. The scan
   * this path replaces (QueryOperatorEquals) unwraps a collection to its element only at size 1, so
   * a scalar @rid never matches a multi-element collection — the fast path must preserve that
   * empty-result parity rather than wrongly fetching every element.
   */
  @Test
  public void ridEqualsMultiElementCollectionParam_returnsEmpty() {
    var className = createClassInstance().getName();
    session.begin();
    var a = session.newInstance(className);
    a.setProperty("tag", "a");
    var b = session.newInstance(className);
    b.setProperty("tag", "b");
    var ridA = a.getIdentity();
    var ridB = b.getIdentity();
    session.commit();

    // A scalar equality against a 2-element collection: the scan matches nothing.
    Map<Object, Object> params = new HashMap<>();
    params.put("p", List.of(ridA, ridB));

    var sql = "select from " + className + " where @rid = :p";
    var plan = explainPlanWithParams(sql, params);
    Assert.assertTrue(
        "a scalar @rid against a multi-element collection must fall through to the class scan, "
            + "plan was: " + plan,
        plan.contains("FETCH FROM CLASS"));
    Assert.assertFalse(
        "a scalar @rid = <2-element collection> must NOT expand into a RID fetch, plan was: "
            + plan,
        plan.contains("FETCH FROM RIDs"));

    try (var result = session.query(sql, params)) {
      Assert.assertFalse(
          "@rid = <2-element collection> must return empty (parity with the scan)",
          result.hasNext());
    }
  }

  /**
   * A scalar {@code @rid = :param} where the param binds to a 1-element RID collection must return
   * that record — pinning the size-1 unwrap boundary that mirrors QueryOperatorEquals: a size-1
   * collection unwraps to its element and matches the scalar @rid, so the fast path fetches it.
   */
  @Test
  public void ridEqualsSingleElementCollectionParam_returnsRecord() {
    var className = createClassInstance().getName();
    session.begin();
    var doc = session.newInstance(className);
    doc.setProperty("tag", "single");
    var rid = doc.getIdentity();
    session.commit();

    Map<Object, Object> params = new HashMap<>();
    params.put("p", List.of(rid));

    var sql = "select from " + className + " where @rid = :p";
    try (var result = session.query(sql, params)) {
      Assert.assertTrue("a size-1 collection must unwrap and match the scalar @rid",
          result.hasNext());
      Assert.assertEquals("single", result.next().getProperty("tag"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * A negated {@code @rid NOT IN [...]} must fall through to the class scan unoptimized — the
   * complement is a distinct AST node that never reaches the RID extractors, so it is not a direct
   * RID fetch. Over a class with two records, {@code NOT IN [ridA]} returns every record except A.
   */
  @Test
  public void ridNotInList_fallsThroughToScan() {
    var className = createClassInstance().getName();
    session.begin();
    var a = session.newInstance(className);
    a.setProperty("tag", "a");
    var b = session.newInstance(className);
    b.setProperty("tag", "b");
    var ridA = a.getIdentity();
    session.commit();

    var sql = "select from " + className + " where @rid not in [" + ridA + "]";
    var plan = explainPlan(sql);
    Assert.assertTrue(
        "@rid NOT IN must fall through to the class scan, plan was: " + plan,
        plan.contains("FETCH FROM CLASS"));
    Assert.assertFalse(
        "the RID fetch fast path must not fire for a negated IN, plan was: " + plan,
        plan.contains("FETCH FROM RIDs"));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      // Only record b survives the NOT IN [ridA] filter.
      Assert.assertEquals("b", result.next().getProperty("tag"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * When both {@code @rid = <a>} and {@code @rid IN [<a>, <b>]} appear, the equality is extracted
   * first and drives the RID fetch; the leftover IN becomes a post-fetch FilterStep remainder. The
   * two convergent predicates leave only record {@code a}, via a single RID fetch.
   */
  @Test
  public void ridEqualsAndRidInList_equalityWinsReturnsSingle() {
    var className = createClassInstance().getName();
    session.begin();
    var a = session.newInstance(className);
    a.setProperty("tag", "a");
    var b = session.newInstance(className);
    b.setProperty("tag", "b");
    var ridA = a.getIdentity();
    var ridB = b.getIdentity();
    session.commit();

    var sql = "select from " + className
        + " where @rid = " + ridA + " and @rid in [" + ridA + ", " + ridB + "]";
    var plan = explainPlan(sql);
    Assert.assertTrue(
        "the equality must drive a RID fetch, plan was: " + plan,
        plan.contains("FETCH FROM RIDs"));
    Assert.assertEquals(
        "exactly one RID fetch (equality extracted, IN left as the filter remainder), plan was: "
            + plan,
        1,
        countOccurrences(plan, "FETCH FROM RIDs"));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals("a", result.next().getProperty("tag"));
      Assert.assertFalse(
          "only record a satisfies both @rid = a and @rid IN [a, b]", result.hasNext());
    }
  }

  /**
   * Regression (YTDB-1167): a {@code @rid IN (subquery)} predicate under a class target must fall
   * through to the class scan. Before the fast path runs, extractSubQueries() rewrites the inline
   * subquery into {@code @rid IN $$$SUBQUERY$$_N} — a reference to an internal LET variable. That
   * reference reports {@code isEarlyCalculated() == true} (it is an internal alias), but its value
   * is bound only when the LET step runs during execution, so evaluating it at plan time yields an
   * empty candidate set. Optimizing it would wrongly collapse the outer query to an EmptyStep and
   * return zero rows; the planner must instead keep a class scan whose post-filter runs after the
   * LET step. The plan does still contain a {@code FETCH FROM RIDs}, but it belongs to the
   * subquery's own {@code from [rid]} RID-list target, not to the outer class-target query — so the
   * assertion checks the outer predicate survives as a runtime filter over the scan, not the
   * absence of any RID fetch. Both the {@code select @rid} and the {@code select *} subquery
   * projections are covered, mirroring the two forms in
   * {@code UpdateStatementExecutionTest.testUpdateWhereSubquery}.
   */
  @Test
  public void ridInSubquery_fallsThroughToScan() {
    var className = createClassInstance().getName();
    session.begin();
    var doc = session.newInstance(className);
    doc.setProperty("tag", "s");
    var rid = doc.getIdentity();
    session.commit();

    // Both projections resolve the subquery to the single record's @rid at execution time.
    for (var subProjection : List.of("@rid", "*")) {
      var sql = "select from " + className
          + " where @rid in (select " + subProjection + " from [" + rid + "])";
      var plan = explainPlan(sql);
      Assert.assertTrue(
          "a subquery-valued @rid IN must fall through to the class scan, plan was: " + plan,
          plan.contains("FETCH FROM CLASS"));
      Assert.assertTrue(
          "the subquery predicate must survive as a post-LET runtime filter — the fast path must "
              + "neither consume it nor collapse the outer query to an EmptyStep, plan was: "
              + plan,
          plan.contains("@rid IN $$$SUBQUERY$$"));

      // Correctness: the scan-plus-filter must still return the one record the subquery names.
      // The pre-fix bug collapsed the outer query to an EmptyStep, so this returned zero rows.
      try (var result = session.query(sql)) {
        Assert.assertTrue(
            "the subquery names exactly the one record, which must be returned", result.hasNext());
        Assert.assertEquals("s", result.next().getProperty("tag"));
        Assert.assertFalse(result.hasNext());
      }
    }
  }

  /**
   * A bound-parameter RID list ({@code @rid IN :params}) under a class target must compile to the
   * direct RID fetch and return exactly the matching records. This is the only test of the
   * bound-param arm of the IN detector (wrapEarlyCalculableInRight's rightParam branch); the other
   * IN tests use a list literal, which takes the rightMathExpression branch.
   */
  @Test
  public void ridInBoundParamList_compilesToRidFetch() {
    var className = createClassInstance().getName();
    session.begin();
    var d0 = session.newInstance(className);
    d0.setProperty("tag", "p0");
    var d1 = session.newInstance(className);
    d1.setProperty("tag", "p1");
    var rid0 = d0.getIdentity();
    var rid1 = d1.getIdentity();
    session.commit();

    Map<Object, Object> params = new HashMap<>();
    params.put("rids", List.of(rid0, rid1));
    var sql = "select from " + className + " where @rid in :rids";

    var plan = explainPlanWithParams(sql, params);
    Assert.assertTrue("@rid IN :param must compile to FetchFromRidsStep, plan was: " + plan,
        plan.contains("FETCH FROM RIDs"));
    Assert.assertFalse(plan.contains("FETCH FROM CLASS"));
    try (var result = session.query(sql, params)) {
      Set<String> seen = new HashSet<>();
      while (result.hasNext()) {
        seen.add(result.next().getProperty("tag"));
      }
      Assert.assertEquals(Set.of("p0", "p1"), seen);
    }
  }

  /**
   * {@code @rid IN [<list>] AND <extra>} must let the IN list drive the fetch and apply the extra
   * predicate as exactly one downstream FilterStep — the IN-list twin of
   * {@link #ridEqualsWithExtraPredicate_appliesRemainderExactlyOnce}. Exercises the compound-AND
   * remainder branch of extractRidInList (buildWhereWithout), which the sole-condition IN
   * tests never reach.
   */
  @Test
  public void ridInListWithExtraPredicate_appliesRemainderExactlyOnce() {
    var className = createClassInstance().getName();
    session.begin();
    var a = session.newInstance(className);
    a.setProperty("status", "A");
    var b = session.newInstance(className);
    b.setProperty("status", "B");
    var ridA = a.getIdentity();
    var ridB = b.getIdentity();
    session.commit();

    var sql = "select from " + className
        + " where @rid in [" + ridA + ", " + ridB + "] and status = 'A'";
    var plan = explainPlan(sql);
    Assert.assertTrue("IN list must drive the RID fetch, plan was: " + plan,
        plan.contains("FETCH FROM RIDs"));
    Assert.assertEquals(
        "the extra predicate must be chained as exactly one FilterStep, plan was: " + plan,
        1,
        countOccurrences(plan, "FILTER ITEMS WHERE"));
    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals("only ridA survives status='A'", "A",
          result.next().getProperty("status"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /** Runs EXPLAIN and returns the pretty-printed plan string. */
  private String explainPlan(String sql) {
    // Delegate to the params variant with an empty map so the EXPLAIN assertion contract
    // lives in exactly one place.
    return explainPlanWithParams(sql, Map.of());
  }

  /** Runs EXPLAIN with bound parameters and returns the pretty-printed plan string. */
  private String explainPlanWithParams(String sql, Map<Object, Object> params) {
    try (var result = session.query("explain " + sql, params)) {
      Assert.assertTrue("EXPLAIN must produce a row", result.hasNext());
      String planAsString = result.next().getProperty("executionPlanAsString");
      Assert.assertNotNull("EXPLAIN must expose executionPlanAsString", planAsString);
      return planAsString;
    }
  }

  /**
   * A per-record LET subquery with {@code WHERE @rid = $parent.$current.<field>} must compile
   * the inner SELECT as a {@code FetchFromCorrelatedRidStep} instead of a full class scan.
   * This is the IC1-style pattern where the correlated RID is not known at plan time but
   * resolves to exactly one record per parent row.
   */
  @Test
  public void correlatedRidInLetSubquery_usesCorrelatedRidFetch() {
    var personClass = createClassInstance().getName();
    var companyClass = createClassInstance().getName();
    session.begin();
    var company = session.newInstance(companyClass);
    company.setProperty("name", "JetBrains");
    var companyRid = company.getIdentity();
    var person = session.newInstance(personClass);
    person.setProperty("fname", "Alice");
    person.setProperty("companyRef", companyRid);
    session.commit();

    var sql = "SELECT fname, $comp as company FROM " + personClass
        + " LET $comp = (SELECT name FROM " + companyClass
        + " WHERE @rid = $parent.$current.companyRef)";

    var plan = explainPlan(sql);
    Assert.assertTrue(
        "correlated @rid = $parent.$current.<field> must compile to "
            + "FetchFromCorrelatedRidStep, plan was: " + plan,
        plan.contains("FETCH FROM CORRELATED RID"));
    Assert.assertFalse(
        "the inner subquery must NOT use a full class scan, plan was: " + plan,
        plan.contains("FETCH FROM CLASS " + companyClass));

    try (var result = session.query(sql)) {
      Assert.assertTrue("query must return one row", result.hasNext());
      var row = result.next();
      Assert.assertEquals("Alice", row.getProperty("fname"));
      Assert.assertEquals(
          "LET must resolve the company named by companyRef",
          "JetBrains",
          singleLetProperty(row.getProperty("company"), "name"));
      Assert.assertFalse("only one row expected", result.hasNext());
    }
  }

  /**
   * Correlated RID fetch with an additional predicate in the LET subquery's WHERE clause:
   * {@code WHERE @rid = $parent.$current.ref AND status = 'active'}. The RID predicate drives
   * the fetch and the remaining predicate is applied as a filter.
   */
  @Test
  public void correlatedRidWithExtraPredicate_appliesRemainder() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    var activeChild = session.newInstance(childClass);
    activeChild.setProperty("status", "active");
    activeChild.setProperty("val", 42);
    var activeRid = activeChild.getIdentity();
    var inactiveChild = session.newInstance(childClass);
    inactiveChild.setProperty("status", "inactive");
    inactiveChild.setProperty("val", 99);
    var inactiveRid = inactiveChild.getIdentity();
    var p1 = session.newInstance(parentClass);
    p1.setProperty("name", "activeParent");
    p1.setProperty("childRef", activeRid);
    var p2 = session.newInstance(parentClass);
    p2.setProperty("name", "inactiveParent");
    p2.setProperty("childRef", inactiveRid);
    session.commit();

    var sql = "SELECT name, $info as info FROM " + parentClass
        + " LET $info = (SELECT val FROM " + childClass
        + " WHERE @rid = $parent.$current.childRef AND status = 'active')"
        + " ORDER BY name";

    var plan = explainPlan(sql);
    Assert.assertTrue(
        "correlated RID plus remainder must compile to FetchFromCorrelatedRidStep, plan was: "
            + plan,
        plan.contains("FETCH FROM CORRELATED RID"));
    Assert.assertFalse(
        "the inner subquery must NOT use a full class scan, plan was: " + plan,
        plan.contains("FETCH FROM CLASS " + childClass));
    Assert.assertEquals(
        "the extra predicate must be chained as exactly one FilterStep, plan was: " + plan,
        1,
        countOccurrences(plan, "FILTER ITEMS WHERE"));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      var activeRow = result.next();
      Assert.assertEquals("activeParent", activeRow.getProperty("name"));
      Assert.assertEquals(
          "active child must keep val=42 after the remainder filter",
          42,
          ((Number) singleLetProperty(activeRow.getProperty("info"), "val")).intValue());
      Assert.assertTrue(result.hasNext());
      var inactiveRow = result.next();
      Assert.assertEquals("inactiveParent", inactiveRow.getProperty("name"));
      assertLetEmpty(
          "inactive child must be excluded by status='active'",
          inactiveRow.getProperty("info"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * A null correlated RID (parent field unset) must yield an empty LET subquery, not a class
   * scan and not a thrown error — the scan+filter this path replaces also matches nothing for
   * {@code @rid = null}.
   */
  @Test
  public void correlatedRidNull_yieldsEmptySubquery() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    session.newInstance(childClass).setProperty("val", 1);
    session.newInstance(parentClass);
    session.commit();

    var sql = "SELECT $info as info FROM " + parentClass
        + " LET $info = (SELECT val FROM " + childClass
        + " WHERE @rid = $parent.$current.childRef)";

    var plan = explainPlan(sql);
    Assert.assertTrue(
        "null-RHS correlated fetch must still compile to FetchFromCorrelatedRidStep, plan was: "
            + plan,
        plan.contains("FETCH FROM CORRELATED RID"));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      assertLetEmpty("null RID must produce an empty LET result",
          result.next().getProperty("info"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * A correlated RID that names a record of a different class than the subquery FROM target must
   * not be loaded. Scan+filter used class membership for free; the correlated fetch has to apply
   * the same collection-id check or it would leak a wrong-class record.
   */
  @Test
  public void correlatedRidWrongClass_yieldsEmptySubquery() {
    var parentClass = createClassInstance().getName();
    var companyClass = createClassInstance().getName();
    var personClass = createClassInstance().getName();
    session.begin();
    var person = session.newInstance(personClass);
    person.setProperty("fname", "Alice");
    var personRid = person.getIdentity();
    session.newInstance(companyClass).setProperty("name", "JetBrains");
    var parent = session.newInstance(parentClass);
    parent.setProperty("companyRef", personRid);
    session.commit();

    var sql = "SELECT $comp as company FROM " + parentClass
        + " LET $comp = (SELECT name FROM " + companyClass
        + " WHERE @rid = $parent.$current.companyRef)";

    var plan = explainPlan(sql);
    Assert.assertTrue(
        "wrong-class RID must still compile to FetchFromCorrelatedRidStep, plan was: " + plan,
        plan.contains("FETCH FROM CORRELATED RID"));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      assertLetEmpty(
          "Person RID must not load as a Company", result.next().getProperty("company"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * A correlated RID pointing at a deleted in-class position must yield empty, matching a class
   * scan that never visits a dangling position.
   */
  @Test
  public void correlatedRidDeletedTarget_yieldsEmptySubquery() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    var child = session.newInstance(childClass);
    child.setProperty("val", 7);
    var childRid = child.getIdentity();
    var parent = session.newInstance(parentClass);
    parent.setProperty("childRef", childRid);
    session.commit();
    session.begin();
    session.execute("delete from " + childClass + " where @rid = " + childRid).close();
    session.commit();

    var sql = "SELECT $info as info FROM " + parentClass
        + " LET $info = (SELECT val FROM " + childClass
        + " WHERE @rid = $parent.$current.childRef)";

    var plan = explainPlan(sql);
    Assert.assertTrue(
        "deleted-target correlated fetch must still compile to FetchFromCorrelatedRidStep, plan "
            + "was: " + plan,
        plan.contains("FETCH FROM CORRELATED RID"));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      assertLetEmpty(
          "deleted target RID must produce an empty LET result",
          result.next().getProperty("info"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * IC1-shaped LET: expand(outE) from Person where {@code @rid = $parent.$current.friendVertex},
   * then project {@code inV().name}. RHS is a LINK to a vertex entity (Identifiable). Plan must
   * use correlated RID fetch; result must be the friend's university name, not a mere edge count.
   */
  @Test
  public void correlatedRidIc1Shape_expandOutE_withEntityFriendVertex() {
    var personClass = "Person" + System.nanoTime();
    var uniClass = "Uni" + System.nanoTime();
    var studyAt = "StudyAt" + System.nanoTime();
    var outerClass = createClassInstance().getName();
    session.createVertexClass(personClass);
    session.createVertexClass(uniClass);
    session.createEdgeClass(studyAt);

    session.begin();
    var friend = session.newVertex(personClass);
    friend.setProperty("fname", "Bob");
    var uni = session.newVertex(uniClass);
    uni.setProperty("name", "MIT");
    session.newEdge(friend, uni, studyAt);
    // Store the vertex entity itself (Identifiable), matching IC1's friendVertex projection.
    var outer = session.newInstance(outerClass);
    outer.setProperty("friendVertex", friend);
    // Noise person with no STUDY_AT edge — wrong-friend fetch would yield empty uniName.
    session.newVertex(personClass).setProperty("fname", "Other");
    session.commit();

    var sql = "SELECT $unis as universities FROM " + outerClass
        + " LET $unis = ("
        + "SELECT inV().name as uniName FROM ("
        + "SELECT expand(outE('" + studyAt + "')) FROM " + personClass
        + " WHERE @rid = $parent.$current.friendVertex"
        + "))";

    var plan = explainPlan(sql);
    Assert.assertTrue(
        "IC1-shaped correlated @rid = $parent.$current.friendVertex must use "
            + "FetchFromCorrelatedRidStep, plan was: " + plan,
        plan.contains("FETCH FROM CORRELATED RID"));
    Assert.assertFalse(
        "inner Person lookup must not scan the class, plan was: " + plan,
        plan.contains("FETCH FROM CLASS " + personClass));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals(
          "friendVertex must resolve Bob's STUDY_AT university, not the noise person",
          "MIT",
          singleLetProperty(result.next().getProperty("universities"), "uniName"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * Correlated equality whose RHS is a LINK to an entity (Identifiable), not a RID property value.
   * {@code toRecordIdCandidate}'s Identifiable arm must resolve the identity and fetch the record.
   */
  @Test
  public void correlatedRidEntityLinkRhs_fetchesRecord() {
    var parentClass = createClassInstance().getName();
    var companyClass = createClassInstance().getName();
    session.begin();
    var company = session.newInstance(companyClass);
    company.setProperty("name", "JetBrains");
    var parent = session.newInstance(parentClass);
    parent.setProperty("company", company);
    session.commit();

    var sql = "SELECT $comp as company FROM " + parentClass
        + " LET $comp = (SELECT name FROM " + companyClass
        + " WHERE @rid = $parent.$current.company)";

    var plan = explainPlan(sql);
    Assert.assertTrue(
        "Identifiable LINK RHS must still use FetchFromCorrelatedRidStep, plan was: " + plan,
        plan.contains("FETCH FROM CORRELATED RID"));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals(
          "JetBrains",
          singleLetProperty(result.next().getProperty("company"), "name"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * Reversed correlated operands ({@code $parent.$current.ref = @rid}) must take the same
   * correlated fetch path as {@code @rid = $parent...} — the equality extractor accepts both
   * orders.
   */
  @Test
  public void correlatedRidReversedOperands_usesCorrelatedRidFetch() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    var child = session.newInstance(childClass);
    child.setProperty("val", 11);
    var childRid = child.getIdentity();
    var parent = session.newInstance(parentClass);
    parent.setProperty("childRef", childRid);
    session.commit();

    var sql = "SELECT $info as info FROM " + parentClass
        + " LET $info = (SELECT val FROM " + childClass
        + " WHERE $parent.$current.childRef = @rid)";

    var plan = explainPlan(sql);
    Assert.assertTrue(
        "reversed correlated equality must use FetchFromCorrelatedRidStep, plan was: " + plan,
        plan.contains("FETCH FROM CORRELATED RID"));
    Assert.assertFalse(plan.contains("FETCH FROM CLASS " + childClass));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals(
          11,
          ((Number) singleLetProperty(result.next().getProperty("info"), "val")).intValue());
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * Subclass RID under a superclass FROM in a correlated LET must load the subclass record —
   * polymorphic membership, same as the plan-time subclass criterion.
   */
  @Test
  public void correlatedRidSubclassUnderSuperclass_returnsRecord() {
    var parentClass = createClassInstance().getName();
    var superClass = createClassInstance();
    var subClass = createChildClassInstance(superClass);
    session.begin();
    var sub = session.newInstance(subClass.getName());
    sub.setProperty("tag", "sub");
    var subRid = sub.getIdentity();
    var parent = session.newInstance(parentClass);
    parent.setProperty("ref", subRid);
    session.commit();

    var sql = "SELECT $x as x FROM " + parentClass
        + " LET $x = (SELECT tag FROM " + superClass.getName()
        + " WHERE @rid = $parent.$current.ref)";

    var plan = explainPlan(sql);
    Assert.assertTrue(plan.contains("FETCH FROM CORRELATED RID"));
    Assert.assertFalse(plan.contains("FETCH FROM CLASS " + superClass.getName()));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals("sub", singleLetProperty(result.next().getProperty("x"), "tag"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * Correlated equality whose RHS evaluates to a size-1 Collection must unwrap and fetch — parity
   * with QueryOperatorEquals and the plan-time size-1 collection param path.
   */
  @Test
  public void correlatedRidSingleElementCollectionRhs_fetchesRecord() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    var child = session.newInstance(childClass);
    child.setProperty("val", 3);
    var childRid = child.getIdentity();
    var parent = session.newInstance(parentClass);
    parent.getOrCreateLinkList("refs").add(childRid);
    session.commit();

    var sql = "SELECT $info as info FROM " + parentClass
        + " LET $info = (SELECT val FROM " + childClass
        + " WHERE @rid = $parent.$current.refs)";

    var plan = explainPlan(sql);
    Assert.assertTrue(plan.contains("FETCH FROM CORRELATED RID"));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals(
          3,
          ((Number) singleLetProperty(result.next().getProperty("info"), "val")).intValue());
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * Correlated equality whose RHS is a Collection of size 2+ must yield empty (not expand like IN).
   * Matches plan-time {@code @rid = [#a, #b]} / multi-element param parity with QueryOperatorEquals.
   */
  @Test
  public void correlatedRidMultiElementCollectionRhs_yieldsEmpty() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    var a = session.newInstance(childClass);
    a.setProperty("val", 1);
    var b = session.newInstance(childClass);
    b.setProperty("val", 2);
    var parent = session.newInstance(parentClass);
    parent.getOrCreateLinkList("refs").addAll(List.of(a.getIdentity(), b.getIdentity()));
    session.commit();

    var sql = "SELECT $info as info FROM " + parentClass
        + " LET $info = (SELECT val FROM " + childClass
        + " WHERE @rid = $parent.$current.refs)";

    var plan = explainPlan(sql);
    Assert.assertTrue(
        "size-2+ equality RHS must still compile to FetchFromCorrelatedRidStep (runtime empty), "
            + "plan was: " + plan,
        plan.contains("FETCH FROM CORRELATED RID"));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      assertLetEmpty(
          "@rid = <size-2 collection> must not expand into a multi-fetch",
          result.next().getProperty("info"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * Correlated equality whose RHS is an empty Collection must yield empty — size-0 unwrap boundary
   * complementary to the size-1 and size-2+ cases.
   */
  @Test
  public void correlatedRidEmptyCollectionRhs_yieldsEmpty() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    session.newInstance(childClass).setProperty("val", 1);
    var parent = session.newInstance(parentClass);
    parent.getOrCreateLinkList("refs");
    session.commit();

    var sql = "SELECT $info as info FROM " + parentClass
        + " LET $info = (SELECT val FROM " + childClass
        + " WHERE @rid = $parent.$current.refs)";

    var plan = explainPlan(sql);
    Assert.assertTrue(plan.contains("FETCH FROM CORRELATED RID"));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      assertLetEmpty(
          "@rid = <empty collection> must match nothing",
          result.next().getProperty("info"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * Correlated equality whose RHS is a malformed RID string must yield empty (no throw) — parity
   * with plan-time {@code @rid = 'garbage'} and QueryOperatorEquals. Successful string parsing is
   * covered by {@link FetchFromCorrelatedRidStepTest#stringLiteralRidFetchesRecord} (shared
   * {@code toRecordIdCandidate} String arm).
   */
  @Test
  public void correlatedRidMalformedStringRhs_yieldsEmpty() {
    var parentSchema = createClassInstance();
    parentSchema.createProperty("childRef", PropertyType.STRING);
    var parentClass = parentSchema.getName();
    var childClass = createClassInstance().getName();
    session.begin();
    session.newInstance(childClass).setProperty("val", 1);
    var parent = session.newInstance(parentClass);
    parent.setProperty("childRef", "garbage");
    session.commit();

    var sql = "SELECT $info as info FROM " + parentClass
        + " LET $info = (SELECT val FROM " + childClass
        + " WHERE @rid = $parent.$current.childRef)";

    var plan = explainPlan(sql);
    Assert.assertTrue(
        "malformed string RHS must still compile to FetchFromCorrelatedRidStep, plan was: "
            + plan,
        plan.contains("FETCH FROM CORRELATED RID"));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      assertLetEmpty(
          "malformed RID string must yield empty LET (no throw)",
          result.next().getProperty("info"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * Correlated {@code @rid IN $parent.$current.<list>} must fall through to a class scan — the
   * correlated step is equality-only and must not pretend IN expands a parent-bound list.
   */
  @Test
  public void correlatedRidInParentList_fallsThroughToScan() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    var a = session.newInstance(childClass);
    a.setProperty("tag", "a");
    var b = session.newInstance(childClass);
    b.setProperty("tag", "b");
    var parent = session.newInstance(parentClass);
    parent.getOrCreateLinkList("refs").addAll(List.of(a.getIdentity(), b.getIdentity()));
    session.commit();

    var sql = "SELECT $info as info FROM " + parentClass
        + " LET $info = (SELECT tag FROM " + childClass
        + " WHERE @rid IN $parent.$current.refs)";

    var plan = explainPlan(sql);
    Assert.assertFalse(
        "correlated @rid IN $parent must NOT use FetchFromCorrelatedRidStep, plan was: " + plan,
        plan.contains("FETCH FROM CORRELATED RID"));
    Assert.assertTrue(
        "correlated @rid IN $parent must fall through to the class scan, plan was: " + plan,
        plan.contains("FETCH FROM CLASS " + childClass));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      var info = result.next().getProperty("info");
      Assert.assertTrue(info instanceof List);
      Set<String> tags = new HashSet<>();
      for (var row : (List<?>) info) {
        Assert.assertTrue(
            "LET subquery rows must be Result instances, got: "
                + (row == null ? "null" : row.getClass().getName()),
            row instanceof Result);
        tags.add(((Result) row).getProperty("tag"));
      }
      // Scan+filter must still return both members named by the parent list.
      Assert.assertEquals(Set.of("a", "b"), tags);
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * Plan-time {@code @rid = a OR @rid = b} must not take the RID-fetch fast path (multiple OR
   * branches) and must still return both records via the class scan.
   */
  @Test
  public void ridEqualsOrRidEquals_fallsThroughToScanReturnsBoth() {
    var className = createClassInstance().getName();
    session.begin();
    var a = session.newInstance(className);
    a.setProperty("tag", "a");
    var b = session.newInstance(className);
    b.setProperty("tag", "b");
    var ridA = a.getIdentity();
    var ridB = b.getIdentity();
    session.commit();

    var sql = "select from " + className + " where @rid = " + ridA + " or @rid = " + ridB;
    var plan = explainPlan(sql);
    Assert.assertTrue(
        "@rid = a OR @rid = b must fall through to the class scan, plan was: " + plan,
        plan.contains("FETCH FROM CLASS"));
    Assert.assertFalse(
        "OR of RID equalities must not compile to FetchFromRidsStep, plan was: " + plan,
        plan.contains("FETCH FROM RIDs"));
    Assert.assertFalse(plan.contains("FETCH FROM CORRELATED RID"));

    try (var result = session.query(sql)) {
      Set<String> seen = new HashSet<>();
      while (result.hasNext()) {
        seen.add(result.next().getProperty("tag"));
      }
      Assert.assertEquals(Set.of("a", "b"), seen);
    }
  }

  /**
   * Several parent rows, each with a different correlated RID, must each resolve independently —
   * one fetch per parent row, not a shared stale RID from the first parent.
   */
  @Test
  public void correlatedRidMultipleParents_eachResolvesOwnTarget() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    var c1 = session.newInstance(childClass);
    c1.setProperty("tag", "one");
    var c2 = session.newInstance(childClass);
    c2.setProperty("tag", "two");
    var p1 = session.newInstance(parentClass);
    p1.setProperty("name", "p1");
    p1.setProperty("childRef", c1.getIdentity());
    var p2 = session.newInstance(parentClass);
    p2.setProperty("name", "p2");
    p2.setProperty("childRef", c2.getIdentity());
    session.commit();

    var sql = "SELECT name, $info as info FROM " + parentClass
        + " LET $info = (SELECT tag FROM " + childClass
        + " WHERE @rid = $parent.$current.childRef) ORDER BY name";

    var plan = explainPlan(sql);
    Assert.assertTrue(plan.contains("FETCH FROM CORRELATED RID"));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      var row1 = result.next();
      Assert.assertEquals("p1", row1.getProperty("name"));
      Assert.assertEquals(
          "first parent must resolve its own child (tag=one), not a stale RID",
          "one",
          singleLetProperty(row1.getProperty("info"), "tag"));
      Assert.assertTrue(result.hasNext());
      var row2 = result.next();
      Assert.assertEquals("p2", row2.getProperty("name"));
      Assert.assertEquals(
          "second parent must resolve its own child (tag=two), not the first parent's RID",
          "two",
          singleLetProperty(row2.getProperty("info"), "tag"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * Correlated {@code @rid = $parent...} against a nonexistent FROM class must still throw when the
   * plan is built (EXPLAIN), matching the plan-time RID path's class-existence contract. The
   * correlated handler falls through on a missing class so the scan path can raise the error rather
   * than chaining an EmptyStep that would mask a typo'd class name.
   */
  @Test
  public void correlatedRidNonexistentClass_throwsClassNotPresent() {
    var parentClass = createClassInstance().getName();
    var missingClass = "NoSuchClass" + System.nanoTime();
    session.begin();
    var parent = session.newInstance(parentClass);
    parent.setProperty("ref", "#12:0");
    session.commit();

    var sql = "SELECT $x as x FROM " + parentClass
        + " LET $x = (SELECT FROM " + missingClass
        + " WHERE @rid = $parent.$current.ref)";
    try {
      explainPlan(sql);
      Assert.fail("correlated @rid against a missing class must throw at plan time, not EXPLAIN");
    } catch (CommandExecutionException e) {
      Assert.assertTrue(
          "the error must name the missing class, message was: " + e.getMessage(),
          e.getMessage().contains(missingClass));
    }
  }

  /**
   * Plan-time scalar {@code @rid = [#a, #b]} (equality, Collection size 2) must fall through and
   * return empty — not IN semantics, not a multi-RID fetch.
   */
  @Test
  public void ridEqualsTwoElementLiteralCollection_returnsEmpty() {
    var className = createClassInstance().getName();
    session.begin();
    var a = session.newInstance(className);
    a.setProperty("tag", "a");
    var b = session.newInstance(className);
    b.setProperty("tag", "b");
    var ridA = a.getIdentity();
    var ridB = b.getIdentity();
    session.commit();

    var sql = "select from " + className + " where @rid = [" + ridA + ", " + ridB + "]";
    var plan = explainPlan(sql);
    Assert.assertTrue(
        "@rid = [a, b] must fall through to the class scan, plan was: " + plan,
        plan.contains("FETCH FROM CLASS"));
    Assert.assertFalse(
        "@rid = [a, b] must NOT expand into FetchFromRidsStep, plan was: " + plan,
        plan.contains("FETCH FROM RIDs"));

    try (var result = session.query(sql)) {
      Assert.assertFalse(
          "@rid = [two rids] must return empty (equals, not IN)", result.hasNext());
    }
  }

  /**
   * A scalar {@code @rid = :param} bound to a non-Collection multi-value ({@code Object[]}) must
   * fall through and return empty — QueryOperatorEquals only unwraps {@code Collection}, so an
   * array never matches a scalar {@code @rid}. The fast path must not treat arrays as IN-lists.
   */
  @Test
  public void ridEqualsObjectArrayParam_returnsEmpty() {
    var className = createClassInstance().getName();
    session.begin();
    var a = session.newInstance(className);
    a.setProperty("tag", "a");
    var b = session.newInstance(className);
    b.setProperty("tag", "b");
    var ridA = a.getIdentity();
    var ridB = b.getIdentity();
    session.commit();

    Map<Object, Object> params = new HashMap<>();
    params.put("p", new Object[] {ridA, ridB});

    var sql = "select from " + className + " where @rid = :p";
    var plan = explainPlanWithParams(sql, params);
    Assert.assertTrue(
        "Object[] RHS must fall through to the class scan, plan was: " + plan,
        plan.contains("FETCH FROM CLASS"));
    Assert.assertFalse(
        "Object[] must NOT expand into FetchFromRidsStep, plan was: " + plan,
        plan.contains("FETCH FROM RIDs"));

    try (var result = session.query(sql, params)) {
      Assert.assertFalse(
          "@rid = Object[] must return empty (parity with QueryOperatorEquals)",
          result.hasNext());
    }
  }

  /**
   * {@code @rid = $parent.$current} evaluates to an identifiable {@code Result}; the
   * identifier-set fetch must return the parent row itself.
   */
  @Test
  public void correlatedRidBareParentRow_selectsCorrelatedRidPlanOnly() {
    var className = createClassInstance().getName();
    session.begin();
    session.newInstance(className).setProperty("tag", "self");
    session.commit();

    var sql = "SELECT $self as self FROM " + className
        + " LET $self = (SELECT tag FROM " + className
        + " WHERE @rid = $parent.$current)";

    var plan = explainPlan(sql);
    Assert.assertTrue(
        "$parent.$current is a parent-rooted chain, so the gate must keep the correlated fetch, "
            + "plan was: " + plan,
        plan.contains("FETCH FROM CORRELATED RID"));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals(
          "self",
          singleLetProperty(result.next().getProperty("self"), "tag"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * Gate whitelist, accept: a constant bracket index over a parent-rooted link list
   * ({@code @rid = $parent.$current.refs[0]}) stays on the correlated fetch, because the index is
   * a literal and every link is parent-rooted. The indexed element is a plain RID, so the rows are
   * asserted as well: the fetch must return exactly the first linked child and not the second.
   */
  @Test
  public void correlatedRidConstantIndexOnParentList_fetchesFirstElement() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    var first = session.newInstance(childClass);
    first.setProperty("tag", "first");
    var second = session.newInstance(childClass);
    second.setProperty("tag", "second");
    var parent = session.newInstance(parentClass);
    parent.getOrCreateLinkList("refs")
        .addAll(List.of(first.getIdentity(), second.getIdentity()));
    session.commit();

    var sql = "SELECT $info as info FROM " + parentClass
        + " LET $info = (SELECT tag FROM " + childClass
        + " WHERE @rid = $parent.$current.refs[0])";

    var plan = explainPlan(sql);
    Assert.assertTrue(
        "a literal index on a parent chain must keep the correlated fetch, plan was: " + plan,
        plan.contains("FETCH FROM CORRELATED RID"));
    Assert.assertFalse(
        "the child scan must be gone once the correlated fetch is chosen, plan was: " + plan,
        plan.contains("FETCH FROM CLASS " + childClass));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals(
          "the literal index must select the first linked child, not the second",
          "first",
          singleLetProperty(result.next().getProperty("info"), "tag"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * Gate whitelist, accept, plan selection only: a constant bracket index over a parent LET
   * variable ({@code @rid = $parent.$r[0]}) is still a chain rooted at {@code $parent}, so the gate
   * must admit it.
   *
   * <p>KNOWN GAP, deliberately not asserted. {@code $r} holds subquery rows, so the index yields a
   * {@code Result}, and {@code SelectExecutionPlanner.toRecordIdCandidate} has no {@code Result}
   * case. The fast path therefore returns no rows today while the class scan plus filter returns
   * one. This test asserts plan selection and nothing else. Row parity for a {@code Result}-valued
   * right side is the acceptance condition of the value-domain track. No assertion here pins the
   * current zero-row outcome, because pinning a defect would make the fix look like a regression.
   */
  @Test
  public void correlatedRidConstantIndexOnParentLetVariable_selectsCorrelatedRidPlanOnly() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    session.newInstance(childClass).setProperty("tag", "a");
    session.newInstance(parentClass).setProperty("name", "p");
    session.commit();

    var sql = "SELECT $info as info FROM " + parentClass
        + " LET $r = (SELECT FROM " + childClass + "),"
        + " $info = (SELECT tag FROM " + childClass
        + " WHERE @rid = $parent.$r[0])";

    var plan = explainPlan(sql);
    Assert.assertTrue(
        "a literal index on a parent LET variable must keep the correlated fetch, plan was: "
            + plan,
        plan.contains("FETCH FROM CORRELATED RID"));
  }

  /**
   * Gate whitelist, reject: a function call anywhere on the right side falls back to the class
   * scan plus filter. Here {@code ifnull}'s first argument is a bare inner property, so the scan
   * evaluates it once per inner row, while the correlated fetch would evaluate it once against a
   * null record. The rows prove the fallback still resolves the parent reference.
   */
  @Test
  public void correlatedRidFunctionCallArgument_fallsThroughToScan() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    var target = session.newInstance(childClass);
    target.setProperty("tag", "target");
    var other = session.newInstance(childClass);
    other.setProperty("tag", "other");
    var parent = session.newInstance(parentClass);
    parent.setProperty("ref", target.getIdentity());
    session.commit();

    var sql = "SELECT $info as info FROM " + parentClass
        + " LET $info = (SELECT tag FROM " + childClass
        + " WHERE @rid = ifnull(otherRef, $parent.$current.ref))";

    var plan = explainPlan(sql);
    Assert.assertFalse(
        "a function call on the right side must NOT use the correlated fetch, plan was: " + plan,
        plan.contains("FETCH FROM CORRELATED RID"));
    Assert.assertTrue(
        "a rejected right side must fall through to the child scan, plan was: " + plan,
        plan.contains("FETCH FROM CLASS " + childClass));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals(
          "scan plus filter must still coalesce to the parent reference and match one child",
          "target",
          singleLetProperty(result.next().getProperty("info"), "tag"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * Gate whitelist, reject, the counterexample that motivates the whitelist: a function that reads
   * the inner row with no signal in the syntax tree. {@code out()} falls back to the current-record
   * system variable when the record argument is null, so the correlated fetch would evaluate it
   * once against null and return nothing, while the scan plus filter matches every self-looped
   * vertex. Each vertex has an edge to itself, so per-row {@code first(out(edge))} is the row
   * itself and all three rows match.
   */
  @Test
  public void correlatedRidHiddenInnerRowFunctionRead_fallsThroughToScan() {
    var vertexClass = "Vtx" + System.nanoTime();
    var edgeClass = "Loop" + System.nanoTime();
    var parentClass = createClassInstance().getName();
    session.createVertexClass(vertexClass);
    session.createEdgeClass(edgeClass);

    session.begin();
    for (var i = 0; i < 3; i++) {
      var vertex = session.newVertex(vertexClass);
      vertex.setProperty("tag", "v" + i);
      session.newEdge(vertex, vertex, edgeClass);
    }
    session.newInstance(parentClass).setProperty("name", "p");
    session.commit();

    var sql = "SELECT $info as info FROM " + parentClass
        + " LET $info = (SELECT tag FROM " + vertexClass
        + " WHERE @rid = ifnull($parent.$current.missing, first(out('" + edgeClass + "'))))";

    var plan = explainPlan(sql);
    Assert.assertFalse(
        "a hidden inner-row read through a function must NOT use the correlated fetch, plan was: "
            + plan,
        plan.contains("FETCH FROM CORRELATED RID"));
    Assert.assertTrue(
        "the rejected right side must fall through to the vertex scan, plan was: " + plan,
        plan.contains("FETCH FROM CLASS " + vertexClass));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals(
          "every self-looped vertex must match when the function is evaluated per row",
          Set.of("v0", "v1", "v2"),
          letTags(result.next().getProperty("info")));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * Gate whitelist, reject: a method call on a parent-rooted value. {@code SQLMethodCall} can also
   * reach the current-record system variable, by its own route, so the gate refuses the whole chain
   * even though every named link is parent-rooted. The rows prove the scan fallback still resolves
   * the same single child.
   */
  @Test
  public void correlatedRidMethodCallOnParentValue_fallsThroughToScan() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    var first = session.newInstance(childClass);
    first.setProperty("tag", "first");
    var second = session.newInstance(childClass);
    second.setProperty("tag", "second");
    var parent = session.newInstance(parentClass);
    parent.getOrCreateLinkList("refs")
        .addAll(List.of(first.getIdentity(), second.getIdentity()));
    session.commit();

    var sql = "SELECT $info as info FROM " + parentClass
        + " LET $info = (SELECT tag FROM " + childClass
        + " WHERE @rid = $parent.$current.refs.asList()[0])";

    var plan = explainPlan(sql);
    Assert.assertFalse(
        "a method call in the chain must NOT use the correlated fetch, plan was: " + plan,
        plan.contains("FETCH FROM CORRELATED RID"));
    Assert.assertTrue(
        "a rejected right side must fall through to the child scan, plan was: " + plan,
        plan.contains("FETCH FROM CLASS " + childClass));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals(
          "scan plus filter must still select the first linked child",
          "first",
          singleLetProperty(result.next().getProperty("info"), "tag"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * Gate whitelist, reject: an arithmetic composition is not a single chain, so the gate refuses
   * it even though both operands are parent-rooted. The null-coalescing operator keeps the value
   * well defined, so the rows prove the scan fallback resolves the right-hand operand.
   */
  @Test
  public void correlatedRidArithmeticOperand_fallsThroughToScan() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    var target = session.newInstance(childClass);
    target.setProperty("tag", "target");
    var other = session.newInstance(childClass);
    other.setProperty("tag", "other");
    var parent = session.newInstance(parentClass);
    parent.setProperty("ref", target.getIdentity());
    session.commit();

    var sql = "SELECT $info as info FROM " + parentClass
        + " LET $info = (SELECT tag FROM " + childClass
        + " WHERE @rid = $parent.$current.missingRef ?? $parent.$current.ref)";

    var plan = explainPlan(sql);
    Assert.assertFalse(
        "an arithmetic operand must NOT use the correlated fetch, plan was: " + plan,
        plan.contains("FETCH FROM CORRELATED RID"));
    Assert.assertTrue(
        "a rejected right side must fall through to the child scan, plan was: " + plan,
        plan.contains("FETCH FROM CLASS " + childClass));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals(
          "scan plus filter must coalesce the absent operand to the parent reference",
          "target",
          singleLetProperty(result.next().getProperty("info"), "tag"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * Gate whitelist, reject: a bare inner property name has no {@code $parent} root at all, so it
   * reads the inner row by definition and must stay on the scan. Only the child that links to
   * itself satisfies {@code @rid = selfRef}, which pins that the filter runs per inner row.
   */
  @Test
  public void correlatedRidBareInnerProperty_fallsThroughToScan() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    var selfLinked = session.newInstance(childClass);
    selfLinked.setProperty("tag", "self");
    selfLinked.setProperty("selfRef", selfLinked.getIdentity());
    var otherLinked = session.newInstance(childClass);
    otherLinked.setProperty("tag", "other");
    otherLinked.setProperty("selfRef", selfLinked.getIdentity());
    session.newInstance(parentClass).setProperty("name", "p");
    session.commit();

    var sql = "SELECT $info as info FROM " + parentClass
        + " LET $info = (SELECT tag FROM " + childClass
        + " WHERE @rid = selfRef)";

    var plan = explainPlan(sql);
    Assert.assertFalse(
        "a bare inner property must NOT use the correlated fetch, plan was: " + plan,
        plan.contains("FETCH FROM CORRELATED RID"));
    Assert.assertTrue(
        "a bare inner property must fall through to the child scan, plan was: " + plan,
        plan.contains("FETCH FROM CLASS " + childClass));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals(
          "only the self-linked child satisfies the per-row predicate",
          Set.of("self"),
          letTags(result.next().getProperty("info")));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * Correlated fetch is LET-hosted only. A projection subquery is not LET-hosted, so a
   * parent-only chain must fall through to the class scan even though the whitelist admits the
   * expression.
   */
  @Test
  public void correlatedRidInProjectionSubquery_withoutLetHost_fallsThroughToScan() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    var child = session.newInstance(childClass);
    child.setProperty("tag", "hit");
    var parent = session.newInstance(parentClass);
    parent.setProperty("ref", child.getIdentity());
    session.commit();

    var sql = "SELECT (SELECT tag FROM " + childClass
        + " WHERE @rid = $parent.$current.ref) AS info FROM " + parentClass;

    var plan = explainPlan(sql);
    Assert.assertFalse(
        "projection subqueries are not LET-hosted, plan was: " + plan,
        plan.contains("FETCH FROM CORRELATED RID"));
    Assert.assertTrue(
        "projection subqueries must scan the target class, plan was: " + plan,
        plan.contains("FETCH FROM CLASS " + childClass));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      var info = result.next().getProperty("info");
      Assert.assertTrue(info instanceof List);
      Assert.assertEquals(1, ((List<?>) info).size());
      Assert.assertEquals(
          "hit",
          ((Result) ((List<?>) info).get(0)).getProperty("tag"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * Differential parity: append {@code OR tag = 'zzz'} to force the scan oracle while the fast path
   * keeps the correlated fetch. No row carries tag {@code zzz}, so both paths return the same set.
   */
  @Test
  public void correlatedRidFetch_matchesScanOracleWithTagDisjunct() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    var hit = session.newInstance(childClass);
    hit.setProperty("tag", "hit");
    var parent = session.newInstance(parentClass);
    parent.setProperty("ref", hit.getIdentity());
    session.commit();

    var fastSql = "SELECT $info as info FROM " + parentClass
        + " LET $info = (SELECT tag FROM " + childClass
        + " WHERE @rid = $parent.$current.ref)";
    var oracleSql = "SELECT $info as info FROM " + parentClass
        + " LET $info = (SELECT tag FROM " + childClass
        + " WHERE @rid = $parent.$current.ref OR tag = 'zzz')";

    var fastPlan = explainPlan(fastSql);
    Assert.assertTrue(
        "fast path must use the correlated fetch, plan was: " + fastPlan,
        fastPlan.contains("FETCH FROM CORRELATED RID"));

    Assert.assertEquals(
        "correlated fetch must match the scan oracle row set and order",
        letTagsOrdered(runSingleParentRow(oracleSql, "info")),
        letTagsOrdered(runSingleParentRow(fastSql, "info")));
  }

  /**
   * Nested size-one collections ({@code [[rid]]}) must unwrap to the inner identifier the way
   * {@code QueryOperatorEquals} does through LINK conversion — BG-3 parity.
   */
  @Test
  public void correlatedRidNestedSizeOneCollection_fetchesRow() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    var hit = session.newInstance(childClass);
    hit.setProperty("tag", "hit");
    session.newInstance(parentClass);
    session.commit();

    var fastSql = "SELECT $info AS info FROM " + parentClass
        + " LET $w = [[" + hit.getIdentity() + "]],"
        + " $info = (SELECT tag FROM " + childClass + " WHERE @rid = $parent.$w)";
    var oracleSql = "SELECT $info AS info FROM " + parentClass
        + " LET $w = [[" + hit.getIdentity() + "]],"
        + " $info = (SELECT tag FROM " + childClass
        + " WHERE @rid = $parent.$w OR tag = 'zzz')";

    Assert.assertEquals(
        letTagsOrdered(runSingleParentRow(oracleSql, "info")),
        letTagsOrdered(runSingleParentRow(fastSql, "info")));
  }

  /**
   * An uncommitted {@code EntityImpl} RHS must match the committed scan path — BG-4 parity.
   */
  @Test
  public void correlatedRidUncommittedEntity_returnsFreshRow() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    session.newInstance(childClass).setProperty("tag", "committed");
    session.commit();

    session.begin();
    var fresh = session.newInstance(childClass);
    fresh.setProperty("tag", "fresh");
    var parent = session.newInstance(parentClass);
    parent.setProperty("ref", fresh);

    var sql = "SELECT $info AS info FROM " + parentClass
        + " LET $info = (SELECT tag FROM " + childClass
        + " WHERE @rid = $parent.$current.ref)";
    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals(
          List.of("fresh"),
          letTagsOrdered(result.next().getProperty("info")));
      Assert.assertFalse(result.hasNext());
    }
    session.commit();
  }

  /**
   * Projection correlated subqueries must fetch when the outer statement hosts rows through a user
   * LET variable — AD-21 regression (variable {@code FROM $pv} source).
   */
  @Test
  public void correlatedRidProjection_withLetVariableSource_fetchesParentRow() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    var target = session.newInstance(childClass);
    target.setProperty("name", "target");
    var selfRef = session.newInstance(childClass);
    selfRef.setProperty("name", "selfRef");
    session.commit();
    session.begin();
    session.load(selfRef.getIdentity()).asEntity().setProperty("k", selfRef.getIdentity());
    for (var tag : List.of("p1", "p2", "p3")) {
      var row = session.newInstance(parentClass);
      row.setProperty("tag", tag);
      row.setProperty("k", target.getIdentity());
    }
    session.commit();

    var sql = "SELECT tag, (SELECT name FROM " + childClass
        + " WHERE @rid = $parent.$current.k) AS projForm"
        + " FROM $pv LET $pv = (SELECT FROM " + parentClass + " ORDER BY tag)"
        + " ORDER BY tag";

    var plan = explainPlan(sql);
    Assert.assertTrue(
        "projection subquery with a user LET variable source must fetch, plan was: " + plan,
        plan.contains("FETCH FROM CORRELATED RID"));

    List<String> names = new ArrayList<>();
    try (var result = session.query(sql)) {
      while (result.hasNext()) {
        var row = result.next();
        var proj = row.getProperty("projForm");
        Assert.assertTrue(proj instanceof List);
        var list = (List<?>) proj;
        Assert.assertEquals(1, list.size());
        names.add(((Result) list.get(0)).getProperty("name"));
      }
    }
    Assert.assertEquals(List.of("target", "target", "target"), names);
  }

  /**
   * AD-14: a size-one collection of a projection {@code Result} unwraps once only — recursive
   * unwrap would invent rows.
   */
  @Test
  public void correlatedRidSingleWrapOfProjectionResult_yieldsEmpty() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    session.newInstance(childClass).setProperty("tag", "hit");
    session.newInstance(parentClass);
    session.commit();

    var sql = "SELECT $info AS info FROM " + parentClass
        + " LET $r = (SELECT @rid AS ids FROM " + parentClass + "),"
        + " $w = [$r],"
        + " $info = (SELECT tag FROM " + childClass + " WHERE @rid = $parent.$w)";
    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      assertLetEmpty(
          "single wrap of a projection Result must yield no child rows",
          result.next().getProperty("info"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * Membership set of two (one-property projection {@code Result} from {@code SELECT ids FROM P}):
   * {@code LIMIT 1} must return the identifier-ascending first row, not the first link in value
   * order — Probe20 HR2 parity.
   */
  @Test
  public void correlatedRidMembershipSet_limitOne_returnsIdentifierAscendingFirst() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    var first = session.newInstance(childClass);
    first.setProperty("tag", "first");
    var second = session.newInstance(childClass);
    second.setProperty("tag", "second");
    var parent = session.newInstance(parentClass);
    // Value order is reversed relative to identifier ascending order.
    parent.getOrCreateLinkList("revIds").addAll(List.of(second.getIdentity(), first.getIdentity()));
    session.commit();

    var ridFirst = (RecordIdInternal) first.getIdentity();
    var ridSecond = (RecordIdInternal) second.getIdentity();
    var expectedLimitTag = ridFirst.compareTo(ridSecond) < 0 ? "first" : "second";

    var innerSubquery = "(SELECT tag FROM " + childClass
        + " WHERE @rid = $parent.$current)";
    var innerLimitSubquery = "(SELECT tag FROM " + childClass
        + " WHERE @rid = $parent.$current LIMIT 1)";
    var limitSql = "SELECT $info AS info FROM (SELECT revIds FROM " + parentClass + ")"
        + " LET $info = " + innerLimitSubquery;

    var plan = explainPlan(limitSql);
    Assert.assertTrue(
        "membership LIMIT 1 must keep the correlated fetch, plan was: " + plan,
        plan.contains("FETCH FROM CORRELATED RID"));

    try (var result = session.query(limitSql)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals(
          "LIMIT 1 must take the identifier-ascending first row, not the first link in the list",
          expectedLimitTag,
          singleLetProperty(result.next().getProperty("info"), "tag"));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * A two-element membership set with a foreign-class identifier and a deleted sibling must return
   * only the live in-class row — Probe20 H7 parity.
   */
  @Test
  public void correlatedRidMembershipSet_foreignAndDeleted_keepsLiveInClass() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    var foreignClass = createClassInstance().getName();
    session.begin();
    var live = session.newInstance(childClass);
    live.setProperty("tag", "live");
    var doomed = session.newInstance(childClass);
    doomed.setProperty("tag", "doomed");
    var foreign = session.newInstance(foreignClass);
    foreign.setProperty("tag", "foreign");
    var parent = session.newInstance(parentClass);
    parent.getOrCreateLinkList("mixIds").addAll(List.of(
        live.getIdentity(), foreign.getIdentity(), doomed.getIdentity()));
    session.commit();
    session.begin();
    session.execute("DELETE FROM " + childClass + " WHERE @rid = " + doomed.getIdentity()).close();
    session.commit();

    var sql = "SELECT $info AS info FROM (SELECT mixIds FROM " + parentClass + ")"
        + " LET $info = (SELECT tag FROM " + childClass
        + " WHERE @rid = $parent.$current)";

    var plan = explainPlan(sql);
    Assert.assertTrue(
        "mixed membership set must use the correlated fetch, plan was: " + plan,
        plan.contains("FETCH FROM CORRELATED RID"));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals(Set.of("live"), letTags(result.next().getProperty("info")));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * With a two-element membership set, a remainder predicate ({@code AND tag <> 'second'}) must
   * filter after the fetch and drop only the excluded row — Probe20 H2 parity.
   */
  @Test
  public void correlatedRidMembershipSet_remainderFilter_appliesAfterFetch() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    var first = session.newInstance(childClass);
    first.setProperty("tag", "first");
    var second = session.newInstance(childClass);
    second.setProperty("tag", "second");
    var parent = session.newInstance(parentClass);
    parent.getOrCreateLinkList("ids").addAll(List.of(first.getIdentity(), second.getIdentity()));
    session.commit();

    var sql = "SELECT $info AS info FROM (SELECT ids FROM " + parentClass + ")"
        + " LET $info = (SELECT tag FROM " + childClass
        + " WHERE @rid = $parent.$current AND tag <> 'second')";

    var plan = explainPlan(sql);
    Assert.assertTrue(
        "membership fetch plus remainder must compile to FetchFromCorrelatedRidStep, plan was: "
            + plan,
        plan.contains("FETCH FROM CORRELATED RID"));
    Assert.assertEquals(
        "the remainder must become exactly one FilterStep, plan was: " + plan,
        1,
        countOccurrences(plan, "FILTER ITEMS WHERE"));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals(Set.of("first"), letTags(result.next().getProperty("info")));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * A plain two-element membership set must return both matching rows in identifier-ascending
   * order — Probe20 H1 parity (projection-wrapped link list, not a direct size-2 collection).
   */
  @Test
  public void correlatedRidMembershipSet_returnsBothRowsInIdentifierOrder() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    var first = session.newInstance(childClass);
    first.setProperty("tag", "first");
    var second = session.newInstance(childClass);
    second.setProperty("tag", "second");
    var parent = session.newInstance(parentClass);
    parent.getOrCreateLinkList("ids").addAll(List.of(first.getIdentity(), second.getIdentity()));
    session.commit();

    var ridFirst = (RecordIdInternal) first.getIdentity();
    var ridSecond = (RecordIdInternal) second.getIdentity();
    var expectedFirstTag = ridFirst.compareTo(ridSecond) < 0 ? "first" : "second";
    var expectedSecondTag = ridFirst.compareTo(ridSecond) < 0 ? "second" : "first";

    var sql = "SELECT $info AS info FROM (SELECT ids FROM " + parentClass + ")"
        + " LET $info = (SELECT tag FROM " + childClass
        + " WHERE @rid = $parent.$current)";

    var plan = explainPlan(sql);
    Assert.assertTrue(
        "two-element membership must use the correlated fetch, plan was: " + plan,
        plan.contains("FETCH FROM CORRELATED RID"));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      Assert.assertEquals(
          List.of(expectedFirstTag, expectedSecondTag),
          letTagsOrdered(result.next().getProperty("info")));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * Terminal record-attribute suffix ({@code .@rid}) on a parent link must fetch the linked row —
   * TQ-11 / ParentOnlyChain default arm.
   */
  @Test
  public void correlatedRidTerminalRecordAttributeOnLink_fetchesTargetRow() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    var hit = session.newInstance(childClass);
    hit.setProperty("tag", "hit");
    var parent = session.newInstance(parentClass);
    parent.setProperty("ref", hit.getIdentity());
    session.commit();

    var fastSql = "SELECT $info AS info FROM " + parentClass
        + " LET $info = (SELECT tag FROM " + childClass
        + " WHERE @rid = $parent.$current.ref.@rid)";
    var oracleSql = "SELECT $info AS info FROM " + parentClass
        + " LET $info = (SELECT tag FROM " + childClass
        + " WHERE @rid = $parent.$current.ref.@rid OR tag = 'zzz')";

    var plan = explainPlan(fastSql);
    Assert.assertTrue(
        "terminal .@rid on a parent link must use correlated fetch, plan was: " + plan,
        plan.contains("FETCH FROM CORRELATED RID"));

    Assert.assertEquals(
        letTagsOrdered(runSingleParentRow(oracleSql, "info")),
        letTagsOrdered(runSingleParentRow(fastSql, "info")));
  }

  /**
   * Inline projection subqueries grouped without a user LET must stay on the scan path — BG-6 /
   * Probe39 G2.
   */
  @Test
  public void correlatedRidInlineProjectionGroup_withoutUserLet_usesScan() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    var hit = session.newInstance(childClass);
    hit.setProperty("tag", "t1");
    hit.setProperty("kind", "post");
    var parent = session.newInstance(parentClass);
    parent.setProperty("ref", hit.getIdentity());
    session.commit();

    var sql = "SELECT (SELECT count(*) AS n FROM"
        + " (SELECT FROM " + childClass + " WHERE @rid = $parent.$current.ref)"
        + " WHERE kind = 'post') AS a,"
        + " (SELECT count(*) AS n FROM"
        + " (SELECT FROM " + childClass + " WHERE @rid = $parent.$current.ref)"
        + " WHERE kind = 'comment') AS b"
        + " FROM " + parentClass;

    var plan = explainPlan(sql);
    Assert.assertFalse(
        "inline projection group without user LET must not fetch by correlated RID, plan was: "
            + plan,
        plan.contains("FETCH FROM CORRELATED RID"));

    try (var result = session.query(sql)) {
      Assert.assertTrue(result.hasNext());
      var row = result.next();
      Assert.assertEquals(1L, projectionCount(row.getProperty("a")));
      Assert.assertEquals(0L, projectionCount(row.getProperty("b")));
      Assert.assertFalse(result.hasNext());
    }
  }

  /**
   * User LET materialized group with nested {@code [[rid]]} detector must match develop — Probe39
   * G3.
   */
  @Test
  public void correlatedRidUserLetGroup_nestedRidDetector_matchesScanOracle() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    var hit = session.newInstance(childClass);
    hit.setProperty("tag", "t1");
    hit.setProperty("kind", "post");
    var parent = session.newInstance(parentClass);
    parent.setProperty("ref", hit.getIdentity());
    session.commit();
    var rid = hit.getIdentity().toString();

    var fastSql = "SELECT $a AS a, $b AS b FROM " + parentClass
        + " LET $w = [[" + rid + "]],"
        + " $a = (SELECT count(*) AS n FROM"
        + " (SELECT FROM " + childClass + " WHERE @rid = $parent.$w) WHERE kind = 'post'),"
        + " $b = (SELECT count(*) AS n FROM"
        + " (SELECT FROM " + childClass + " WHERE @rid = $parent.$w) WHERE kind = 'comment')";
    var oracleSql = "SELECT $a AS a, $b AS b FROM " + parentClass
        + " LET $w = [[" + rid + "]],"
        + " $a = (SELECT count(*) AS n FROM"
        + " (SELECT FROM " + childClass + " WHERE @rid = $parent.$w OR kind = 'zzz')"
        + " WHERE kind = 'post'),"
        + " $b = (SELECT count(*) AS n FROM"
        + " (SELECT FROM " + childClass + " WHERE @rid = $parent.$w OR kind = 'zzz')"
        + " WHERE kind = 'comment')";

    Assert.assertEquals(
        letCountsOrdered(runSingleParentRow(oracleSql, "a"), runSingleParentRow(oracleSql, "b")),
        letCountsOrdered(runSingleParentRow(fastSql, "a"), runSingleParentRow(fastSql, "b")));
  }

  /**
   * Inline projection group with a user LET and nested {@code [[rid]]} must match develop — Probe39
   * G4 / BG-6 regression.
   */
  @Test
  public void correlatedRidInlineProjectionGroup_withUserLet_matchesScanOracle() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    var hit = session.newInstance(childClass);
    hit.setProperty("tag", "t1");
    hit.setProperty("kind", "post");
    session.newInstance(parentClass);
    session.commit();
    var rid = hit.getIdentity().toString();

    var fastSql = "SELECT (SELECT count(*) AS n FROM"
        + " (SELECT FROM " + childClass + " WHERE @rid = $parent.$w) WHERE kind = 'post') AS a,"
        + " (SELECT count(*) AS n FROM"
        + " (SELECT FROM " + childClass + " WHERE @rid = $parent.$w) WHERE kind = 'comment') AS b"
        + " FROM " + parentClass + " LET $w = [[" + rid + "]]";
    var oracleSql = "SELECT (SELECT count(*) AS n FROM"
        + " (SELECT FROM " + childClass + " WHERE @rid = $parent.$w OR kind = 'zzz')"
        + " WHERE kind = 'post') AS a,"
        + " (SELECT count(*) AS n FROM"
        + " (SELECT FROM " + childClass + " WHERE @rid = $parent.$w OR kind = 'zzz')"
        + " WHERE kind = 'comment') AS b"
        + " FROM " + parentClass + " LET $w = [[" + rid + "]]";

    Assert.assertEquals(
        letCountsOrdered(runSingleParentRow(fastSql, "a"), runSingleParentRow(fastSql, "b")),
        letCountsOrdered(runSingleParentRow(oracleSql, "a"), runSingleParentRow(oracleSql, "b")));
  }

  /**
   * Table A/B differential gate: representative LET-hosted shapes must match the scan oracle with
   * pinned order — TQ-5 integration slice.
   */
  @Test
  public void correlatedRidFetch_differentialMatrix_matchesScanOracleInOrder() {
    var parentClass = createClassInstance().getName();
    var childClass = createClassInstance().getName();
    session.begin();
    var first = session.newInstance(childClass);
    first.setProperty("tag", "first");
    var second = session.newInstance(childClass);
    second.setProperty("tag", "second");
    var parent = session.newInstance(parentClass);
    parent.setProperty("ref", first.getIdentity());
    parent.getOrCreateLinkList("ids").addAll(List.of(second.getIdentity(), first.getIdentity()));
    session.commit();

    assertFetchMatchesScanOracle(
        "SELECT $info AS info FROM " + parentClass
            + " LET $info = (SELECT tag FROM " + childClass
            + " WHERE @rid = $parent.$current.ref)",
        "SELECT $info AS info FROM " + parentClass
            + " LET $info = (SELECT tag FROM " + childClass
            + " WHERE @rid = $parent.$current.ref OR tag = 'zzz')");

    assertFetchMatchesScanOracle(
        "SELECT $info AS info FROM (SELECT ids FROM " + parentClass + ")"
            + " LET $info = (SELECT tag FROM " + childClass
            + " WHERE @rid = $parent.$current)",
        "SELECT $info AS info FROM (SELECT ids FROM " + parentClass + ")"
            + " LET $info = (SELECT tag FROM " + childClass
            + " WHERE @rid = $parent.$current OR tag = 'zzz')");

    assertFetchMatchesScanOracle(
        "SELECT $info AS info FROM " + parentClass
            + " LET $w = [[" + first.getIdentity() + "]],"
            + " $info = (SELECT tag FROM " + childClass + " WHERE @rid = $parent.$w)",
        "SELECT $info AS info FROM " + parentClass
            + " LET $w = [[" + first.getIdentity() + "]],"
            + " $info = (SELECT tag FROM " + childClass
            + " WHERE @rid = $parent.$w OR tag = 'zzz')");
  }

  private void assertFetchMatchesScanOracle(String fastSql, String oracleSql) {
    var fastPlan = explainPlan(fastSql);
    Assert.assertTrue(
        "fast path must use correlated fetch, plan was: " + fastPlan,
        fastPlan.contains("FETCH FROM CORRELATED RID"));
    Assert.assertEquals(
        letTagsOrdered(runSingleParentRow(oracleSql, "info")),
        letTagsOrdered(runSingleParentRow(fastSql, "info")));
  }

  private static long projectionCount(Object projectionValue) {
    Assert.assertTrue(projectionValue instanceof List);
    var list = (List<?>) projectionValue;
    Assert.assertEquals(1, list.size());
    var row = list.get(0);
    Assert.assertTrue(row instanceof Result);
    return ((Number) ((Result) row).getProperty("n")).longValue();
  }

  private static List<Long> letCountsOrdered(Object letA, Object letB) {
    return List.of(singleLetCount(letA), singleLetCount(letB));
  }

  private static long singleLetCount(Object letValue) {
    Assert.assertTrue(letValue instanceof List);
    var list = (List<?>) letValue;
    Assert.assertEquals(1, list.size());
    var row = list.get(0);
    Assert.assertTrue(row instanceof Result);
    return ((Number) ((Result) row).getProperty("n")).longValue();
  }

  /** Runs {@code sql} and returns the {@code letProperty} value from the sole parent row. */
  private Object runSingleParentRow(String sql, String letProperty) {
    try (var result = session.query(sql)) {
      Assert.assertTrue("query must return one parent row", result.hasNext());
      var value = result.next().getProperty(letProperty);
      Assert.assertFalse("query must return only one parent row", result.hasNext());
      return value;
    }
  }

  /**
   * Collects the {@code tag} projection of every row of a LET subquery list result. Used by the
   * multi-row fallback assertions, where the row set matters but the order does not.
   */
  private static Set<String> letTags(Object letValue) {
    Assert.assertNotNull("LET result must not be null when rows are expected", letValue);
    Assert.assertTrue(
        "LET result must be a List, got: " + letValue.getClass().getName(),
        letValue instanceof List);
    Set<String> tags = new HashSet<>();
    for (var row : (List<?>) letValue) {
      Assert.assertTrue(
          "LET subquery row must be a Result, got: "
              + (row == null ? "null" : row.getClass().getName()),
          row instanceof Result);
      tags.add(((Result) row).getProperty("tag"));
    }
    return tags;
  }

  /**
   * Collects {@code tag} values from a LET subquery list in fetch order. Used when row order is
   * part of the parity contract (identifier-ascending set fetch before LIMIT or SKIP).
   */
  private static List<String> letTagsOrdered(Object letValue) {
    Assert.assertNotNull("LET result must not be null when rows are expected", letValue);
    Assert.assertTrue(
        "LET result must be a List, got: " + letValue.getClass().getName(),
        letValue instanceof List);
    List<String> tags = new ArrayList<>();
    for (var row : (List<?>) letValue) {
      Assert.assertTrue(
          "LET subquery row must be a Result, got: "
              + (row == null ? "null" : row.getClass().getName()),
          row instanceof Result);
      tags.add(((Result) row).getProperty("tag"));
    }
    return tags;
  }

  /**
   * Extracts a single projected property from a one-row LET subquery list result. Fails loudly if
   * the LET is null, empty, multi-row, or not a {@link Result} — success-path tests must pin the
   * distinguishing value, not merely {@code size == 1}.
   */
  private static Object singleLetProperty(Object letValue, String property) {
    Assert.assertNotNull("LET result must not be null when a row is expected", letValue);
    Assert.assertTrue(
        "LET result must be a List, got: " + letValue.getClass().getName(),
        letValue instanceof List);
    var list = (List<?>) letValue;
    Assert.assertEquals("LET subquery must return exactly one row", 1, list.size());
    var row = list.get(0);
    Assert.assertTrue(
        "LET subquery row must be a Result, got: "
            + (row == null ? "null" : row.getClass().getName()),
        row instanceof Result);
    return ((Result) row).getProperty(property);
  }

  /** Asserts a LET subquery produced no rows ({@code null} or an empty list). */
  private static void assertLetEmpty(String message, Object letValue) {
    Assert.assertTrue(
        message + " (got: " + letValue + ")",
        letValue == null || (letValue instanceof List<?> list && list.isEmpty()));
  }

  /** Counts non-overlapping occurrences of {@code needle} in {@code haystack}. */
  private static int countOccurrences(String haystack, String needle) {
    var count = 0;
    var from = 0;
    while (true) {
      var idx = haystack.indexOf(needle, from);
      if (idx < 0) {
        break;
      }
      count++;
      from = idx + needle.length();
    }
    return count;
  }
}
