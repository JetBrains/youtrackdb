/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.sql.operator;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.EntitySerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.RecordSerializerBinary;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterCondition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for entity/schema-dependent comparison operators that require an active database
 * transaction with persisted entities and schema access: Contains, ContainsAll, ContainsValue,
 * Instanceof, Traverse. Extends DbTestBase for access to the session needed by transaction loading,
 * schema access, and entity persistence.
 */
public class EntitySchemaOperatorsTest extends DbTestBase {

  private static final EntitySerializer SERIALIZER =
      RecordSerializerBinary.INSTANCE.getCurrentSerializer();

  private BasicCommandContext newCommandContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  /**
   * Evaluate operator with a dummy condition (no sub-condition extraction). The condition wraps
   * left="field" (String) and right=the value, ensuring no SQLFilterCondition sub-condition is
   * extracted because neither operand is a SQLFilterCondition instance.
   */
  private Object eval(QueryOperator op, Object left, Object right) {
    var ctx = newCommandContext();
    var cond = new SQLFilterCondition("field", op, right);
    return op.evaluateRecord(null, null, cond, left, right, ctx, SERIALIZER);
  }

  /**
   * Creates a sub-condition that always evaluates to true regardless of the record: compares the
   * literal string "X" with "X" using QueryOperatorEquals.
   */
  private SQLFilterCondition alwaysTrueCondition() {
    return new SQLFilterCondition("X", new QueryOperatorEquals(), "X");
  }

  /**
   * Creates a sub-condition that always evaluates to false regardless of the record: compares the
   * literal string "X" with "Y" using QueryOperatorEquals.
   */
  private SQLFilterCondition alwaysFalseCondition() {
    return new SQLFilterCondition("X", new QueryOperatorEquals(), "Y");
  }

  // ===== QueryOperatorContains — Simple Value Path =====

  @Test
  public void testContainsSimpleValueInList() {
    // Left is a list containing the value 2; right is 2 — should find a match
    var contains = new QueryOperatorContains();
    Assert.assertEquals(true, eval(contains, Arrays.asList(1, 2, 3), 2));
  }

  @Test
  public void testContainsSimpleValueNotInList() {
    // Left is a list not containing 99 — should return false
    var contains = new QueryOperatorContains();
    Assert.assertEquals(false, eval(contains, Arrays.asList(1, 2, 3), 99));
  }

  @Test
  public void testContainsEmptyList() {
    // Empty list never contains anything — should return false
    var contains = new QueryOperatorContains();
    Assert.assertEquals(false, eval(contains, Collections.emptyList(), 1));
  }

  @Test
  public void testContainsCrossTypeIntegerLong() {
    // Cross-type numeric containment: Integer 10 should match Long 10L
    // via QueryOperatorEquals.equals() type coercion
    var contains = new QueryOperatorContains();
    Assert.assertEquals(true, eval(contains, Arrays.asList(10L, 20L, 30L), 10));
  }

  @Test
  public void testContainsStringInList() {
    // String containment in a list of strings
    var contains = new QueryOperatorContains();
    Assert.assertEquals(true, eval(contains, Arrays.asList("a", "b", "c"), "b"));
  }

  @Test
  public void testContainsStringNotInList() {
    var contains = new QueryOperatorContains();
    Assert.assertEquals(false, eval(contains, Arrays.asList("a", "b", "c"), "z"));
  }

  @Test
  public void testContainsRightSideIterableValueFound() {
    // When right is an iterable and left is a single value, the operator iterates right
    var contains = new QueryOperatorContains();
    Assert.assertEquals(true, eval(contains, 2, Arrays.asList(1, 2, 3)));
  }

  @Test
  public void testContainsRightSideIterableValueNotFound() {
    var contains = new QueryOperatorContains();
    Assert.assertEquals(false, eval(contains, 99, Arrays.asList(1, 2, 3)));
  }

  @Test
  public void testContainsNeitherIterableReturnsFalse() {
    // When neither left nor right is Iterable, evaluateExpression returns false
    var contains = new QueryOperatorContains();
    Assert.assertEquals(false, eval(contains, 1, 1));
  }

  @Test
  public void testContainsNullLeftReturnsFalse() {
    // QueryOperatorEqualityNotNulls short-circuits: null left → false
    var contains = new QueryOperatorContains();
    Assert.assertEquals(false, eval(contains, null, Arrays.asList(1)));
  }

  @Test
  public void testContainsNullRightReturnsFalse() {
    // QueryOperatorEqualityNotNulls short-circuits: null right → false
    var contains = new QueryOperatorContains();
    Assert.assertEquals(false, eval(contains, Arrays.asList(1), null));
  }

  // ===== QueryOperatorContains — Condition Path =====

  @Test
  public void testContainsWithConditionMatchInLeftIterable() {
    // Left is a list of identifiable records, condition always true — match on first element
    session.getMetadata().getSchema().createClass("ContainsTest");
    session.begin();
    var e1 = session.newInstance("ContainsTest");
    e1.setProperty("name", "Alice");

    var e2 = session.newInstance("ContainsTest");
    e2.setProperty("name", "Bob");

    session.commit();

    var contains = new QueryOperatorContains();
    var ctx = newCommandContext();
    var subCond = alwaysTrueCondition();
    // Outer condition: right is the sub-condition
    var outerCond = new SQLFilterCondition("field", contains, subCond);

    session.begin();
    var ids = List.of(e1.getIdentity(), e2.getIdentity());
    Object result = contains.evaluateRecord(null, null, outerCond, ids, subCond, ctx, SERIALIZER);
    session.commit();
    Assert.assertEquals(true, result);
  }

  @Test
  public void testContainsWithConditionNoMatchInLeftIterable() {
    // Left is a list of identifiable records, condition always false — no match
    session.getMetadata().getSchema().createClass("ContainsNoMatch");
    session.begin();
    var e1 = session.newInstance("ContainsNoMatch");
    e1.setProperty("name", "Alice");

    session.commit();

    var contains = new QueryOperatorContains();
    var ctx = newCommandContext();
    var subCond = alwaysFalseCondition();
    var outerCond = new SQLFilterCondition("field", contains, subCond);

    session.begin();
    var ids = List.of(e1.getIdentity());
    Object result = contains.evaluateRecord(null, null, outerCond, ids, subCond, ctx, SERIALIZER);
    session.commit();
    Assert.assertEquals(false, result);
  }

