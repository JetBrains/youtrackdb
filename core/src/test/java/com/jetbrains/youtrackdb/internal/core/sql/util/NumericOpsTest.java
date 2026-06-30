package com.jetbrains.youtrackdb.internal.core.sql.util;

import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMathExpression.Operator;
import java.math.BigDecimal;
import java.util.Date;
import org.junit.Assert;
import org.junit.Test;

/**
 * Characterization tests for {@link NumericOps}, the shared numeric-promotion engine lifted out of
 * {@code SQLMathExpression.Operator}.
 *
 * <p>This is an exact-preservation lift: every assertion below pins what the engine does today
 * (result type AND value), not what it arguably should do. Where an arm's behavior is surprising —
 * floating-point {@code BIT_AND} truncating through {@code long}, shift/bitwise on
 * {@code Float}/{@code Double}/{@code BigDecimal} yielding {@code null}, integer-divide widening to
 * {@code Double}, the deliberate exception-type shifts on out-of-scope paths — the test comment says
 * so and asserts the behavior as-is. None of these tests is a "fix"; they exist so a future edit to
 * the engine cannot silently change a result type, a value, or which exception fires.
 *
 * <p>The arms are driven through the public {@code Operator.apply(...)} surface, which delegates
 * straight into {@code NumericOps}, mirroring the {@code testTypes} idiom in {@code
 * MathExpressionTest}. The five typed overloads reach {@code NumericOps.apply(Operator, T, T)}; the
 * {@code (Object, Object)} overloads reach the object-level entry points
 * ({@code applyObject}/{@code plusObject}/{@code minusObject}/{@code xorObject}/{@code
 * bitOrObject}/{@code nullCoalescingObject}). {@code (Object)} casts are used where an untyped
 * literal would be ambiguous between the typed overloads.
 */
public class NumericOpsTest {

  // ---------------------------------------------------------------------------------------------
  // Typed-pair arms: Integer
  // ---------------------------------------------------------------------------------------------

  // Pins every operator arm of NumericOps.apply(Operator, Integer, Integer). The arithmetic ops
  // return Integer; the Integer-overflow specials upgrade to Long; SLASH widens to Double on a
  // non-zero remainder. Shifts and bitwise ops compute integral results directly.
  @Test
  public void testIntegerTypedArms() {
    Assert.assertEquals(Integer.valueOf(12), Operator.STAR.apply(3, 4));
    // Exact divide keeps Integer; inexact divide widens to Double (pinned here at the typed level).
    Assert.assertEquals(Integer.valueOf(2), Operator.SLASH.apply(8, 4));
    Assert.assertEquals(Double.valueOf(3.5), Operator.SLASH.apply(7, 2));
    Assert.assertEquals(Integer.valueOf(1), Operator.REM.apply(7, 2));
    Assert.assertEquals(Integer.valueOf(7), Operator.PLUS.apply(3, 4));
    Assert.assertEquals(Integer.valueOf(-1), Operator.MINUS.apply(3, 4));

    // Overflow/underflow specials upgrade the result to Long.
    Assert.assertEquals(Long.class, Operator.PLUS.apply(Integer.MAX_VALUE, 1).getClass());
    Assert.assertEquals(2147483648L, Operator.PLUS.apply(Integer.MAX_VALUE, 1));
    Assert.assertEquals(Long.class, Operator.MINUS.apply(Integer.MIN_VALUE, 1).getClass());
    Assert.assertEquals(-2147483649L, Operator.MINUS.apply(Integer.MIN_VALUE, 1));

    // Shifts and bitwise ops on Integer.
    Assert.assertEquals(Integer.valueOf(8), Operator.LSHIFT.apply(1, 3));
    Assert.assertEquals(Integer.valueOf(2), Operator.RSHIFT.apply(8, 2));
    // Unsigned right shift of a negative differs from RSHIFT: it fills with zeros.
    Assert.assertEquals(Integer.valueOf(1073741823), Operator.RUNSIGNEDSHIFT.apply(-4, 2));
    Assert.assertEquals(Integer.valueOf(4), Operator.BIT_AND.apply(6, 5));
    Assert.assertEquals(Integer.valueOf(6), Operator.XOR.apply(3, 5));
    Assert.assertEquals(Integer.valueOf(7), Operator.BIT_OR.apply(3, 5));
  }

