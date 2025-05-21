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

import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.spatial.engine.LuceneSpatialIndexContainer;
import com.jetbrains.youtrack.db.internal.spatial.query.SpatialQueryContext;
import com.jetbrains.youtrack.db.internal.spatial.shape.ShapeBuilder;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Sort;
import org.apache.lucene.spatial.query.SpatialArgs;
import org.apache.lucene.spatial.query.SpatialOperation;
import org.locationtech.spatial4j.distance.DistanceUtils;
import org.locationtech.spatial4j.shape.Point;

/**
 *
 */
public class SpatialQueryBuilderNear extends SpatialQueryBuilderAbstract {

  public static final String NAME = "near";

  public SpatialQueryBuilderNear(LuceneSpatialIndexContainer manager, ShapeBuilder factory) {
    super(manager, factory);
  }

  @Override
  public SpatialQueryContext build(DatabaseSessionEmbedded db, Map<String, Object> query)
      throws Exception {
    var shape = parseShape(query);

    double distance =
        Optional.ofNullable(query.get(MAX_DISTANCE))
            .map(Number.class::cast)
            .map(n -> n.doubleValue())
            .orElse(0D);

    var p = (Point) shape;

    var args =
        new SpatialArgs(
            SpatialOperation.Intersects,
            factory
                .context()
                .makeCircle(
                    p.getX(),
                    p.getY(),
                    DistanceUtils.dist2Degrees(distance, DistanceUtils.EARTH_MEAN_RADIUS_KM)));
    var filterQuery = manager.strategy().makeQuery(args);
    var valueSource = manager.strategy().makeDistanceValueSource(p);
    var searcher = manager.searcher(db.getStorage());
    var distSort = new Sort(valueSource.getSortField(false)).rewrite(searcher);

    var q =
        new BooleanQuery.Builder()
            .add(filterQuery, BooleanClause.Occur.MUST)
            .add(new MatchAllDocsQuery(), BooleanClause.Occur.SHOULD)
            .build();

    var context = new BasicCommandContext();
    context.setDatabaseSession(db);
    return new SpatialQueryContext(context, searcher, q, Arrays.asList(distSort.getSort()))
        .setSpatialArgs(args);
  }

  @Override
  public String getName() {
    return NAME;
  }
}
