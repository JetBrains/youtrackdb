/*
 *
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.jetbrains.youtrack.db.internal.spatial;

import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import java.util.ArrayList;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneSpatialTxPointTest extends BaseSpatialLuceneTest {

  @Before
  public void init() {

    Schema schema = session.getMetadata().getSchema();
    var oClass = schema.createVertexClass("City");
    oClass.createProperty("location", PropertyType.EMBEDDED, schema.getClass("OPoint"));
    oClass.createProperty("name", PropertyType.STRING);

    var place = schema.createVertexClass("Place");
    place.createProperty("latitude", PropertyType.DOUBLE);
    place.createProperty("longitude", PropertyType.DOUBLE);
    place.createProperty("name", PropertyType.STRING);

    session.execute("CREATE INDEX City.location ON City(location) SPATIAL ENGINE LUCENE").close();
  }

  protected Entity newCity(String name, final Double longitude, final Double latitude) {
    var location = newPoint(longitude, latitude);

    var city = session.newVertex("City");
    city.setProperty("name", name);
    city.setProperty("location", location);

    return city;
  }

  private Entity newPoint(final Double longitude, final Double latitude) {
    var location = session.newEmbeddedEntity("OPoint");
    location.newEmbeddedList("coordinates", new ArrayList<Double>() {
      {
        add(longitude);
        add(latitude);
      }
    });
    return location;
  }

  @Test
  public void testIndexingTxPoint() {
    session.begin();
    newCity("Rome", 12.5, 41.9);

    var query =
        "select * from City where  ST_WITHIN(location,{ 'shape' : { 'type' : 'ORectangle' ,"
            + " 'coordinates' : [12.314015,41.8262816,12.6605063,41.963125]} }) = true";
    var docs = session.query(query);

    Assert.assertEquals(1, docs.stream().count());

    session.rollback();

    query =
        "select * from City where  ST_WITHIN(location,{ 'shape' : { 'type' : 'ORectangle' ,"
            + " 'coordinates' : [12.314015,41.8262816,12.6605063,41.963125]} }) = true";
    docs = session.query(query);

    Assert.assertEquals(0, docs.stream().count());
  }

  @Test
  public void testIndexingUpdateTxPoint() {
    session.begin();
    var rome = newCity("Rome", -0.1275, 51.507222);
    session.commit();

    session.begin();

    var activeTx = session.getActiveTransaction();
    rome = activeTx.load(rome);
    rome.setProperty("location", newPoint(12.5, 41.9));

    session.commit();

    var query =
        "select * from City where  ST_WITHIN(location,{ 'shape' : { 'type' : 'ORectangle' ,"
            + " 'coordinates' : [12.314015,41.8262816,12.6605063,41.963125]} }) = true";
    var docs = session.query(query);

    Assert.assertEquals(1, docs.stream().count());

    var index = session.getSharedContext().getIndexManager().getIndex(session, "City.location");

    session.begin();
    Assert.assertEquals(1, index.size(session));
    session.commit();
  }

  @Test
  public void testIndexingComplexUpdateTxPoint() {
    session.begin();
    var rome = newCity("Rome", 12.5, 41.9);
    var london = newCity("London", -0.1275, 51.507222);
    session.commit();

    session.begin();

    var activeTx1 = session.getActiveTransaction();
    rome = activeTx1.load(rome);
    var activeTx = session.getActiveTransaction();
    london = activeTx.load(london);

    rome.setProperty("location", newPoint(12.5, 41.9));
    london.setProperty("location", newPoint(-0.1275, 51.507222));
    london.setProperty("location", newPoint(-0.1275, 51.507222));
    london.setProperty("location", newPoint(12.5, 41.9));

    session.commit();

    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(session, "City.location");

    Assert.assertEquals(2, index.size(session));
    session.commit();
  }
}
