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

import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
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
    try (var result = session.query(sql)) {
      Assert.assertFalse(
          "a malformed RID string must yield an empty result rather than throwing",
          result.hasNext());
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

    var plan = explainPlan("select from " + className + " where " + rid + " = @rid");
    Assert.assertTrue("reversed <literal> = @rid must still use the RID fetch, plan was: " + plan,
        plan.contains("FETCH FROM RIDs"));
    Assert.assertFalse(plan.contains("FETCH FROM CLASS"));
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
