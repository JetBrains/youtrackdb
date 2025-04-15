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
package com.jetbrains.youtrack.db.internal.spatial.functions;

import com.jetbrains.youtrack.db.internal.spatial.BaseSpatialLuceneTest;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class LuceneSpatialIntersectsTest extends BaseSpatialLuceneTest {

  @Test
  public void testIntersectsNoIndex() {

    var execute =
        session.query(
            "SELECT ST_Intersects('POINT(0 0)', 'LINESTRING ( 2 0, 0 2 )') as ST_Intersects");
    var next = execute.next();

    Assert.assertEquals(next.getProperty("ST_Intersects"), false);
    execute.close();
    execute =
        session.query(
            "SELECT ST_Intersects('POINT(0 0)', 'LINESTRING ( 0 0, 0 2 )') as ST_Intersects");
    next = execute.next();

    Assert.assertEquals(next.getProperty("ST_Intersects"), true);
    execute.close();
  }

  @Test
  public void testIntersectsIndex() {

    session.execute("create class Lines extends v").close();
    session.execute("create property Lines.geometry EMBEDDED OLINESTRING").close();

    session.begin();
    session.execute("insert into Lines set geometry = ST_GeomFromText('LINESTRING ( 2 0, 0 2 )')")
        .close();
    session.execute("insert into Lines set geometry = ST_GeomFromText('LINESTRING ( 0 0, 0 2 )')")
        .close();
    session.commit();

    session.execute("create index L.g on Lines (geometry) SPATIAL engine lucene").close();
    var execute =
        session.query("SELECT from lines where ST_Intersects(geometry, 'POINT(0 0)') = true");

    Assert.assertEquals(execute.stream().count(), 1);
  }
}
