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

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.spatial.BaseSpatialLuceneTest;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class LuceneSpatialWithinTest extends BaseSpatialLuceneTest {

  @Test
  public void testWithinNoIndex() {

    var execute =
        session.query(
            "select ST_Within(smallc,smallc) as smallinsmall,ST_Within(smallc, bigc) As smallinbig,"
                + " ST_Within(bigc,smallc) As biginsmall from (SELECT"
                + " ST_Buffer(ST_GeomFromText('POINT(50 50)'), 20) As"
                + " smallc,ST_Buffer(ST_GeomFromText('POINT(50 50)'), 40) As bigc)");
    var next = execute.next();

    Assert.assertEquals(next.getProperty("smallinsmall"), true);
    Assert.assertEquals(next.getProperty("smallinbig"), true);
    Assert.assertEquals(next.getProperty("biginsmall"), false);
  }

  @Test
  public void testWithinIndex() {

    session.execute("create class Polygon extends v").close();
    session.execute("create property Polygon.geometry EMBEDDED OPolygon").close();

    session.begin();
    session.execute(
            "insert into Polygon set geometry = ST_Buffer(ST_GeomFromText('POINT(50 50)'), 20)")
        .close();
    session.execute(
            "insert into Polygon set geometry = ST_Buffer(ST_GeomFromText('POINT(50 50)'), 40)")
        .close();
    session.commit();

    session.execute("create index Polygon.g on Polygon (geometry) SPATIAL engine lucene").close();
    var execute =
        session.query(
            "SELECT from Polygon where ST_Within(geometry, ST_Buffer(ST_GeomFromText('POINT(50"
                + " 50)'), 50)) = true");

    Assert.assertEquals(execute.stream().count(), 2);

    execute =
        session.query(
            "SELECT from Polygon where ST_Within(geometry, ST_Buffer(ST_GeomFromText('POINT(50"
                + " 50)'), 30)) = true");

    Assert.assertEquals(execute.stream().count(), 1);
  }

  @Test
  public void testWithinNewExecutor() throws Exception {
    session.execute("create class Polygon extends v");
    session.execute("create property Polygon.geometry EMBEDDED OPolygon");

    session.begin();
    session.execute(
        "insert into Polygon set geometry = ST_Buffer(ST_GeomFromText('POINT(50 50)'), 20)");
    session.execute(
        "insert into Polygon set geometry = ST_Buffer(ST_GeomFromText('POINT(50 50)'), 40)");
    session.commit();

    session.execute("create index Polygon.g on Polygon(geometry) SPATIAL ENGINE LUCENE");
    var execute =
        session.query(
            "SELECT from Polygon where ST_Within(geometry, ST_Buffer(ST_GeomFromText('POINT(50"
                + " 50)'), 50)) = true");

    assertThat(IteratorUtils.count(execute)).isEqualTo(2);

    execute =
        session.query(
            "SELECT from Polygon where ST_Within(geometry, ST_Buffer(ST_GeomFromText('POINT(50"
                + " 50)'), 30)) = true");

    assertThat(IteratorUtils.count(execute)).isEqualTo(1);
  }
}