  // Pins the NULL_COALESCING arm of the Integer typed method. The arm returns the left operand when
  // non-null. Reached through the typed overload (both operands non-null Integers here), the arm
  // returns left because left != null.
  @Test
  public void testIntegerNullCoalescingArm() {
    Assert.assertEquals(Integer.valueOf(3), Operator.NULL_COALESCING.apply(3, 4));
  }

  // ---------------------------------------------------------------------------------------------
  // Typed-pair arms: Long
  // ---------------------------------------------------------------------------------------------

  // Pins every operator arm of NumericOps.apply(Operator, Long, Long). Mirrors the Integer form but
  // without the overflow upgrade (Long already absorbs the Integer-overflow cases).
  @Test
  public void testLongTypedArms() {
    Assert.assertEquals(Long.valueOf(12L), Operator.STAR.apply(3L, 4L));
    Assert.assertEquals(Long.valueOf(2L), Operator.SLASH.apply(8L, 4L));
    Assert.assertEquals(Double.valueOf(3.5), Operator.SLASH.apply(7L, 2L));
    Assert.assertEquals(Long.valueOf(1L), Operator.REM.apply(7L, 2L));
    Assert.assertEquals(Long.valueOf(7L), Operator.PLUS.apply(3L, 4L));
    Assert.assertEquals(Long.valueOf(-1L), Operator.MINUS.apply(3L, 4L));
    Assert.assertEquals(Long.valueOf(8L), Operator.LSHIFT.apply(1L, 3L));
    Assert.assertEquals(Long.valueOf(2L), Operator.RSHIFT.apply(8L, 2L));
    Assert.assertEquals(
        Long.valueOf(4611686018427387903L), Operator.RUNSIGNEDSHIFT.apply(-4L, 2L));
    Assert.assertEquals(Long.valueOf(4L), Operator.BIT_AND.apply(6L, 5L));
    Assert.assertEquals(Long.valueOf(6L), Operator.XOR.apply(3L, 5L));
    Assert.assertEquals(Long.valueOf(7L), Operator.BIT_OR.apply(3L, 5L));
  }

  // Pins the NULL_COALESCING arm of the Long typed method (returns left when non-null).
  @Test
  public void testLongNullCoalescingArm() {
    Assert.assertEquals(Long.valueOf(3L), Operator.NULL_COALESCING.apply(3L, 4L));
  }

  // ---------------------------------------------------------------------------------------------
  // Typed-pair arms: Float
  // ---------------------------------------------------------------------------------------------

  // Pins every operator arm of NumericOps.apply(Operator, Float, Float). Arithmetic ops compute in
  // float; SLASH does NOT widen (it is plain float division, unlike integer SLASH); shift, XOR, and
  // BIT_OR are undefined on floating-point and return null; BIT_AND falls back to a Long bitwise op
  // through the integral (longValue) operands. The last three are surprising and pinned as-is.
  @Test
  public void testFloatTypedArms() {
    Assert.assertEquals(Float.valueOf(12f), Operator.STAR.apply(3f, 4f));
    // Float SLASH is plain float division — no integer-style widening to Double.
    Assert.assertEquals(Float.valueOf(3.5f), Operator.SLASH.apply(7f, 2f));
    Assert.assertEquals(Float.class, Operator.SLASH.apply(7f, 2f).getClass());
    Assert.assertEquals(Float.valueOf(1f), Operator.REM.apply(7f, 2f));
    Assert.assertEquals(Float.valueOf(7f), Operator.PLUS.apply(3f, 4f));
    Assert.assertEquals(Float.valueOf(-1f), Operator.MINUS.apply(3f, 4f));

    // Shift and bitwise-OR/XOR are undefined on float and return null.
    Assert.assertNull(Operator.LSHIFT.apply(8f, 1f));
    Assert.assertNull(Operator.RSHIFT.apply(8f, 1f));
    Assert.assertNull(Operator.RUNSIGNEDSHIFT.apply(8f, 1f));
    Assert.assertNull(Operator.XOR.apply(3f, 5f));
    Assert.assertNull(Operator.BIT_OR.apply(3f, 5f));

    // BIT_AND truncates both floats to long and ANDs the integral values: (6 & 5) == 4 as Long.
    var floatAnd = Operator.BIT_AND.apply(6.9f, 5.9f);
    Assert.assertEquals(Long.class, floatAnd.getClass());
    Assert.assertEquals(4L, floatAnd);
  }

  // Pins the NULL_COALESCING arm of the Float typed method (returns left when non-null).
  @Test
  public void testFloatNullCoalescingArm() {
    Assert.assertEquals(Float.valueOf(3f), Operator.NULL_COALESCING.apply(3f, 4f));
  }

