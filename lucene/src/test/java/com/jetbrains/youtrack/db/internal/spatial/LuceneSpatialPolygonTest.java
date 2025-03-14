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

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLSynchQuery;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneSpatialPolygonTest extends BaseSpatialLuceneTest {

  @Before
  public void init() {
    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");
    var oClass = schema.createClass("Place");
    oClass.addSuperClass(v);
    oClass.createProperty("location", PropertyType.EMBEDDED, schema.getClass("OPolygon"));
    oClass.createProperty("name", PropertyType.STRING);

    session.command("CREATE INDEX Place.location ON Place(location) SPATIAL ENGINE LUCENE").close();
  }

  @Test
  public void testPolygonWithoutIndex() throws IOException {
    testIndexingPolygon();
    session.command("drop index Place.location").close();
    queryPolygon();
  }

  protected void queryPolygon() {

    var query = "select * from Place where location && 'POINT(13.383333 52.516667)'";
    List<EntityImpl> docs = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(docs.size(), 1);

    query = "select * from Place where location && 'POINT(12.5 41.9)'";
    docs = session.query(new SQLSynchQuery<EntityImpl>(query));

    Assert.assertEquals(docs.size(), 0);
  }

  @Test
  public void testIndexingPolygon() throws IOException {

    var systemResourceAsStream = ClassLoader.getSystemResourceAsStream("germany.json");

    EntityImpl doc = ((EntityImpl) session.newEntity()).updateFromJSON(systemResourceAsStream);

    Map geometry = doc.field("geometry");

    var type = (String) geometry.get("type");
    var location = ((EntityImpl) session.newEntity("O" + type));
    location.field("coordinates", geometry.get("coordinates"));
    var germany = ((EntityImpl) session.newEntity("Place"));
    germany.field("name", "Germany");
    germany.field("location", location);

    session.begin();
    session.commit();

    var index = session.getMetadata().getIndexManagerInternal().getIndex(session, "Place.location");

    session.begin();
    Assert.assertEquals(1, index.size(session));
    session.commit();
    queryPolygon();
  }
}
