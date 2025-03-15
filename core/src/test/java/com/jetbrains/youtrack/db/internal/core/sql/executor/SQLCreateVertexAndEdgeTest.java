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

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class SQLCreateVertexAndEdgeTest extends DbTestBase {

  @Test
  public void testCreateEdgeDefaultClass() {
    session.command("create class V1 extends V").close();

    session.command("create class E1 extends E").close();

    // VERTEXES
    session.begin();
    var v1 = session.command("create vertex").next().asVertex();
    session.commit();

    session.begin();
    v1 = session.bindToSession(v1);
    Assert.assertEquals("V", v1.getSchemaClassName());

    var v2 = session.command("create vertex V1").next().asVertex();
    session.commit();

    session.begin();
    v2 = session.bindToSession(v2);
    Assert.assertEquals("V1", v2.getSchemaClassName());

    var v3 = session.command("create vertex set brand = 'fiat'").next().asVertex();
    session.commit();
    session.begin();
    v3 = session.bindToSession(v3);
    Assert.assertEquals("V", v3.getSchemaClassName());
    Assert.assertEquals("fiat", v3.getProperty("brand"));

    var v4 =
        session.command("create vertex V1 set brand = 'fiat',name = 'wow'").next().asVertex();
    session.commit();

    session.begin();
    v4 = session.bindToSession(v4);
    Assert.assertEquals("V1", v4.getSchemaClassName());
    Assert.assertEquals("fiat", v4.getProperty("brand"));
    Assert.assertEquals("wow", v4.getProperty("name"));

    var v5 = session.command("create vertex V1").next().asVertex();
    session.commit();

    session.begin();
    v5 = session.bindToSession(v5);
    Assert.assertEquals("V1", v5.getSchemaClassName());

    // EDGES

    var edges =
        session.command("create edge from " + v1.getIdentity() + " to " + v2.getIdentity());
    session.commit();
    assertEquals(1, edges.stream().count());

    session.begin();
    edges = session.command("create edge E1 from " + v1.getIdentity() + " to " + v3.getIdentity());
    session.commit();
    assertEquals(1, edges.stream().count());

    session.begin();
    edges =
        session.command(
            "create edge from " + v1.getIdentity() + " to " + v4.getIdentity() + " set weight = 3");
    session.commit();

    session.begin();
    EntityImpl e3 = edges.next().getIdentity().getRecord(session);
    Assert.assertEquals("E", e3.getSchemaClassName());
    Assert.assertEquals(e3.getPropertyInternal("out"), session.bindToSession(v1));
    Assert.assertEquals(e3.getPropertyInternal("in"), session.bindToSession(v4));
    Assert.assertEquals(3, e3.<Object>getProperty("weight"));

    edges =
        session.command(
            "create edge E1 from "
                + v2.getIdentity()
                + " to "
                + v3.getIdentity()
                + " set weight = 10");
    session.commit();
    session.begin();
    EntityImpl e4 = edges.next().getIdentity().getRecord(session);
    Assert.assertEquals("E1", e4.getSchemaClassName());
    Assert.assertEquals(e4.getPropertyInternal("out"), session.bindToSession(v2));
    Assert.assertEquals(e4.getPropertyInternal("in"), session.bindToSession(v3));
    Assert.assertEquals(10, e4.<Object>getProperty("weight"));

    edges =
        session.command(
            "create edge e1 from "
                + v3.getIdentity()
                + " to "
                + v5.getIdentity()
                + " set weight = 17");

    EntityImpl e5 = edges.next().getIdentity().getRecord(session);
    Assert.assertEquals("E1", e5.getSchemaClassName());
    session.commit();
  }

  /**
   * from issue #2925
   */
  @Test
  public void testSqlScriptThatCreatesEdge() {
    var cmd = "begin;\n";
    cmd += "let a = create vertex set script = true;\n";
    cmd += "let b = select from v limit 1;\n";
    cmd += "let e = create edge from $a to $b;\n";
    cmd += "commit retry 100;\n";
    cmd += "return $e";

    var result = session.query("select from V");
    var before = result.stream().count();

    session.execute("sql", cmd).close();

    session.begin();
    result = session.query("select from V");
    Assert.assertEquals(result.stream().count(), before + 1);
    session.commit();
  }

  @Test
  public void testNewParser() {
    session.begin();
    var v1 = session.command("create vertex").next().asVertex();
    session.commit();

    session.begin();
    v1 = session.bindToSession(v1);
    Assert.assertEquals("V", v1.getSchemaClassName());

    var vid = v1.getIdentity();

    session.command("create edge from " + vid + " to " + vid).close();

    session.command("create edge E from " + vid + " to " + vid).close();

    session.command("create edge from " + vid + " to " + vid + " set foo = 'bar'").close();

    session.command("create edge E from " + vid + " to " + vid + " set bar = 'foo'").close();
    session.commit();
  }

  @Test
  public void testCannotAlterEClassname() {
    session.command("create class ETest extends E").close();

    try {
      session.command("alter class ETest name ETest2").close();
      Assert.fail();
    } catch (CommandExecutionException e) {
      Assert.assertTrue(true);
    }

    try {
      session.command("alter class ETest name ETest2 unsafe").close();
      Assert.assertTrue(true);
    } catch (CommandExecutionException e) {
      Assert.fail();
    }
  }

  @Test
  public void testSqlScriptThatDeletesEdge() {
    session.begin();
    session.command("create vertex V set name = 'testSqlScriptThatDeletesEdge1'").close();
    session.command("create vertex V set name = 'testSqlScriptThatDeletesEdge2'").close();
    session.command(
            "create edge E from (select from V where name = 'testSqlScriptThatDeletesEdge1') to"
                + " (select from V where name = 'testSqlScriptThatDeletesEdge2') set name ="
                + " 'testSqlScriptThatDeletesEdge'")
        .close();
    session.commit();

    var cmd = "BEGIN;\n";
    cmd += "LET $groupVertices = SELECT FROM V WHERE name = 'testSqlScriptThatDeletesEdge1';\n";
    cmd += "LET $removeRoleEdge = DELETE edge E WHERE outV() in $groupVertices\n;";
    cmd += "COMMIT;\n";
    cmd += "RETURN $groupVertices;\n";

    session.execute("sql", cmd);

    session.begin();
    var edges = session.query("select from E where name = 'testSqlScriptThatDeletesEdge'");
    Assert.assertEquals(0, edges.stream().count());
    session.commit();
  }
}
