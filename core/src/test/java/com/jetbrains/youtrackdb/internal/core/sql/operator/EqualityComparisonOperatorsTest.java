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
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.EntitySerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.RecordSerializerBinary;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for equality hierarchy and comparison operators that require a database session: Equals,
 * NotEquals, NotEquals2, Major, MajorEquals, Minor, MinorEquals, Between, In. Extends DbTestBase for
 * access to the session needed by PropertyTypeInternal.convert() and castComparableNumber().
 */
public class EqualityComparisonOperatorsTest extends DbTestBase {

  private static final EntitySerializer SERIALIZER =
      RecordSerializerBinary.INSTANCE.getCurrentSerializer();

  private Object eval(QueryOperator op, Object left, Object right) {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return op.evaluateRecord(null, null, null, left, right, ctx, SERIALIZER);
  }

  // ===== QueryOperatorEquals =====

  @Test
  public void testEqualsSameIntegers() {
    var eq = new QueryOperatorEquals();
    Assert.assertEquals(true, eval(eq, 10, 10));
  }

  @Test
  public void testEqualsIntegerVsLongCrossType() {
    // Cross-type numeric comparison: Integer vs Long
    var eq = new QueryOperatorEquals();
    Assert.assertEquals(true, eval(eq, 10, 10L));
  }

  @Test
  public void testEqualsSameStrings() {
    var eq = new QueryOperatorEquals();
    Assert.assertEquals(true, eval(eq, "hello", "hello"));
  }

  @Test
  public void testEqualsDifferentStrings() {
    var eq = new QueryOperatorEquals();
    Assert.assertEquals(false, eval(eq, "hello", "world"));
  }

  @Test
  public void testEqualsSameDates() {
    var eq = new QueryOperatorEquals();
    var d1 = new Date(1000L);
    var d2 = new Date(1000L);
    Assert.assertEquals(true, eval(eq, d1, d2));
  }

  @Test
  public void testEqualsDifferentDates() {
    var eq = new QueryOperatorEquals();
    Assert.assertEquals(false, eval(eq, new Date(1000L), new Date(2000L)));
  }

  @Test
  public void testEqualsBigDecimalVsInteger() {
    // Cross-type: BigDecimal vs Integer uses castComparableNumber
    var eq = new QueryOperatorEquals();
    Assert.assertEquals(true, eval(eq, new BigDecimal(10), 10));
  }

  @Test
  public void testEqualsDoubleVsFloat() {
    // 3.14f and 3.14d have different IEEE 754 representations, so they are not equal.
    // Use an exact representable value for cross-type comparison.
    var eq = new QueryOperatorEquals();
    Assert.assertEquals(true, eval(eq, 0.5d, 0.5f));
    Assert.assertEquals(false, eval(eq, 3.14d, 3.14f));
  }

  @Test
  public void testEqualsNullLeftReturnsFalse() {
    // EqualityNotNulls: null left returns false
    var eq = new QueryOperatorEquals();
    Assert.assertEquals(false, eval(eq, null, 10));
  }

  @Test
  public void testEqualsNullRightReturnsFalse() {
    var eq = new QueryOperatorEquals();
    Assert.assertEquals(false, eval(eq, 10, null));
  }

  @Test
  public void testEqualsBothNullReturnsFalse() {
    // NULL != NULL in SQL semantics — both-null comparison yields false
    var eq = new QueryOperatorEquals();
    Assert.assertEquals(false, eval(eq, null, null));
  }

  @Test
  public void testEqualsMultiElementCollectionVsScalar() {
    // Multi-element collection is NOT unwrapped (size() != 1), so comparison is false
    var eq = new QueryOperatorEquals();
    Assert.assertEquals(false, eval(eq, Arrays.asList(1, 2), 1));
    Assert.assertEquals(false, eval(eq, 1, Arrays.asList(1, 2)));
  }

  @Test
  public void testEqualsCollectionWithOneElement() {
    // Single-element collection is unwrapped and compared
    var eq = new QueryOperatorEquals();
    Assert.assertEquals(true, eval(eq, Collections.singletonList(10), 10));
  }

