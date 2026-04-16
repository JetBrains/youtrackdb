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
import com.jetbrains.youtrackdb.internal.core.sql.operator.math.QueryOperatorDivide;
import java.math.BigDecimal;
import java.util.Date;
import org.junit.Assert;
import org.junit.Test;

/** Tests for the SQL division operator across numeric types. */
public class QueryOperatorDivideTest {

  private final QueryOperator operator = new QueryOperatorDivide();

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
  public void testIntDivInt() {
    Assert.assertEquals(10 / 3, eval(10, 3));
  }

  @Test
  public void testLongDivLong() {
    Assert.assertEquals(10L / 3L, eval(10L, 3L));
  }

  @Test
  public void testFloatDivInt() {
    Assert.assertEquals(10.1f / 3f, eval(10.1f, 3));
  }

  @Test
  public void testIntDivFloat() {
    Assert.assertEquals(10f / 3.1f, eval(10, 3.1f));
  }

  @Test
  public void testDoubleDivInt() {
    Assert.assertEquals(10.1d / 3, eval(10.1d, 3));
  }

  @Test
  public void testIntDivDouble() {
    Assert.assertEquals(10 / 3.1d, eval(10, 3.1d));
  }

  @Test
  public void testBigDecimalDivInt() {
    Assert.assertEquals(
        new BigDecimal(10).divide(new BigDecimal(4)), eval(new BigDecimal(10), 4));
  }

  @Test
  public void testIntDivBigDecimal() {
    Assert.assertEquals(
        new BigDecimal(10).divide(new BigDecimal(4)), eval(10, new BigDecimal(4)));
  }

  // --- Short type combinations ---

  @Test
  public void testShortDivShort() {
    // Java widens short arithmetic to int, so result is Integer not Short
    Object result = eval((short) 10, (short) 3);
    Assert.assertTrue(result instanceof Integer);
    Assert.assertEquals(3, result);
  }

  // --- Float combinations ---

  @Test
  public void testFloatDivFloat() {
    Assert.assertEquals(10.0f / 3.0f, eval(10.0f, 3.0f));
  }

  // --- Date-to-long conversion ---

  @Test
  public void testDateDivLong() {
    long time = 1000L;
    Date date = new Date(time);
    Assert.assertEquals(time / 5L, eval(date, 5L));
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

  // --- Division by zero ---

  @Test(expected = ArithmeticException.class)
  public void testIntDivByZeroThrowsArithmeticException() {
    // Integer division by zero is not caught by the operator — throws ArithmeticException
    eval(10, 0);
  }

  @Test(expected = ArithmeticException.class)
  public void testLongDivByZeroThrowsArithmeticException() {
    eval(10L, 0L);
  }

  @Test(expected = ArithmeticException.class)
  public void testBigDecimalDivNonTerminatingThrowsArithmeticException() {
    // BigDecimal(10)/BigDecimal(3) = 3.333... is non-terminating.
    // Production code calls divide() without RoundingMode, so this throws.
    eval(new BigDecimal(10), new BigDecimal(3));
  }
}