  // ---------------------------------------------------------------------------------------------
  // Typed-pair arms: Double
  // ---------------------------------------------------------------------------------------------

  // Pins every operator arm of NumericOps.apply(Operator, Double, Double). Same shape as the Float
  // form: arithmetic computes in double, SLASH is plain double division, shift/XOR/BIT_OR return
  // null, BIT_AND falls back through Long. Pinned exactly so a future edit cannot drift the Double
  // arm away from the Float arm.
  @Test
  public void testDoubleTypedArms() {
    Assert.assertEquals(Double.valueOf(12d), Operator.STAR.apply(3d, 4d));
    Assert.assertEquals(Double.valueOf(3.5d), Operator.SLASH.apply(7d, 2d));
    Assert.assertEquals(Double.valueOf(1d), Operator.REM.apply(7d, 2d));
    Assert.assertEquals(Double.valueOf(7d), Operator.PLUS.apply(3d, 4d));
    Assert.assertEquals(Double.valueOf(-1d), Operator.MINUS.apply(3d, 4d));

    Assert.assertNull(Operator.LSHIFT.apply(8d, 1d));
    Assert.assertNull(Operator.RSHIFT.apply(8d, 1d));
    Assert.assertNull(Operator.RUNSIGNEDSHIFT.apply(8d, 1d));
    Assert.assertNull(Operator.XOR.apply(3d, 5d));
    Assert.assertNull(Operator.BIT_OR.apply(3d, 5d));

    var doubleAnd = Operator.BIT_AND.apply(6.9d, 5.9d);
    Assert.assertEquals(Long.class, doubleAnd.getClass());
    Assert.assertEquals(4L, doubleAnd);
  }

  // Pins the NULL_COALESCING arm of the Double typed method (returns left when non-null).
  @Test
  public void testDoubleNullCoalescingArm() {
    Assert.assertEquals(Double.valueOf(3d), Operator.NULL_COALESCING.apply(3d, 4d));
  }

  // ---------------------------------------------------------------------------------------------
  // Typed-pair arms: BigDecimal
  // ---------------------------------------------------------------------------------------------

  // Pins every operator arm of NumericOps.apply(Operator, BigDecimal, BigDecimal). Arithmetic uses
  // BigDecimal's own methods; SLASH rounds HALF_UP; REM uses remainder(); every shift and bitwise
  // operator is undefined on arbitrary-precision decimals and returns null.
  @Test
  public void testBigDecimalTypedArms() {
    Assert.assertEquals(
        new BigDecimal(12), Operator.STAR.apply(new BigDecimal(3), new BigDecimal(4)));
    Assert.assertEquals(
        new BigDecimal(2), Operator.SLASH.apply(new BigDecimal(8), new BigDecimal(4)));
    // SLASH rounds HALF_UP: 7/2 == 4 (3.5 rounds up to 4 at scale 0, the left operand's scale).
    Assert.assertEquals(
        new BigDecimal(4), Operator.SLASH.apply(new BigDecimal(7), new BigDecimal(2)));
    Assert.assertEquals(
        new BigDecimal(1), Operator.REM.apply(new BigDecimal(7), new BigDecimal(2)));
    Assert.assertEquals(
        new BigDecimal(7), Operator.PLUS.apply(new BigDecimal(3), new BigDecimal(4)));
    Assert.assertEquals(
        new BigDecimal(-1), Operator.MINUS.apply(new BigDecimal(3), new BigDecimal(4)));

    Assert.assertNull(Operator.LSHIFT.apply(new BigDecimal(8), new BigDecimal(1)));
    Assert.assertNull(Operator.RSHIFT.apply(new BigDecimal(8), new BigDecimal(1)));
    Assert.assertNull(Operator.RUNSIGNEDSHIFT.apply(new BigDecimal(8), new BigDecimal(1)));
    Assert.assertNull(Operator.BIT_AND.apply(new BigDecimal(6), new BigDecimal(5)));
    Assert.assertNull(Operator.XOR.apply(new BigDecimal(3), new BigDecimal(5)));
    Assert.assertNull(Operator.BIT_OR.apply(new BigDecimal(3), new BigDecimal(5)));
  }