  @Test
  public void testContainsWithConditionMatchInRightIterable() {
    // Right is a list of identifiable records, condition always true — match
    session.getMetadata().getSchema().createClass("ContainsRight");
    session.begin();
    var e1 = session.newInstance("ContainsRight");
    e1.setProperty("name", "Alice");

    session.commit();

    var contains = new QueryOperatorContains();
    var ctx = newCommandContext();
    var subCond = alwaysTrueCondition();
    // Outer condition: LEFT is the sub-condition
    var outerCond = new SQLFilterCondition(subCond, contains, "field");

    session.begin();
    var ids = List.of(e1.getIdentity());
    Object result = contains.evaluateRecord(null, null, outerCond, subCond, ids, ctx, SERIALIZER);
    session.commit();
    Assert.assertEquals(true, result);
  }

  @Test
  public void testContainsWithConditionSkipsNonIdentifiableElements() {
    // Non-Identifiable elements in the iterable (e.g., plain Integers) are skipped
    // by the "null, default -> continue" branch, so no match unless other elements match
    var contains = new QueryOperatorContains();
    var ctx = newCommandContext();
    var subCond = alwaysTrueCondition();
    var outerCond = new SQLFilterCondition("field", contains, subCond);

    // List of plain integers — all hit the "null, default -> continue" branch
    Object result =
        contains.evaluateRecord(
            null, null, outerCond, Arrays.asList(1, 2, 3), subCond, ctx, SERIALIZER);
    Assert.assertEquals(false, result);
  }

  @Test
  public void testContainsWithConditionMapElement() {
    // Map element in the left iterable — wraps in ResultInternal when value is not Identifiable
    var contains = new QueryOperatorContains();
    var ctx = newCommandContext();
    var subCond = alwaysTrueCondition();
    var outerCond = new SQLFilterCondition("field", contains, subCond);

    Map<String, Object> map = new HashMap<>();
    map.put("key", "value");
    // The sub-condition evaluates against a ResultInternal wrapping this map
    Object result =
        contains.evaluateRecord(
            null, null, outerCond, List.of(map), subCond, ctx, SERIALIZER);
    Assert.assertEquals(true, result);
  }

  // ===== QueryOperatorContains — Index/RID =====

  @Test
  public void testContainsIndexReuseNoCondition() {
    // When neither left nor right is a SQLFilterCondition, returns INDEX_METHOD
    var contains = new QueryOperatorContains();
    Assert.assertEquals(IndexReuseType.INDEX_METHOD,
        contains.getIndexReuseType("field", "value"));
  }

  @Test
  public void testContainsIndexReuseWithLeftCondition() {
    // When left is a SQLFilterCondition, returns NO_INDEX
    var contains = new QueryOperatorContains();
    var cond = alwaysTrueCondition();
    Assert.assertEquals(IndexReuseType.NO_INDEX,
        contains.getIndexReuseType(cond, "value"));
  }

  @Test
  public void testContainsIndexReuseWithRightCondition() {
    // When right is a SQLFilterCondition, returns NO_INDEX
    var contains = new QueryOperatorContains();
    var cond = alwaysTrueCondition();
    Assert.assertEquals(IndexReuseType.NO_INDEX,
        contains.getIndexReuseType("field", cond));
  }

  @Test
  public void testContainsRidRangesNull() {
    var contains = new QueryOperatorContains();
    Assert.assertNull(contains.getBeginRidRange(null, "left", "right"));
    Assert.assertNull(contains.getEndRidRange(null, "left", "right"));
  }

  // ===== QueryOperatorContainsAll =====

  @Test
  public void testContainsAllArrayVsArrayAllMatch() {
    // All elements in right array are in left array — returns true
    var op = new QueryOperatorContainsAll();
    Object[] left = {1, 2, 3, 4};
    Object[] right = {2, 3};
    Assert.assertEquals(true, eval(op, left, right));
  }

  @Test
  public void testContainsAllArrayVsArrayPartialMatch() {
    // Not all elements in right array are in left array — returns false
    var op = new QueryOperatorContainsAll();
    Object[] left = {1, 2, 3};
    Object[] right = {2, 99};
    Assert.assertEquals(false, eval(op, left, right));
  }

  @Test
  public void testContainsAllArrayVsEmptyArray() {
    // Right array is empty → matches == 0, right.length == 0 → 0 == 0 → true (vacuous truth)
    var op = new QueryOperatorContainsAll();
    Object[] left = {1, 2};
    Object[] right = {};
    Assert.assertEquals(true, eval(op, left, right));
  }

  @Test
  public void testContainsAllArrayVsCollectionAllMatch() {
    // Left array vs right Collection — all elements in right found in left
    var op = new QueryOperatorContainsAll();
    Object[] left = {1, 2, 3};
    List<Object> right = Arrays.asList(1, 3);
    Assert.assertEquals(true, eval(op, left, right));
  }

  @Test
  public void testContainsAllArrayVsCollectionPartialMatch() {
    // Not all elements match
    var op = new QueryOperatorContainsAll();
    Object[] left = {1, 2};
    List<Object> right = Arrays.asList(1, 99);
    Assert.assertEquals(false, eval(op, left, right));
  }

  @Test
  public void testContainsAllLeftCollectionAllEqualToValue() {
    // Left is collection, no condition, right is a value — all elements must equal right
    var op = new QueryOperatorContainsAll();
    Assert.assertEquals(true, eval(op, Arrays.asList(5, 5, 5), 5));
  }

  @Test
  public void testContainsAllLeftCollectionNotAllEqualToValue() {
    // One element differs — returns false
    var op = new QueryOperatorContainsAll();
    Assert.assertEquals(false, eval(op, Arrays.asList(5, 5, 6), 5));
  }

  @Test
  public void testContainsAllLeftEmptyCollectionVacuousTruth() {
    // Empty collection: the for loop never runs, falls through to return true (vacuous truth)
    var op = new QueryOperatorContainsAll();
    Assert.assertEquals(true, eval(op, Collections.emptyList(), "anything"));
  }

