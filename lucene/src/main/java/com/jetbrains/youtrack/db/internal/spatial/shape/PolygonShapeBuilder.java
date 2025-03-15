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
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.spatial4j.shape.jts.JtsGeometry;

/**
 *
 */
public class PolygonShapeBuilder extends ComplexShapeBuilder<JtsGeometry> {

  @Override
  public String getName() {
    return "OPolygon";
  }

  @Override
  public OShapeType getType() {
    return OShapeType.POLYGON;
  }

  @Override
  public void initClazz(DatabaseSessionInternal db) {

    Schema schema = db.getMetadata().getSchema();
    var polygon = schema.createAbstractClass(getName(), superClass(db));
    polygon.createProperty(COORDINATES, PropertyType.EMBEDDEDLIST, PropertyType.EMBEDDEDLIST);

    if (GlobalConfiguration.SPATIAL_ENABLE_DIRECT_WKT_READER.getValueAsBoolean()) {
      var polygonZ = schema.createAbstractClass(getName() + "Z", superClass(db));
      polygonZ.createProperty(COORDINATES, PropertyType.EMBEDDEDLIST,
          PropertyType.EMBEDDEDLIST);
    }
  }

  @Override
  public JtsGeometry fromDoc(EntityImpl document) {
    validate(document);
    List<List<List<Number>>> coordinates = document.getProperty("coordinates");

    return toShape(createPolygon(coordinates));
  }

  protected Polygon createPolygon(List<List<List<Number>>> coordinates) {
    Polygon shape;
    if (coordinates.size() == 1) {
      var coords = coordinates.get(0);
      var linearRing = createLinearRing(coords);
      shape = GEOMETRY_FACTORY.createPolygon(linearRing);
    } else {
      var i = 0;
      LinearRing outerRing = null;
      var holes = new LinearRing[coordinates.size() - 1];
      for (var coordinate : coordinates) {
        if (i == 0) {
          outerRing = createLinearRing(coordinate);
        } else {
          holes[i - 1] = createLinearRing(coordinate);
        }
        i++;
      }
      shape = GEOMETRY_FACTORY.createPolygon(outerRing, holes);
    }
    return shape;
  }

  protected LinearRing createLinearRing(List<List<Number>> coords) {
    var crs = new Coordinate[coords.size()];
    var i = 0;
    for (var points : coords) {
      crs[i] = new Coordinate(points.get(0).doubleValue(), points.get(1).doubleValue());
      i++;
    }
    return GEOMETRY_FACTORY.createLinearRing(crs);
  }

  @Override
  public EntityImpl toEntitty(JtsGeometry shape) {

    var doc = new EntityImpl(null, getName());
    var polygon = (Polygon) shape.getGeom();
    var polyCoordinates = coordinatesFromPolygon(polygon);
    doc.setProperty(COORDINATES, polyCoordinates);
    return doc;
  }

  @Override
  protected EntityImpl toEntitty(JtsGeometry shape, Geometry geometry) {
    if (geometry == null || Double.isNaN(geometry.getCoordinate().getZ())) {
      return toEntitty(shape);
    }

    var doc = new EntityImpl(null, getName() + "Z");
    var polygon = (Polygon) shape.getGeom();
    var polyCoordinates = coordinatesFromPolygonZ(geometry);
    doc.setProperty(COORDINATES, polyCoordinates);
    return doc;
  }

  protected List<List<List<Double>>> coordinatesFromPolygon(Polygon polygon) {
    List<List<List<Double>>> polyCoordinates = new ArrayList<List<List<Double>>>();
    LineString exteriorRing = polygon.getExteriorRing();
    polyCoordinates.add(coordinatesFromLineString(exteriorRing));
    var i = polygon.getNumInteriorRing();
    for (var j = 0; j < i; j++) {
      LineString interiorRingN = polygon.getInteriorRingN(j);
      polyCoordinates.add(coordinatesFromLineString(interiorRingN));
    }
    return polyCoordinates;
  }

  protected List<List<List<Double>>> coordinatesFromPolygonZ(Geometry polygon) {
    List<List<List<Double>>> polyCoordinates = new ArrayList<>();
    for (var i = 0; i < polygon.getNumGeometries(); i++) {
      polyCoordinates.add(coordinatesFromLineStringZ(polygon.getGeometryN(i)));
    }
    return polyCoordinates;
  }

  @Override
  public String asText(EntityImpl document) {
    if (document.getSchemaClassName().equals("OPolygonZ")) {
      List<List<List<Double>>> coordinates = document.getProperty("coordinates");

      var result =
          coordinates.stream()
              .map(
                  poly ->
                      "("
                          + poly.stream()
                          .map(
                              point ->
                                  (point.stream()
                                      .map(coord -> format(coord))
                                      .collect(Collectors.joining(" "))))
                          .collect(Collectors.joining(", "))
                          + ")")
              .collect(Collectors.joining(" "));
      return "POLYGON Z (" + result + ")";

    } else {
      return super.asText(document);
    }
  }
}
