/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.sql.operator;

import com.jetbrains.youtrackdb.internal.core.sql.operator.math.QueryOperatorDivide;
import com.jetbrains.youtrackdb.internal.core.sql.operator.math.QueryOperatorMinus;
import com.jetbrains.youtrackdb.internal.core.sql.operator.math.QueryOperatorMod;
import com.jetbrains.youtrackdb.internal.core.sql.operator.math.QueryOperatorMultiply;
import com.jetbrains.youtrackdb.internal.core.sql.operator.math.QueryOperatorPlus;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Default operator factory.
 */
public class DefaultQueryOperatorFactory implements QueryOperatorFactory {

  private static final Set<QueryOperator> OPERATORS;

  static {
    final Set<QueryOperator> operators = new HashSet<QueryOperator>();
    operators.add(new QueryOperatorEquals());
    operators.add(new QueryOperatorAnd());
    operators.add(new QueryOperatorOr());
    operators.add(new QueryOperatorNotEquals());
    operators.add(new QueryOperatorNotEquals2());
    operators.add(new QueryOperatorNot());
    operators.add(new QueryOperatorMinorEquals());
    operators.add(new QueryOperatorMinor());
    operators.add(new QueryOperatorMajorEquals());
    operators.add(new QueryOperatorContainsAll());
    operators.add(new QueryOperatorMajor());
    operators.add(new QueryOperatorLike());
    operators.add(new QueryOperatorMatches());
    operators.add(new QueryOperatorInstanceof());
    operators.add(new QueryOperatorIs());
    operators.add(new QueryOperatorIn());
    operators.add(new QueryOperatorContainsKey());
    operators.add(new QueryOperatorContainsValue());
    operators.add(new QueryOperatorContainsText());
    operators.add(new QueryOperatorContains());
    operators.add(new QueryOperatorTraverse());
    operators.add(new QueryOperatorBetween());
    operators.add(new QueryOperatorPlus());
    operators.add(new QueryOperatorMinus());
    operators.add(new QueryOperatorMultiply());
    operators.add(new QueryOperatorDivide());
    operators.add(new QueryOperatorMod());
    OPERATORS = Collections.unmodifiableSet(operators);
  }

  /**
   * {@inheritDoc}
   */
  public Set<QueryOperator> getOperators() {
    return OPERATORS;
  }
}
