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
package com.jetbrains.youtrackdb.internal.spatial.functions;

import com.jetbrains.youtrackdb.internal.spatial.BaseSpatialLuceneTest;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Test;
import org.locationtech.spatial4j.distance.DistanceUtils;

/**
 *
 */
public class LuceneSpatialDistanceSphereTest extends BaseSpatialLuceneTest {

  @Test
  public void testDistanceSphereNoIndex() {

    var execute =
        session.execute(
            "select ST_Distance(ST_GEOMFROMTEXT('POINT(12.4662748"
                + " 41.8914114)'),ST_GEOMFROMTEXT('POINT(12.4664632 41.8904382)')) as distanceDeg,"
                + " \n"
                + "ST_Distance_Sphere(ST_GEOMFROMTEXT('POINT(12.4662748"
                + " 41.8914114)'),ST_GEOMFROMTEXT('POINT(12.4664632 41.8904382)')) as"
                + " distanceMeter");

    var next = execute.next();

    Double distanceDeg = next.getProperty("distanceDeg");
    Double distanceMeter = next.getProperty("distanceMeter");
    Assert.assertNotNull(distanceDeg);
    Assert.assertNotNull(distanceMeter);
    Assert.assertFalse(execute.hasNext());

    Assert.assertEquals(109, distanceMeter.intValue());

    Double d =
        DistanceUtils.degrees2Dist(distanceDeg, DistanceUtils.EARTH_EQUATORIAL_RADIUS_KM) * 1000;
    Assert.assertNotEquals(d, distanceMeter);
  }

  // TODO
  // Need more test with index
  @Test
  public void testWithinIndex() {

    session.execute("create class Place extends v").close();
    session.execute("create property Place.location EMBEDDED OPoint").close();

    session.begin();
    session.execute(
            "insert into Place set name =  'Dar Poeta',location = ST_GeomFromText('POINT(12.4684635"
                + " 41.8914114)')")
        .close();
    session.execute(
            "insert into Place set name  = 'Antilia Pub',location ="
                + " ST_GeomFromText('POINT(12.4686519 41.890438)')")
        .close();

    session.execute(
            "insert into Place set name = 'Museo Di Roma in Trastevere',location ="
                + " ST_GeomFromText('POINT(12.4689762 41.8898916)')")
        .close();
    session.commit();

    session.execute("create index Place.l on Place (location) SPATIAL engine lucene").close();
    session.begin();
    var execute =
        session.execute(
            "SELECT from Place where ST_Distance_Sphere(location, ST_GeomFromText('POINT(12.468933"
                + " 41.890303)')) < 50");

    Assert.assertEquals(2, execute.stream().count());
    execute =
        session.execute(
            "SELECT from Place where ST_Distance_Sphere(location, ST_GeomFromText('POINT(12.468933"
                + " 41.890303)')) > 50");

    Assert.assertEquals(1, execute.stream().count());
    session.commit();
  }

  // Need more test with index
  @Test
  public void testDistanceProjection() {

    session.execute("create class Restaurant extends v").close();
    session.execute("create property Restaurant.location EMBEDDED OPoint").close();

    session.begin();
    session.execute(
            "INSERT INTO  Restaurant SET name = 'London', location = St_GeomFromText(\"POINT"
                + " (-0.1277583 51.5073509)\")")
        .close();
    session.execute(
            "INSERT INTO  Restaurant SET name = 'Trafalgar', location = St_GeomFromText(\"POINT"
                + " (-0.1280688 51.5080388)\")")
        .close();

    session.execute(
            "INSERT INTO  Restaurant SET name = 'Lambeth North Station', location ="
                + " St_GeomFromText(\"POINT (-0.1120681 51.4989103)\")")
        .close();

    session.execute(
            "INSERT INTO  Restaurant SET name = 'Montreal', location = St_GeomFromText(\"POINT"
                + " (-73.567256 45.5016889)\")")
        .close();
    session.commit();

    session.execute("CREATE INDEX bla ON Restaurant (location) SPATIAL ENGINE LUCENE;\n").close();
    var execute =
        session
            .query(
                "SELECT  ST_Distance_Sphere(location, St_GeomFromText(\"POINT (-0.1277583"
                    + " 51.5073509)\")) as dist from Restaurant;")
            .stream()
            .collect(Collectors.toList());

    Assert.assertEquals(4, execute.size());
  }

  @Test
  public void testNullObject() {
    var execute =
        session.execute(
            "select ST_Distance({ locationCoordinates: null },ST_GEOMFROMTEXT('POINT(12.4664632"
                + " 41.8904382)')) as distanceMeter");

    var next = execute.next();
    Assert.assertNull(next.getProperty("distanceMeter"));
    Assert.assertFalse(execute.hasNext());
    execute.close();
  }

  @Test
  public void testSphereNullObject() {
    var execute =
        session.execute(
            "select ST_Distance_Sphere({ locationCoordinates: null"
                + " },ST_GEOMFROMTEXT('POINT(12.4664632 41.8904382)')) as distanceMeter");

    var next = execute.next();
    Assert.assertNull(next.getProperty("distanceMeter"));
    Assert.assertFalse(execute.hasNext());
    execute.close();
  }
}
