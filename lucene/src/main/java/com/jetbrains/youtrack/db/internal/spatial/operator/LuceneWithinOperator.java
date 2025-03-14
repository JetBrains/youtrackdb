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
package com.jetbrains.youtrack.db.internal.spatial.operator;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.EntitySerializer;
import com.jetbrains.youtrack.db.internal.core.sql.IndexSearchResult;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrack.db.internal.core.sql.operator.IndexReuseType;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryTargetOperator;
import com.jetbrains.youtrack.db.internal.lucene.operator.LuceneOperatorUtil;
import com.jetbrains.youtrack.db.internal.spatial.collections.SpatialCompositeKey;
import com.jetbrains.youtrack.db.internal.spatial.shape.legacy.ShapeBuilderLegacy;
import com.jetbrains.youtrack.db.internal.spatial.shape.legacy.ShapeBuilderLegacyImpl;
import java.util.List;
import java.util.stream.Stream;
import org.apache.lucene.spatial.query.SpatialOperation;
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

  @Override
  public Stream<RawPair<Object, RID>> executeIndexQuery(
      CommandContext iContext, Index index, List<Object> keyParams, boolean ascSortOrder) {
    iContext.setVariable("$luceneIndex", true);
    return index.getRids(iContext.getDatabaseSession(),
            new SpatialCompositeKey(keyParams).setOperation(SpatialOperation.IsWithin)
                .setContext(iContext))
        .map(
            (rid) -> new RawPair<>(new SpatialCompositeKey(keyParams).setContext(iContext), rid));
  }

  @Override
  public RID getBeginRidRange(DatabaseSession session, Object iLeft, Object iRight) {
    return null;
  }

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