  // Pins HALF_UP rounding at a fractional scale: dividing values that carry a fractional scale shows
  // the rounding mode in effect rather than truncation. 10.0 / 3 at scale 1 rounds 3.33... to 3.3.
  @Test
  public void testBigDecimalDivideRoundsHalfUp() {
    var result = Operator.SLASH.apply(new BigDecimal("10.0"), new BigDecimal("3"));
    Assert.assertEquals(new BigDecimal("3.3"), result);
  }

  // Pins the NULL_COALESCING arm of the BigDecimal typed method (returns left when non-null).
  @Test
  public void testBigDecimalNullCoalescingArm() {
    Assert.assertEquals(
        new BigDecimal(3), Operator.NULL_COALESCING.apply(new BigDecimal(3), new BigDecimal(4)));
  }

  // ---------------------------------------------------------------------------------------------
  // Widening entry: every mixed-type left/right promotion arm
  // ---------------------------------------------------------------------------------------------

  // Pins the Integer/Short left operand against each right-operand type in the widening entry
  // NumericOps.apply(Number, Operator, Number). A Short left operand exercises the same branch as
  // Integer (the branch admits both); the right operand drives the common-type selection. Result
  // types: Integer (int op int), Long (int op long), Float (int op float), Double (int op double),
  // BigDecimal (int op decimal).
  @Test
  public void testWideningIntegerLeftAgainstEachRight() {
    Assert.assertEquals(7, NumericOps.apply(3, Operator.PLUS, 4));
    Assert.assertEquals(7L, NumericOps.apply(3, Operator.PLUS, 4L));
    Assert.assertEquals(7f, NumericOps.apply(3, Operator.PLUS, 4f));
    Assert.assertEquals(7d, NumericOps.apply(3, Operator.PLUS, 4d));
    Assert.assertEquals(new BigDecimal(7), NumericOps.apply(3, Operator.PLUS, new BigDecimal(4)));
    // Short left routes through the same Integer/Short branch as a plain Integer left.
    Assert.assertEquals(7, NumericOps.apply((short) 3, Operator.PLUS, 4));
    Assert.assertEquals(
        new BigDecimal(7), NumericOps.apply((short) 3, Operator.PLUS, new BigDecimal(4)));
  }

  // Pins the Long left operand against each right-operand type. Integer/Long/Short right all widen
  // to Long; Float right widens to Float; Double right widens to Double; BigDecimal right widens to
  // BigDecimal.
  @Test
  public void testWideningLongLeftAgainstEachRight() {
    Assert.assertEquals(7L, NumericOps.apply(3L, Operator.PLUS, 4));
    Assert.assertEquals(7L, NumericOps.apply(3L, Operator.PLUS, 4L));
    Assert.assertEquals(7L, NumericOps.apply(3L, Operator.PLUS, (short) 4));
    Assert.assertEquals(7f, NumericOps.apply(3L, Operator.PLUS, 4f));
    Assert.assertEquals(7d, NumericOps.apply(3L, Operator.PLUS, 4d));
    Assert.assertEquals(new BigDecimal(7), NumericOps.apply(3L, Operator.PLUS, new BigDecimal(4)));
  }

  // Pins the Float left operand against each right-operand type. Short/Integer/Long/Float right all
  // compute in Float; Double right widens to Double; BigDecimal right widens to BigDecimal (via
  // BigDecimal.valueOf on the float).
  @Test
  public void testWideningFloatLeftAgainstEachRight() {
    Assert.assertEquals(7f, NumericOps.apply(3f, Operator.PLUS, (short) 4));
    Assert.assertEquals(7f, NumericOps.apply(3f, Operator.PLUS, 4));
    Assert.assertEquals(7f, NumericOps.apply(3f, Operator.PLUS, 4L));
    Assert.assertEquals(7f, NumericOps.apply(3f, Operator.PLUS, 4f));
    Assert.assertEquals(7d, NumericOps.apply(3f, Operator.PLUS, 4d));
    Assert.assertEquals(
        new BigDecimal("7.0"), NumericOps.apply(3f, Operator.PLUS, new BigDecimal(4)));
  }

  // Pins the Double left operand against each right-operand type. Short/Integer/Long/Float/Double
  // right all compute in Double; BigDecimal right widens to BigDecimal (via BigDecimal.valueOf on
  // the double).
  @Test
  public void testWideningDoubleLeftAgainstEachRight() {
    Assert.assertEquals(7d, NumericOps.apply(3d, Operator.PLUS, (short) 4));
    Assert.assertEquals(7d, NumericOps.apply(3d, Operator.PLUS, 4));
    Assert.assertEquals(7d, NumericOps.apply(3d, Operator.PLUS, 4L));
    Assert.assertEquals(7d, NumericOps.apply(3d, Operator.PLUS, 4f));
    Assert.assertEquals(7d, NumericOps.apply(3d, Operator.PLUS, 4d));
    Assert.assertEquals(
        new BigDecimal("7.0"), NumericOps.apply(3d, Operator.PLUS, new BigDecimal(4)));
  }

