/*
 *
 *  * Copyright YouTrackDB
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */
package com.jetbrains.youtrackdb.internal.core.sql.executor;

import java.util.HashMap;
import java.util.Map;
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

    try (var result = session.query(sql)) {
      var count = 0;
      while (result.hasNext()) {
        result.next();
        count++;
      }
      Assert.assertEquals("both listed RIDs must be fetched", 2, count);
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

  /** Runs EXPLAIN and returns the pretty-printed plan string. */
  private String explainPlan(String sql) {
    try (var result = session.query("explain " + sql)) {
      Assert.assertTrue("EXPLAIN must produce a row", result.hasNext());
      String planAsString = result.next().getProperty("executionPlanAsString");
      Assert.assertNotNull("EXPLAIN must expose executionPlanAsString", planAsString);
      return planAsString;
    }
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
