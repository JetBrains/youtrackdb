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

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneSpatialPointTest extends BaseSpatialLuceneTest {

  private static final String PWKT = "POINT(-160.2075374 21.9029803)";

  @Before
  public void init() {

    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");
    var oClass = schema.createVertexClass("City");
    oClass.createProperty("location", PropertyType.EMBEDDED, schema.getClass("OPoint"));
    oClass.createProperty("name", PropertyType.STRING);

    var place = schema.createVertexClass("Place");
    place.createProperty("latitude", PropertyType.DOUBLE);
    place.createProperty("longitude", PropertyType.DOUBLE);
    place.createProperty("name", PropertyType.STRING);

    session.execute("CREATE INDEX City.location ON City(location) SPATIAL ENGINE LUCENE").close();

    session.execute("CREATE INDEX Place.l_lon ON Place(latitude,longitude) SPATIAL ENGINE LUCENE")
        .close();

    session.begin();
    newCity(session, "Rome", 12.5, 41.9);
    newCity(session, "London", -0.1275, 51.507222);

    var rome1 = ((EntityImpl) session.newVertex("Place"));
    rome1.setProperty("name", "Rome");
    rome1.setProperty("latitude", 41.9);
    rome1.setProperty("longitude", 12.5);
    session.commit();

    session.begin();
    session.execute(
            "insert into City set name = 'TestInsert' , location = ST_GeomFromText('" + PWKT + "')")
        .close();
    session.commit();
  }

  @Test
  public void testPointWithoutIndex() {

    session.execute("Drop INDEX City.location").close();
    queryPoint();
  }

  @Test
  public void testIndexingPoint() {

    queryPoint();
  }

  protected void queryPoint() {
    // TODO remove = true when parser will support index function without expression
    var query =
        "select * from City where  ST_WITHIN(location,{ 'shape' : { 'type' : 'ORectangle' ,"
            + " 'coordinates' : [12.314015,41.8262816,12.6605063,41.963125]} }) = true";
    var docs = session.query(query);

    Assert.assertEquals(1, docs.stream().count());

    query =
        "select * from City where  ST_WITHIN(location,'POLYGON ((12.314015 41.8262816, 12.314015"
            + " 41.963125, 12.6605063 41.963125, 12.6605063 41.8262816, 12.314015 41.8262816))') ="
            + " true";
    docs = session.query(query);

    Assert.assertEquals(1, docs.stream().count());

    query =
        "select * from City where  ST_WITHIN(location,ST_GeomFromText('POLYGON ((12.314015"
            + " 41.8262816, 12.314015 41.963125, 12.6605063 41.963125, 12.6605063 41.8262816,"
            + " 12.314015 41.8262816))')) = true";
    docs = session.query(query);
    Assert.assertEquals(1, docs.stream().count());

//    query =
//        "select * from City where location && 'LINESTRING(-160.06393432617188"
//            + " 21.996535232496047,-160.1099395751953 21.94304553343818,-160.169677734375"
//            + " 21.89399562866819,-160.21087646484375 21.844928843026818,-160.21018981933594"
//            + " 21.787556698550834)' ";
//    List<?> old = db.query(query).toList();
//
//    Assert.assertEquals(1, old.size());
  }

  protected static EntityImpl newCity(DatabaseSession db, String name, final Double longitude,
      final Double latitude) {
    return db.computeInTx(transaction -> {
      var location = ((EntityImpl) transaction.newEmbeddedEntity("OPoint"));
      location.newEmbeddedList("coordinates", new ArrayList<Double>() {
        {
          add(longitude);
          add(latitude);
        }
      });

      var city = ((EntityImpl) transaction.newVertex("City"));
      city.setProperty("name", name);
      city.setProperty("location", location);
      return city;
    });
  }
}
