/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrack.db.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

public class SQLUpdateEdgeTest extends DbTestBase {

  @Test
  public void testUpdateEdge() {

    session.execute("create class V1 extends V").close();

    session.execute("create class E1 extends E").close();

    // VERTEXES
    session.begin();
    var v1 = session.execute("create vertex").next().asEntity();
    assertEquals("V", v1.getSchemaClass().getName());

    var v2 = session.execute("create vertex V1").next().asEntity();
    assertEquals("V1", v2.getSchemaClass().getName());

    var v3 =
        session.execute("create vertex set vid = 'v3', brand = 'fiat'").next().asEntity();

    assertEquals("V", v3.getSchemaClass().getName());
    assertEquals("fiat", v3.getProperty("brand"));

    var v4 =
        session.execute("create vertex V1 set vid = 'v4',  brand = 'fiat',name = 'wow'")
            .next()
            .asEntity();
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    v4 = activeTx.load(v4);
    assertEquals("V1", v4.getSchemaClassName());
    assertEquals("fiat", v4.getProperty("brand"));
    assertEquals("wow", v4.getProperty("name"));

    var edges =
        session.execute("create edge E1 from " + v1.getIdentity() + " to " + v2.getIdentity());
    var edge = edges.next().asStatefulEdge();
    assertFalse(edges.hasNext());
    assertEquals("E1", edge.getSchemaClassName());
    session.commit();

    session.begin();
    session.execute(
            "update edge E1 set out = "
                + v3.getIdentity()
                + ", in = "
                + v4.getIdentity()
                + " where @rid = "
                + edge.getIdentity())
        .close();
    session.commit();

    session.begin();
    var result = session.query("select expand(out('E1')) from " + v3.getIdentity());
    var vertex4 = result.next();
    Assert.assertEquals("v4", vertex4.getProperty("vid"));

    result = session.query("select expand(in('E1')) from " + v4.getIdentity());
    var vertex3 = result.next();
    Assert.assertEquals("v3", vertex3.getProperty("vid"));

    result = session.query("select expand(out('E1')) from " + v1.getIdentity());
    Assert.assertEquals(0, result.stream().count());

    result = session.query("select expand(in('E1')) from " + v2.getIdentity());
    Assert.assertEquals(0, result.stream().count());
    session.commit();
  }

  @Test
  public void testUpdateEdgeOfTypeE() {
    // issue #6378
    session.begin();
    var v1 = session.execute("create vertex").next().asVertex();
    var v2 = session.execute("create vertex").next().asVertex();
    var v3 = session.execute("create vertex").next().asVertex();
    session.commit();

    session.begin();
    var edges =
        session.execute("create edge E from " + v1.getIdentity() + " to " + v2.getIdentity());
    var edge = edges.next().asStatefulEdge();

    session.execute("UPDATE EDGE " + edge.getIdentity() + " SET in = " + v3.getIdentity());
    session.commit();

    session.begin();
    var result = session.query("select expand(out()) from " + v1.getIdentity());
    Assert.assertEquals(result.next().getIdentity(), v3.getIdentity());

    result = session.query("select expand(in()) from " + v3.getIdentity());
    Assert.assertEquals(result.next().getIdentity(), v1.getIdentity());

    result = session.execute("select expand(in()) from " + v2.getIdentity());
    Assert.assertFalse(result.hasNext());
    session.commit();
  }
}
