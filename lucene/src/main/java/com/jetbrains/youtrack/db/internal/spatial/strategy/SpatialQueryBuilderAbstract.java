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
import java.util.Map;
import org.apache.lucene.spatial.SpatialStrategy;
import org.apache.lucene.spatial.bbox.BBoxStrategy;
import org.locationtech.spatial4j.shape.Shape;

/**
 *
 */
public abstract class SpatialQueryBuilderAbstract {

  public static final String GEO_FILTER = "geo_filter";
  public static final String SHAPE = "shape";
  public static final String SHAPE_TYPE = "type";
  public static final String COORDINATES = "coordinates";
  public static final String MAX_DISTANCE = "maxDistance";
  protected LuceneSpatialIndexContainer manager;
  protected ShapeBuilder factory;

  public SpatialQueryBuilderAbstract(LuceneSpatialIndexContainer manager, ShapeBuilder factory) {
    this.manager = manager;
    this.factory = factory;
  }

  public abstract SpatialQueryContext build(DatabaseSessionInternal db, Map<String, Object> query)
      throws Exception;

  protected Shape parseShape(Map<String, Object> query) {

    var geometry = query.get(SHAPE);

    if (geometry == null) {
      throw new IndexEngineException("Invalid spatial query. Missing shape field " + query, null);
    }
    var parsed = factory.fromObject(geometry);

    if (parsed == null) {
      throw new IndexEngineException(
          "Invalid spatial query. Invalid shape field. Found: " + geometry.getClass().getName(),
          null);
    }
    return parsed;
  }

  protected boolean isOnlyBB(SpatialStrategy spatialStrategy) {
    return spatialStrategy instanceof BBoxStrategy;
  }

  public abstract String getName();
}
