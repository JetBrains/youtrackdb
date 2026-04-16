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
import com.jetbrains.youtrackdb.internal.core.sql.operator.math.QueryOperatorMultiply;
import java.math.BigDecimal;
import java.util.Date;
import org.junit.Assert;
import org.junit.Test;

/** Tests for the SQL multiplication operator across numeric types. */
public class QueryOperatorMultiplyTest {

  private final QueryOperator operator = new QueryOperatorMultiply();

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

  @Test
  public void testIntTimesInt() {
    Assert.assertEquals(100, eval(10, 10));
  }

  @Test
  public void testLongTimesLong() {
    Assert.assertEquals(100L, eval(10L, 10L));
  }

  @Test
  public void testIntOverflowUpscalesToLong() {
    Assert.assertEquals(100000000000L, eval(10000000, 10000));
  }

  @Test
  public void testFloatTimesInt() {
    Assert.assertEquals(10.1f * 10f, eval(10.1f, 10));
  }

  @Test
  public void testIntTimesFloat() {
    Assert.assertEquals(10f * 10.1f, eval(10, 10.1f));
  }

  @Test
  public void testDoubleTimesInt() {
    Assert.assertEquals(10.1d * 10, eval(10.1d, 10));
  }

  @Test
  public void testIntTimesDouble() {
    Assert.assertEquals(10 * 10.1d, eval(10, 10.1d));
  }

  @Test
  public void testBigDecimalTimesInt() {
    Assert.assertEquals(
        new BigDecimal(10).multiply(new BigDecimal(10)), eval(new BigDecimal(10), 10));
  }

  @Test
  public void testIntTimesBigDecimal() {
    Assert.assertEquals(
        new BigDecimal(10).multiply(new BigDecimal(10)), eval(10, new BigDecimal(10)));
  }

  // --- Short type combinations ---

  @Test
  public void testShortTimesShort() {
    // Java widens short arithmetic to int, so result is Integer not Short
    Object result = eval((short) 5, (short) 3);
    Assert.assertTrue(result instanceof Integer);
    Assert.assertEquals(15, result);
  }

  // --- Float combinations ---

  @Test
  public void testFloatTimesFloat() {
    Assert.assertEquals(2.5f * 3.0f, eval(2.5f, 3.0f));
  }

  // --- Date-to-long conversion ---

  @Test
  public void testDateTimesLong() {
    long time = 100L;
    Date date = new Date(time);
    Assert.assertEquals(time * 5L, eval(date, 5L));
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

  // --- Non-numeric returns null ---

  @Test
  public void testNonNumericReturnsNull() {
    Assert.assertNull(eval("hello", "world"));
  }

  // --- getMaxPrecisionClass utility method ---

  @Test
  public void testGetMaxPrecisionClassBigDecimalWins() {
    Assert.assertEquals(
        BigDecimal.class, QueryOperatorMultiply.getMaxPrecisionClass(new BigDecimal(1), 1));
  }

  @Test
  public void testGetMaxPrecisionClassDoubleWins() {
    Assert.assertEquals(Double.class, QueryOperatorMultiply.getMaxPrecisionClass(1.0d, 1));
  }

  @Test
  public void testGetMaxPrecisionClassFloatWins() {
    Assert.assertEquals(Float.class, QueryOperatorMultiply.getMaxPrecisionClass(1.0f, 1));
  }

  @Test
  public void testGetMaxPrecisionClassLongWins() {
    Assert.assertEquals(Long.class, QueryOperatorMultiply.getMaxPrecisionClass(1L, 1));
  }

  @Test
  public void testGetMaxPrecisionClassIntegerWins() {
    Assert.assertEquals(Integer.class, QueryOperatorMultiply.getMaxPrecisionClass(1, 1));
  }

  @Test
  public void testGetMaxPrecisionClassShortWins() {
    Assert.assertEquals(
        Short.class, QueryOperatorMultiply.getMaxPrecisionClass((short) 1, (short) 1));
  }

  @Test
  public void testGetMaxPrecisionClassHigherPrecisionOnRight() {
    // Verify symmetry: higher-precision type on the right side is also detected
    Assert.assertEquals(Double.class, QueryOperatorMultiply.getMaxPrecisionClass(1, 1.0d));
    Assert.assertEquals(Long.class, QueryOperatorMultiply.getMaxPrecisionClass((short) 1, 1L));
    Assert.assertEquals(
        BigDecimal.class, QueryOperatorMultiply.getMaxPrecisionClass(1, new BigDecimal(1)));
  }

  // --- tryDownscaleToInt ---

  @Test
  public void testTryDownscaleToIntWithinRange() {
    Object result = QueryOperatorMultiply.tryDownscaleToInt(100L);
    Assert.assertTrue(result instanceof Integer);
    Assert.assertEquals(100, result);
  }

  @Test
  public void testTryDownscaleToIntAboveRange() {
    long input = (long) Integer.MAX_VALUE + 1;
    Object result = QueryOperatorMultiply.tryDownscaleToInt(input);
    Assert.assertTrue(result instanceof Long);
    Assert.assertEquals(input, result);
  }

  @Test
  public void testTryDownscaleToIntBelowRange() {
    long input = (long) Integer.MIN_VALUE - 1;
    Object result = QueryOperatorMultiply.tryDownscaleToInt(input);
    Assert.assertTrue(result instanceof Long);
    Assert.assertEquals(input, result);
  }

  @Test
  public void testTryDownscaleToIntAtExactMaxValue() {
    // Integer.MAX_VALUE is a valid int but the production code uses strict < which
    // excludes this boundary — it stays as Long. Pre-existing off-by-one.
    Object result = QueryOperatorMultiply.tryDownscaleToInt((long) Integer.MAX_VALUE);
    Assert.assertTrue(result instanceof Long);
    Assert.assertEquals((long) Integer.MAX_VALUE, result);
  }

  @Test
  public void testTryDownscaleToIntAtExactMinValue() {
    // Integer.MIN_VALUE is a valid int but the production code uses strict > which
    // excludes this boundary — it stays as Long. Pre-existing off-by-one.
    Object result = QueryOperatorMultiply.tryDownscaleToInt((long) Integer.MIN_VALUE);
    Assert.assertTrue(result instanceof Long);
    Assert.assertEquals((long) Integer.MIN_VALUE, result);
  }

  // --- toBigDecimal utility ---

  @Test
  public void testToBigDecimalFromBigDecimal() {
    BigDecimal bd = new BigDecimal("3.14");
    Assert.assertSame(bd, QueryOperatorMultiply.toBigDecimal(bd));
  }

  @Test
  public void testToBigDecimalFromDouble() {
    Assert.assertEquals(
        BigDecimal.valueOf(3.14d), QueryOperatorMultiply.toBigDecimal(3.14d));
  }

  @Test
  public void testToBigDecimalFromFloat() {
    Assert.assertEquals(
        BigDecimal.valueOf(3.14f), QueryOperatorMultiply.toBigDecimal(3.14f));
  }

  @Test
  public void testToBigDecimalFromLong() {
    Assert.assertEquals(new BigDecimal(100L), QueryOperatorMultiply.toBigDecimal(100L));
  }

  @Test
  public void testToBigDecimalFromInteger() {
    Assert.assertEquals(new BigDecimal(100), QueryOperatorMultiply.toBigDecimal(100));
  }

  @Test
  public void testToBigDecimalFromShort() {
    Assert.assertEquals(new BigDecimal(10), QueryOperatorMultiply.toBigDecimal((short) 10));
  }
}
