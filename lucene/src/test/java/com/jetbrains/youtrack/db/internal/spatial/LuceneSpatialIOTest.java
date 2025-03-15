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
package com.jetbrains.youtrack.db.internal.spatial;

import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.spatial.shape.GeometryCollectionShapeBuilder;
import com.jetbrains.youtrack.db.internal.spatial.shape.LineStringShapeBuilder;
import com.jetbrains.youtrack.db.internal.spatial.shape.MultiLineStringShapeBuilder;
import com.jetbrains.youtrack.db.internal.spatial.shape.MultiPointShapeBuilder;
import com.jetbrains.youtrack.db.internal.spatial.shape.MultiPolygonShapeBuilder;
import com.jetbrains.youtrack.db.internal.spatial.shape.PointShapeBuilder;
import com.jetbrains.youtrack.db.internal.spatial.shape.PolygonShapeBuilder;
import com.jetbrains.youtrack.db.internal.spatial.shape.RectangleShapeBuilder;
import com.jetbrains.youtrack.db.internal.spatial.shape.ShapeFactory;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.spatial4j.shape.Point;
import org.locationtech.spatial4j.shape.jts.JtsGeometry;

/**
 *
 */
public class LuceneSpatialIOTest extends BaseSpatialLuceneTest {

  // POINT
  @Test
  public void testPointIO() throws ParseException, org.locationtech.jts.io.ParseException {

    var doc = ((EntityImpl) session.newEntity("OPoint"));
    doc.setProperty("coordinates", new ArrayList<Double>() {
          {
            add(-100d);
            add(45d);
          }
        });
    var builder = new PointShapeBuilder();

    var p1 = builder.asText(doc);
    Assert.assertNotNull(p1);

    var point = context.makePoint(-100d, 45d);

    var p2 = context.getGeometryFrom(point).toText();

    Assert.assertEquals(p2, p1);

    var parsed = builder.toEntitty(p2);

    Assert.assertEquals(doc.<PointShapeBuilder>getProperty("coordinates"),
        parsed.getProperty("coordinates"));
  }

  // MULTIPOINT
  @Test
  public void testMultiPointIO() {

    var doc = ((EntityImpl) session.newEntity("OMultiPoint"));
    doc.setProperty("coordinates", new ArrayList<List<Double>>() {
          {
            add(Arrays.asList(-71.160281, 42.258729));
            add(Arrays.asList(-71.160837, 42.259113));
            add(Arrays.asList(-71.161144, 42.25932));
          }
        });

    var builder = new MultiPointShapeBuilder();
    var multiPoint = builder.asText(doc);

    List<Coordinate> points =
        new ArrayList<Coordinate>() {
          {
            add(new Coordinate(-71.160281, 42.258729));
            add(new Coordinate(-71.160837, 42.259113));
            add(new Coordinate(-71.161144, 42.25932));
          }
        };

    var multiPoint1 =
        geometryFactory.createMultiPoint(points.toArray(new Coordinate[points.size()]));

    Assert.assertEquals(multiPoint1.toText(), multiPoint);
  }

  // RECTANGLE
  @Test
  @Ignore
  public void testRectangleIO() {

    var doc = rectangle();

    var builder = new RectangleShapeBuilder();

    var rect = builder.asText(doc);

    Assert.assertNotNull(rect);

    var rectangle = context.makeRectangle(-45d, 45d, -30d, 30d);

    var rect1 = context.getGeometryFrom(rectangle).toText();

    Assert.assertEquals(rect1, rect);
  }

  // LINESTRING
  @Test
  public void testLineStringIO() {

    var doc = ((EntityImpl) session.newEntity("OLineString"));
    doc.setProperty("coordinates", new ArrayList<List<Double>>() {
          {
            add(Arrays.asList(-71.160281, 42.258729));
            add(Arrays.asList(-71.160837, 42.259113));
            add(Arrays.asList(-71.161144, 42.25932));
          }
        });

    var builder = new LineStringShapeBuilder();
    var lineString = builder.asText(doc);

    var shape =
        context.makeLineString(
            new ArrayList<Point>() {
              {
                add(context.makePoint(-71.160281, 42.258729));
                add(context.makePoint(-71.160837, 42.259113));
                add(context.makePoint(-71.161144, 42.25932));
              }
            });

    var lineString1 = context.getGeometryFrom(shape).toText();

    Assert.assertEquals(lineString1, lineString);
  }

