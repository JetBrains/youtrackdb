package com.jetbrains.youtrackdb.internal.core.sql;

import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperator;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorAnd;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorBetween;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorContains;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorContainsAll;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorContainsKey;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorContainsText;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorContainsValue;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorEquals;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorIn;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorInstanceof;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorIs;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorLike;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorMajor;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorMajorEquals;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorMatches;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorMinor;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorMinorEquals;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorNot;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorNotEquals;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorNotEquals2;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorOr;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorTraverse;
import com.jetbrains.youtrackdb.internal.core.sql.operator.math.QueryOperatorDivide;
import com.jetbrains.youtrackdb.internal.core.sql.operator.math.QueryOperatorMinus;
import com.jetbrains.youtrackdb.internal.core.sql.operator.math.QueryOperatorMod;
import com.jetbrains.youtrackdb.internal.core.sql.operator.math.QueryOperatorMultiply;
import com.jetbrains.youtrackdb.internal.core.sql.operator.math.QueryOperatorPlus;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class QueryOperatorTest {

  @Test
  public void testOperatorOrder() {
    // check operators are in the correct order
    final var operators = SQLEngine.getRecordOperators();

    var i = 0;
    Assert.assertTrue(operators[i++] instanceof QueryOperatorEquals);
    Assert.assertTrue(operators[i++] instanceof QueryOperatorAnd);
    Assert.assertTrue(operators[i++] instanceof QueryOperatorOr);
    Assert.assertTrue(operators[i++] instanceof QueryOperatorNotEquals);
    Assert.assertTrue(operators[i++] instanceof QueryOperatorNotEquals2);
    Assert.assertTrue(operators[i++] instanceof QueryOperatorNot);
    Assert.assertTrue(operators[i++] instanceof QueryOperatorMinorEquals);
    Assert.assertTrue(operators[i++] instanceof QueryOperatorMinor);
    Assert.assertTrue(operators[i++] instanceof QueryOperatorMajorEquals);
    Assert.assertTrue(operators[i++] instanceof QueryOperatorContainsAll);
    Assert.assertTrue(operators[i++] instanceof QueryOperatorMajor);
    Assert.assertTrue(operators[i++] instanceof QueryOperatorLike);
    Assert.assertTrue(operators[i++] instanceof QueryOperatorMatches);
    Assert.assertTrue(operators[i++] instanceof QueryOperatorInstanceof);
    Assert.assertTrue(operators[i++] instanceof QueryOperatorIs);
    Assert.assertTrue(operators[i++] instanceof QueryOperatorIn);
    Assert.assertTrue(operators[i++] instanceof QueryOperatorContainsKey);
    Assert.assertTrue(operators[i++] instanceof QueryOperatorContainsValue);
    Assert.assertTrue(operators[i++] instanceof QueryOperatorContainsText);
    Assert.assertTrue(operators[i++] instanceof QueryOperatorContains);
    Assert.assertTrue(operators[i++] instanceof QueryOperatorTraverse);
    Assert.assertTrue(operators[i++] instanceof QueryOperatorBetween);
    Assert.assertTrue(operators[i++] instanceof QueryOperatorPlus);
    Assert.assertTrue(operators[i++] instanceof QueryOperatorMinus);
    Assert.assertTrue(operators[i++] instanceof QueryOperatorMultiply);
    Assert.assertTrue(operators[i++] instanceof QueryOperatorDivide);
    Assert.assertTrue(operators[i++] instanceof QueryOperatorMod);
  }

  // --- QueryOperator.compare() tests ---

  @Test
  public void testCompareEqualOperator() {
    // Comparing an operator to itself should return EQUAL
    QueryOperator equals = new QueryOperatorEquals();
    Assert.assertEquals(QueryOperator.ORDER.EQUAL, equals.compare(equals));
  }

  @Test
  public void testCompareBeforeOperator() {
    // Equals (index 0) should be BEFORE Mod (index 26)
    QueryOperator equals = new QueryOperatorEquals();
    QueryOperator mod = new QueryOperatorMod();
    Assert.assertEquals(QueryOperator.ORDER.BEFORE, equals.compare(mod));
  }

  @Test
  public void testCompareAfterOperator() {
    // Mod (index 26) should be AFTER Equals (index 0)
    QueryOperator mod = new QueryOperatorMod();
    QueryOperator equals = new QueryOperatorEquals();
    Assert.assertEquals(QueryOperator.ORDER.AFTER, mod.compare(equals));
  }

  // --- QueryOperator.toString() returns the keyword ---

  @Test
  public void testToStringReturnsKeyword() {
    Assert.assertEquals("=", new QueryOperatorEquals().toString());
    Assert.assertEquals("+", new QueryOperatorPlus().toString());
    Assert.assertEquals("-", new QueryOperatorMinus().toString());
    Assert.assertEquals("*", new QueryOperatorMultiply().toString());
    Assert.assertEquals("/", new QueryOperatorDivide().toString());
    Assert.assertEquals("%", new QueryOperatorMod().toString());
  }

  // --- QueryOperator.getSyntax() ---

  @Test
  public void testGetSyntax() {
    QueryOperator plus = new QueryOperatorPlus();
    Assert.assertEquals("<left> + <right>", plus.getSyntax());
  }

  // --- QueryOperator.isUnary() ---

  @Test
  public void testIsUnaryFalseForBinaryOperators() {
    Assert.assertFalse(new QueryOperatorPlus().isUnary());
    Assert.assertFalse(new QueryOperatorEquals().isUnary());
  }

  @Test
  public void testIsUnaryTrueForNot() {
    Assert.assertTrue(new QueryOperatorNot().isUnary());
  }

  // --- QueryOperator.configure() returns this (stateless) ---

  @Test
  public void testConfigureReturnsSameInstance() {
    QueryOperator op = new QueryOperatorPlus();
    Assert.assertSame(op, op.configure(List.of()));
  }

  // --- QueryOperator.canBeMerged() / canShortCircuit() / isSupportingBinaryEvaluate() ---

  @Test
  public void testCanBeMergedDefaultTrue() {
    Assert.assertTrue(new QueryOperatorPlus().canBeMerged());
  }

  @Test
  public void testCanShortCircuitDefaultFalse() {
    Assert.assertFalse(new QueryOperatorPlus().canShortCircuit(null));
  }

  @Test
  public void testIsSupportingBinaryEvaluateDefaultFalse() {
    Assert.assertFalse(new QueryOperatorPlus().isSupportingBinaryEvaluate());
  }
}