  @Test
  public void testEqualsRightCollectionWithOneElement() {
    var eq = new QueryOperatorEquals();
    Assert.assertEquals(true, eval(eq, 10, Collections.singletonList(10)));
  }

  @Test
  public void testEqualsByteArrays() {
    var eq = new QueryOperatorEquals();
    Assert.assertEquals(true, eval(eq, new byte[] {1, 2, 3}, new byte[] {1, 2, 3}));
  }

  @Test
  public void testEqualsByteArraysDifferent() {
    var eq = new QueryOperatorEquals();
    Assert.assertEquals(false, eval(eq, new byte[] {1, 2, 3}, new byte[] {4, 5, 6}));
  }

  @Test
  public void testEqualsIdentityShortCircuit() {
    // Same object reference → true without any type conversion
    var eq = new QueryOperatorEquals();
    var obj = new Object();
    Assert.assertEquals(true, eval(eq, obj, obj));
  }

  @Test
  public void testEqualsSupportsBinaryEvaluate() {
    Assert.assertTrue(new QueryOperatorEquals().isSupportingBinaryEvaluate());
  }

  @Test
  public void testEqualsIndexReuseType() {
    var eq = new QueryOperatorEquals();
    Assert.assertEquals(IndexReuseType.INDEX_METHOD, eq.getIndexReuseType("a", "b"));
    Assert.assertEquals(IndexReuseType.NO_INDEX, eq.getIndexReuseType(null, "b"));
    Assert.assertEquals(IndexReuseType.NO_INDEX, eq.getIndexReuseType("a", null));
  }

  // NOTE: QueryOperatorEquals lines 87-91 have duplicate `iRight instanceof Result` check
  // (dead code). The second branch at line 89 is unreachable because line 87 catches it first.

  // ===== QueryOperatorNotEquals =====

  @Test
  public void testNotEqualsSameIntegers() {
    var ne = new QueryOperatorNotEquals();
    Assert.assertEquals(false, eval(ne, 10, 10));
  }

  @Test
  public void testNotEqualsDifferentIntegers() {
    var ne = new QueryOperatorNotEquals();
    Assert.assertEquals(true, eval(ne, 10, 20));
  }

  @Test
  public void testNotEqualsCrossType() {
    var ne = new QueryOperatorNotEquals();
    Assert.assertEquals(false, eval(ne, 10, 10L));
  }

  @Test
  public void testNotEqualsNullLeftReturnsFalse() {
    var ne = new QueryOperatorNotEquals();
    Assert.assertEquals(false, eval(ne, null, 10));
  }

  @Test
  public void testNotEqualsNullRightReturnsFalse() {
    var ne = new QueryOperatorNotEquals();
    Assert.assertEquals(false, eval(ne, 10, null));
  }

  @Test
  public void testNotEqualsBothNullReturnsFalse() {
    var ne = new QueryOperatorNotEquals();
    Assert.assertEquals(false, eval(ne, null, null));
  }

  @Test
  public void testNotEqualsIndexReuseType() {
    var ne = new QueryOperatorNotEquals();
    Assert.assertEquals(IndexReuseType.NO_INDEX, ne.getIndexReuseType("a", "b"));
  }

  @Test
  public void testNotEqualsSupportsBinaryEvaluate() {
    Assert.assertTrue(new QueryOperatorNotEquals().isSupportingBinaryEvaluate());
  }

  // ===== QueryOperatorNotEquals2 =====

  @Test
  public void testNotEquals2SameIntegers() {
    var ne2 = new QueryOperatorNotEquals2();
    Assert.assertEquals(false, eval(ne2, 10, 10));
  }

  @Test
  public void testNotEquals2DifferentIntegers() {
    var ne2 = new QueryOperatorNotEquals2();
    Assert.assertEquals(true, eval(ne2, 10, 20));
  }

  @Test
  public void testNotEquals2NullReturnsFalse() {
    var ne2 = new QueryOperatorNotEquals2();
    Assert.assertEquals(false, eval(ne2, null, 10));
    Assert.assertEquals(false, eval(ne2, 10, null));
  }