  // MULTILINESTRING
  @Test
  public void testMultiLineStringIO() {

    var doc = ((EntityImpl) session.newEntity("OMultiLineString"));
    doc.setProperty("coordinates", new ArrayList<List<List<Double>>>() {
          {
            add(
                new ArrayList<List<Double>>() {
                  {
                    add(Arrays.asList(-71.160281, 42.258729));
                    add(Arrays.asList(-71.160837, 42.259113));
                    add(Arrays.asList(-71.161144, 42.25932));
                  }
                });
          }
        });

    var builder = new MultiLineStringShapeBuilder();
    var multiLineString = builder.asText(doc);

    var shape =
        context.makeLineString(
            new ArrayList<Point>() {
              {
                add(context.makePoint(-71.160281, 42.258729));
                add(context.makePoint(-71.160837, 42.259113));
                add(context.makePoint(-71.161144, 42.25932));
              }
            });

    var geometry = (JtsGeometry) shape;

    var lineString = (LineString) geometry.getGeom();
    var multiLineString2 =
        geometryFactory.createMultiLineString(new LineString[]{lineString});
    var multiLineString1 = multiLineString2.toText();

    Assert.assertEquals(multiLineString1, multiLineString);
  }

  // POLYGON
  @Test
  public void testPolygonNoHolesIO() {

    var doc = ((EntityImpl) session.newEntity("OPolygon"));
    doc.setProperty("coordinates", new ArrayList<List<List<Double>>>() {
          {
            add(
                new ArrayList<List<Double>>() {
                  {
                    add(Arrays.asList(-45d, 30d));
                    add(Arrays.asList(45d, 30d));
                    add(Arrays.asList(45d, -30d));
                    add(Arrays.asList(-45d, -30d));
                    add(Arrays.asList(-45d, 30d));
                  }
                });
          }
        });

    List<Coordinate> coordinates = new ArrayList<Coordinate>();
    coordinates.add(new Coordinate(-45, 30));
    coordinates.add(new Coordinate(45, 30));
    coordinates.add(new Coordinate(45, -30));
    coordinates.add(new Coordinate(-45, -30));
    coordinates.add(new Coordinate(-45, 30));

    var builder = new PolygonShapeBuilder();

    var p1 = builder.asText(doc);
    var polygon1 =
        geometryFactory.createPolygon(coordinates.toArray(new Coordinate[coordinates.size()]));
    var p2 = polygon1.toText();
    Assert.assertEquals(p2, p1);
  }

  // POLYGON
  @Test
  public void testPolygonHolesIO() {

    var doc = ((EntityImpl) session.newEntity("OPolygon"));
    doc.setProperty("coordinates", polygonCoordTestHole());

    var polygon1 = polygonTestHole();

    var builder = new PolygonShapeBuilder();
    var p1 = builder.asText(doc);

    var p2 = polygon1.toText();
    Assert.assertEquals(p2, p1);
  }

  // MULTIPOLYGON
  @Test
  public void testMultiPolygon() throws IOException {

    var builder = new MultiPolygonShapeBuilder();
    var multiPolygon = loadMultiPolygon();
    var multiPolygon1 = createMultiPolygon();

    var m1 = builder.asText(multiPolygon);
    var m2 = multiPolygon1.toText();
    Assert.assertEquals(m2, m1);
  }

  // GEOMETRY COLLECTION
  @Test
  public void testGeometryCollection() throws IOException {

    var builder =
        new GeometryCollectionShapeBuilder(ShapeFactory.INSTANCE);
    var geometryCollection = geometryCollection();
    var collection = createGeometryCollection();

    var m1 = builder.asText(geometryCollection);
    var m2 = collection.toText();
    Assert.assertEquals(m2, m1);
  }
}
