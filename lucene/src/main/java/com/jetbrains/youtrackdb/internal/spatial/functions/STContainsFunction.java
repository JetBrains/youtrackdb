/**
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * <p>*
 */
package com.jetbrains.youtrackdb.internal.spatial.functions;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCompareOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFromClause;
import com.jetbrains.youtrackdb.internal.spatial.shape.ShapeFactory;
import com.jetbrains.youtrackdb.internal.spatial.strategy.SpatialQueryBuilderContains;
import java.util.Collection;
import javax.annotation.Nullable;

/**
 *
 */
public class STContainsFunction extends SpatialFunctionAbstractIndexable {

  public static final String NAME = "st_contains";

  private final ShapeFactory factory = ShapeFactory.INSTANCE;

  public STContainsFunction() {
    super(NAME, 2, 2);
  }

  @Nullable
  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      Object iCurrentResult,
      Object[] iParams,
      CommandContext iContext) {

    if (containsNull(iParams)) {
      return null;
    }

    var shape = toShape(iParams[0]);

    Object param1 = null;
    if (iParams[1] instanceof Collection<?> collection && collection.size() == 1) {
      param1 = collection.iterator().next();
    } else {
      param1 = iParams[1];
    }

    var shape1 = toShape(param1);

    return factory.operation().contains(shape, shape1);
  }

  @Override
  protected boolean isValidBinaryOperator(SQLBinaryCompareOperator operator) {
    return true;
  }

  @Nullable
  @Override
  public String getSyntax(DatabaseSession session) {
    return null;
  }

  @Override
  protected String operator() {
    return SpatialQueryBuilderContains.NAME;
  }

  @Override
  public Iterable<Identifiable> searchFromTarget(
      SQLFromClause target,
      SQLBinaryCompareOperator operator,
      Object rightValue,
      CommandContext ctx,
      SQLExpression... args) {
    return results(target, args, ctx, rightValue);
  }
}
