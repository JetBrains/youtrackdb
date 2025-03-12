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

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.List;
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
  public JtsGeometry fromDoc(EntityImpl document) {
    validate(document);
    List<List<List<Number>>> coordinates = document.field(COORDINATES);
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
  public EntityImpl toEntitty(JtsGeometry shape) {
    final var geom = (MultiLineString) shape.getGeom();

    List<List<List<Double>>> coordinates = new ArrayList<List<List<Double>>>();
    var doc = new EntityImpl(null, getName());
    for (var i = 0; i < geom.getNumGeometries(); i++) {
      final var lineString = (LineString) geom.getGeometryN(i);
      coordinates.add(coordinatesFromLineString(lineString));
    }

    doc.field(COORDINATES, coordinates);
    return doc;
  }

  @Override
  protected EntityImpl toEntitty(JtsGeometry shape, Geometry geometry) {
    if (geometry == null || Double.isNaN(geometry.getCoordinates()[0].getZ())) {
      return toEntitty(shape);
    }

    List<List<List<Double>>> coordinates = new ArrayList<List<List<Double>>>();
    var doc = new EntityImpl(null, getName() + "Z");
    for (var i = 0; i < geometry.getNumGeometries(); i++) {
      final var lineString = geometry.getGeometryN(i);
      coordinates.add(coordinatesFromLineStringZ(lineString));
    }

    doc.field(COORDINATES, coordinates);
    return doc;
  }

  @Override
  public String asText(EntityImpl document) {
    if (document.getSchemaClassName().equals("OMultiLineStringZ")) {
      List<List<List<Double>>> coordinates = document.getProperty("coordinates");

      var result =
          coordinates.stream()
              .map(
                  line ->
                      "("
                          + line.stream()
                          .map(
                              point ->
                                  (point.stream()
                                      .map(coord -> format(coord))
                                      .collect(Collectors.joining(" "))))
                          .collect(Collectors.joining(", "))
                          + ")")
              .collect(Collectors.joining(", "));
      return "MULTILINESTRING Z(" + result + ")";

    } else {
      return super.asText(document);
    }
  }
}