  @Test
  public void testNotEquals2SupportsBinaryEvaluate() {
    Assert.assertTrue(new QueryOperatorNotEquals2().isSupportingBinaryEvaluate());
  }

  // ===== QueryOperatorMajor (>) =====

  @Test
  public void testMajorGreaterReturnsTrue() {
    var gt = new QueryOperatorMajor();
    Assert.assertEquals(true, eval(gt, 20, 10));
  }

  @Test
  public void testMajorEqualReturnsFalse() {
    var gt = new QueryOperatorMajor();
    Assert.assertEquals(false, eval(gt, 10, 10));
  }

  @Test
  public void testMajorLessReturnsFalse() {
    var gt = new QueryOperatorMajor();
    Assert.assertEquals(false, eval(gt, 5, 10));
  }

  @Test
  public void testMajorStrings() {
    var gt = new QueryOperatorMajor();
    Assert.assertEquals(true, eval(gt, "b", "a"));
    Assert.assertEquals(false, eval(gt, "a", "b"));
  }

  @Test
  public void testMajorDates() {
    var gt = new QueryOperatorMajor();
    Assert.assertEquals(true, eval(gt, new Date(2000L), new Date(1000L)));
    Assert.assertEquals(false, eval(gt, new Date(1000L), new Date(2000L)));
  }

  @Test
  public void testMajorNullReturnsFalse() {
    var gt = new QueryOperatorMajor();
    Assert.assertEquals(false, eval(gt, null, 10));
    Assert.assertEquals(false, eval(gt, 10, null));
  }

  @Test
  public void testMajorSupportsBinaryEvaluate() {
    Assert.assertTrue(new QueryOperatorMajor().isSupportingBinaryEvaluate());
  }

  @Test
  public void testMajorIndexReuseType() {
    var gt = new QueryOperatorMajor();
    Assert.assertEquals(IndexReuseType.INDEX_METHOD, gt.getIndexReuseType("a", "b"));
    Assert.assertEquals(IndexReuseType.NO_INDEX, gt.getIndexReuseType(null, "b"));
    Assert.assertEquals(IndexReuseType.NO_INDEX, gt.getIndexReuseType("a", null));
  }

  @Test
  public void testMajorCrossTypeIntegerVsLong() {
    var gt = new QueryOperatorMajor();
    Assert.assertEquals(true, eval(gt, 20, 10L));
    Assert.assertEquals(false, eval(gt, 5, 10L));
  }

  // ===== QueryOperatorMajorEquals (>=) =====

  @Test
  public void testMajorEqualsGreaterReturnsTrue() {
    var ge = new QueryOperatorMajorEquals();
    Assert.assertEquals(true, eval(ge, 20, 10));
  }

  @Test
  public void testMajorEqualsEqualReturnsTrue() {
    var ge = new QueryOperatorMajorEquals();
    Assert.assertEquals(true, eval(ge, 10, 10));
  }

  @Test
  public void testMajorEqualsLessReturnsFalse() {
    var ge = new QueryOperatorMajorEquals();
    Assert.assertEquals(false, eval(ge, 5, 10));
  }

  @Test
  public void testMajorEqualsNullReturnsFalse() {
    var ge = new QueryOperatorMajorEquals();
    Assert.assertEquals(false, eval(ge, null, 10));
    Assert.assertEquals(false, eval(ge, 10, null));
  }

  // ===== QueryOperatorMinor (<) =====

  @Test
  public void testMinorLessReturnsTrue() {
    var lt = new QueryOperatorMinor();
    Assert.assertEquals(true, eval(lt, 5, 10));
  }

  @Test
  public void testMinorEqualReturnsFalse() {
    var lt = new QueryOperatorMinor();
    Assert.assertEquals(false, eval(lt, 10, 10));
  }

  @Test
  public void testMinorGreaterReturnsFalse() {
    var lt = new QueryOperatorMinor();
    Assert.assertEquals(false, eval(lt, 20, 10));
  }

