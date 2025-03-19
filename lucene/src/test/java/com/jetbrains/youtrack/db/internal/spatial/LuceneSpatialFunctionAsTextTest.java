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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.text.ParseException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 */
public class LuceneSpatialFunctionAsTextTest extends BaseSpatialLuceneTest {

  @Before
  public void init() {

    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");
    var oClass = schema.createClass("Location");
    oClass.addSuperClass(v);
    oClass.createProperty("geometry", PropertyType.EMBEDDED, schema.getClass("OShape"));
    oClass.createProperty("name", PropertyType.STRING);

    initData();
  }

  private void initData() {

    createLocation("OPoint", point());
    createLocation("OMultiPoint", multiPoint());
    createLocation("OLineString", lineStringDoc());
    createLocation("OMultiLineString", multiLineString());
    createLocation("ORectangle", rectangle());
    createLocation("OPolygon", polygon());
    createLocation("OMultiPolygon", loadMultiPolygon());
    createLocation("OGeometryCollection", geometryCollection());
  }

  protected void createLocation(String name, EntityImpl geometry) {
    var doc = ((EntityImpl) session.newEntity("Location"));
    doc.setProperty("name", name);
    doc.setProperty("geometry", geometry);

    session.begin();
    session.commit();
  }

  @Test
  public void testPoint() {

    queryAndAssertGeom("OPoint", POINTWKT);
  }

  protected void queryAndAssertGeom(String name, String wkt) {
    var results =
        session.execute("select *, ST_AsText(geometry) as text from Location where name = ? ",
            name);

    assertTrue(results.hasNext());
    var doc = results.next();

    String asText = doc.getProperty("text");

    Assert.assertNotNull(asText);
    Assert.assertEquals(asText, wkt);
    assertFalse(results.hasNext());
  }

  @Test
  public void testMultiPoint() {

    queryAndAssertGeom("OMultiPoint", MULTIPOINTWKT);
  }

  @Test
  public void testLineString() {
    queryAndAssertGeom("OLineString", LINESTRINGWKT);
  }

  @Test
  public void testMultiLineString() {
    queryAndAssertGeom("OMultiLineString", MULTILINESTRINGWKT);
  }

  @Test
  @Ignore
  public void testRectangle() {
    queryAndAssertGeom("ORectangle", RECTANGLEWKT);
  }

  @Test
  public void testBugEnvelope() {
    try {
      var shape = context.readShapeFromWkt(RECTANGLEWKT);

      var geometryFrom = context.getGeometryFrom(shape);
      Assert.assertEquals(geometryFrom.toText(), RECTANGLEWKT);
    } catch (ParseException e) {
      e.printStackTrace();
    }
  }

  @Test
  public void testPolygon() {
    queryAndAssertGeom("OPolygon", POLYGONWKT);
  }

  @Test
  public void testGeometryCollection() {
    queryAndAssertGeom("OGeometryCollection", GEOMETRYCOLLECTION);
  }

  @Test
  public void testMultiPolygon() {
    queryAndAssertGeom("OMultiPolygon", MULTIPOLYGONWKT);
  }
}