  @Test
  public void testContainsAllRightCollectionAllEqualToValue() {
    // Right is collection, no condition — all elements must equal left value
    var op = new QueryOperatorContainsAll();
    Assert.assertEquals(true, eval(op, 5, Arrays.asList(5, 5)));
  }

  @Test
  public void testContainsAllRightCollectionNotAllEqual() {
    var op = new QueryOperatorContainsAll();
    Assert.assertEquals(false, eval(op, 5, Arrays.asList(5, 6)));
  }

  @Test
  public void testContainsAllCrossTypeIntegerLong() {
    // Cross-type: Integer and Long should match via QueryOperatorEquals.equals()
    var op = new QueryOperatorContainsAll();
    Object[] left = {1L, 2L, 3L};
    Object[] right = {1, 2};
    Assert.assertEquals(true, eval(op, left, right));
  }

  @Test
  public void testContainsAllNullLeftReturnsFalse() {
    var op = new QueryOperatorContainsAll();
    Assert.assertEquals(false, eval(op, null, Arrays.asList(1)));
  }

  @Test
  public void testContainsAllNullRightReturnsFalse() {
    var op = new QueryOperatorContainsAll();
    Assert.assertEquals(false, eval(op, Arrays.asList(1), null));
  }

  @Test
  public void testContainsAllNonArrayNonCollection() {
    // Neither array nor collection: the code falls through to return true (vacuous truth)
    // because none of the if-else branches match.
    //
    // WHEN-FIXED: when QueryOperatorContainsAll adds a default-false fallthrough (or throws
    // an illegal-argument), update this assertion and delete this WHEN-FIXED block.
    var op = new QueryOperatorContainsAll();
    Assert.assertEquals("Documents vacuous-truth fallthrough — revisit after fix",
        true, eval(op, "string", "string"));
  }

  @Test
  public void testContainsAllArrayVsArrayLeftDuplicatesOverCounts() {
    // Pre-existing counting bug: the outer loop iterates LEFT, counting matches per left element
    // against RIGHT. When LEFT has duplicates matching the same right element, matches over-counts.
    // {2, 2, 3} CONTAINSALL {2, 3} should semantically be true (left contains both 2 and 3),
    // but the algorithm counts matches=3 (two 2's + one 3) vs right.length=2, returning false.
    //
    // WHEN-FIXED: the correct algorithm iterates RIGHT (counting how many right elements
    // are present in LEFT) or uses a Set-based containment. After the fix, flip the
    // assertion below to `true` and delete this WHEN-FIXED block.
    var op = new QueryOperatorContainsAll();
    Object[] left = {2, 2, 3};
    Object[] right = {2, 3};
    Assert.assertEquals("Documents the over-counting bug — flip to true after fix",
        false, eval(op, left, right));
  }

  @Test
  public void testContainsAllArrayVsScalarFallsThroughToTrue() {
    // Left is an array, right is a scalar — neither array nor Collection sub-branches match,
    // falls through to return true (vacuous truth). Documents this edge case.
    //
    // WHEN-FIXED: when the operator validates right-operand type (must be array/collection),
    // update this assertion and delete this WHEN-FIXED block.
    var op = new QueryOperatorContainsAll();
    Object[] left = {1, 2, 3};
    Assert.assertEquals("Documents array-vs-scalar vacuous-truth — revisit after fix",
        true, eval(op, left, 99));
  }

  @Test
  public void testContainsAllIndexReuse() {
    var op = new QueryOperatorContainsAll();
    Assert.assertEquals(IndexReuseType.NO_INDEX,
        op.getIndexReuseType("left", "right"));
  }

  @Test
  public void testContainsAllRidRangesNull() {
    var op = new QueryOperatorContainsAll();
    Assert.assertNull(op.getBeginRidRange(null, "left", "right"));
    Assert.assertNull(op.getEndRidRange(null, "left", "right"));
  }

  // ===== QueryOperatorContainsAll — Condition Path =====

  @Test
  public void testContainsAllWithConditionAllMatch() {
    // Left is collection, condition always true — all elements pass → returns true
    var op = new QueryOperatorContainsAll();
    var ctx = newCommandContext();
    var subCond = alwaysTrueCondition();
    var outerCond = new SQLFilterCondition("field", op, subCond);

    // Use a list of EntityImpl for the condition path
    session.begin();
    var e1 = session.newInstance();
    e1.setProperty("a", 1);
    var e2 = session.newInstance();
    e2.setProperty("a", 2);
    var list = new ArrayList<EntityImpl>();
    list.add(e1);
    list.add(e2);
    session.rollback();

    Object result =
        op.evaluateRecord(null, null, outerCond, list, subCond, ctx, SERIALIZER);
    Assert.assertEquals(true, result);
  }

  @Test
  public void testContainsAllWithConditionOneFails() {
    // Condition always false — first element fails → returns false
    var op = new QueryOperatorContainsAll();
    var ctx = newCommandContext();
    var subCond = alwaysFalseCondition();
    var outerCond = new SQLFilterCondition("field", op, subCond);

    session.begin();
    var e1 = session.newInstance();
    e1.setProperty("a", 1);
    var list = new ArrayList<EntityImpl>();
    list.add(e1);
    session.rollback();

    Object result =
        op.evaluateRecord(null, null, outerCond, list, subCond, ctx, SERIALIZER);
    Assert.assertEquals(false, result);
  }

  // ===== QueryOperatorContainsValue — Simple Value Path =====

  @Test
  public void testContainsValueMapHasValue() {
    // Left is a Map containing the search value — should return true
    var op = new QueryOperatorContainsValue();
    Map<String, Object> map = new HashMap<>();
    map.put("a", 1);
    map.put("b", 2);
    Assert.assertEquals(true, eval(op, map, 2));
  }

  @Test
  public void testContainsValueMapDoesNotHaveValue() {
    // Left map does not contain the search value — should return false
    var op = new QueryOperatorContainsValue();
    Map<String, Object> map = new HashMap<>();
    map.put("a", 1);
    map.put("b", 2);
    Assert.assertEquals(false, eval(op, map, 99));
  }