  @Test
  public void testMinorStrings() {
    var lt = new QueryOperatorMinor();
    Assert.assertEquals(true, eval(lt, "a", "b"));
    Assert.assertEquals(false, eval(lt, "b", "a"));
  }

  @Test
  public void testMinorNullReturnsFalse() {
    var lt = new QueryOperatorMinor();
    Assert.assertEquals(false, eval(lt, null, 10));
    Assert.assertEquals(false, eval(lt, 10, null));
  }

  @Test
  public void testMinorCrossTypeBigDecimalVsInteger() {
    var lt = new QueryOperatorMinor();
    Assert.assertEquals(true, eval(lt, new BigDecimal("3"), 5));
    Assert.assertEquals(false, eval(lt, new BigDecimal("10"), 5));
  }

  // ===== QueryOperatorMinorEquals (<=) =====

  @Test
  public void testMinorEqualsLessReturnsTrue() {
    var le = new QueryOperatorMinorEquals();
    Assert.assertEquals(true, eval(le, 5, 10));
  }

  @Test
  public void testMinorEqualsEqualReturnsTrue() {
    var le = new QueryOperatorMinorEquals();
    Assert.assertEquals(true, eval(le, 10, 10));
  }

  @Test
  public void testMinorEqualsGreaterReturnsFalse() {
    var le = new QueryOperatorMinorEquals();
    Assert.assertEquals(false, eval(le, 20, 10));
  }

  @Test
  public void testMinorEqualsNullReturnsFalse() {
    var le = new QueryOperatorMinorEquals();
    Assert.assertEquals(false, eval(le, null, 10));
    Assert.assertEquals(false, eval(le, 10, null));
  }

  // ===== QueryOperatorBetween =====

  @Test
  public void testBetweenInRangeInclusive() {
    var between = new QueryOperatorBetween();
    // Right operand is a 3-element collection: [lower, AND, upper]
    Assert.assertEquals(true, eval(between, 5, Arrays.asList(1, "AND", 10)));
  }

  @Test
  public void testBetweenAtLowerBoundInclusive() {
    var between = new QueryOperatorBetween();
    Assert.assertEquals(true, eval(between, 1, Arrays.asList(1, "AND", 10)));
  }

  @Test
  public void testBetweenAtUpperBoundInclusive() {
    var between = new QueryOperatorBetween();
    Assert.assertEquals(true, eval(between, 10, Arrays.asList(1, "AND", 10)));
  }

  @Test
  public void testBetweenBelowRange() {
    var between = new QueryOperatorBetween();
    Assert.assertEquals(false, eval(between, 0, Arrays.asList(1, "AND", 10)));
  }

  @Test
  public void testBetweenAboveRange() {
    var between = new QueryOperatorBetween();
    Assert.assertEquals(false, eval(between, 11, Arrays.asList(1, "AND", 10)));
  }

  @Test
  public void testBetweenLeftExclusive() {
    var between = new QueryOperatorBetween();
    between.setLeftInclusive(false);
    // At lower bound with exclusive: should be false
    Assert.assertEquals(false, eval(between, 1, Arrays.asList(1, "AND", 10)));
    // Just above lower bound: should be true
    Assert.assertEquals(true, eval(between, 2, Arrays.asList(1, "AND", 10)));
  }

  @Test
  public void testBetweenRightExclusive() {
    var between = new QueryOperatorBetween();
    between.setRightInclusive(false);
    // At upper bound with exclusive: should be false
    Assert.assertEquals(false, eval(between, 10, Arrays.asList(1, "AND", 10)));
    // Just below upper bound: should be true
    Assert.assertEquals(true, eval(between, 9, Arrays.asList(1, "AND", 10)));
  }

  @Test
  public void testBetweenStrings() {
    var between = new QueryOperatorBetween();
    Assert.assertEquals(true, eval(between, "m", Arrays.asList("a", "AND", "z")));
    // "m" < "n" lexicographically, so below range
    Assert.assertEquals(false, eval(between, "m", Arrays.asList("n", "AND", "z")));
  }

