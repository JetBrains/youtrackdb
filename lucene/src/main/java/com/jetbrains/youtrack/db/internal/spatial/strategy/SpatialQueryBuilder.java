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
package com.jetbrains.youtrack.db.internal.spatial.strategy;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.index.IndexEngineException;
import com.jetbrains.youtrack.db.internal.spatial.engine.LuceneSpatialIndexContainer;
import com.jetbrains.youtrack.db.internal.spatial.query.SpatialQueryContext;
import com.jetbrains.youtrack.db.internal.spatial.shape.ShapeBuilder;
import java.util.HashMap;
import java.util.Map;

/**
 *
 */
public class SpatialQueryBuilder extends SpatialQueryBuilderAbstract {

  private final Map<String, SpatialQueryBuilderAbstract> operators =
      new HashMap<String, SpatialQueryBuilderAbstract>();

  public SpatialQueryBuilder(LuceneSpatialIndexContainer manager, ShapeBuilder factory) {
    super(manager, factory);
    initOperators(manager, factory);
  }

  private void initOperators(LuceneSpatialIndexContainer manager, ShapeBuilder factory) {
    addOperator(new SpatialQueryBuilderWithin(manager, factory));
    addOperator(new SpatialQueryBuilderContains(manager, factory));
    addOperator(new SpatialQueryBuilderNear(manager, factory));
    addOperator(new SpatialQueryBuilderDWithin(manager, factory));
    addOperator(new SpatialQueryBuilderIntersects(manager, factory));
    addOperator(new SpatialQueryBuilderDistanceSphere(manager, factory));
    addOperator(new SpatialQueryBuilderOverlap(manager, factory));
  }

  private void addOperator(SpatialQueryBuilderAbstract builder) {
    operators.put(builder.getName(), builder);
  }

  public SpatialQueryContext build(DatabaseSessionInternal db, Map<String, Object> query)
      throws Exception {

    var operation = parseOperation(query);

    return operation.build(db, query);
  }

  @Override
  public String getName() {
    throw new UnsupportedOperationException();
  }

  private SpatialQueryBuilderAbstract parseOperation(Map<String, Object> query) {

    var operator = (String) query.get(GEO_FILTER);
    var spatialQueryBuilder = operators.get(operator);
    if (spatialQueryBuilder == null) {
      throw new IndexEngineException("Operator " + operator + " not supported.", null);
    }
    return spatialQueryBuilder;
  }
}
