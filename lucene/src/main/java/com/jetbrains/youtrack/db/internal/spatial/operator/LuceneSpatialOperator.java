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
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.EntitySerializer;
import com.jetbrains.youtrack.db.internal.core.sql.IndexSearchResult;
import com.jetbrains.youtrack.db.internal.core.sql.SQLEngine;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrack.db.internal.core.sql.operator.IndexReuseType;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryTargetOperator;
import com.jetbrains.youtrack.db.internal.lucene.operator.LuceneOperatorUtil;
import com.jetbrains.youtrack.db.internal.spatial.shape.ShapeBuilder;
import com.jetbrains.youtrack.db.internal.spatial.shape.ShapeFactory;
import java.util.List;

/**
 *
 */
public abstract class LuceneSpatialOperator extends QueryTargetOperator {

  protected ShapeBuilder factory;

  protected LuceneSpatialOperator(String iKeyword, int iPrecedence, boolean iLogical) {
    super(iKeyword, iPrecedence, iLogical);
    factory = ShapeFactory.INSTANCE;
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

  // TODO HANDLE EVALUATE RECORD
  @Override
  public Object evaluateRecord(
      Result iRecord,
      EntityImpl iCurrentResult,
      SQLFilterCondition iCondition,
      Object iLeft,
      Object iRight,
      CommandContext iContext,
      final EntitySerializer serializer) {

    var function = SQLEngine.getInstance().getFunction(iContext.getDatabaseSession(), keyword);
    return function.execute(
        this, iRecord, iCurrentResult, new Object[]{iLeft, iCondition.getRight()}, iContext);
  }

  @Override
  public IndexReuseType getIndexReuseType(Object iLeft, Object iRight) {
    return IndexReuseType.INDEX_OPERATOR;
  }

  @Override
  public RID getBeginRidRange(DatabaseSession session, Object iLeft, Object iRight) {
    return null;
  }

  @Override
  public RID getEndRidRange(DatabaseSession session, Object iLeft, Object iRight) {
    return null;
  }
}
