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

import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.RecordSerializerBinary;
import com.jetbrains.youtrackdb.internal.core.sql.operator.math.QueryOperatorMod;
import java.math.BigDecimal;
import java.util.Date;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for the SQL modulo "%" operator across all numeric types, including Date-to-long
 * conversion, null propagation, BigDecimal remainder paths, and non-numeric fallback.
 */
public class QueryOperatorModTest {

  private final QueryOperator operator = new QueryOperatorMod();

  private Object eval(Object left, Object right) {
    return operator.evaluateRecord(
        null,
        null,
        null,
        left,
        right,
        null,
        RecordSerializerBinary.INSTANCE.getCurrentSerializer());
  }

  // --- Integer modulo ---

  @Test
  public void testIntegerModInteger() {
    Assert.assertEquals(10 % 3, eval(10, 3));
  }

  @Test
  public void testIntegerModIntegerExact() {
    // 10 % 5 == 0 — exact division
    Assert.assertEquals(0, eval(10, 5));
  }

  @Test
  public void testIntegerModNegativeDivisor() {
    // Java % preserves sign of dividend: 10 % -3 == 1
    Assert.assertEquals(10 % -3, eval(10, -3));
  }

  @Test
  public void testNegativeIntegerModPositive() {
    // -10 % 3 == -1
    Assert.assertEquals(-10 % 3, eval(-10, 3));
  }

  // --- Long modulo ---

  @Test
  public void testLongModLong() {
    Assert.assertEquals(10L % 3L, eval(10L, 3L));
  }

  @Test
  public void testLongModLongLargeValues() {
    Assert.assertEquals(Long.MAX_VALUE % 7L, eval(Long.MAX_VALUE, 7L));
  }

  // --- Short modulo ---

  @Test
  public void testShortModShort() {
    // Java widens short arithmetic to int, so result is Integer not Short
    Object result = eval((short) 10, (short) 3);
    Assert.assertTrue(result instanceof Integer);
    Assert.assertEquals(1, result);
  }

  // --- Float modulo ---

  @Test
  public void testFloatModFloat() {
    Assert.assertEquals(10.5f % 3.0f, eval(10.5f, 3.0f));
  }

  @Test
  public void testFloatModInteger() {
    // Left is Float, so Mod dispatches via the l instanceof Float branch,
    // computing l.floatValue() % r.floatValue()
    Assert.assertEquals(10.5f % 3, eval(10.5f, 3));
  }

  // --- Double modulo ---

  @Test
  public void testDoubleModDouble() {
    Assert.assertEquals(10.5d % 3.0d, eval(10.5d, 3.0d));
  }

  @Test
  public void testDoubleModInteger() {
    Assert.assertEquals(10.5d % 3, eval(10.5d, 3));
  }

  // --- BigDecimal remainder ---

  @Test
  public void testBigDecimalModBigDecimal() {
    Assert.assertEquals(
        new BigDecimal(10).remainder(new BigDecimal(3)),
        eval(new BigDecimal(10), new BigDecimal(3)));
  }

  @Test
  public void testBigDecimalModInteger() {
    Assert.assertEquals(
        new BigDecimal(10).remainder(new BigDecimal(3)), eval(new BigDecimal(10), 3));
  }

  @Test
  public void testBigDecimalModLong() {
    Assert.assertEquals(
        new BigDecimal(10).remainder(new BigDecimal(3L)), eval(new BigDecimal(10), 3L));
  }

  @Test
  public void testBigDecimalModShort() {
    Assert.assertEquals(
        new BigDecimal(10).remainder(new BigDecimal((short) 3)),
        eval(new BigDecimal(10), (short) 3));
  }

  @Test
  public void testBigDecimalModFloat() {
    Assert.assertEquals(
        new BigDecimal(10).remainder(BigDecimal.valueOf(3.0f)),
        eval(new BigDecimal(10), 3.0f));
  }

  @Test
  public void testBigDecimalModDouble() {
    Assert.assertEquals(
        new BigDecimal(10).remainder(BigDecimal.valueOf(3.0d)),
        eval(new BigDecimal(10), 3.0d));
  }

  // --- Date-to-long conversion ---

  @Test
  public void testDateLeftConvertedToLong() {
    // Date operands are converted to their epoch millis before modulo
    long time = 1000000L;
    Date date = new Date(time);
    Assert.assertEquals(time % 7L, eval(date, 7L));
  }

  @Test
  public void testDateRightConvertedToLong() {
    long time = 7L;
    Date date = new Date(time);
    Assert.assertEquals(1000000L % time, eval(1000000L, date));
  }

  @Test
  public void testBothDatesConvertedToLong() {
    long t1 = 1000000L;
    long t2 = 300000L;
    Assert.assertEquals(t1 % t2, eval(new Date(t1), new Date(t2)));
  }

  // --- Null propagation ---

  @Test
  public void testNullLeftReturnsNull() {
    Assert.assertNull(eval(null, 10));
  }

  @Test
  public void testNullRightReturnsNull() {
    Assert.assertNull(eval(10, null));
  }

  @Test
  public void testBothNullReturnsNull() {
    Assert.assertNull(eval(null, null));
  }

  // --- Non-numeric returns null ---

  @Test
  public void testNonNumericReturnsNull() {
    // String operands are not numbers — should return null
    Assert.assertNull(eval("hello", "world"));
  }

  @Test
  public void testStringLeftNumericRightReturnsNull() {
    Assert.assertNull(eval("hello", 10));
  }

  // --- Division by zero ---

  @Test(expected = ArithmeticException.class)
  public void testIntModByZeroThrowsArithmeticException() {
    // Integer modulo by zero is not caught by the operator — throws ArithmeticException
    eval(10, 0);
  }

  @Test(expected = ArithmeticException.class)
  public void testLongModByZeroThrowsArithmeticException() {
    eval(10L, 0L);
  }

  // --- Cross-type truncation (Mod dispatches on left type only) ---

  @Test
  public void testShortModLongTruncatesRight() {
    // Mod dispatches on left type only (unlike Plus/Minus/Multiply/Divide which use
    // getMaxPrecisionClass). When left is Short, r.shortValue() truncates the right operand
    // via 16-bit narrowing: 100000L narrows to short -31072, then 10 % -31072 = 10.
    //
    // WHEN-FIXED: when Mod adopts getMaxPrecisionClass like the other math ops, the right
    // operand will no longer be truncated and the result will be 10 % 100000L = 10L.
    // Update the expected value/type and delete this WHEN-FIXED block.
    Object result = eval((short) 10, 100000L);
    Assert.assertEquals("Documents short-left truncation of long right operand — revisit after fix",
        10, result);
    Assert.assertTrue("Java widens short arithmetic to int — result is Integer",
        result instanceof Integer);
  }

  // --- Index reuse and RID range ---

  @Test
  public void testGetIndexReuseTypeReturnsNoIndex() {
    Assert.assertEquals(IndexReuseType.NO_INDEX, operator.getIndexReuseType(10, 3));
  }

  @Test
  public void testGetBeginRidRangeReturnsNull() {
    Assert.assertNull(((QueryOperatorMod) operator).getBeginRidRange(null, 10, 3));
  }

  @Test
  public void testGetEndRidRangeReturnsNull() {
    Assert.assertNull(((QueryOperatorMod) operator).getEndRidRange(null, 10, 3));
  }
}
