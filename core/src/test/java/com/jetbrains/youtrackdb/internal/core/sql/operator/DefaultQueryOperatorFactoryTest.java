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

import com.jetbrains.youtrackdb.internal.core.sql.operator.math.QueryOperatorDivide;
import com.jetbrains.youtrackdb.internal.core.sql.operator.math.QueryOperatorMinus;
import com.jetbrains.youtrackdb.internal.core.sql.operator.math.QueryOperatorMod;
import com.jetbrains.youtrackdb.internal.core.sql.operator.math.QueryOperatorMultiply;
import com.jetbrains.youtrackdb.internal.core.sql.operator.math.QueryOperatorPlus;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for DefaultQueryOperatorFactory: verifies it returns all 27 expected operators in an
 * unmodifiable set.
 */
public class DefaultQueryOperatorFactoryTest {

  private final DefaultQueryOperatorFactory factory = new DefaultQueryOperatorFactory();

  @Test
  public void testGetOperatorsReturnsExpectedCount() {
    // 22 comparison/logical + 5 math = 27 operators total
    Set<QueryOperator> operators = factory.getOperators();
    Assert.assertEquals(27, operators.size());
  }

  @Test
  public void testGetOperatorsReturnsUnmodifiableSet() {
    // The set should be unmodifiable — adding should throw
    Set<QueryOperator> operators = factory.getOperators();
    try {
      operators.add(new QueryOperatorPlus());
      Assert.fail("Expected UnsupportedOperationException");
    } catch (UnsupportedOperationException e) {
      // expected
    }
  }

  @Test
  public void testContainsAllComparisonOperators() {
    Set<QueryOperator> operators = factory.getOperators();
    assertContainsOperatorOfType(operators, QueryOperatorEquals.class);
    assertContainsOperatorOfType(operators, QueryOperatorNotEquals.class);
    assertContainsOperatorOfType(operators, QueryOperatorNotEquals2.class);
    assertContainsOperatorOfType(operators, QueryOperatorMajor.class);
    assertContainsOperatorOfType(operators, QueryOperatorMajorEquals.class);
    assertContainsOperatorOfType(operators, QueryOperatorMinor.class);
    assertContainsOperatorOfType(operators, QueryOperatorMinorEquals.class);
    assertContainsOperatorOfType(operators, QueryOperatorBetween.class);
    assertContainsOperatorOfType(operators, QueryOperatorIn.class);
    assertContainsOperatorOfType(operators, QueryOperatorLike.class);
    assertContainsOperatorOfType(operators, QueryOperatorMatches.class);
    assertContainsOperatorOfType(operators, QueryOperatorIs.class);
    assertContainsOperatorOfType(operators, QueryOperatorInstanceof.class);
  }

  @Test
  public void testContainsAllLogicalOperators() {
    Set<QueryOperator> operators = factory.getOperators();
    assertContainsOperatorOfType(operators, QueryOperatorAnd.class);
    assertContainsOperatorOfType(operators, QueryOperatorOr.class);
    assertContainsOperatorOfType(operators, QueryOperatorNot.class);
  }

  @Test
  public void testContainsAllCollectionOperators() {
    Set<QueryOperator> operators = factory.getOperators();
    assertContainsOperatorOfType(operators, QueryOperatorContains.class);
    assertContainsOperatorOfType(operators, QueryOperatorContainsAll.class);
    assertContainsOperatorOfType(operators, QueryOperatorContainsKey.class);
    assertContainsOperatorOfType(operators, QueryOperatorContainsValue.class);
    assertContainsOperatorOfType(operators, QueryOperatorContainsText.class);
  }

  @Test
  public void testContainsTraverseOperator() {
    assertContainsOperatorOfType(factory.getOperators(), QueryOperatorTraverse.class);
  }

  @Test
  public void testContainsAllMathOperators() {
    Set<QueryOperator> operators = factory.getOperators();
    assertContainsOperatorOfType(operators, QueryOperatorPlus.class);
    assertContainsOperatorOfType(operators, QueryOperatorMinus.class);
    assertContainsOperatorOfType(operators, QueryOperatorMultiply.class);
    assertContainsOperatorOfType(operators, QueryOperatorDivide.class);
    assertContainsOperatorOfType(operators, QueryOperatorMod.class);
  }

  @Test
  public void testGetOperatorsReturnsSameInstance() {
    // Factory should return the same static unmodifiable set each time
    Assert.assertSame(factory.getOperators(), factory.getOperators());
  }

  private void assertContainsOperatorOfType(
      Set<QueryOperator> operators, Class<? extends QueryOperator> type) {
    boolean found = operators.stream().anyMatch(type::isInstance);
    Assert.assertTrue(
        "Expected operator of type " + type.getSimpleName() + " not found", found);
  }
}
