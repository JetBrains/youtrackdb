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
import com.jetbrains.youtrackdb.api.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.spatial4j.shape.jts.JtsGeometry;

public class MultiLineStringShapeBuilder extends ComplexShapeBuilder<JtsGeometry> {

  @Override
  public String getName() {
    return "OMultiLineString";
  }

  @Override
  public OShapeType getType() {
    return OShapeType.MULTILINESTRING;
  }

  @Override
  public JtsGeometry fromResult(Result document) {
    List<List<List<Number>>> coordinates = document.getProperty(COORDINATES);
    var multiLine = new LineString[coordinates.size()];
    var j = 0;
    for (var coordinate : coordinates) {
      multiLine[j] = createLineString(coordinate);
      j++;
    }
    return toShape(GEOMETRY_FACTORY.createMultiLineString(multiLine));
  }

  @Override
  public void initClazz(DatabaseSessionInternal db) {
    Schema schema = db.getMetadata().getSchema();
    var lineString = schema.createAbstractClass(getName(), superClass(db));
    lineString.createProperty(COORDINATES, PropertyType.EMBEDDEDLIST,
        PropertyType.EMBEDDEDLIST);

    if (GlobalConfiguration.SPATIAL_ENABLE_DIRECT_WKT_READER.getValueAsBoolean()) {
      var lineStringZ = schema.createAbstractClass(getName() + "Z", superClass(db));
      lineStringZ.createProperty(COORDINATES, PropertyType.EMBEDDEDLIST,
          PropertyType.EMBEDDEDLIST);
    }
  }

  @Override
  public EmbeddedEntity toEmbeddedEntity(JtsGeometry shape, DatabaseSessionInternal session) {
    final var geom = (MultiLineString) shape.getGeom();

    List<List<List<Double>>> coordinates = new ArrayList<List<List<Double>>>();

    var entity = session.newEmbeddedEntity(getName());
    for (var i = 0; i < geom.getNumGeometries(); i++) {
      final var lineString = (LineString) geom.getGeometryN(i);
      coordinates.add(coordinatesFromLineString(lineString));
    }

    entity.newEmbeddedList(COORDINATES, coordinates);
    return entity;
  }

  @Override
  protected EmbeddedEntity toEmbeddedEntity(JtsGeometry shape, Geometry geometry,
      DatabaseSessionInternal session) {
    if (geometry == null || Double.isNaN(geometry.getCoordinates()[0].getZ())) {
      return toEmbeddedEntity(shape, session);
    }

    List<List<List<Double>>> coordinates = new ArrayList<List<List<Double>>>();
    var result = session.newEmbeddedEntity(getName() + "Z");
    for (var i = 0; i < geometry.getNumGeometries(); i++) {
      final var lineString = geometry.getGeometryN(i);
      coordinates.add(coordinatesFromLineStringZ(lineString));
    }

    result.newEmbeddedList(COORDINATES, coordinates);
    return result;
  }

  @Override
  public String asText(EmbeddedEntity entity) {
    if (Objects.equals(entity.getSchemaClassName(), "OMultiLineStringZ")) {
      List<List<List<Double>>> coordinates = entity.getProperty("coordinates");
      var result =
          coordinates.stream()
              .map(
                  line ->
                      "("
                          + line.stream()
                          .map(
                              point ->
                                  (point.stream()
                                      .map(this::format)
                                      .collect(Collectors.joining(" "))))
                          .collect(Collectors.joining(", "))
                          + ")")
              .collect(Collectors.joining(", "));
      return "MULTILINESTRING Z(" + result + ")";

    } else {
      return super.asText(entity);
    }
  }
}
