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
public class LuceneSpatialTxPointTest extends BaseSpatialLuceneTest {

  @Before
  public void init() {

    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");
    var oClass = schema.createClass("City");
    oClass.addSuperClass(v);
    oClass.createProperty("location", PropertyType.EMBEDDED, schema.getClass("OPoint"));
    oClass.createProperty("name", PropertyType.STRING);

    var place = schema.createClass("Place");
    place.addSuperClass(v);
    place.createProperty("latitude", PropertyType.DOUBLE);
    place.createProperty("longitude", PropertyType.DOUBLE);
    place.createProperty("name", PropertyType.STRING);

    session.command("CREATE INDEX City.location ON City(location) SPATIAL ENGINE LUCENE").close();
  }

  protected EntityImpl newCity(String name, final Double longitude, final Double latitude) {

    var location = newPoint(longitude, latitude);

    var city = ((EntityImpl) session.newEntity("City"));
    city.field("name", name);
    city.field("location", location);
    return city;
  }

  private EntityImpl newPoint(final Double longitude, final Double latitude) {
    var location = ((EntityImpl) session.newEntity("OPoint"));
    location.field(
        "coordinates",
        new ArrayList<Double>() {
          {
            add(longitude);
            add(latitude);
          }
        });
    return location;
  }

  @Test
  public void testIndexingTxPoint() {

    var rome = newCity("Rome", 12.5, 41.9);

    session.begin();

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

    var rome = newCity("Rome", -0.1275, 51.507222);

    session.begin();
    rome = rome;
    session.commit();

    session.begin();

    rome = session.bindToSession(rome);
    rome.field("location", newPoint(12.5, 41.9));

    session.commit();

    var query =
        "select * from City where  ST_WITHIN(location,{ 'shape' : { 'type' : 'ORectangle' ,"
            + " 'coordinates' : [12.314015,41.8262816,12.6605063,41.963125]} }) = true";
    var docs = session.query(query);

    Assert.assertEquals(1, docs.stream().count());

    var index = session.getMetadata().getIndexManagerInternal().getIndex(session, "City.location");

    session.begin();
    Assert.assertEquals(1, index.getInternal().size(session));
    session.commit();
  }

  @Test
  public void testIndexingComplexUpdateTxPoint() {

    var rome = newCity("Rome", 12.5, 41.9);
    var london = newCity("London", -0.1275, 51.507222);

    session.begin();
    rome = rome;
    london = london;
    session.commit();

    session.begin();

    rome = session.bindToSession(rome);
    london = session.bindToSession(london);

    rome.field("location", newPoint(12.5, 41.9));
    london.field("location", newPoint(-0.1275, 51.507222));
    london.field("location", newPoint(-0.1275, 51.507222));
    london.field("location", newPoint(12.5, 41.9));

    session.commit();

    session.begin();
    var index = session.getMetadata().getIndexManagerInternal().getIndex(session, "City.location");

    Assert.assertEquals(2, index.getInternal().size(session));
    session.commit();
  }
}
