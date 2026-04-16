/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.sql.operator;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.query.QueryRuntimeValueMulti;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemField;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemFieldAll;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemFieldAny;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemParameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/**
 * Coverage gap-filling tests for SQL operators. Targets uncovered RID range methods,
 * QueryRuntimeValueMulti paths in evaluateRecord, and additional In/Between/Not
 * branches. These complement the existing operator test files which focus on
 * evaluateExpression paths.
 */
public class OperatorCoverageGapTest extends DbTestBase {

  // =====================================================================
  // Helper: create SQLFilterItemField with @rid root (session-independent)
  // =====================================================================
  private static SQLFilterItemField ridField() {
    return new SQLFilterItemField(null, "@rid", null);
  }

  private static SQLFilterItemField nonRidField() {
    return new SQLFilterItemField(null, "name", null);
  }

  private static SQLFilterItemParameter ridParam(RID rid) {
    var param = new SQLFilterItemParameter("p");
    param.setValue(rid);
    return param;
  }

  /**
   * Build a SQLFilterCondition whose getBeginRidRange returns the given RID
   * (uses >= operator: @rid >= rid → begin = rid).
   */
  private static SQLFilterCondition condWithBeginRange(RID rid) {
    return new SQLFilterCondition(ridField(), new QueryOperatorMajorEquals(), rid);
  }

  /**
   * Build a SQLFilterCondition whose getEndRidRange returns the given RID
   * (uses < operator: @rid < rid → end = rid).
   */
  private static SQLFilterCondition condWithEndRange(RID rid) {
    return new SQLFilterCondition(ridField(), new QueryOperatorMinor(), rid);
  }

  /**
   * Build a SQLFilterCondition whose getBeginRidRange AND getEndRidRange
   * both return non-null. Uses AND with two sub-conditions.
   */
  private static SQLFilterCondition condWithBothRanges(RID begin, RID end) {
    var left = condWithBeginRange(begin);
    var right = condWithEndRange(end);
    return new SQLFilterCondition(left, new QueryOperatorAnd(), right);
  }