  @Test
  public void testContainsValueEmptyMap() {
    // Empty map — for loop doesn't execute, falls through to return false
    var op = new QueryOperatorContainsValue();
    Assert.assertEquals(false, eval(op, Collections.emptyMap(), 1));
  }

  @Test
  public void testContainsValueStringValue() {
    // Map values are strings — test string equality
    var op = new QueryOperatorContainsValue();
    Map<String, Object> map = new HashMap<>();
    map.put("name", "Alice");
    map.put("city", "Rome");
    Assert.assertEquals(true, eval(op, map, "Rome"));
  }

  @Test
  public void testContainsValueCrossTypeIntegerLong() {
    // Cross-type containment: map has Long value, searching for Integer
    var op = new QueryOperatorContainsValue();
    Map<String, Object> map = new HashMap<>();
    map.put("count", 42L);
    Assert.assertEquals(true, eval(op, map, 42));
  }

  @Test
  public void testContainsValueRightSideMapNoConditionFallsThrough() {
    // Right is a Map, no condition — the code uses containsValue to check if left is a value
    // in the right map (bug was fixed: now uses containsValue when condition is null)
    var op = new QueryOperatorContainsValue();
    Map<String, Object> rightMap = new HashMap<>();
    rightMap.put("a", 1);
    rightMap.put("b", 2);
    Assert.assertEquals(true, eval(op, 2, rightMap));
  }

  @Test
  public void testContainsValueRightSideMapValueNotPresent() {
    var op = new QueryOperatorContainsValue();
    Map<String, Object> rightMap = new HashMap<>();
    rightMap.put("a", 1);
    Assert.assertEquals(false, eval(op, 99, rightMap));
  }

  @Test
  public void testContainsValueNonMapReturnsFalse() {
    // Neither left nor right is a Map — falls through to return false
    var op = new QueryOperatorContainsValue();
    Assert.assertEquals(false, eval(op, "string", "value"));
  }

  @Test
  public void testContainsValueNullLeftReturnsFalse() {
    var op = new QueryOperatorContainsValue();
    Assert.assertEquals(false, eval(op, null, "value"));
  }

  @Test
  public void testContainsValueNullRightReturnsFalse() {
    var op = new QueryOperatorContainsValue();
    Map<String, Object> map = new HashMap<>();
    map.put("a", 1);
    Assert.assertEquals(false, eval(op, map, null));
  }

  // ===== QueryOperatorContainsValue — Bug Fix Regression =====

  @Test
  public void testContainsValueRightMapConditionEarlyReturnFixRegression() {
    // Falsifiable regression test for the early-return bug in the right-side Map condition path.
    // Old buggy code: first value fails condition → return map.containsValue(iLeft).
    // If iLeft is also a value in the map, containsValue returns TRUE (wrong).
    // Fixed code: iterates ALL values, condition false for all → returns FALSE.
    var op = new QueryOperatorContainsValue();
    var ctx = newCommandContext();
    var subCond = alwaysFalseCondition();
    var outerCond = new SQLFilterCondition("field", op, subCond);

    session.begin();
    var e1 = session.newInstance();
    e1.setProperty("val", "first");
    var e2 = session.newInstance();
    e2.setProperty("val", "second");

    // Right-side map contains e1 and e2 as values
    Map<String, Object> rightMap = new HashMap<>();
    rightMap.put("a", e1);
    rightMap.put("b", e2);
    session.rollback();

    // Pass e1 as iLeft — it IS a value in rightMap, so containsValue(e1) would return true.
    // Buggy code: condition false for first value → containsValue(e1) → true
    // Fixed code: condition false for all values → loop ends → false
    Object result =
        op.evaluateRecord(null, null, outerCond, e1, rightMap, ctx, SERIALIZER);
    Assert.assertEquals(false, result);
  }

  @Test
  public void testContainsValueRightMapConditionAllMatchAfterFix() {
    // Positive regression test: condition is always true → any match returns true.
    // Both old and new code return true here; this verifies the happy path still works.
    var op = new QueryOperatorContainsValue();
    var ctx = newCommandContext();
    var subCond = alwaysTrueCondition();
    var outerCond = new SQLFilterCondition("field", op, subCond);

    session.begin();
    var e1 = session.newInstance();
    e1.setProperty("val", "first");

    Map<String, Object> rightMap = new HashMap<>();
    rightMap.put("a", e1);
    session.rollback();

    Object result =
        op.evaluateRecord(null, null, outerCond, subCond, rightMap, ctx, SERIALIZER);
    Assert.assertEquals(true, result);
  }

  // ===== QueryOperatorContainsValue — Index/RID =====

  @Test
  public void testContainsValueIndexReuseNoCondition() {
    var op = new QueryOperatorContainsValue();
    Assert.assertEquals(IndexReuseType.INDEX_METHOD,
        op.getIndexReuseType("field", "value"));
  }

  @Test
  public void testContainsValueIndexReuseWithLeftCondition() {
    var op = new QueryOperatorContainsValue();
    var cond = alwaysTrueCondition();
    Assert.assertEquals(IndexReuseType.NO_INDEX, op.getIndexReuseType(cond, "value"));
  }

  @Test
  public void testContainsValueIndexReuseWithRightCondition() {
    var op = new QueryOperatorContainsValue();
    var cond = alwaysTrueCondition();
    Assert.assertEquals(IndexReuseType.NO_INDEX, op.getIndexReuseType("field", cond));
  }

  @Test
  public void testContainsValueRidRangesNull() {
    var op = new QueryOperatorContainsValue();
    Assert.assertNull(op.getBeginRidRange(null, "left", "right"));
    Assert.assertNull(op.getEndRidRange(null, "left", "right"));
  }

  // ===== QueryOperatorContainsValue — Left-side Map Condition Path =====