  // Pins the BigDecimal left operand against each right-operand type. Every right type widens to a
  // BigDecimal result. Integer/Short right go through new BigDecimal(intValue); Long right through
  // new BigDecimal(longValue); Float/Double right through BigDecimal.valueOf; BigDecimal right is
  // used directly.
  @Test
  public void testWideningBigDecimalLeftAgainstEachRight() {
    Assert.assertEquals(new BigDecimal(7), NumericOps.apply(new BigDecimal(3), Operator.PLUS, 4));
    Assert.assertEquals(new BigDecimal(7), NumericOps.apply(new BigDecimal(3), Operator.PLUS, 4L));
    Assert.assertEquals(
        new BigDecimal(7), NumericOps.apply(new BigDecimal(3), Operator.PLUS, (short) 4));
    Assert.assertEquals(
        new BigDecimal("7.0"), NumericOps.apply(new BigDecimal(3), Operator.PLUS, 4f));
    Assert.assertEquals(
        new BigDecimal("7.0"), NumericOps.apply(new BigDecimal(3), Operator.PLUS, 4d));
    Assert.assertEquals(
        new BigDecimal(7), NumericOps.apply(new BigDecimal(3), Operator.PLUS, new BigDecimal(4)));
  }

  // Pins the widening entry's two throw paths. A null operand (either side) throws
  // IllegalArgumentException with the "increment a null value" message; an unsupported runtime type
  // (here a Number subclass the dispatch does not handle, java.util.concurrent.atomic.AtomicInteger)
  // falls through every branch to the final "Cannot increment value" IllegalArgumentException.
  @Test
  public void testWideningThrowPaths() {
    Assert.assertThrows(
        IllegalArgumentException.class, () -> NumericOps.apply(null, Operator.PLUS, 1));
    Assert.assertThrows(
        IllegalArgumentException.class, () -> NumericOps.apply(1, Operator.PLUS, null));
    // An unhandled Number subclass falls through to the terminal throw.
    Number unhandled = new java.util.concurrent.atomic.AtomicInteger(1);
    Assert.assertThrows(
        IllegalArgumentException.class, () -> NumericOps.apply(unhandled, Operator.PLUS, 1));
    Assert.assertThrows(
        IllegalArgumentException.class, () -> NumericOps.apply(1, Operator.PLUS, unhandled));
  }

  // ---------------------------------------------------------------------------------------------
  // Object-level entry points
  // ---------------------------------------------------------------------------------------------

  // Pins NumericOps.applyObject (the lifted base apply(Object, Object)): null left returns right,
  // null right returns left, two Numbers widen through the engine, and any non-Number/non-null pair
  // returns null. STAR is used so the per-constant apply(Object, Object) does not intercept (STAR's
  // own body forwards straight to applyObject).
  @Test
  public void testApplyObjectBaseEntry() {
    Assert.assertEquals(5, NumericOps.applyObject(Operator.STAR, null, 5));
    Assert.assertEquals(5, NumericOps.applyObject(Operator.STAR, 5, null));
    Assert.assertEquals(20, NumericOps.applyObject(Operator.STAR, 4, 5));
    // Two non-Number, non-null operands are unsupported and return null.
    Assert.assertNull(NumericOps.applyObject(Operator.STAR, "a", "b"));
  }

  // Pins NumericOps.plusObject, the lifted PLUS apply(Object, Object). Covers: both-null short
  // circuit to null, null-left returns right, null-right returns left, numeric addition through the
  // engine, Date + Long / Long + Date producing a Date, and String concatenation when neither
  // numeric nor Date matched.
  @Test
  public void testPlusObjectAllBranches() {
    Assert.assertNull(NumericOps.plusObject(null, null));
    Assert.assertEquals(5, NumericOps.plusObject(null, 5));
    Assert.assertEquals(5, NumericOps.plusObject(5, null));
    Assert.assertEquals(9, NumericOps.plusObject(4, 5));

    var date = new Date(1_000L);
    var dateLeft = NumericOps.plusObject(date, 500L);
    Assert.assertEquals(Date.class, dateLeft.getClass());
    Assert.assertEquals(1_500L, ((Date) dateLeft).getTime());
    var dateRight = NumericOps.plusObject(500L, date);
    Assert.assertEquals(1_500L, ((Date) dateRight).getTime());

    // Neither numeric nor Date: String concatenation with the other operand toString()-ed.
    Assert.assertEquals("a1", NumericOps.plusObject("a", 1));
    Assert.assertEquals("1b", NumericOps.plusObject(1, "b"));
    Assert.assertEquals("ab", NumericOps.plusObject("a", "b"));
  }

