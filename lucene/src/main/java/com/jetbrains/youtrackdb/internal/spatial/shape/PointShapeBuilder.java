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

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.EmbeddedEntity;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.spatial4j.shape.Point;

public class PointShapeBuilder extends ShapeBuilder<Point> {

  public static final String NAME = "OPoint";

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public OShapeType getType() {
    return OShapeType.POINT;
  }

  @Override
  public void initClazz(DatabaseSessionInternal db) {

    Schema schema = db.getMetadata().getSchema();
    var point = schema.createAbstractClass(NAME, superClass(db));
    var coordinates = point.createProperty(COORDINATES, PropertyType.EMBEDDEDLIST,
        PropertyType.DOUBLE);
    coordinates.setMin("2");
    coordinates.setMax("2");

    if (GlobalConfiguration.SPATIAL_ENABLE_DIRECT_WKT_READER.getValueAsBoolean()) {
      var pointz = schema.createAbstractClass(NAME + "Z", superClass(db));
      var coordinatesz = pointz.createProperty(COORDINATES,
          PropertyType.EMBEDDEDLIST,
          PropertyType.DOUBLE);
      coordinatesz.setMin("3");
      coordinatesz.setMax("3");
    }
  }

  @Override
  public Point fromResult(Result document) {
    List<Number> coordinates = document.getProperty(COORDINATES);
    if (coordinates.size() == 2) {
      return SHAPE_FACTORY.pointXY(
          coordinates.get(0).doubleValue(), coordinates.get(1).doubleValue());
    } else {
      return SHAPE_FACTORY.pointXYZ(
          coordinates.get(0).doubleValue(),
          coordinates.get(1).doubleValue(),
          coordinates.get(2).doubleValue());
    }
  }

  @Override
  public EmbeddedEntity toEmbeddedEntity(final Point shape, DatabaseSessionInternal session) {
    var entity = session.newEmbeddedEntity(NAME);
    entity.newEmbeddedList(COORDINATES, new ArrayList<Double>() {
          {
            add(shape.getX());
            add(shape.getY());
          }
        });
    return entity;
  }

  @Override
  protected EmbeddedEntity toEmbeddedEntity(Point parsed, Geometry geometry,
      DatabaseSessionInternal session) {
    if (geometry == null || Double.isNaN(geometry.getCoordinate().getZ())) {
      return toEmbeddedEntity(parsed, session);
    }

    var entity =  session.newEmbeddedEntity(NAME + "Z");
    entity.newEmbeddedList(COORDINATES, new ArrayList<Double>() {
          {
            add(geometry.getCoordinate().getX());
            add(geometry.getCoordinate().getY());
            add(geometry.getCoordinate().getZ());
          }
        });
    return entity;
  }

  @Override
  public String asText(EmbeddedEntity entity) {
    if (Objects.equals(entity.getSchemaClassName(), "OPointZ")) {
      List<Double> coordinates = entity.getProperty("coordinates");
      return "POINT Z ("
          + format(coordinates.get(0))
          + " "
          + format(coordinates.get(1))
          + " "
          + format(coordinates.get(2))
          + ")";
    } else {
      return super.asText(entity);
    }
  }
}
