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
import com.jetbrains.youtrack.db.internal.spatial.engine.LuceneSpatialIndexContainer;
import com.jetbrains.youtrack.db.internal.spatial.query.SpatialQueryContext;
import com.jetbrains.youtrack.db.internal.spatial.shape.ShapeBuilder;
import java.util.Map;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.spatial.SpatialStrategy;
import org.apache.lucene.spatial.query.SpatialArgs;
import org.apache.lucene.spatial.query.SpatialOperation;
import org.locationtech.spatial4j.shape.Shape;

/**
 *
 */
public class SpatialQueryBuilderContains extends SpatialQueryBuilderAbstract {

  public static final String NAME = "contains";

  public SpatialQueryBuilderContains(LuceneSpatialIndexContainer manager, ShapeBuilder factory) {
    super(manager, factory);
  }

  @Override
  public SpatialQueryContext build(DatabaseSessionInternal db, Map<String, Object> query)
      throws Exception {
    var shape = parseShape(query);
    var strategy = manager.strategy();

    if (isOnlyBB(strategy)) {
      shape = shape.getBoundingBox();
    }
    var args = new SpatialArgs(SpatialOperation.Intersects, shape);

    var filterQuery = strategy.makeQuery(args);

    var q =
        new BooleanQuery.Builder()
            .add(filterQuery, BooleanClause.Occur.MUST)
            .add(new MatchAllDocsQuery(), BooleanClause.Occur.SHOULD)
            .build();

    return new SpatialQueryContext(null, manager.searcher(db.getStorage()), q);
  }

  @Override
  public String getName() {
    return NAME;
  }
}
