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
import com.jetbrains.youtrackdb.internal.core.sql.operator.math.QueryOperatorPlus;
import java.math.BigDecimal;
import java.util.Date;
import org.junit.Assert;
import org.junit.Test;

/** Tests for the SQL addition operator across numeric types. */
public class QueryOperatorPlusTest {

  private final QueryOperator operator = new QueryOperatorPlus();

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
  public void testIntPlusInt() {
    Assert.assertEquals(10 + 10, eval(10, 10));
  }

  @Test
  public void testLongPlusLong() {
    Assert.assertEquals(10L + 10L, eval(10L, 10L));
  }

  @Test
  public void testIntOverflowUpscalesToLong() {
    Assert.assertEquals(
        (long) Integer.MAX_VALUE + Integer.MAX_VALUE, eval(Integer.MAX_VALUE, Integer.MAX_VALUE));
  }

  @Test
  public void testFloatPlusInt() {
    Assert.assertEquals(10.1 + 10, eval(10.1, 10));
  }

  @Test
  public void testIntPlusFloat() {
    Assert.assertEquals(10 + 10.1, eval(10, 10.1));
  }

  @Test
  public void testDoublePlusInt() {
    Assert.assertEquals(10.1d + 10, eval(10.1d, 10));
  }

  @Test
  public void testIntPlusDouble() {
    Assert.assertEquals(10 + 10.1d, eval(10, 10.1d));
  }

  @Test
  public void testBigDecimalPlusInt() {
    Assert.assertEquals(new BigDecimal(10).add(new BigDecimal(10)), eval(new BigDecimal(10), 10));
  }

  @Test
  public void testIntPlusBigDecimal() {
    Assert.assertEquals(
        new BigDecimal(10).add(new BigDecimal(120)), eval(10, new BigDecimal(120)));
  }

  // --- Short type combinations ---

  @Test
  public void testShortPlusShort() {
    // Java widens short arithmetic to int, so result is Integer not Short
    Object result = eval((short) 5, (short) 3);
    Assert.assertTrue(result instanceof Integer);
    Assert.assertEquals(8, result);
  }

  @Test
  public void testShortPlusInt() {
    // Int is higher precision than Short, so result is int math
    Assert.assertEquals(5 + 3, eval((short) 5, 3));
  }

  // --- String concatenation ---

  @Test
  public void testStringPlusInt() {
    // String left triggers concatenation path
    Assert.assertEquals("hello10", eval("hello", 10));
  }

  @Test
  public void testIntPlusString() {
    // String right triggers concatenation path
    Assert.assertEquals("10world", eval(10, "world"));
  }

  @Test
  public void testStringPlusString() {
    Assert.assertEquals("helloworld", eval("hello", "world"));
  }

  // --- Date-to-long conversion ---

  @Test
  public void testDatePlusLong() {
    long time = 1000L;
    Date date = new Date(time);
    // Date is converted to epoch millis, then treated as Long + Long
    Assert.assertEquals(time + 500L, eval(date, 500L));
  }

  @Test
  public void testLongPlusDate() {
    long time = 500L;
    Date date = new Date(time);
    Assert.assertEquals(1000L + time, eval(1000L, date));
  }

  // --- Null propagation: Plus returns the other operand when one is null ---

  @Test
  public void testNullLeftReturnsRight() {
    Assert.assertEquals(10, eval(null, 10));
  }

  @Test
  public void testNullRightReturnsLeft() {
    Assert.assertEquals(10, eval(10, null));
  }

  @Test
  public void testBothNullReturnsNull() {
    // null left → return right (null)
    Assert.assertNull(eval(null, null));
  }

  // --- Non-numeric, non-string returns null ---

  @Test
  public void testNonNumericNonStringReturnsNull() {
    // Two booleans — not Number and not String, so returns null
    Assert.assertNull(eval(true, false));
  }

  // --- Float combinations ---

  @Test
  public void testFloatPlusFloat() {
    Assert.assertEquals(2.5f + 3.5f, eval(2.5f, 3.5f));
  }
}