  @Test
  public void testContainsValueLeftMapWithConditionMatch() {
    // Left is a Map whose values are EntityImpl, condition always true → returns true
    var op = new QueryOperatorContainsValue();
    var ctx = newCommandContext();
    var subCond = alwaysTrueCondition();
    var outerCond = new SQLFilterCondition("field", op, subCond);

    session.begin();
    var e1 = session.newInstance();
    e1.setProperty("val", "first");

    Map<String, Object> leftMap = new HashMap<>();
    leftMap.put("a", e1);
    session.rollback();

    Object result =
        op.evaluateRecord(null, null, outerCond, leftMap, subCond, ctx, SERIALIZER);
    Assert.assertEquals(true, result);
  }

  @Test
  public void testContainsValueLeftMapWithConditionNoMatch() {
    // Left is a Map whose values are EntityImpl, condition always false → returns false
    var op = new QueryOperatorContainsValue();
    var ctx = newCommandContext();
    var subCond = alwaysFalseCondition();
    var outerCond = new SQLFilterCondition("field", op, subCond);

    session.begin();
    var e1 = session.newInstance();
    e1.setProperty("val", "first");

    Map<String, Object> leftMap = new HashMap<>();
    leftMap.put("a", e1);
    session.rollback();

    Object result =
        op.evaluateRecord(null, null, outerCond, leftMap, subCond, ctx, SERIALIZER);
    Assert.assertEquals(false, result);
  }

  // ===== QueryOperatorContainsValue — Map/Entity Conversion =====

  @Test
  public void testContainsValueMapMatchesMapRight() {
    // When val is a Map and right is a Map, they are compared directly
    var op = new QueryOperatorContainsValue();
    Map<String, Object> leftMap = new HashMap<>();
    Map<String, Object> innerMap = new HashMap<>();
    innerMap.put("x", 1);
    leftMap.put("nested", innerMap);

    // Searching for a map value: the inner map should be found
    Map<String, Object> searchMap = new HashMap<>();
    searchMap.put("x", 1);
    Assert.assertEquals(true, eval(op, leftMap, searchMap));
  }

  // ===== QueryOperatorInstanceof =====

  @Test
  public void testInstanceofDirectClassMatch() {
    // Entity of class "Animal" instanceof "Animal" → true
    session.getMetadata().getSchema().createClass("Animal");
    session.begin();
    var e = session.newInstance("Animal");
    e.setProperty("name", "dog");

    session.commit();

    var op = new QueryOperatorInstanceof();
    var ctx = newCommandContext();
    var cond = new SQLFilterCondition("field", op, "Animal");

    session.begin();
    Object result =
        op.evaluateRecord(null, null, cond, e.getIdentity(), "Animal", ctx, SERIALIZER);
    session.commit();
    Assert.assertEquals(true, result);
  }

  @Test
  public void testInstanceofSubclassMatch() {
    // Entity of class "Dog" which extends "Animal" → instanceof "Animal" is true
    var animalBase = session.getMetadata().getSchema().createClass("AnimalBase");
    session.getMetadata().getSchema().createClass("Dog", animalBase);
    session.begin();
    var e = session.newInstance("Dog");
    e.setProperty("breed", "Labrador");

    session.commit();

    var op = new QueryOperatorInstanceof();
    var ctx = newCommandContext();
    var cond = new SQLFilterCondition("field", op, "AnimalBase");

    session.begin();
    Object result =
        op.evaluateRecord(null, null, cond, e.getIdentity(), "AnimalBase", ctx, SERIALIZER);
    session.commit();
    Assert.assertEquals(true, result);
  }

  @Test
  public void testInstanceofSubclassOfSelf() {
    // isSubClassOf should return true for the class itself
    session.getMetadata().getSchema().createClass("SelfCheck");
    session.begin();
    var e = session.newInstance("SelfCheck");

    session.commit();

    var op = new QueryOperatorInstanceof();
    var ctx = newCommandContext();
    var cond = new SQLFilterCondition("field", op, "SelfCheck");

    session.begin();
    Object result =
        op.evaluateRecord(null, null, cond, e.getIdentity(), "SelfCheck", ctx, SERIALIZER);
    session.commit();
    Assert.assertEquals(true, result);
  }

  @Test
  public void testInstanceofUnrelatedClassReturnsFalse() {
    // Entity of class "Cat" is not instanceof "Vehicle" → false
    session.getMetadata().getSchema().createClass("Cat");
    session.getMetadata().getSchema().createClass("Vehicle");
    session.begin();
    var e = session.newInstance("Cat");

    session.commit();

    var op = new QueryOperatorInstanceof();
    var ctx = newCommandContext();
    var cond = new SQLFilterCondition("field", op, "Vehicle");

    session.begin();
    Object result =
        op.evaluateRecord(null, null, cond, e.getIdentity(), "Vehicle", ctx, SERIALIZER);
    session.commit();
    Assert.assertEquals(false, result);
  }

  @Test
  public void testInstanceofStringClassNameLeft() {
    // Left is a String class name (not an Identifiable) — looks up by name
    var fruit = session.getMetadata().getSchema().createClass("Fruit");
    session.getMetadata().getSchema().createClass("Apple", fruit);

    var op = new QueryOperatorInstanceof();
    var ctx = newCommandContext();
    var cond = new SQLFilterCondition("field", op, "Fruit");

    // "Apple" as a string class name — Apple is a subclass of Fruit → true
    Object result = op.evaluateRecord(null, null, cond, "Apple", "Fruit", ctx, SERIALIZER);
    Assert.assertEquals(true, result);
  }

  @Test
  public void testInstanceofStringClassNameUnrelated() {
    session.getMetadata().getSchema().createClass("FruitClass");
    session.getMetadata().getSchema().createClass("VeggieClass");

    var op = new QueryOperatorInstanceof();
    var ctx = newCommandContext();
    var cond = new SQLFilterCondition("field", op, "FruitClass");

    // "VeggieClass" is not a subclass of "FruitClass" → false
    Object result =
        op.evaluateRecord(null, null, cond, "VeggieClass", "FruitClass", ctx, SERIALIZER);
    Assert.assertEquals(false, result);
  }

