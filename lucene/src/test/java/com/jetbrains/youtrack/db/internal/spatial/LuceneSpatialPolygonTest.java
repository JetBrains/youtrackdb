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
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 */
public class LuceneSpatialPolygonTest extends BaseSpatialLuceneTest {

  @Before
  public void init() {
    Schema schema = session.getMetadata().getSchema();
    var oClass = schema.createVertexClass("Place");
    oClass.createProperty("location", PropertyType.EMBEDDED, schema.getClass("OPolygon"));
    oClass.createProperty("name", PropertyType.STRING);

    session.execute("CREATE INDEX Place.location ON Place(location) SPATIAL ENGINE LUCENE").close();
  }

  @Test
  @Ignore
  public void testPolygonWithoutIndex() throws IOException {
    testIndexingPolygon();
    session.execute("drop index Place.location").close();
    queryPolygon();
  }

  protected void queryPolygon() {
    session.begin();
    var query = "select * from Place where location && 'POINT(13.383333 52.516667)'";
    var docs = session.query(query).entityStream().toList();

    Assert.assertEquals(docs.size(), 1);

    query = "select * from Place where location && 'POINT(12.5 41.9)'";
    docs = session.query(query).entityStream().toList();

    Assert.assertEquals(docs.size(), 0);
    session.commit();
  }

  @Test
  @Ignore
  public void testIndexingPolygon() throws IOException {
    session.begin();
    var systemResourceAsStream = ClassLoader.getSystemResourceAsStream("germany.json");

    var map = JSONSerializerJackson.mapFromJson(systemResourceAsStream);

    Map geometry = (Map) map.get("geometry");

    var type = (String) geometry.get("type");
    var location = session.newEmbeddedEntity("O" + type);
    //noinspection unchecked
    location.newEmbeddedList("coordinates", (List<Object>) geometry.get("coordinates"));
    var germany = session.newVertex("Place");
    germany.setProperty("name", "Germany");
    germany.setProperty("location", location);
    session.commit();

    var index = session.getSharedContext().getIndexManager().getIndex(session, "Place.location");

    session.begin();
    Assert.assertEquals(1, index.size(session));
    session.commit();
    queryPolygon();
  }
}
