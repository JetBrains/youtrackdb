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

import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class LuceneGeoUpdateTest extends BaseSpatialLuceneTest {

  @Test
  public void testUpdate() {

    session.execute("create class City extends V").close();

    session.execute("create property City.location embedded OPoint").close();

    session.execute("CREATE INDEX City.location ON City(location) SPATIAL ENGINE LUCENE").close();
    session.begin();
    session.execute(
            "insert into City set name = 'TestInsert' , location ="
                + " ST_GeomFromText('POINT(-160.2075374 21.9029803)')")
        .close();
    session.commit();

    var index = session.getMetadata().getIndexManagerInternal().getIndex(session, "City.location");

    session.begin();
    session.execute(
            "update City set name = 'TestInsert' , location = ST_GeomFromText('POINT(12.5 41.9)')")
        .close();
    session.commit();

    session.begin();
    Assert.assertEquals(1, index.size(session));
    session.commit();
  }
}