  @Test
  public void testInstanceofInvalidRightClassThrows() {
    // Right operand references a non-existent class → throws CommandExecutionException
    // with a message referencing the missing class name
    var op = new QueryOperatorInstanceof();
    var ctx = newCommandContext();
    var cond = new SQLFilterCondition("field", op, "NonExistent");

    try {
      op.evaluateRecord(null, null, cond, "AnyClass", "NonExistent", ctx, SERIALIZER);
      Assert.fail("Expected CommandExecutionException for non-existent class");
    } catch (CommandExecutionException e) {
      Assert.assertTrue(
          "Exception message should reference the missing class",
          e.getMessage().contains("NonExistent"));
    }
  }

  @Test
  public void testInstanceofLeftStringNonExistentClassReturnsFalse() {
    // Left is a String naming a class not in schema → schema.getClass() returns null → cls=null
    // → returns false. Documents asymmetry: right non-existent throws, left non-existent is false.
    session.getMetadata().getSchema().createClass("ExistingTarget");

    var op = new QueryOperatorInstanceof();
    var ctx = newCommandContext();
    var cond = new SQLFilterCondition("field", op, "ExistingTarget");

    Object result =
        op.evaluateRecord(null, null, cond, "NoSuchClass", "ExistingTarget", ctx, SERIALIZER);
    Assert.assertEquals(false, result);
  }

  @Test
  public void testInstanceofNullLeftReturnsFalse() {
    // QueryOperatorEqualityNotNulls short-circuits on null left BEFORE any
    // schema lookup — no class creation needed; right-hand name can be arbitrary.
    var op = new QueryOperatorInstanceof();
    Assert.assertEquals(false, eval(op, null, "AnyUndefinedClass"));
  }

  @Test
  public void testInstanceofNullRightReturnsFalse() {
    // QueryOperatorEqualityNotNulls: null right → false
    var op = new QueryOperatorInstanceof();
    Assert.assertEquals(false, eval(op, "SomeClass", null));
  }

  @Test
  public void testInstanceofIndexReuse() {
    var op = new QueryOperatorInstanceof();
    Assert.assertEquals(IndexReuseType.NO_INDEX,
        op.getIndexReuseType("left", "right"));
  }

  @Test
  public void testInstanceofRidRangesNull() {
    var op = new QueryOperatorInstanceof();
    Assert.assertNull(op.getBeginRidRange(null, "left", "right"));
    Assert.assertNull(op.getEndRidRange(null, "left", "right"));
  }

  // ===== QueryOperatorTraverse — Configure =====

  @Test
  public void testTraverseConfigureNoParams() {
    // configure(null) returns the same instance unchanged
    var traverse = new QueryOperatorTraverse();
    var result = traverse.configure(null);
    Assert.assertSame(traverse, result);
  }

  @Test
  public void testTraverseConfigureWithStartEnd() {
    // configure with start and end deep levels
    var traverse = new QueryOperatorTraverse();
    var result = (QueryOperatorTraverse) traverse.configure(Arrays.asList("2", "5"));
    Assert.assertEquals(2, result.getStartDeepLevel());
    Assert.assertEquals(5, result.getEndDeepLevel());
    // Default fields = {"any()"}
    Assert.assertArrayEquals(new String[] {"any()"}, result.getCfgFields());
  }

  @Test
  public void testTraverseConfigureWithFields() {
    // configure with custom fields (comma-separated)
    var traverse = new QueryOperatorTraverse();
    var result = (QueryOperatorTraverse) traverse.configure(Arrays.asList("0", "3", "name,age"));
    Assert.assertEquals(0, result.getStartDeepLevel());
    Assert.assertEquals(3, result.getEndDeepLevel());
    Assert.assertArrayEquals(new String[] {"name", "age"}, result.getCfgFields());
  }

  @Test
  public void testTraverseConfigureWithQuotedFields() {
    // Quoted field names should have quotes stripped
    var traverse = new QueryOperatorTraverse();
    var result =
        (QueryOperatorTraverse) traverse.configure(Arrays.asList("0", "-1", "'name,age'"));
    Assert.assertArrayEquals(new String[] {"name", "age"}, result.getCfgFields());
  }

  @Test
  public void testTraverseConfigureWithDoubleQuotedFields() {
    // Double-quoted field names should also have quotes stripped
    var traverse = new QueryOperatorTraverse();
    var result =
        (QueryOperatorTraverse) traverse.configure(Arrays.asList("0", "-1", "\"name,age\""));
    Assert.assertArrayEquals(new String[] {"name", "age"}, result.getCfgFields());
  }

  @Test
  public void testTraverseConfigureOnlyStartLevel() {
    // Only start level provided — end level defaults to -1 (infinite)
    var traverse = new QueryOperatorTraverse();
    var result = (QueryOperatorTraverse) traverse.configure(Arrays.asList("3"));
    Assert.assertEquals(3, result.getStartDeepLevel());
    Assert.assertEquals(-1, result.getEndDeepLevel());
  }

  @Test
  public void testTraverseConfigureEmptyList() {
    // Empty list — both default
    var traverse = new QueryOperatorTraverse();
    var result = (QueryOperatorTraverse) traverse.configure(Collections.emptyList());
    Assert.assertEquals(0, result.getStartDeepLevel());
    Assert.assertEquals(-1, result.getEndDeepLevel());
  }

  // ===== QueryOperatorTraverse — Getters / toString / syntax =====

  @Test
  public void testTraverseGettersDefault() {
    var traverse = new QueryOperatorTraverse();
    Assert.assertEquals(0, traverse.getStartDeepLevel());
    Assert.assertEquals(-1, traverse.getEndDeepLevel());
    Assert.assertNull(traverse.getCfgFields());
  }

  @Test
  public void testTraverseConstructorWithParams() {
    var traverse = new QueryOperatorTraverse(2, 5, new String[] {"name", "age"});
    Assert.assertEquals(2, traverse.getStartDeepLevel());
    Assert.assertEquals(5, traverse.getEndDeepLevel());
    Assert.assertArrayEquals(new String[] {"name", "age"}, traverse.getCfgFields());
  }

  @Test
  public void testTraverseToString() {
    var traverse = new QueryOperatorTraverse(1, 3, new String[] {"name"});
    Assert.assertEquals("TRAVERSE(1,3,[name])", traverse.toString());
  }

