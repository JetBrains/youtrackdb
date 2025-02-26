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

    session.command("create class V1 extends V").close();

    session.command("create class E1 extends E").close();

    // VERTEXES
    session.begin();
    var v1 = session.command("create vertex").next().castToEntity();
    assertEquals("V", v1.getSchemaClass().getName(session));

    var v2 = session.command("create vertex V1").next().castToEntity();
    assertEquals("V1", v2.getSchemaClass().getName(session));

    var v3 =
        session.command("create vertex set vid = 'v3', brand = 'fiat'").next().castToEntity();

    assertEquals("V", v3.getSchemaClass().getName(session));
    assertEquals("fiat", v3.getProperty("brand"));

    var v4 =
        session.command("create vertex V1 set vid = 'v4',  brand = 'fiat',name = 'wow'")
            .next()
            .castToEntity();
    session.commit();

    session.begin();
    v4 = session.bindToSession(v4);
    assertEquals("V1", v4.getSchemaClassName());
    assertEquals("fiat", v4.getProperty("brand"));
    assertEquals("wow", v4.getProperty("name"));

    var edges =
        session.command("create edge E1 from " + v1.getIdentity() + " to " + v2.getIdentity());
    var edge = edges.next().castToStatefulEdge();
    assertFalse(edges.hasNext());
    assertEquals("E1", edge.getSchemaClassName());
    session.commit();

    session.begin();
    session.command(
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
    var v1 = session.command("create vertex").next().castToVertex();
    var v2 = session.command("create vertex").next().castToVertex();
    var v3 = session.command("create vertex").next().castToVertex();
    session.commit();

    session.begin();
    var edges =
        session.command("create edge E from " + v1.getIdentity() + " to " + v2.getIdentity());
    var edge = edges.next().castToStatefulEdge();

    session.command("UPDATE EDGE " + edge.getIdentity() + " SET in = " + v3.getIdentity());
    session.commit();

    session.begin();
    var result = session.query("select expand(out()) from " + v1.getIdentity());
    Assert.assertEquals(result.next().getIdentity(), v3.getIdentity());

    result = session.query("select expand(in()) from " + v3.getIdentity());
    Assert.assertEquals(result.next().getIdentity(), v1.getIdentity());

    result = session.command("select expand(in()) from " + v2.getIdentity());
    Assert.assertFalse(result.hasNext());
    session.commit();
  }
}
