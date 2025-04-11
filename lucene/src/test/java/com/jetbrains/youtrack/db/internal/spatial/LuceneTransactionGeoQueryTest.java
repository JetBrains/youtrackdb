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
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.lucene.tests.LuceneBaseTest;
import java.util.ArrayList;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 */
public class LuceneTransactionGeoQueryTest extends LuceneBaseTest {

  private static final String PWKT = "POINT(-160.2075374 21.9029803)";

  @Test
  @Ignore
  public void testPointTransactionRollBack() {
    Schema schema = session.getMetadata().getSchema();
    var oClass = schema.createVertexClass("City");
    oClass.createProperty("location", PropertyType.EMBEDDED, schema.getClass("OPoint"));
    oClass.createProperty("name", PropertyType.STRING);

    session.execute("CREATE INDEX City.location ON City(location) SPATIAL ENGINE LUCENE").close();

    var idx = session.getSharedContext().getIndexManager().getIndex(session, "City.location");
    var rome = newCity(session, "Rome", 12.5, 41.9);
    var london = newCity(session, "London", -0.1275, 51.507222);

    session.begin();

    session.execute(
            "insert into City set name = 'TestInsert' , location = ST_GeomFromText('"
                + PWKT
                + "')")
        .close();
    var query =
        "select * from City where location && 'LINESTRING(-160.06393432617188"
            + " 21.996535232496047,-160.1099395751953 21.94304553343818,-160.169677734375"
            + " 21.89399562866819,-160.21087646484375 21.844928843026818,-160.21018981933594"
            + " 21.787556698550834)' ";
    var docs = session.query(query).entityStream().toList();
    Assert.assertEquals(1, docs.size());
    Assert.assertEquals(3, idx.size(session));
    session.rollback();

    query =
        "select * from City where location && 'LINESTRING(-160.06393432617188"
            + " 21.996535232496047,-160.1099395751953 21.94304553343818,-160.169677734375"
            + " 21.89399562866819,-160.21087646484375 21.844928843026818,-160.21018981933594"
            + " 21.787556698550834)' ";
    docs = session.query(query).entityStream().toList();

    session.begin();
    Assert.assertEquals(0, docs.size());
    Assert.assertEquals(0, idx.size(session));
    session.commit();
  }

  @Test
  @Ignore
  public void testPointTransactionUpdate() {
    Schema schema = session.getMetadata().getSchema();
    var oClass = schema.createVertexClass("City");
    oClass.createProperty("location", PropertyType.EMBEDDED, schema.getClass("OPoint"));
    oClass.createProperty("name", PropertyType.STRING);

    session.execute("CREATE INDEX City.location ON City(location) SPATIAL ENGINE LUCENE").close();

    var idx = session.getSharedContext().getIndexManager().getIndex(session, "City.location");
    var rome = newCity(session, "Rome", 12.5, 41.9);

    session.begin();

    session.commit();

    var query =
        "select * from City where location && 'LINESTRING(-160.06393432617188"
            + " 21.996535232496047,-160.1099395751953 21.94304553343818,-160.169677734375"
            + " 21.89399562866819,-160.21087646484375 21.844928843026818,-160.21018981933594"
            + " 21.787556698550834)' ";
    var docs = session.query(query).entityStream().toList();

    session.begin();
    Assert.assertEquals(0, docs.size());
    Assert.assertEquals(1, idx.size(session));

    session.execute("update City set location = ST_GeomFromText('" + PWKT + "')").close();

    query =
        "select * from City where location && 'LINESTRING(-160.06393432617188"
            + " 21.996535232496047,-160.1099395751953 21.94304553343818,-160.169677734375"
            + " 21.89399562866819,-160.21087646484375 21.844928843026818,-160.21018981933594"
            + " 21.787556698550834)' ";
    docs = session.query(query).entityStream().toList();
    Assert.assertEquals(1, docs.size());
    Assert.assertEquals(1, idx.size(session));

    session.commit();

    query =
        "select * from City where location && 'LINESTRING(-160.06393432617188"
            + " 21.996535232496047,-160.1099395751953 21.94304553343818,-160.169677734375"
            + " 21.89399562866819,-160.21087646484375 21.844928843026818,-160.21018981933594"
            + " 21.787556698550834)' ";
    docs = session.query(query).entityStream().toList();

    session.begin();
    Assert.assertEquals(1, docs.size());
    Assert.assertEquals(1, idx.size(session));
    session.commit();
  }

  protected static Entity newCity(DatabaseSession db, String name, final Double longitude,
      final Double latitude) {
    return db.computeInTx(transaction -> {
      var location = transaction.newEmbeddedEntity("OPoint");
      location.newEmbeddedList("coordinates", new ArrayList<Double>() {
        {
          add(longitude);
          add(latitude);
        }
      });

      var city = transaction.newVertex("City");
      city.setProperty("name", name);
      city.setProperty("location", location);
      return city;
    });
  }

}