  @Test
  public void testTraverseSyntax() {
    var traverse = new QueryOperatorTraverse();
    Assert.assertTrue(traverse.getSyntax().contains("TRAVERSE"));
    Assert.assertTrue(traverse.getSyntax().contains("begin-deep-level"));
  }

  @Test
  public void testTraverseIndexReuse() {
    var traverse = new QueryOperatorTraverse();
    Assert.assertEquals(IndexReuseType.NO_INDEX,
        traverse.getIndexReuseType("left", "right"));
  }

  @Test
  public void testTraverseRidRangesNull() {
    var traverse = new QueryOperatorTraverse();
    Assert.assertNull(traverse.getBeginRidRange(null, "left", "right"));
    Assert.assertNull(traverse.getEndRidRange(null, "left", "right"));
  }

  // ===== QueryOperatorTraverse — Traversal with Entities =====

  @Test
  public void testTraverseLinkedEntityMatchesCondition() {
    // Create linked entities: A -> B -> C, condition matches C's name
    var traverseNode = session.getMetadata().getSchema().createClass("TraverseNode");
    traverseNode.createProperty("name", PropertyType.STRING);
    traverseNode.createProperty("next", PropertyType.LINK, traverseNode);

    session.begin();
    var c = session.newInstance("TraverseNode");
    c.setProperty("name", "target");

    var b = session.newInstance("TraverseNode");
    b.setProperty("name", "middle");
    b.setProperty("next", c.getIdentity());

    var a = session.newInstance("TraverseNode");
    a.setProperty("name", "start");
    a.setProperty("next", b.getIdentity());

    session.commit();

    // Traverse with "any()" fields, condition always true — should match
    var traverse = new QueryOperatorTraverse(0, -1, new String[] {"any()"});
    var ctx = newCommandContext();
    var subCond = alwaysTrueCondition();
    // Put sub-condition on right, target on left
    var outerCond = new SQLFilterCondition("field", traverse, subCond);

    session.begin();
    Object result =
        traverse.evaluateRecord(
            null, null, outerCond, a.getIdentity(), subCond, ctx, SERIALIZER);
    session.commit();
    Assert.assertEquals(true, result);
  }

  @Test
  public void testTraverseNoMatchReturnsFalse() {
    // Create a single entity with no links, condition always false — should return false
    session.getMetadata().getSchema().createClass("TraverseNoMatch");
    session.begin();
    var a = session.newInstance("TraverseNoMatch");
    a.setProperty("name", "alone");

    session.commit();

    var traverse = new QueryOperatorTraverse(0, -1, new String[] {"any()"});
    var ctx = newCommandContext();
    var subCond = alwaysFalseCondition();
    var outerCond = new SQLFilterCondition("field", traverse, subCond);

    session.begin();
    Object result =
        traverse.evaluateRecord(
            null, null, outerCond, a.getIdentity(), subCond, ctx, SERIALIZER);
    session.commit();
    Assert.assertEquals(false, result);
  }

  @Test
  public void testTraverseDepthLimitBlocksChildren() {
    // Verify depth limiting actually prevents reaching deeper levels.
    // A -> B. startDeepLevel=1 skips root. endDeepLevel=0 blocks level 1.
    // So B at level 1 is blocked even though the condition would match.
    var traverseDepth = session.getMetadata().getSchema().createClass("TraverseDepth");
    traverseDepth.createProperty("next", PropertyType.LINK, traverseDepth);

    session.begin();
    var b = session.newInstance("TraverseDepth");

    var a = session.newInstance("TraverseDepth");
    a.setProperty("next", b.getIdentity());
    session.commit();

    // startDeepLevel=1 skips root, endDeepLevel=0 blocks level 1 children
    var traverse = new QueryOperatorTraverse(1, 0, new String[] {"any()"});
    var ctx = newCommandContext();
    var subCond = alwaysTrueCondition();
    var outerCond = new SQLFilterCondition("field", traverse, subCond);

    session.begin();
    Object result =
        traverse.evaluateRecord(
            null, null, outerCond, a.getIdentity(), subCond, ctx, SERIALIZER);
    session.commit();
    // Level 0: root skipped. Level 1: endDeepLevel=0, iLevel=1 > 0 → blocked → false
    Assert.assertEquals(false, result);

    // Verify without depth limit the same data returns true (B is reachable)
    var traverseUnlimited = new QueryOperatorTraverse(1, -1, new String[] {"any()"});
    var outerCondUnlimited = new SQLFilterCondition("field", traverseUnlimited, subCond);

    session.begin();
    Object unlimited =
        traverseUnlimited.evaluateRecord(
            null, null, outerCondUnlimited, a.getIdentity(), subCond, ctx, SERIALIZER);
    session.commit();
    // Without depth limit, B at level 1 matches → true
    Assert.assertEquals(true, unlimited);
  }

  @Test
  public void testTraverseStartLevelSkipsEarlyLevels() {
    // Create single entity. Start level 1 means level 0 is skipped, so the root entity
    // doesn't match even though condition is true. Only traversed children at level >= 1 count.
    session.getMetadata().getSchema().createClass("TraverseStartLevel");
    session.begin();
    var a = session.newInstance("TraverseStartLevel");
    a.setProperty("name", "root");

    session.commit();

    // Start level 1, end unlimited — root entity at level 0 is skipped
    var traverse = new QueryOperatorTraverse(1, -1, new String[] {"any()"});
    var ctx = newCommandContext();
    var subCond = alwaysTrueCondition();
    var outerCond = new SQLFilterCondition("field", traverse, subCond);

    session.begin();
    Object result =
        traverse.evaluateRecord(
            null, null, outerCond, a.getIdentity(), subCond, ctx, SERIALIZER);
    session.commit();
    // Level 0 is skipped, no children to traverse at level 1 → false
    Assert.assertEquals(false, result);
  }

