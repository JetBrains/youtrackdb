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
package com.jetbrains.youtrackdb.internal.core.sql.parser;

import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMathExpression.Operator;
import java.math.BigDecimal;
import java.util.Date;
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

    // The widened result must be the correct long, not the wrapped int. A regression that widened
    // after the int wrap (e.g. (long)(left + right)) would still return a Long but the wrong value.
    Assert.assertEquals(2147483648L, SQLMathExpression.Operator.PLUS.apply(Integer.MAX_VALUE, 1));
    Assert.assertEquals(
        -2147483649L, SQLMathExpression.Operator.MINUS.apply(Integer.MIN_VALUE, 1));
    // The non-overflow case must stay Integer (the upgrade branch must not fire spuriously).
    Assert.assertEquals(Integer.valueOf(3), SQLMathExpression.Operator.PLUS.apply(1, 2));
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

  // Characterization test for integer-divide widening (the promotion engine now lives in
  // NumericOps). A division whose remainder is zero keeps the operand's integer type; a division
  // with a non-zero remainder widens to Double. The existing testTypes only exercises 1/1 (exact),
  // so this pins the widening branch the engine performs.
  @Test
  public void testIntegerDivideWidening() {
    // Exact integer division keeps the integer type.
    Assert.assertEquals(Integer.valueOf(3), Operator.SLASH.apply(6, 2));
    Assert.assertEquals(Long.valueOf(3L), Operator.SLASH.apply(6L, 2L));

    // Non-exact integer division widens to Double.
    var intResult = Operator.SLASH.apply(7, 2);
    Assert.assertEquals(Double.class, intResult.getClass());
    Assert.assertEquals(3.5, intResult);

    var longResult = Operator.SLASH.apply(7L, 2L);
    Assert.assertEquals(Double.class, longResult.getClass());
    Assert.assertEquals(3.5, longResult);

    // Divide-by-zero diverges by operand type, and the divergence is invisible in the diff.
    // Integer/Long SLASH throw ArithmeticException (the `left % right == 0` exact-division check
    // evaluates `%` first, which throws on a zero divisor); BigDecimal SLASH throws too. Float and
    // Double SLASH do not throw; they yield Infinity / NaN. Pin all four so a future edit to the
    // exact-division shortcut cannot silently change which exception fires, or whether one fires.
    Assert.assertThrows(ArithmeticException.class, () -> Operator.SLASH.apply(1, 0));
    Assert.assertThrows(ArithmeticException.class, () -> Operator.SLASH.apply(1L, 0L));
    Assert.assertThrows(
        ArithmeticException.class, () -> Operator.SLASH.apply(BigDecimal.ONE, BigDecimal.ZERO));
    Assert.assertEquals(Float.POSITIVE_INFINITY, Operator.SLASH.apply(1f, 0f));
    Assert.assertTrue(((Double) Operator.SLASH.apply(0d, 0d)).isNaN());
  }

  // Characterization test for + - * / null propagation through Operator.apply(Object, Object).
  // PLUS and MINUS treat null as the additive identity (null + x = x, null - x = 0 - x); STAR and
  // SLASH propagate null (null * x = null, null / x = null). The existing suite drives these only
  // via DocValidationTest at the SQL level; this pins them directly on the engine's entry point.
  @Test
  public void testArithmeticNullPropagation() {
    // PLUS: null behaves as additive identity. The (Object) casts route to apply(Object, Object);
    // an untyped null would be ambiguous between the Double and BigDecimal typed overloads.
    Assert.assertEquals(5, Operator.PLUS.apply((Object) null, (Object) 5));
    Assert.assertEquals(5, Operator.PLUS.apply((Object) 5, (Object) null));
    Assert.assertNull(Operator.PLUS.apply((Object) null, (Object) null));

    // MINUS: x - null = x, null - x = 0 - x.
    Assert.assertEquals(5, Operator.MINUS.apply((Object) 5, (Object) null));
    Assert.assertEquals(-5, Operator.MINUS.apply((Object) null, (Object) 5));
    Assert.assertNull(Operator.MINUS.apply((Object) null, (Object) null));

    // STAR and SLASH propagate null (any null operand yields null).
    Assert.assertNull(Operator.STAR.apply((Object) null, (Object) 5));
    Assert.assertNull(Operator.STAR.apply((Object) 5, (Object) null));
    Assert.assertNull(Operator.SLASH.apply((Object) null, (Object) 5));
    Assert.assertNull(Operator.SLASH.apply((Object) 5, (Object) null));
  }

  // Characterization test for Date arithmetic. Date + Long and Long + Date both yield a Date whose
  // epoch is the sum; Date - Long yields a Date whose epoch is the difference. Date arithmetic is
  // driven elsewhere only through QueryOperatorPlus/Minus (a separate operator implementation that
  // never touches SQLMathExpression.Operator), so this pins the lifted engine's Date branch.
  @Test
  public void testDateArithmetic() {
    var base = new Date(1_000L);

    var plusRight = Operator.PLUS.apply(base, 500L);
    Assert.assertEquals(Date.class, plusRight.getClass());
    Assert.assertEquals(1_500L, ((Date) plusRight).getTime());
    // The engine builds a fresh Date; the operand must be neither mutated nor handed back.
    Assert.assertEquals(1_000L, base.getTime());
    Assert.assertNotSame(base, plusRight);

    var plusLeft = Operator.PLUS.apply(500L, base);
    Assert.assertEquals(Date.class, plusLeft.getClass());
    Assert.assertEquals(1_500L, ((Date) plusLeft).getTime());
    Assert.assertEquals(1_000L, base.getTime());
    Assert.assertNotSame(base, plusLeft);

    var minus = Operator.MINUS.apply(base, 250L);
    Assert.assertEquals(Date.class, minus.getClass());
    Assert.assertEquals(750L, ((Date) minus).getTime());
    Assert.assertEquals(1_000L, base.getTime());
    Assert.assertNotSame(base, minus);
  }

  // Characterization test for String concatenation through PLUS. When either operand is a String,
  // PLUS concatenates with the other operand toString()-ed rather than performing arithmetic.
  @Test
  public void testStringConcatenation() {
    Assert.assertEquals("ab", Operator.PLUS.apply("a", "b"));
    Assert.assertEquals("a1", Operator.PLUS.apply("a", 1));
    Assert.assertEquals("1b", Operator.PLUS.apply(1, "b"));
  }

  // Characterization test pinning a deliberate divergence from the pre-lift enum: a Short left
  // operand against a BigDecimal right operand. The old widening code was `new BigDecimal((Integer)
  // a)`, which threw ClassCastException for a Short (reachable via a `shortField op decimalField`
  // arithmetic expression). The lifted form widens via `a.intValue()`, which handles both Integer
  // and Short, so Short + BigDecimal now computes a value. This pins the improved behavior so the
  // path is no longer untested. The symmetric BigDecimal + Short case always computed; it is
  // confirmed unchanged.
  @Test
  public void testShortWithBigDecimalWidens() {
    Assert.assertEquals(new BigDecimal(5), Operator.PLUS.apply((short) 2, BigDecimal.valueOf(3)));
    Assert.assertEquals(new BigDecimal(6), Operator.STAR.apply((short) 2, BigDecimal.valueOf(3)));
    // Symmetric direction (BigDecimal left, Short right) computed before and after the lift.
    Assert.assertEquals(new BigDecimal(5), Operator.PLUS.apply(BigDecimal.valueOf(2), (short) 3));
  }

  // Characterization test pinning a deliberate exception-type change from the pre-lift enum on an
  // out-of-scope Date error path. When one operand is a Date and the other is neither a Number nor
  // a Date (e.g. a String), `toLong` returns null for the non-Date operand. The old Date branch
  // called the typed apply(Long, Long) overload, which threw NullPointerException on the null
  // unboxing; the lifted Date branch routes through the widening entry, whose null guard throws
  // IllegalArgumentException instead. Normal Date +/- Long is unaffected (covered by
  // testDateArithmetic). This pins the cleaner IAE so the NPE->IAE shift is not treated as
  // accidental by a future reader.
  @Test
  public void testDateWithNonNumericThrowsIllegalArgument() {
    var date = new Date(1_000L);
    Assert.assertThrows(IllegalArgumentException.class, () -> Operator.PLUS.apply(date, "x"));
    Assert.assertThrows(IllegalArgumentException.class, () -> Operator.MINUS.apply(date, "x"));
  }
}
