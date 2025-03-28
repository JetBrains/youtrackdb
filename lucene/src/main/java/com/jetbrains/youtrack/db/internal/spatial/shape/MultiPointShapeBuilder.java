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
package com.jetbrains.youtrack.db.internal.spatial.shape;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.EmbeddedEntity;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.spatial4j.shape.jts.JtsGeometry;

public class MultiPointShapeBuilder extends ComplexShapeBuilder<JtsGeometry> {

  @Override
  public String getName() {
    return "OMultiPoint";
  }

  @Override
  public OShapeType getType() {
    return OShapeType.MULTIPOINT;
  }

  @Override
  public JtsGeometry fromResult(Result document) {
    List<List<Number>> coordinates = document.getProperty(COORDINATES);
    var coords = new Coordinate[coordinates.size()];
    var i = 0;
    for (var coordinate : coordinates) {
      coords[i] = new Coordinate(coordinate.get(0).doubleValue(), coordinate.get(1).doubleValue());
      i++;
    }
    return toShape(GEOMETRY_FACTORY.createMultiPoint(coords));
  }

  @Override
  public void initClazz(DatabaseSessionInternal db) {
    Schema schema = db.getMetadata().getSchema();
    var multiPoint = schema.createAbstractClass(getName(), superClass(db));
    multiPoint.createProperty(COORDINATES, PropertyType.EMBEDDEDLIST,
        PropertyType.EMBEDDEDLIST);
  }

  @Override
  public EmbeddedEntity toEmbeddedEntity(final JtsGeometry shape, DatabaseSessionInternal session) {
    final var geom = (MultiPoint) shape.getGeom();

    var entity = session.newEmbeddedEntity(getName());
    entity.setProperty(COORDINATES, new ArrayList<List<Double>>() {
      {
        var coordinates = geom.getCoordinates();
        for (var coordinate : coordinates) {
          add(Arrays.asList(coordinate.x, coordinate.y));
        }
      }
    });
    return entity;
  }
}
