package com.jetbrains.youtrackdb.internal.core.sql;

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
import org.junit.Assert;
import org.junit.Test;

public class QueryOperatorTest {

  @Test
  public void testOperatorOrder() {

    // check operator are the correct order
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
}