  // Pins NumericOps.minusObject, the lifted MINUS apply(Object, Object). Covers: both-null returns
  // null, Number-left/null-right returns the left operand, null-left/Number-right returns 0 - right,
  // numeric subtraction through the engine, Date - Long producing a Date, and the fall-through null
  // for a non-Number/non-Date pair (two Strings).
  @Test
  public void testMinusObjectAllBranches() {
    Assert.assertNull(NumericOps.minusObject(null, null));
    Assert.assertEquals(5, NumericOps.minusObject(5, null));
    Assert.assertEquals(-5, NumericOps.minusObject(null, 5));
    Assert.assertEquals(-1, NumericOps.minusObject(4, 5));

    var date = new Date(1_000L);
    var minusDate = NumericOps.minusObject(date, 250L);
    Assert.assertEquals(Date.class, minusDate.getClass());
    Assert.assertEquals(750L, ((Date) minusDate).getTime());

    // Neither numeric nor Date and not the identity cases: falls through to the null result.
    Assert.assertNull(NumericOps.minusObject("a", "b"));
  }

  // Pins NumericOps.xorObject, the lifted XOR apply(Object, Object). Covers: both-null returns null,
  // Number-left/null-right treats null as 0 (left ^ 0 == left), null-left/Number-right treats null
  // as 0 (0 ^ right == right), two Numbers go through the engine, and a non-Number pair returns
  // null.
  @Test
  public void testXorObjectAllBranches() {
    Assert.assertNull(NumericOps.xorObject(null, null));
    Assert.assertEquals(3, NumericOps.xorObject(3, null));
    Assert.assertEquals(5, NumericOps.xorObject(null, 5));
    Assert.assertEquals(6, NumericOps.xorObject(3, 5));
    Assert.assertNull(NumericOps.xorObject("a", "b"));
  }

  // Pins NumericOps.bitOrObject, the lifted BIT_OR apply(Object, Object). Covers: both-null short
  // circuit to null, null operands coerced to 0 then OR-ed through the base object promotion, and a
  // two-Number OR.
  @Test
  public void testBitOrObjectAllBranches() {
    Assert.assertNull(NumericOps.bitOrObject(null, null));
    // null coerced to 0: 0 | 5 == 5; 3 | 0 == 3.
    Assert.assertEquals(5, NumericOps.bitOrObject(null, 5));
    Assert.assertEquals(3, NumericOps.bitOrObject(3, null));
    Assert.assertEquals(7, NumericOps.bitOrObject(3, 5));
  }

  // Pins NumericOps.nullCoalescingObject, the lifted NULL_COALESCING apply(Object, Object): left
  // when non-null, otherwise right (including when both are null).
  @Test
  public void testNullCoalescingObjectAllBranches() {
    Assert.assertEquals(3, NumericOps.nullCoalescingObject(3, 5));
    Assert.assertEquals(5, NumericOps.nullCoalescingObject(null, 5));
    Assert.assertNull(NumericOps.nullCoalescingObject(null, null));
  }

  // ---------------------------------------------------------------------------------------------
  // toLong helper
  // ---------------------------------------------------------------------------------------------

  // Pins NumericOps.toLong: a Number yields its long value, a Date yields its epoch milliseconds,
  // and any other type yields null. toLong is package-private, reachable from this same-package
  // test; the Date and Number arms are also exercised transitively by the Date arithmetic above,
  // but the null-for-other arm is only reachable directly.
  @Test
  public void testToLongHelper() {
    Assert.assertEquals(Long.valueOf(7L), NumericOps.toLong(7));
    Assert.assertEquals(Long.valueOf(7L), NumericOps.toLong(7L));
    Assert.assertEquals(Long.valueOf(1_000L), NumericOps.toLong(new Date(1_000L)));
    Assert.assertNull(NumericOps.toLong("not a number"));
    Assert.assertNull(NumericOps.toLong(null));
  }
}
