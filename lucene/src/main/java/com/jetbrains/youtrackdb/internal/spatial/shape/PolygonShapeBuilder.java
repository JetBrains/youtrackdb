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
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

    Schema schema = db.getMetadata().getSlowMutableSchema();
    var polygon = schema.createAbstractClass(getName(), superClass(db));
    polygon.createProperty(COORDINATES, PropertyType.EMBEDDEDLIST, PropertyType.EMBEDDEDLIST);

    if (GlobalConfiguration.SPATIAL_ENABLE_DIRECT_WKT_READER.getValueAsBoolean()) {
      var polygonZ = schema.createAbstractClass(getName() + "Z", superClass(db));
      polygonZ.createProperty(COORDINATES, PropertyType.EMBEDDEDLIST,
          PropertyType.EMBEDDEDLIST);
    }
  }

  @Override
  public JtsGeometry fromResult(Result document) {
    List<List<List<Number>>> coordinates = document.getProperty("coordinates");
    return toShape(createPolygon(coordinates));
  }

  protected static Polygon createPolygon(List<List<List<Number>>> coordinates) {
    Polygon shape;
    if (coordinates.size() == 1) {
      var coords = coordinates.getFirst();
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

  protected static LinearRing createLinearRing(List<List<Number>> coords) {
    var crs = new Coordinate[coords.size()];
    var i = 0;
    for (var points : coords) {
      crs[i] = new Coordinate(points.get(0).doubleValue(), points.get(1).doubleValue());
      i++;
    }
    return GEOMETRY_FACTORY.createLinearRing(crs);
  }

  @Override
  public EmbeddedEntity toEmbeddedEntity(JtsGeometry shape, DatabaseSessionInternal session) {

    var entity = session.newEmbeddedEntity(getName());
    var polygon = (Polygon) shape.getGeom();
    var polyCoordinates = coordinatesFromPolygon(polygon);
    entity.newEmbeddedList(COORDINATES, polyCoordinates);
    return entity;
  }

  @Override
  protected EmbeddedEntity toEmbeddedEntity(JtsGeometry shape, Geometry geometry,
      DatabaseSessionInternal session) {
    if (geometry == null || Double.isNaN(geometry.getCoordinate().getZ())) {
      return toEmbeddedEntity(shape, session);
    }

    var entity = session.newEmbeddedEntity(getName() + "Z");
    var polygon = (Polygon) shape.getGeom();
    var polyCoordinates = coordinatesFromPolygonZ(geometry);
    entity.newEmbeddedList(COORDINATES, polyCoordinates);
    return entity;
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
  public String asText(EmbeddedEntity entity) {
    if (Objects.equals(entity.getSchemaClassName(), "OPolygonZ")) {
      List<List<List<Double>>> coordinates = entity.getProperty("coordinates");

      var result =
          coordinates.stream()
              .map(
                  poly ->
                      "("
                          + poly.stream()
                          .map(
                              point ->
                                  (point.stream()
                                      .map(this::format)
                                      .collect(Collectors.joining(" "))))
                          .collect(Collectors.joining(", "))
                          + ")")
              .collect(Collectors.joining(" "));
      return "POLYGON Z (" + result + ")";
    } else {
      return super.asText(entity);
    }
  }
}