  @Test
  public void testTraverseWithSpecificFieldName() {
    // Traverse only the "next" field, not "any()"
    var traverseSpecific = session.getMetadata().getSchema().createClass("TraverseSpecific");
    traverseSpecific.createProperty("next", PropertyType.LINK, traverseSpecific);
    traverseSpecific.createProperty("other", PropertyType.LINK, traverseSpecific);

    session.begin();
    var target = session.newInstance("TraverseSpecific");
    target.setProperty("name", "target");

    var root = session.newInstance("TraverseSpecific");
    root.setProperty("name", "root");
    root.setProperty("next", target.getIdentity());

    session.commit();

    var traverse = new QueryOperatorTraverse(0, -1, new String[] {"next"});
    var ctx = newCommandContext();
    var subCond = alwaysTrueCondition();
    var outerCond = new SQLFilterCondition("field", traverse, subCond);

    session.begin();
    Object result =
        traverse.evaluateRecord(
            null, null, outerCond, root.getIdentity(), subCond, ctx, SERIALIZER);
    session.commit();
    Assert.assertEquals(true, result);
  }

  @Test
  public void testTraverseCycleDetection() {
    // Create a cycle: A -> B -> A. Traverse should not loop infinitely.
    var traverseCycle = session.getMetadata().getSchema().createClass("TraverseCycle");
    traverseCycle.createProperty("next", PropertyType.LINK, traverseCycle);

    session.begin();
    var a = session.newInstance("TraverseCycle");
    a.setProperty("name", "a");

    var b = session.newInstance("TraverseCycle");
    b.setProperty("name", "b");
    b.setProperty("next", a.getIdentity());

    // Create cycle: a -> b -> a
    a.setProperty("next", b.getIdentity());

    session.commit();

    // Condition is always false — should traverse the cycle without hanging, return false
    var traverse = new QueryOperatorTraverse(0, -1, new String[] {"any()"});
    var ctx = newCommandContext();
    var subCond = alwaysFalseCondition();
    var outerCond = new SQLFilterCondition("field", traverse, subCond);

    session.begin();
    Object result =
        traverse.evaluateRecord(
            null, null, outerCond, a.getIdentity(), subCond, ctx, SERIALIZER);
    session.commit();
    // Cycle is detected via evaluatedRecords Set — does not loop infinitely
    Assert.assertEquals(false, result);
  }

  // ===== QueryOperatorTraverse — Bug Fix Regression =====

  @Test
  public void testTraverseAllFieldBranchReachableAfterFix() {
    // Falsifiable regression test for the ALL() field branch copy-paste bug.
    // Use startDeepLevel=1 to skip the root entity match, forcing the ALL branch to be reached.
    // Root has only a "child" link property (no string properties that would fail ALL).
    // With fix: "all()" matches ALL branch → iterates "child" → traverse succeeds → true
    // With bug: "all()" doesn't match ANY() → else branch → traverse(getProperty("all()")) → null
    //           → returns false
    var traverseAllFix = session.getMetadata().getSchema().createClass("TraverseAllFix");
    traverseAllFix.createProperty("child", PropertyType.LINK, traverseAllFix);

    session.begin();
    // Child has no properties set → propertyNames() empty at its level
    var child = session.newInstance("TraverseAllFix");

    // Root has only "child" property → ALL iterates just ["child"]
    var root = session.newInstance("TraverseAllFix");
    root.setProperty("child", child.getIdentity());
    session.commit();

    // startDeepLevel=1: root at level 0 is skipped, cfgFields loop runs at level 0
    var traverse = new QueryOperatorTraverse(1, -1, new String[] {"all()"});
    var ctx = newCommandContext();
    var subCond = alwaysTrueCondition();
    var outerCond = new SQLFilterCondition("field", traverse, subCond);

    session.begin();
    Object result =
        traverse.evaluateRecord(
            null, null, outerCond, root.getIdentity(), subCond, ctx, SERIALIZER);
    session.commit();
    // With fix: ALL branch → traverse(child, ..., 1) → child loaded, condition true → true
    // With bug: else → traverse(null) → false
    Assert.assertEquals(true, result);
  }

  @Test
  public void testTraverseAllFieldBranchFailsWhenTraversalFails() {
    // ALL() branch: if any property traversal returns false, the whole thing returns false.
    // Use startDeepLevel=1 and an entity with a string property (not traversable).
    // Traversing a string value returns false → !false = true → ALL returns false immediately.
    session.getMetadata().getSchema().createClass("TraverseAllFail");

    session.begin();
    var root = session.newInstance("TraverseAllFail");
    root.setProperty("name", "root");
    session.commit();

    // startDeepLevel=1 skips root condition check, enters cfgFields loop
    var traverse = new QueryOperatorTraverse(1, -1, new String[] {"all()"});
    var ctx = newCommandContext();
    var subCond = alwaysTrueCondition();
    var outerCond = new SQLFilterCondition("field", traverse, subCond);

    session.begin();
    Object result =
        traverse.evaluateRecord(
            null, null, outerCond, root.getIdentity(), subCond, ctx, SERIALIZER);
    session.commit();
    // ALL: traverse("root" string, ..., 1) → string falls through → false
    // !false = true → return false from ALL branch
    Assert.assertEquals(false, result);
  }

  @Test
  public void testTraverseMapValues() {
    // Traverse through a Map target — iterates map values
    var traverse = new QueryOperatorTraverse(0, -1, new String[] {"any()"});
    var ctx = newCommandContext();
    var subCond = alwaysTrueCondition();
    // Sub-condition on right side
    var outerCond = new SQLFilterCondition(subCond, traverse, "field");

    Map<String, Object> map = new HashMap<>();
    map.put("a", "value1");
    map.put("b", "value2");

    // Target is a map, traverse iterates its values at level 1
    // Since values are strings (not Identifiable/EntityImpl), traverse just evaluates them
    // Strings are not handled by any branch in traverse(), so returns false
    Object result =
        traverse.evaluateRecord(null, null, outerCond, subCond, map, ctx, SERIALIZER);
    Assert.assertEquals(false, result);
  }

  @Test
  public void testTraverseNullLeftReturnsFalse() {
    // QueryOperatorEqualityNotNulls: null left → false
    var traverse = new QueryOperatorTraverse();
    Assert.assertEquals(false, eval(traverse, null, "target"));
  }

  @Test
  public void testTraverseNullRightReturnsFalse() {
    var traverse = new QueryOperatorTraverse();
    Assert.assertEquals(false, eval(traverse, "target", null));
  }
}
