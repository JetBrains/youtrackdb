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
package com.jetbrains.youtrackdb.internal.spatial.operator;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.EntitySerializer;
import com.jetbrains.youtrackdb.internal.core.sql.IndexSearchResult;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrackdb.internal.core.sql.operator.IndexReuseType;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryTargetOperator;
import com.jetbrains.youtrackdb.internal.lucene.operator.LuceneOperatorUtil;
import com.jetbrains.youtrackdb.internal.spatial.shape.ShapeFactory;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.locationtech.spatial4j.distance.DistanceUtils;
import org.locationtech.spatial4j.shape.Point;
import org.locationtech.spatial4j.shape.Shape;
import org.locationtech.spatial4j.shape.SpatialRelation;

public class LuceneNearOperator extends QueryTargetOperator {

  private final ShapeFactory factory = ShapeFactory.INSTANCE;

  public LuceneNearOperator() {
    super("NEAR", 5, false);
  }

  @Override
  public Object evaluateRecord(
      Result iRecord,
      EntityImpl iCurrentResult,
      SQLFilterCondition iCondition,
      Object iLeft,
      Object iRight,
      CommandContext iContext,
      final EntitySerializer serializer) {

    var left = (List<Number>) iLeft;

    var lat = left.get(0).doubleValue();
    var lon = left.get(1).doubleValue();

    Shape shape = factory.context().makePoint(lon, lat);
    var right = (List<Number>) iRight;

    var lat1 = right.get(0).doubleValue();
    var lon1 = right.get(1).doubleValue();
    Shape shape1 = factory.context().makePoint(lon1, lat1);

    var map = (Map) right.get(2);
    double distance = 0;

    var n = (Number) map.get("maxDistance");
    if (n != null) {
      distance = n.doubleValue();
    }
    var p = (Point) shape1;
    var circle =
        factory
            .context()
            .makeCircle(
                p.getX(),
                p.getY(),
                DistanceUtils.dist2Degrees(distance, DistanceUtils.EARTH_MEAN_RADIUS_KM));
    var docDistDEG = factory.context().getDistCalc().distance((Point) shape, p);
    final var docDistInKM =
        DistanceUtils.degrees2Dist(docDistDEG, DistanceUtils.EARTH_EQUATORIAL_RADIUS_KM);
    iContext.setVariable("distance", docDistInKM);
    return shape.relate(circle) == SpatialRelation.WITHIN;
  }

  @Override
  public IndexReuseType getIndexReuseType(Object iLeft, Object iRight) {
    return IndexReuseType.INDEX_OPERATOR;
  }

  @Nullable
  @Override
  public RID getBeginRidRange(DatabaseSession session, Object iLeft, Object iRight) {
    return null;
  }

  @Nullable
  @Override
  public RID getEndRidRange(DatabaseSession session, Object iLeft, Object iRight) {
    return null;
  }

  @Override
  public String getSyntax() {
    return "<left> NEAR[(<begin-deep-level> [,<maximum-deep-level> [,<fields>]] )] ( <conditions>"
        + " )";
  }

  @Override
  public IndexSearchResult getOIndexSearchResult(
      SchemaClassInternal iSchemaClass,
      SQLFilterCondition iCondition,
      List<IndexSearchResult> iIndexSearchResults,
      CommandContext context) {
    return LuceneOperatorUtil.buildOIndexSearchResult(
        iSchemaClass, iCondition, iIndexSearchResults, context);
  }
}
