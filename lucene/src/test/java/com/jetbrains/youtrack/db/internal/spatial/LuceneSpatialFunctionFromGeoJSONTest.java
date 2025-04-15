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

import java.util.HashMap;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class LuceneSpatialFunctionFromGeoJSONTest extends BaseSpatialLuceneTest {

  @Test
  public void geoPointTest() {
    queryAndMatch(
        "POINT(11.11111 12.22222)", "{\"type\":\"Point\",\"coordinates\":[11.11111,12.22222]}");
  }

  @Test
  public void geoLineStringTest() {
    queryAndMatch(
        "LINESTRING(1 2 , 3 4)", "{\"type\":\"LineString\",\"coordinates\":[[1,2],[3,4]]}");
  }

  protected void queryAndMatch(String input, String match) {

    var query =
        session.query(
            "SELECT ST_AsGeoJSON(ST_GeomFromText(:geo)) as geo;",
            new HashMap() {
              {
                put("geo", input);
              }
            });
    var result = query.stream().findFirst().get();
    String geo = result.getProperty("geo");
    Assert.assertEquals(match, geo);
  }
}