  @Test
  public void testBetweenCrossTypeNumeric() {
    // Integer value, Long boundaries — castComparableNumber handles this
    var between = new QueryOperatorBetween();
    Assert.assertEquals(true, eval(between, 5, Arrays.asList(1L, "AND", 10L)));
  }

  @Test
  public void testBetweenNullReturnsFalse() {
    var between = new QueryOperatorBetween();
    Assert.assertEquals(false, eval(between, null, Arrays.asList(1, "AND", 10)));
  }

  @Test
  public void testBetweenWrongSizeThrows() {
    var between = new QueryOperatorBetween();
    // Only 2 elements instead of 3 — should throw with BETWEEN syntax in message
    try {
      eval(between, 5, Arrays.asList(1, 10));
      Assert.fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      Assert.assertTrue(
          "Exception message should contain BETWEEN",
          e.getMessage().contains("BETWEEN"));
    }
  }

  @Test
  public void testBetweenBothExclusive() {
    var between = new QueryOperatorBetween();
    between.setLeftInclusive(false);
    between.setRightInclusive(false);
    // 5 is strictly between 1 and 10
    Assert.assertEquals(true, eval(between, 5, Arrays.asList(1, "AND", 10)));
    // At boundaries: strictly exclusive rejects both endpoints
    Assert.assertEquals(false, eval(between, 1, Arrays.asList(1, "AND", 10)));
    Assert.assertEquals(false, eval(between, 10, Arrays.asList(1, "AND", 10)));
  }

  @Test
  public void testBetweenInvertedRangeAlwaysFalse() {
    // Lower > upper: no value can satisfy both comparisons
    var between = new QueryOperatorBetween();
    Assert.assertEquals(false, eval(between, 5, Arrays.asList(10, "AND", 1)));
  }

  @Test
  public void testBetweenPointRangeInclusive() {
    // lower == upper, both inclusive: only the exact value matches
    var between = new QueryOperatorBetween();
    Assert.assertEquals(true, eval(between, 5, Arrays.asList(5, "AND", 5)));
    Assert.assertEquals(false, eval(between, 4, Arrays.asList(5, "AND", 5)));
  }

  @Test
  public void testBetweenSyntax() {
    Assert.assertEquals(
        "<left> BETWEEN <minRange> AND <maxRange>", new QueryOperatorBetween().getSyntax());
  }

  // ===== QueryOperatorIn =====

  @Test
  public void testInValueInList() {
    var in = new QueryOperatorIn();
    Assert.assertEquals(true, eval(in, 5, Arrays.asList(1, 5, 10)));
  }

  @Test
  public void testInValueNotInList() {
    var in = new QueryOperatorIn();
    Assert.assertEquals(false, eval(in, 7, Arrays.asList(1, 5, 10)));
  }

  @Test
  public void testInEmptyCollection() {
    var in = new QueryOperatorIn();
    // Empty right collection: recognized as multi-value but the iteration finds
    // no match, then falls through to iLeft.equals(iRight) which returns false
    Assert.assertEquals(false, eval(in, 5, Collections.emptyList()));
  }

  @Test
  public void testInValueInSet() {
    // Set path: uses Set.contains() for fast lookup
    var in = new QueryOperatorIn();
    var set = new HashSet<>(Arrays.asList(1, 5, 10));
    Assert.assertEquals(true, eval(in, 5, set));
  }

  @Test
  public void testInValueNotInSet() {
    var in = new QueryOperatorIn();
    var set = new HashSet<>(Arrays.asList(1, 5, 10));
    Assert.assertEquals(false, eval(in, 7, set));
  }

  @Test
  public void testInValueInArray() {
    var in = new QueryOperatorIn();
    Assert.assertEquals(true, eval(in, 5, new Object[] {1, 5, 10}));
  }

  @Test
  public void testInValueNotInArray() {
    var in = new QueryOperatorIn();
    Assert.assertEquals(false, eval(in, 7, new Object[] {1, 5, 10}));
  }

  @Test
  public void testInLeftIsList() {
    // Left is multi-value, right is single value
    var in = new QueryOperatorIn();
    Assert.assertEquals(true, eval(in, Arrays.asList(1, 5, 10), 5));
  }

