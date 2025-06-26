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
package com.jetbrains.youtrack.db.internal.spatial.shape.legacy;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.index.CompositeKey;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import org.locationtech.spatial4j.context.SpatialContext;
import org.locationtech.spatial4j.shape.Point;

/**
 *
 */
public class PointLegecyBuilder implements ShapeBuilderLegacy<Point> {

  @Override
  public Point makeShape(DatabaseSessionInternal session, CompositeKey key, SpatialContext ctx) {
    var lat = ((Double) PropertyTypeInternal.convert(session, key.getKeys().get(0),
        Double.class)).doubleValue();
    var lng = ((Double) PropertyTypeInternal.convert(session, key.getKeys().get(1),
        Double.class)).doubleValue();
    return ctx.makePoint(lng, lat);
  }

  @Override
  public boolean canHandle(CompositeKey key) {

    var canHandle = key.getKeys().size() == 2;
    for (var o : key.getKeys()) {
      if (!(o instanceof Number)) {
        canHandle = false;
        break;
      }
    }
    return canHandle;
  }
}