  private BasicCommandContext ctx() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession((DatabaseSessionEmbedded) session);
    return ctx;
  }

  // =====================================================================
  // Major (>) — RID range: begin = next(right), end = null
  // =====================================================================

  /** > with @rid field and direct RID → begin = next RID */
  @Test
  public void testMajorBeginRidRangeWithDirectRid() {
    var op = new QueryOperatorMajor();
    var rid = new RecordId(5, 10);
    RID result = op.getBeginRidRange(null, ridField(), rid);
    // Major returns next RID position
    Assert.assertNotNull(result);
    Assert.assertEquals(5, result.getCollectionId());
    Assert.assertEquals(11, result.getCollectionPosition());
  }

  /** > with @rid field and parameterized RID → begin = next RID */
  @Test
  public void testMajorBeginRidRangeWithParameter() {
    var op = new QueryOperatorMajor();
    var rid = new RecordId(3, 20);
    RID result = op.getBeginRidRange(null, ridField(), ridParam(rid));
    Assert.assertNotNull(result);
    Assert.assertEquals(3, result.getCollectionId());
    Assert.assertEquals(21, result.getCollectionPosition());
  }

  /** > with non-@rid field → null */
  @Test
  public void testMajorBeginRidRangeWithNonRidField() {
    var op = new QueryOperatorMajor();
    Assert.assertNull(op.getBeginRidRange(null, nonRidField(), new RecordId(5, 10)));
  }

  /** > end range is always null */
  @Test
  public void testMajorEndRidRangeAlwaysNull() {
    var op = new QueryOperatorMajor();
    Assert.assertNull(op.getEndRidRange(null, ridField(), new RecordId(5, 10)));
  }

  /** > with @rid field and non-RID, non-parameter right → null */
  @Test
  public void testMajorBeginRidRangeWithStringRight() {
    var op = new QueryOperatorMajor();
    Assert.assertNull(op.getBeginRidRange(null, ridField(), "not-a-rid"));
  }

  // =====================================================================
  // MajorEquals (>=) — RID range: begin = right, end = null
  // =====================================================================

  /** >= with @rid field and direct RID → begin = same RID */
  @Test
  public void testMajorEqualsBeginRidRangeWithDirectRid() {
    var op = new QueryOperatorMajorEquals();
    var rid = new RecordId(5, 10);
    Assert.assertEquals(rid, op.getBeginRidRange(null, ridField(), rid));
  }

  /** >= with @rid field and parameterized RID → begin = same RID */
  @Test
  public void testMajorEqualsBeginRidRangeWithParameter() {
    var op = new QueryOperatorMajorEquals();
    var rid = new RecordId(3, 20);
    Assert.assertEquals(rid, op.getBeginRidRange(null, ridField(), ridParam(rid)));
  }

  /** >= with non-@rid field → null */
  @Test
  public void testMajorEqualsBeginRidRangeWithNonRidField() {
    var op = new QueryOperatorMajorEquals();
    Assert.assertNull(op.getBeginRidRange(null, nonRidField(), new RecordId(5, 10)));
  }

  /** >= end range is always null */
  @Test
  public void testMajorEqualsEndRidRangeAlwaysNull() {
    var op = new QueryOperatorMajorEquals();
    Assert.assertNull(op.getEndRidRange(null, ridField(), new RecordId(5, 10)));
  }

  /** >= with @rid field and non-RID right → null */
  @Test
  public void testMajorEqualsBeginRidRangeWithStringRight() {
    var op = new QueryOperatorMajorEquals();
    Assert.assertNull(op.getBeginRidRange(null, ridField(), "not-a-rid"));
  }

  // =====================================================================
  // Minor (<) — RID range: begin = null, end = right
  // =====================================================================

  /** < with @rid field and direct RID → end = same RID */
  @Test
  public void testMinorEndRidRangeWithDirectRid() {
    var op = new QueryOperatorMinor();
    var rid = new RecordId(5, 10);
    Assert.assertEquals(rid, op.getEndRidRange(null, ridField(), rid));
  }

  /** < with @rid field and parameterized RID → end = same RID */
  @Test
  public void testMinorEndRidRangeWithParameter() {
    var op = new QueryOperatorMinor();
    var rid = new RecordId(3, 20);
    Assert.assertEquals(rid, op.getEndRidRange(null, ridField(), ridParam(rid)));
  }

  /** < with non-@rid field → null */
  @Test
  public void testMinorEndRidRangeWithNonRidField() {
    var op = new QueryOperatorMinor();
    Assert.assertNull(op.getEndRidRange(null, nonRidField(), new RecordId(5, 10)));
  }

  /** < begin range is always null */
  @Test
  public void testMinorBeginRidRangeAlwaysNull() {
    var op = new QueryOperatorMinor();
    Assert.assertNull(op.getBeginRidRange(null, ridField(), new RecordId(5, 10)));
  }

  /** < with @rid field and non-RID right → null */
  @Test
  public void testMinorEndRidRangeWithStringRight() {
    var op = new QueryOperatorMinor();
    Assert.assertNull(op.getEndRidRange(null, ridField(), "not-a-rid"));
  }

  // =====================================================================
  // MinorEquals (<=) — RID range: begin = null, end = right
  // =====================================================================

  /** <= with @rid field and direct RID → end = same RID */
  @Test
  public void testMinorEqualsEndRidRangeWithDirectRid() {
    var op = new QueryOperatorMinorEquals();
    var rid = new RecordId(5, 10);
    Assert.assertEquals(rid, op.getEndRidRange(null, ridField(), rid));
  }

  /** <= with @rid field and parameterized RID → end = same RID */
  @Test
  public void testMinorEqualsEndRidRangeWithParameter() {
    var op = new QueryOperatorMinorEquals();
    var rid = new RecordId(3, 20);
    Assert.assertEquals(rid, op.getEndRidRange(null, ridField(), ridParam(rid)));
  }

  /** <= with non-@rid field → null */
  @Test
  public void testMinorEqualsEndRidRangeWithNonRidField() {
    var op = new QueryOperatorMinorEquals();
    Assert.assertNull(op.getEndRidRange(null, nonRidField(), new RecordId(5, 10)));
  }

  /** <= begin range is always null */
  @Test
  public void testMinorEqualsBeginRidRangeAlwaysNull() {
    var op = new QueryOperatorMinorEquals();
    Assert.assertNull(op.getBeginRidRange(null, ridField(), new RecordId(5, 10)));
  }

  /** <= with @rid field and non-RID right → null */
  @Test
  public void testMinorEqualsEndRidRangeWithStringRight() {
    var op = new QueryOperatorMinorEquals();
    Assert.assertNull(op.getEndRidRange(null, ridField(), "not-a-rid"));
  }

  // =====================================================================
  // AND — RID range: begin = MAX(left, right), end = MIN(left, right)
  // =====================================================================

  /** AND with two conditions both returning begin ranges → MAX */
  @Test
  public void testAndBeginRidRangeBothPresent() {
    var and = new QueryOperatorAnd();
    var small = new RecordId(1, 5);
    var large = new RecordId(1, 20);
    // AND begin range = MAX of the two
    var result = and.getBeginRidRange(null, condWithBeginRange(small),
        condWithBeginRange(large));
    Assert.assertEquals(large, result);
  }

  /** AND with only left returning begin range → returns left */
  @Test
  public void testAndBeginRidRangeOnlyLeft() {
    var and = new QueryOperatorAnd();
    var rid = new RecordId(1, 10);
    // Right condition has no begin range (Minor has no begin range)
    var result = and.getBeginRidRange(null, condWithBeginRange(rid),
        condWithEndRange(new RecordId(1, 30)));
    Assert.assertEquals(rid, result);
  }

  /** AND with only right returning begin range → returns right */
  @Test
  public void testAndBeginRidRangeOnlyRight() {
    var and = new QueryOperatorAnd();
    var rid = new RecordId(1, 10);
    var result = and.getBeginRidRange(null, condWithEndRange(new RecordId(1, 30)),
        condWithBeginRange(rid));
    Assert.assertEquals(rid, result);
  }

  /** AND with two conditions both returning end ranges → MIN */
  @Test
  public void testAndEndRidRangeBothPresent() {
    var and = new QueryOperatorAnd();
    var small = new RecordId(1, 5);
    var large = new RecordId(1, 20);
    // AND end range = MIN of the two
    var result = and.getEndRidRange(null, condWithEndRange(small),
        condWithEndRange(large));
    Assert.assertEquals(small, result);
  }

  /** AND with only left returning end range → returns left */
  @Test
  public void testAndEndRidRangeOnlyLeft() {
    var and = new QueryOperatorAnd();
    var rid = new RecordId(1, 10);
    var result = and.getEndRidRange(null, condWithEndRange(rid),
        condWithBeginRange(new RecordId(1, 30)));
    Assert.assertEquals(rid, result);
  }

  /** AND with only right returning end range → returns right */
  @Test
  public void testAndEndRidRangeOnlyRight() {
    var and = new QueryOperatorAnd();
    var rid = new RecordId(1, 10);
    var result = and.getEndRidRange(null, condWithBeginRange(new RecordId(1, 30)),
        condWithEndRange(rid));
    Assert.assertEquals(rid, result);
  }

  /** AND getIndexReuseType with both non-null → INDEX_INTERSECTION */
  @Test
  public void testAndIndexReuseTypeBothPresent() {
    var and = new QueryOperatorAnd();
    Assert.assertEquals(IndexReuseType.INDEX_INTERSECTION,
        and.getIndexReuseType("left", "right"));
  }

  /** AND getIndexReuseType with null left → NO_INDEX */
  @Test
  public void testAndIndexReuseTypeNullLeft() {
    var and = new QueryOperatorAnd();
    Assert.assertEquals(IndexReuseType.NO_INDEX, and.getIndexReuseType(null, "right"));
  }

  // =====================================================================
  // OR — RID range: begin = MIN(left, right), end = MAX(left, right)
  // =====================================================================

  /** OR with two conditions both returning begin ranges → MIN */
  @Test
  public void testOrBeginRidRangeBothPresent() {
    var or = new QueryOperatorOr();
    var small = new RecordId(1, 5);
    var large = new RecordId(1, 20);
    // OR begin range = MIN of the two
    var result = or.getBeginRidRange(null, condWithBeginRange(small),
        condWithBeginRange(large));
    Assert.assertEquals(small, result);
  }

  /** OR with only left returning begin range → null (OR requires both) */
  @Test
  public void testOrBeginRidRangeOnlyLeft() {
    var or = new QueryOperatorOr();
    var rid = new RecordId(1, 10);
    // OR returns null if either is null
    var result = or.getBeginRidRange(null, condWithBeginRange(rid),
        condWithEndRange(new RecordId(1, 30)));
    Assert.assertNull(result);
  }

  /** OR with two conditions both returning end ranges → MAX */
  @Test
  public void testOrEndRidRangeBothPresent() {
    var or = new QueryOperatorOr();
    var small = new RecordId(1, 5);
    var large = new RecordId(1, 20);
    // OR end range = MAX of the two
    var result = or.getEndRidRange(null, condWithEndRange(small),
        condWithEndRange(large));
    Assert.assertEquals(large, result);
  }

  /** OR with only left returning end range → null (OR requires both) */
  @Test
  public void testOrEndRidRangeOnlyLeft() {
    var or = new QueryOperatorOr();
    var result = or.getEndRidRange(null, condWithEndRange(new RecordId(1, 10)),
        condWithBeginRange(new RecordId(1, 30)));
    Assert.assertNull(result);
  }

  /** OR getIndexReuseType with both non-null → INDEX_UNION */
  @Test
  public void testOrIndexReuseTypeBothPresent() {
    var or = new QueryOperatorOr();
    Assert.assertEquals(IndexReuseType.INDEX_UNION,
        or.getIndexReuseType("left", "right"));
  }

  /** OR getIndexReuseType with null → NO_INDEX */
  @Test
  public void testOrIndexReuseTypeNullRight() {
    var or = new QueryOperatorOr();
    Assert.assertEquals(IndexReuseType.NO_INDEX, or.getIndexReuseType("left", null));
  }

  // =====================================================================
  // NOT — RID range: inverts begin/end
  // =====================================================================

  /** NOT with condition that has only end range → begin = end */
  @Test
  public void testNotBeginRidRangeFromEndRange() {
    var not = new QueryOperatorNot();
    var rid = new RecordId(1, 10);
    // Condition has end range only: NOT swaps end → begin
    var result = not.getBeginRidRange(null, condWithEndRange(rid), null);
    Assert.assertEquals(rid, result);
  }

  /** NOT with condition that has both ranges → begin = null */
  @Test
  public void testNotBeginRidRangeWithBothRanges() {
    var not = new QueryOperatorNot();
    var result = not.getBeginRidRange(null,
        condWithBothRanges(new RecordId(1, 5), new RecordId(1, 20)), null);
    Assert.assertNull(result);
  }

  /** NOT with condition that has only begin range → begin = null */
  @Test
  public void testNotBeginRidRangeWithOnlyBeginRange() {
    var not = new QueryOperatorNot();
    var result = not.getBeginRidRange(null, condWithBeginRange(new RecordId(1, 10)),
        null);
    Assert.assertNull(result);
  }

  /** NOT with condition that has no ranges → null */
  @Test
  public void testNotBeginRidRangeWithNoRanges() {
    var not = new QueryOperatorNot();
    // Condition using Equals has no RID ranges
    var cond = new SQLFilterCondition(nonRidField(), new QueryOperatorEquals(), 42);
    Assert.assertNull(not.getBeginRidRange(null, cond, null));
  }

  /** NOT with condition that has only begin range → end = begin */
  @Test
  public void testNotEndRidRangeFromBeginRange() {
    var not = new QueryOperatorNot();
    var rid = new RecordId(1, 10);
    // Condition has begin range only: NOT swaps begin → end
    var result = not.getEndRidRange(null, condWithBeginRange(rid), null);
    Assert.assertEquals(rid, result);
  }

  /** NOT with condition that has both ranges → end = null */
  @Test
  public void testNotEndRidRangeWithBothRanges() {
    var not = new QueryOperatorNot();
    var result = not.getEndRidRange(null,
        condWithBothRanges(new RecordId(1, 5), new RecordId(1, 20)), null);
    Assert.assertNull(result);
  }

  /** NOT with condition that has only end range → end = null */
  @Test
  public void testNotEndRidRangeWithOnlyEndRange() {
    var not = new QueryOperatorNot();
    var result = not.getEndRidRange(null, condWithEndRange(new RecordId(1, 10)), null);
    Assert.assertNull(result);
  }

  // =====================================================================
  // IN — RID range: min/max of RID collection
  // =====================================================================

  /** IN with @rid on right and RID collection on left → begin = min, end = max */
  @Test
  public void testInBeginRidRangeWithRidOnRight() {
    var op = new QueryOperatorIn();
    var rid1 = new RecordId(1, 5);
    var rid2 = new RecordId(1, 20);
    var rid3 = new RecordId(1, 10);
    var rids = Arrays.asList(rid1, rid2, rid3);
    RID begin = op.getBeginRidRange(null, rids, ridField());
    Assert.assertEquals(rid1, begin); // min
  }

  @Test
  public void testInEndRidRangeWithRidOnRight() {
    var op = new QueryOperatorIn();
    var rid1 = new RecordId(1, 5);
    var rid2 = new RecordId(1, 20);
    var rid3 = new RecordId(1, 10);
    var rids = Arrays.asList(rid1, rid2, rid3);
    RID end = op.getEndRidRange(null, rids, ridField());
    Assert.assertEquals(rid2, end); // max
  }

  /** IN with @rid on left and RID collection on right → same logic */
  @Test
  public void testInBeginRidRangeWithRidOnLeft() {
    var op = new QueryOperatorIn();
    var rid1 = new RecordId(2, 3);
    var rid2 = new RecordId(2, 7);
    var rids = Arrays.asList(rid1, rid2);
    RID begin = op.getBeginRidRange(null, ridField(), rids);
    Assert.assertEquals(rid1, begin);
  }

  @Test
  public void testInEndRidRangeWithRidOnLeft() {
    var op = new QueryOperatorIn();
    var rid1 = new RecordId(2, 3);
    var rid2 = new RecordId(2, 7);
    var rids = Arrays.asList(rid1, rid2);
    RID end = op.getEndRidRange(null, ridField(), rids);
    Assert.assertEquals(rid2, end);
  }

  /** IN with SQLFilterItemParameter in the RID collection → parameter resolved */
  @Test
  public void testInRidRangeWithParameterInCollection() {
    var op = new QueryOperatorIn();
    var rid = new RecordId(1, 15);
    var param = ridParam(rid);
    var rids = Collections.singletonList(param);
    RID begin = op.getBeginRidRange(null, rids, ridField());
    Assert.assertEquals(rid, begin);
  }

  /** IN with non-@rid fields → null */
  @Test
  public void testInRidRangeWithNonRidFields() {
    var op = new QueryOperatorIn();
    Assert.assertNull(op.getBeginRidRange(null, nonRidField(), Arrays.asList(1, 2)));
    Assert.assertNull(op.getEndRidRange(null, nonRidField(), Arrays.asList(1, 2)));
  }

  /** IN with empty RID collection → null (no persistent RIDs found) */
  @Test
  public void testInRidRangeWithEmptyCollection() {
    var op = new QueryOperatorIn();
    // Non-persistent RIDs have position < 0
    var rids = Collections.singletonList("not-a-rid");
    Assert.assertNull(op.getBeginRidRange(null, rids, ridField()));
  }

  /** IN with left as SQLFilterItem → getValue called to resolve */
  @Test
  public void testInBeginRidRangeWithSQLFilterItemLeft() {
    var op = new QueryOperatorIn();
    var rid = new RecordId(1, 42);
    var param = ridParam(rid);
    // Left is a filter item that resolves to a list of RIDs
    RID begin = op.getBeginRidRange(null, param, ridField());
    // param.getValue returns the single RID, which is not iterable
    // so MultiValue.getMultiValueIterable handles it
    Assert.assertNotNull(begin);
  }

  // =====================================================================
  // BETWEEN — RID range: begin = first, end = last
  // =====================================================================

  /** BETWEEN @rid [#1:5, AND, #1:20] → begin=#1:5, end=#1:20 */
  @Test
  public void testBetweenBeginRidRange() {
    var op = new QueryOperatorBetween();
    var rid1 = new RecordId(1, 5);
    var rid2 = new RecordId(1, 20);
    var range = Arrays.asList(rid1, "AND", rid2);
    Assert.assertEquals(rid1, op.getBeginRidRange(null, ridField(), range));
  }

  @Test
  public void testBetweenEndRidRange() {
    var op = new QueryOperatorBetween();
    var rid1 = new RecordId(1, 5);
    var rid2 = new RecordId(1, 20);
    var range = Arrays.asList(rid1, "AND", rid2);
    Assert.assertEquals(rid2, op.getEndRidRange(null, ridField(), range));
  }

  /** BETWEEN with null first element → begin = last element */
  @Test
  public void testBetweenBeginRidRangeNullFirst() {
    var op = new QueryOperatorBetween();
    var rid2 = new RecordId(1, 20);
    var range = Arrays.asList(null, "AND", rid2);
    Assert.assertEquals(rid2, op.getBeginRidRange(null, ridField(), range));
  }

  /** BETWEEN with null last element → end = first element */
  @Test
  public void testBetweenEndRidRangeNullLast() {
    var op = new QueryOperatorBetween();
    var rid1 = new RecordId(1, 5);
    var range = Arrays.asList(rid1, "AND", null);
    Assert.assertEquals(rid1, op.getEndRidRange(null, ridField(), range));
  }

  /** BETWEEN with non-@rid field → null */
  @Test
  public void testBetweenRidRangeNonRidField() {
    var op = new QueryOperatorBetween();
    var range = Arrays.asList(new RecordId(1, 5), "AND", new RecordId(1, 20));
    Assert.assertNull(op.getBeginRidRange(null, nonRidField(), range));
    Assert.assertNull(op.getEndRidRange(null, nonRidField(), range));
  }

  // =====================================================================
  // QueryOperatorEquality.evaluateRecord with QueryRuntimeValueMulti
  // =====================================================================

  /**
   * Create a QueryRuntimeValueMulti with ALL semantics.
   * Uses a mock SQLFilterItemFieldAll whose getRoot returns "ALL()".
   */
  private QueryRuntimeValueMulti allMulti(Object... values) {
    // SQLFilterItemFieldAll requires a SQLPredicate and session, but getRoot()
    // just returns FULL_NAME constant. We use a thin subclass.
    var def = new SQLFilterItemFieldAll(null, null, "ALL(name)", null);
    var collates =
        new ArrayList<com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate>();
    for (int i = 0; i < values.length; i++) {
      collates.add(null);
    }
    return new QueryRuntimeValueMulti(def, values, collates);
  }

  /**
   * Create a QueryRuntimeValueMulti with ANY semantics.
   */
  private QueryRuntimeValueMulti anyMulti(Object... values) {
    var def = new SQLFilterItemFieldAny(null, null, "ANY(name)", null);
    var collates =
        new ArrayList<com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate>();
    for (int i = 0; i < values.length; i++) {
      collates.add(null);
    }
    return new QueryRuntimeValueMulti(def, values, collates);
  }

  /** Equality with LEFT ALL — all values match → true */
  @Test
  public void testEqualityLeftAllAllMatch() {
    var op = new QueryOperatorEquals();
    var multi = allMulti(10, 10, 10);
    var result = op.evaluateRecord(null, null, null, multi, 10, ctx(), null);
    Assert.assertEquals(true, result);
  }

  /** Equality with LEFT ALL — one value doesn't match → false */
  @Test
  public void testEqualityLeftAllOneMismatch() {
    var op = new QueryOperatorEquals();
    var multi = allMulti(10, 20, 10);
    var result = op.evaluateRecord(null, null, null, multi, 10, ctx(), null);
    Assert.assertEquals(false, result);
  }

  /** Equality with LEFT ALL — null value in array → false (null fails match) */
  @Test
  public void testEqualityLeftAllWithNullValue() {
    var op = new QueryOperatorEquals();
    var multi = allMulti(10, null, 10);
    var result = op.evaluateRecord(null, null, null, multi, 10, ctx(), null);
    Assert.assertEquals(false, result);
  }

  /** Equality with LEFT ALL — empty values → false */
  @Test
  public void testEqualityLeftAllEmpty() {
    var op = new QueryOperatorEquals();
    var multi = allMulti();
    var result = op.evaluateRecord(null, null, null, multi, 10, ctx(), null);
    Assert.assertEquals(false, result);
  }

  /** Equality with LEFT ANY — one value matches → true */
  @Test
  public void testEqualityLeftAnyOneMatch() {
    var op = new QueryOperatorEquals();
    var multi = anyMulti(5, 10, 15);
    var result = op.evaluateRecord(null, null, null, multi, 10, ctx(), null);
    Assert.assertEquals(true, result);
  }

  /** Equality with LEFT ANY — no values match → false */
  @Test
  public void testEqualityLeftAnyNoMatch() {
    var op = new QueryOperatorEquals();
    var multi = anyMulti(5, 15, 25);
    var result = op.evaluateRecord(null, null, null, multi, 10, ctx(), null);
    Assert.assertEquals(false, result);
  }

  /** Equality with LEFT ANY — null value skipped (doesn't NPE) → false if no match */
  @Test
  public void testEqualityLeftAnyWithNullValue() {
    var op = new QueryOperatorEquals();
    var multi = anyMulti(null, null, null);
    var result = op.evaluateRecord(null, null, null, multi, 10, ctx(), null);
    Assert.assertEquals(false, result);
  }

  /** Equality with LEFT ANY — empty values → false */
  @Test
  public void testEqualityLeftAnyEmpty() {
    var op = new QueryOperatorEquals();
    var multi = anyMulti();
    var result = op.evaluateRecord(null, null, null, multi, 10, ctx(), null);
    Assert.assertEquals(false, result);
  }

  /** Equality with RIGHT ALL — all values match → true */
  @Test
  public void testEqualityRightAllAllMatch() {
    var op = new QueryOperatorEquals();
    var multi = allMulti(10, 10, 10);
    var result = op.evaluateRecord(null, null, null, 10, multi, ctx(), null);
    Assert.assertEquals(true, result);
  }

  /** Equality with RIGHT ALL — one value doesn't match → false */
  @Test
  public void testEqualityRightAllOneMismatch() {
    var op = new QueryOperatorEquals();
    var multi = allMulti(10, 20, 10);
    var result = op.evaluateRecord(null, null, null, 10, multi, ctx(), null);
    Assert.assertEquals(false, result);
  }

  /** Equality with RIGHT ALL — null value → false */
  @Test
  public void testEqualityRightAllWithNullValue() {
    var op = new QueryOperatorEquals();
    var multi = allMulti(10, null, 10);
    var result = op.evaluateRecord(null, null, null, 10, multi, ctx(), null);
    Assert.assertEquals(false, result);
  }

  /** Equality with RIGHT ALL — empty values → false */
  @Test
  public void testEqualityRightAllEmpty() {
    var op = new QueryOperatorEquals();
    var multi = allMulti();
    var result = op.evaluateRecord(null, null, null, 10, multi, ctx(), null);
    Assert.assertEquals(false, result);
  }

  /** Equality with RIGHT ANY — one value matches → true */
  @Test
  public void testEqualityRightAnyOneMatch() {
    var op = new QueryOperatorEquals();
    var multi = anyMulti(5, 10, 15);
    var result = op.evaluateRecord(null, null, null, 10, multi, ctx(), null);
    Assert.assertEquals(true, result);
  }

  /** Equality with RIGHT ANY — no values match → false */
  @Test
  public void testEqualityRightAnyNoMatch() {
    var op = new QueryOperatorEquals();
    var multi = anyMulti(5, 15, 25);
    var result = op.evaluateRecord(null, null, null, 10, multi, ctx(), null);
    Assert.assertEquals(false, result);
  }

  /** Equality with RIGHT ANY — null value skipped → false if no match */
  @Test
  public void testEqualityRightAnyWithNullValue() {
    var op = new QueryOperatorEquals();
    var multi = anyMulti(null, null, null);
    var result = op.evaluateRecord(null, null, null, 10, multi, ctx(), null);
    Assert.assertEquals(false, result);
  }

  /** Equality with RIGHT ANY — empty values → false */
  @Test
  public void testEqualityRightAnyEmpty() {
    var op = new QueryOperatorEquals();
    var multi = anyMulti();
    var result = op.evaluateRecord(null, null, null, 10, multi, ctx(), null);
    Assert.assertEquals(false, result);
  }

  // =====================================================================
  // QueryOperatorIn.evaluateExpression — additional paths
  // =====================================================================

  /** IN: left is multi-value (List), right is single item → iterate left */
  @Test
  public void testInLeftMultiRightSingle() {
    var op = new QueryOperatorIn();
    var cond = new SQLFilterCondition("stub", op, 10);
    var left = Arrays.asList(5, 10, 15);
    var result = (boolean) op.evaluateRecord(null, null, cond, left, 10, ctx(), null);
    Assert.assertTrue(result);
  }

  /** IN: left is multi-value (List), right is single item — no match → false */
  @Test
  public void testInLeftMultiRightSingleNoMatch() {
    var op = new QueryOperatorIn();
    var cond = new SQLFilterCondition("stub", op, 99);
    var left = Arrays.asList(5, 10, 15);
    var result = (boolean) op.evaluateRecord(null, null, cond, left, 99, ctx(), null);
    Assert.assertFalse(result);
  }

  /** IN: left is Set, right is single item → Set.contains */
  @Test
  public void testInLeftSetRightSingle() {
    var op = new QueryOperatorIn();
    var cond = new SQLFilterCondition("stub", op, 10);
    Set<Object> left = new HashSet<>(Arrays.asList(5, 10, 15));
    var result = (boolean) op.evaluateRecord(null, null, cond, left, 10, ctx(), null);
    Assert.assertTrue(result);
  }

  /** IN: left is array, right is single item → iterate array */
  @Test
  public void testInLeftArrayRightSingle() {
    var op = new QueryOperatorIn();
    var cond = new SQLFilterCondition("stub", op, 10);
    var left = new Object[] {5, 10, 15};
    var result = (boolean) op.evaluateRecord(null, null, cond, left, 10, ctx(), null);
    Assert.assertTrue(result);
  }

  /** IN: right is array → iterate array */
  @Test
  public void testInRightArray() {
    var op = new QueryOperatorIn();
    var cond = new SQLFilterCondition("stub", op, null);
    var right = new Object[] {5, 10, 15};
    var result = (boolean) op.evaluateRecord(null, null, cond, 10, right, ctx(), null);
    Assert.assertTrue(result);
  }

  /** IN: right is array — no match → false, falls through to equals */
  @Test
  public void testInRightArrayNoMatch() {
    var op = new QueryOperatorIn();
    var cond = new SQLFilterCondition("stub", op, null);
    var right = new Object[] {5, 15, 25};
    var result = (boolean) op.evaluateRecord(null, null, cond, 10, right, ctx(), null);
    Assert.assertFalse(result);
  }

  /** IN: both scalars → falls through to iLeft.equals(iRight) */
  @Test
  public void testInBothScalarsEqual() {
    var op = new QueryOperatorIn();
    var cond = new SQLFilterCondition("stub", op, 10);
    var result = (boolean) op.evaluateRecord(null, null, cond, 10, 10, ctx(), null);
    Assert.assertTrue(result);
  }

  /** IN: both scalars not equal → false */
  @Test
  public void testInBothScalarsNotEqual() {
    var op = new QueryOperatorIn();
    var cond = new SQLFilterCondition("stub", op, 20);
    var result = (boolean) op.evaluateRecord(null, null, cond, 10, 20, ctx(), null);
    Assert.assertFalse(result);
  }

  /** IN: left is multi-value (List), right is collection → nested loop */
  @Test
  public void testInLeftMultiRightCollection() {
    var op = new QueryOperatorIn();
    var cond = new SQLFilterCondition("stub", op, null);
    List<Object> left = Arrays.asList(1, 2, 3);
    List<Object> right = Arrays.asList(3, 4, 5);
    var result = (boolean) op.evaluateRecord(null, null, cond, left, right, ctx(), null);
    Assert.assertTrue(result); // 3 is in both
  }

  /** IN: left is multi-value (List), right is collection — no overlap → false */
  @Test
  public void testInLeftMultiRightCollectionNoOverlap() {
    var op = new QueryOperatorIn();
    var cond = new SQLFilterCondition("stub", op, null);
    List<Object> left = Arrays.asList(1, 2);
    List<Object> right = Arrays.asList(3, 4, 5);
    var result = (boolean) op.evaluateRecord(null, null, cond, left, right, ctx(), null);
    Assert.assertFalse(result);
  }

  // =====================================================================
  // QueryOperatorNot.evaluateRecord — wrapping operator path
  // =====================================================================

  /** NOT wrapping Equals: negates the result */
  @Test
  public void testNotWrappingEquals() {
    var eq = new QueryOperatorEquals();
    var not = new QueryOperatorNot(eq);
    var cond = new SQLFilterCondition("stub", eq, 10);
    // Equals returns true for same values, NOT should negate to false
    var result = (boolean) not.evaluateRecord(null, null, cond, 10, 10, ctx(), null);
    Assert.assertFalse(result);
  }

  /** NOT standalone: negates iLeft boolean */
  @Test
  public void testNotStandaloneTrue() {
    var not = new QueryOperatorNot();
    var result = not.evaluateRecord(null, null, null, true, null, null, null);
    Assert.assertEquals(false, result);
  }

  /** NOT standalone: null left → false */
  @Test
  public void testNotStandaloneNullLeft() {
    var not = new QueryOperatorNot();
    var result = not.evaluateRecord(null, null, null, null, null, null, null);
    Assert.assertEquals(false, result);
  }

  // =====================================================================
  // isSupportingBinaryEvaluate — covering the method on comparison ops
  // =====================================================================

  @Test
  public void testMajorSupportsBinaryEvaluate() {
    Assert.assertTrue(new QueryOperatorMajor().isSupportingBinaryEvaluate());
  }

  @Test
  public void testMajorEqualsSupportsBinaryEvaluate() {
    Assert.assertTrue(new QueryOperatorMajorEquals().isSupportingBinaryEvaluate());
  }

  @Test
  public void testMinorSupportsBinaryEvaluate() {
    Assert.assertTrue(new QueryOperatorMinor().isSupportingBinaryEvaluate());
  }

  @Test
  public void testMinorEqualsSupportsBinaryEvaluate() {
    Assert.assertTrue(new QueryOperatorMinorEquals().isSupportingBinaryEvaluate());
  }
}
