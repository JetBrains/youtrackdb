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
import com.jetbrains.youtrackdb.internal.spatial.collections.SpatialCompositeKey;
import com.jetbrains.youtrackdb.internal.spatial.shape.legacy.ShapeBuilderLegacy;
import com.jetbrains.youtrackdb.internal.spatial.shape.legacy.ShapeBuilderLegacyImpl;
import java.util.List;
import javax.annotation.Nullable;
import org.locationtech.spatial4j.context.SpatialContext;
import org.locationtech.spatial4j.shape.Shape;
import org.locationtech.spatial4j.shape.SpatialRelation;

public class LuceneWithinOperator extends QueryTargetOperator {

  private final ShapeBuilderLegacy<Shape> shapeFactory = ShapeBuilderLegacyImpl.INSTANCE;

  public LuceneWithinOperator() {
    super("WITHIN", 5, false);
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

    Shape shape = SpatialContext.GEO.makePoint(lon, lat);

    var shape1 =
        shapeFactory.makeShape(iContext.getDatabaseSession(),
            new SpatialCompositeKey((List<?>) iRight),
            SpatialContext.GEO);

    return shape.relate(shape1) == SpatialRelation.WITHIN;
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
  public IndexSearchResult getOIndexSearchResult(
      SchemaClassInternal iSchemaClass,
      SQLFilterCondition iCondition,
      List<IndexSearchResult> iIndexSearchResults,
      CommandContext context) {
    return LuceneOperatorUtil.buildOIndexSearchResult(
        iSchemaClass, iCondition, iIndexSearchResults, context);
  }
}