  @Test
  public void testInLeftIsListRightIsCollection() {
    // Both multi-value: checks for intersection
    var in = new QueryOperatorIn();
    Assert.assertEquals(true, eval(in, Arrays.asList(1, 5), Arrays.asList(5, 10)));
  }

  @Test
  public void testInLeftIsListRightIsCollectionNoOverlap() {
    var in = new QueryOperatorIn();
    Assert.assertEquals(false, eval(in, Arrays.asList(1, 2), Arrays.asList(5, 10)));
  }

  @Test
  public void testInCrossType() {
    // Integer in Long list — QueryOperatorEquals.equals handles type conversion
    var in = new QueryOperatorIn();
    Assert.assertEquals(true, eval(in, 5, Arrays.asList(1L, 5L, 10L)));
  }

  @Test
  public void testInNullReturnsFalse() {
    var in = new QueryOperatorIn();
    Assert.assertEquals(false, eval(in, null, Arrays.asList(1, 5, 10)));
  }

  @Test
  public void testInStringInList() {
    var in = new QueryOperatorIn();
    Assert.assertEquals(true, eval(in, "hello", Arrays.asList("hello", "world")));
    Assert.assertEquals(false, eval(in, "foo", Arrays.asList("hello", "world")));
  }

  @Test
  public void testInLeftIsSetRightIsSingle() {
    // Left is Set, right is single → Set.contains path
    var in = new QueryOperatorIn();
    var set = new HashSet<>(Arrays.asList(1, 5, 10));
    Assert.assertEquals(true, eval(in, set, 5));
    Assert.assertEquals(false, eval(in, set, 7));
  }

  @Test
  public void testInCrossTypeSetBypassesCoercion() {
    // Set.contains() at line 152 bypasses QueryOperatorEquals type coercion.
    // Set<Long>.contains(Integer(5)) returns false because Integer.equals(Long) is always
    // false in Java — this is a pre-existing inconsistency where the Set path silently
    // produces different results than the iteration path (which uses equals() with coercion).
    var in = new QueryOperatorIn();
    var longSet = new HashSet<>(Arrays.asList(1L, 5L, 10L));
    Assert.assertEquals(false, eval(in, 5, longSet));
  }

  @Test
  public void testInScalarBothSides() {
    // When both operands are non-collection/non-array scalars, falls through to
    // iLeft.equals(iRight) at line 176
    var in = new QueryOperatorIn();
    Assert.assertEquals(true, eval(in, 5, 5));
    Assert.assertEquals(false, eval(in, 5, 6));
  }

  @Test
  public void testInIndexReuseType() {
    var in = new QueryOperatorIn();
    Assert.assertEquals(IndexReuseType.INDEX_METHOD, in.getIndexReuseType("a", "b"));
  }

  // ===== QueryOperatorEquals static method =====

  @Test
  public void testEqualsStaticNullBothReturnsFalse() {
    Assert.assertFalse(QueryOperatorEquals.equals(null, null, null));
  }

  @Test
  public void testEqualsStaticNullLeftReturnsFalse() {
    Assert.assertFalse(QueryOperatorEquals.equals(null, null, 10));
  }

  @Test
  public void testEqualsStaticNullRightReturnsFalse() {
    Assert.assertFalse(QueryOperatorEquals.equals(null, 10, null));
  }

  @Test
  public void testEqualsStaticSameReference() {
    var obj = "test";
    Assert.assertTrue(QueryOperatorEquals.equals(null, obj, obj));
  }

  @Test
  public void testEqualsStaticNumbersCrossType() {
    Assert.assertTrue(
        QueryOperatorEquals.equals(null, 10, 10L));
    Assert.assertTrue(
        QueryOperatorEquals.equals(null, new BigDecimal(10), 10));
  }

  @Test
  public void testEqualsStaticConversionFailureReturnsFalse() {
    // Incompatible types that can't be converted
    Assert.assertFalse(
        QueryOperatorEquals.equals(null, new Date(1000L), "not-a-date"));
  }
}
