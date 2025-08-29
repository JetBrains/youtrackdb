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
import com.jetbrains.youtrackdb.internal.spatial.strategy.SpatialQueryBuilderDistanceSphere;
import java.util.Map;
import javax.annotation.Nullable;
import org.locationtech.spatial4j.distance.DistanceUtils;

/**
 *
 */
public class STDistanceSphereFunction extends SpatialFunctionAbstractIndexable {

  public static final String NAME = "st_distance_sphere";
  private final ShapeFactory factory = ShapeFactory.INSTANCE;

  public STDistanceSphereFunction() {
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
    var shape1 = toShape(iParams[1]);

    if (shape == null || shape1 == null) {
      return null;
    }

    var distance =
        factory.context().getDistCalc().distance(shape.getCenter(), shape1.getCenter());
    final var docDistInKM =
        DistanceUtils.degrees2Dist(distance, DistanceUtils.EARTH_EQUATORIAL_RADIUS_KM);
    return docDistInKM * 1000;
  }

  @Nullable
  @Override
  public String getSyntax(DatabaseSession session) {
    return null;
  }

  @Override
  protected String operator() {
    return SpatialQueryBuilderDistanceSphere.NAME;
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

  @Override
  public long estimate(
      SQLFromClause target,
      SQLBinaryCompareOperator operator,
      Object rightValue,
      CommandContext ctx,
      SQLExpression... args) {

    if (rightValue == null || !isValidBinaryOperator(operator)) {
      return -1;
    }

    return super.estimate(target, operator, rightValue, ctx, args);
  }

  @Override
  protected void onAfterParsing(
      Map<String, Object> params, SQLExpression[] args, CommandContext ctx, Object rightValue) {

    var parsedNumber = (Number) rightValue;

    params.put("distance", parsedNumber.doubleValue());
  }
}
