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

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.spatial.shape.legacy.PointLegecyBuilder;
import java.io.IOException;
import java.util.List;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 */
public class LuceneSpatialFunctionFromTextTest extends BaseSpatialLuceneTest {

  @Test
  public void geomFromTextLineStringTest() {

    var point = lineStringDoc();
    checkFromText(point, "select ST_GeomFromText('" + LINESTRINGWKT + "') as geom");
  }

  protected void checkFromText(EntityImpl source, String query) {

    var docs = session.command(query);

    assertTrue(docs.hasNext());

    var geom = ((Result) docs.next().getProperty("geom")).asEntityOrNull();
    assertGeometry(source, geom);
    assertFalse(docs.hasNext());
  }

  private void assertGeometry(Entity source, Entity geom) {
    Assert.assertNotNull(geom);

    Assert.assertNotNull(geom.getProperty("coordinates"));

    Assert.assertEquals(
        source.getSchemaClassName(), geom.getSchemaClassName());
    Assert.assertEquals(
        geom.<PointLegecyBuilder>getProperty("coordinates"), source.getProperty("coordinates"));
  }

  @Test
  public void geomFromTextMultiLineStringTest() {

    var point = multiLineString();
    checkFromText(point, "select ST_GeomFromText('" + MULTILINESTRINGWKT + "') as geom");
  }

  @Test
  public void geomFromTextPointTest() {

    var point = point();
    checkFromText(point, "select ST_GeomFromText('" + POINTWKT + "') as geom");
  }

  @Test
  public void geomFromTextMultiPointTest() {

    var point = multiPoint();
    checkFromText(point, "select ST_GeomFromText('" + MULTIPOINTWKT + "') as geom");
  }

  // TODO enable
  @Test
  @Ignore
  public void geomFromTextRectangleTest() {
    var polygon = rectangle();
    // RECTANGLE
    checkFromText(polygon, "select ST_GeomFromText('" + RECTANGLEWKT + "') as geom");
  }

  @Test
  public void geomFromTextPolygonTest() {
    var polygon = polygon();
    checkFromText(polygon, "select ST_GeomFromText('" + POLYGONWKT + "') as geom");
  }

  @Test
  public void geomFromTextMultiPolygonTest() throws IOException {
    var polygon = loadMultiPolygon();

    checkFromText(polygon, "select ST_GeomFromText('" + MULTIPOLYGONWKT + "') as geom");
  }

  @Test
  public void geomCollectionFromText() {
    checkFromCollectionText(
        geometryCollection(), "select ST_GeomFromText('" + GEOMETRYCOLLECTION + "') as geom");
  }

  protected void checkFromCollectionText(EntityImpl source, String query) {

    var docs = session.command(query);

    assertTrue(docs.hasNext());
    var geom = ((Result) docs.next().getProperty("geom")).asEntityOrNull();
    assertFalse(docs.hasNext());
    Assert.assertNotNull(geom);

    Assert.assertNotNull(geom.getProperty("geometries"));

    Assert.assertEquals(source.getSchemaClassName(), geom.getSchemaClassName());

    List<EntityImpl> sourceCollection = source.getProperty("geometries");
    List<EntityImpl> targetCollection = source.getProperty("geometries");
    Assert.assertEquals(sourceCollection.size(), targetCollection.size());

    var i = 0;
    for (var entries : sourceCollection) {
      assertGeometry(entries, targetCollection.get(i));
      i++;
    }
  }
}
