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
package com.jetbrains.youtrackdb.internal.spatial.shape;

import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.EmbeddedEntity;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.api.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import java.util.ArrayList;
import java.util.List;
import org.locationtech.spatial4j.shape.Rectangle;

public class RectangleShapeBuilder extends ShapeBuilder<Rectangle> {

  public static final String NAME = "ORectangle";

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public OShapeType getType() {
    return OShapeType.RECTANGLE;
  }

  @Override
  public void initClazz(DatabaseSessionInternal db) {

    Schema schema = db.getMetadata().getSchema();
    var rectangle = schema.createAbstractClass(NAME, superClass(db));
    var coordinates = rectangle.createProperty(COORDINATES,
        PropertyType.EMBEDDEDLIST,
        PropertyType.DOUBLE);
    coordinates.setMin("4");
    coordinates.setMin("4");
  }

  @Override
  public Rectangle fromResult(Result document) {
    List<Number> coordinates = document.getProperty(COORDINATES);

    var topLeft =
        SPATIAL_CONTEXT.makePoint(
            coordinates.get(0).doubleValue(), coordinates.get(1).doubleValue());
    var bottomRight =
        SPATIAL_CONTEXT.makePoint(
            coordinates.get(2).doubleValue(), coordinates.get(3).doubleValue());
    var rectangle = SPATIAL_CONTEXT.makeRectangle(topLeft, bottomRight);
    return rectangle;
  }

  @Override
  public EmbeddedEntity toEmbeddedEntity(final Rectangle shape, DatabaseSessionInternal session) {
    var entity = session.newEmbeddedEntity(NAME);
    entity.newEmbeddedList(COORDINATES, new ArrayList<Double>() {
      {
        add(shape.getMinX());
        add(shape.getMinY());
        add(shape.getMaxX());
        add(shape.getMaxY());
      }
    });

    return entity;
  }
}
