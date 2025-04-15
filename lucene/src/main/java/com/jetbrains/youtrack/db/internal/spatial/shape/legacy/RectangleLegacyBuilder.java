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
import java.util.Collection;
import java.util.List;
import org.locationtech.spatial4j.context.SpatialContext;
import org.locationtech.spatial4j.shape.Point;
import org.locationtech.spatial4j.shape.Rectangle;

/**
 *
 */
public class RectangleLegacyBuilder implements ShapeBuilderLegacy<Rectangle> {

  @Override
  public Rectangle makeShape(DatabaseSessionInternal session, CompositeKey key,
      SpatialContext ctx) {

    var points = new Point[2];
    var i = 0;

    for (var o : key.getKeys()) {
      var numbers = (List<Number>) o;
      var lat = ((Double) PropertyTypeInternal.convert(session, numbers.get(0),
          Double.class)).doubleValue();
      var lng = ((Double) PropertyTypeInternal.convert(session, numbers.get(1),
          Double.class)).doubleValue();
      points[i] = ctx.makePoint(lng, lat);
      i++;
    }

    var lowerLeft = points[0];
    var topRight = points[1];
    if (lowerLeft.getX() > topRight.getX()) {
      var x = lowerLeft.getX();
      lowerLeft = ctx.makePoint(topRight.getX(), lowerLeft.getY());
      topRight = ctx.makePoint(x, topRight.getY());
    }
    return ctx.makeRectangle(lowerLeft, topRight);
  }

  @Override
  public boolean canHandle(CompositeKey key) {
    var canHandle = key.getKeys().size() == 2;
    for (var o : key.getKeys()) {
      if (!(o instanceof Collection)) {
        canHandle = false;
        break;
      } else if (((Collection) o).size() != 2) {
        canHandle = false;
        break;
      }
    }
    return canHandle;
  }
}
