/*
 *
 *  *  Copyright YouTrackDB
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
package com.jetbrains.youtrack.db.internal.core.sql.parser;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLMathExpression.Operator;
import java.math.BigDecimal;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class MathExpressionTest {

  @Test
  public void testTypes() {

    var expr = new SQLMathExpression(-1);

    var basicOps =
        new SQLMathExpression.Operator[]{
            SQLMathExpression.Operator.PLUS,
            SQLMathExpression.Operator.MINUS,
            SQLMathExpression.Operator.STAR,
            SQLMathExpression.Operator.SLASH,
            SQLMathExpression.Operator.REM
        };

    for (var op : basicOps) {
      Assert.assertEquals(op.apply(1, 1).getClass(), Integer.class);

      Assert.assertEquals(op.apply((short) 1, (short) 1).getClass(), Integer.class);

      Assert.assertEquals(op.apply(1L, 1L).getClass(), Long.class);
      Assert.assertEquals(op.apply(1f, 1f).getClass(), Float.class);
      Assert.assertEquals(op.apply(1d, 1d).getClass(), Double.class);
      Assert.assertEquals(op.apply(BigDecimal.ONE, BigDecimal.ONE).getClass(), BigDecimal.class);

      Assert.assertEquals(op.apply(1L, 1).getClass(), Long.class);
      Assert.assertEquals(op.apply(1f, 1).getClass(), Float.class);
      Assert.assertEquals(op.apply(1d, 1).getClass(), Double.class);
      Assert.assertEquals(op.apply(BigDecimal.ONE, 1).getClass(), BigDecimal.class);

      Assert.assertEquals(op.apply(1, 1L).getClass(), Long.class);
      Assert.assertEquals(op.apply(1, 1f).getClass(), Float.class);
      Assert.assertEquals(op.apply(1, 1d).getClass(), Double.class);
      Assert.assertEquals(op.apply(1, BigDecimal.ONE).getClass(), BigDecimal.class);
    }

    Assert.assertEquals(
        SQLMathExpression.Operator.PLUS.apply(Integer.MAX_VALUE, 1).getClass(), Long.class);
    Assert.assertEquals(
        SQLMathExpression.Operator.MINUS.apply(Integer.MIN_VALUE, 1).getClass(), Long.class);
  }

  @Test
  public void testPriority() {
    var exp = new SQLMathExpression(-1);
    exp.addChildExpression(integer(10));
    exp.addOperator(SQLMathExpression.Operator.PLUS);
    exp.addChildExpression(integer(5));
    exp.addOperator(SQLMathExpression.Operator.STAR);
    exp.addChildExpression(integer(8));
    exp.addOperator(SQLMathExpression.Operator.PLUS);
    exp.addChildExpression(integer(2));
    exp.addOperator(SQLMathExpression.Operator.LSHIFT);
    exp.addChildExpression(integer(1));
    exp.addOperator(SQLMathExpression.Operator.PLUS);
    exp.addChildExpression(integer(1));

    var result = exp.execute((Result) null, null);
    Assert.assertTrue(result instanceof Integer);
    Assert.assertEquals(208, result);
  }

  @Test
  public void testPriority2() {
    var exp = new SQLMathExpression(-1);
    exp.addChildExpression(integer(1));
    exp.addOperator(SQLMathExpression.Operator.PLUS);
    exp.addChildExpression(integer(2));
    exp.addOperator(SQLMathExpression.Operator.STAR);
    exp.addChildExpression(integer(3));
    exp.addOperator(SQLMathExpression.Operator.STAR);
    exp.addChildExpression(integer(4));
    exp.addOperator(SQLMathExpression.Operator.PLUS);
    exp.addChildExpression(integer(8));
    exp.addOperator(SQLMathExpression.Operator.RSHIFT);
    exp.addChildExpression(integer(2));
    exp.addOperator(SQLMathExpression.Operator.PLUS);
    exp.addChildExpression(integer(1));
    exp.addOperator(SQLMathExpression.Operator.MINUS);
    exp.addChildExpression(integer(3));
    exp.addOperator(SQLMathExpression.Operator.PLUS);
    exp.addChildExpression(integer(1));

    var result = exp.execute((Result) null, null);
    Assert.assertTrue(result instanceof Integer);
    Assert.assertEquals(16, result);
  }

  @Test
  public void testPriority3() {
    var exp = new SQLMathExpression(-1);
    exp.addChildExpression(integer(3));
    exp.addOperator(SQLMathExpression.Operator.RSHIFT);
    exp.addChildExpression(integer(1));
    exp.addOperator(SQLMathExpression.Operator.LSHIFT);
    exp.addChildExpression(integer(1));

    var result = exp.execute((Result) null, null);
    Assert.assertTrue(result instanceof Integer);
    Assert.assertEquals(2, result);
  }

  @Test
  public void testPriority4() {
    var exp = new SQLMathExpression(-1);
    exp.addChildExpression(integer(3));
    exp.addOperator(SQLMathExpression.Operator.LSHIFT);
    exp.addChildExpression(integer(1));
    exp.addOperator(SQLMathExpression.Operator.RSHIFT);
    exp.addChildExpression(integer(1));

    var result = exp.execute((Result) null, null);
    Assert.assertTrue(result instanceof Integer);
    Assert.assertEquals(3, result);
  }

  @Test
  public void testAnd() {
    var exp = new SQLMathExpression(-1);
    exp.addChildExpression(integer(5));
    exp.addOperator(SQLMathExpression.Operator.BIT_AND);
    exp.addChildExpression(integer(1));

    var result = exp.execute((Result) null, null);
    Assert.assertTrue(result instanceof Integer);
    Assert.assertEquals(1, result);
  }

  @Test
  public void testAnd2() {
    var exp = new SQLMathExpression(-1);
    exp.addChildExpression(integer(5));
    exp.addOperator(SQLMathExpression.Operator.BIT_AND);
    exp.addChildExpression(integer(4));

    var result = exp.execute((Result) null, null);
    Assert.assertTrue(result instanceof Integer);
    Assert.assertEquals(4, result);
  }

  @Test
  public void testOr() {
    var exp = new SQLMathExpression(-1);
    exp.addChildExpression(integer(4));
    exp.addOperator(SQLMathExpression.Operator.BIT_OR);
    exp.addChildExpression(integer(1));

    var result = exp.execute((Result) null, null);
    Assert.assertTrue(result instanceof Integer);
    Assert.assertEquals(5, result);
  }

  private SQLMathExpression integer(Number i) {
    var exp = new SQLBaseExpression(-1);
    var integer = new SQLInteger(-1);
    integer.setValue(i);
    exp.number = integer;
    return exp;
  }

  private SQLMathExpression str(String value) {
    final var exp = new SQLBaseExpression(-1);
    exp.string = "'" + value + "'";
    return exp;
  }

  private SQLMathExpression nullExpr() {
    return new SQLBaseExpression(-1);
  }

  @Test
  public void testNullCoalescing() {
    testNullCoalescingGeneric(integer(20), integer(15), 20);
    testNullCoalescingGeneric(nullExpr(), integer(14), 14);
    testNullCoalescingGeneric(str("32"), nullExpr(), "32");
    testNullCoalescingGeneric(str("2"), integer(5), "2");
    testNullCoalescingGeneric(nullExpr(), str("3"), "3");
  }

  private void testNullCoalescingGeneric(
      SQLMathExpression left, SQLMathExpression right, Object expected) {
    var exp = new SQLMathExpression(-1);
    exp.addChildExpression(left);
    exp.addOperator(Operator.NULL_COALESCING);
    exp.addChildExpression(right);

    var result = exp.execute((Result) null, null);
    //    Assert.assertTrue(result instanceof Integer);
    Assert.assertEquals(expected, result);
  }
}
