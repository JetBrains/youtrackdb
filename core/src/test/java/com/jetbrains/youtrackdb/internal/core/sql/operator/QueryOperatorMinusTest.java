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
import com.jetbrains.youtrackdb.internal.core.sql.operator.math.QueryOperatorMinus;
import java.math.BigDecimal;
import java.util.Date;
import org.junit.Assert;
import org.junit.Test;

/** Tests for the SQL subtraction operator across numeric types. */
public class QueryOperatorMinusTest {

  private final QueryOperator operator = new QueryOperatorMinus();

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
  public void testIntMinusInt() {
    Assert.assertEquals(0, eval(10, 10));
  }

  @Test
  public void testLongMinusLong() {
    Assert.assertEquals(0L, eval(10L, 10L));
  }

  @Test
  public void testIntUnderflowUpscalesToLong() {
    Assert.assertEquals(
        (long) Integer.MIN_VALUE - Integer.MAX_VALUE,
        eval(Integer.MIN_VALUE, Integer.MAX_VALUE));
  }

  @Test
  public void testFloatMinusInt() {
    Assert.assertEquals(10.1 - 10, eval(10.1, 10));
  }

  @Test
  public void testIntMinusFloat() {
    Assert.assertEquals(10 - 10.1, eval(10, 10.1));
  }

  @Test
  public void testDoubleMinusInt() {
    Assert.assertEquals(10.1d - 10, eval(10.1d, 10));
  }

  @Test
  public void testIntMinusDouble() {
    Assert.assertEquals(10 - 10.1d, eval(10, 10.1d));
  }

  @Test
  public void testBigDecimalMinusInt() {
    Assert.assertEquals(
        new BigDecimal(10).subtract(new BigDecimal(10)), eval(new BigDecimal(10), 10));
  }

  @Test
  public void testIntMinusBigDecimal() {
    Assert.assertEquals(
        new BigDecimal(10).subtract(new BigDecimal(10)), eval(10, new BigDecimal(10)));
  }

  // --- Short type combinations ---

  @Test
  public void testShortMinusShort() {
    // Java widens short arithmetic to int, so result is Integer not Short
    Object result = eval((short) 10, (short) 3);
    Assert.assertTrue(result instanceof Integer);
    Assert.assertEquals(7, result);
  }

  // --- Float combinations ---

  @Test
  public void testFloatMinusFloat() {
    Assert.assertEquals(10.5f - 3.5f, eval(10.5f, 3.5f));
  }

  // --- Date-to-long conversion ---

  @Test
  public void testDateMinusLong() {
    long time = 1000L;
    Date date = new Date(time);
    Assert.assertEquals(time - 500L, eval(date, 500L));
  }

  @Test
  public void testLongMinusDate() {
    long time = 500L;
    Date date = new Date(time);
    Assert.assertEquals(1000L - time, eval(1000L, date));
  }

  // --- Null propagation: Minus returns left when right is null ---

  @Test
  public void testNullRightReturnsLeft() {
    Assert.assertEquals(10, eval(10, null));
  }

  @Test
  public void testNullLeftNonNumericReturnsNull() {
    // left is null, right is 10 → null is not a Number, falls through to return null
    Assert.assertNull(eval(null, 10));
  }

  // --- Non-numeric returns null ---

  @Test
  public void testNonNumericReturnsNull() {
    Assert.assertNull(eval("hello", "world"));
  }
}
