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
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.spatial4j.shape.jts.JtsGeometry;

/**
 *
 */
public class MultiPolygonShapeBuilder extends PolygonShapeBuilder {

  @Override
  public String getName() {
    return "OMultiPolygon";
  }

  @Override
  public OShapeType getType() {
    return OShapeType.MULTIPOLYGON;
  }

  @Override
  public void initClazz(DatabaseSessionInternal db) {

    Schema schema = db.getMetadata().getSlowMutableSchema();
    var polygon = schema.createAbstractClass(getName(), superClass(db));
    polygon.createProperty("coordinates", PropertyType.EMBEDDEDLIST, PropertyType.EMBEDDEDLIST);
  }

  @Override
  public JtsGeometry fromResult(Result document) {
    List<List<List<List<Number>>>> coordinates = document.getProperty("coordinates");

    var polygons = new Polygon[coordinates.size()];
    var i = 0;
    for (var coordinate : coordinates) {
      polygons[i] = createPolygon(coordinate);
      i++;
    }
    return toShape(GEOMETRY_FACTORY.createMultiPolygon(polygons));
  }

  @Override
  public EmbeddedEntity toEmbeddedEntity(JtsGeometry shape, DatabaseSessionInternal session) {

    var entity = session.newEmbeddedEntity(getName());

    var multiPolygon = (MultiPolygon) shape.getGeom();
    List<List<List<List<Double>>>> polyCoordinates = new ArrayList<List<List<List<Double>>>>();
    var n = multiPolygon.getNumGeometries();

    for (var i = 0; i < n; i++) {
      var geom = multiPolygon.getGeometryN(i);
      polyCoordinates.add(coordinatesFromPolygon((Polygon) geom));
    }

    entity.newEmbeddedList(COORDINATES, polyCoordinates);
    return entity;
  }
}
