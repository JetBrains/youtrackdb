/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class CommandExecutorSQLDeleteVertexTest extends DbTestBase {

  public void beforeTest() throws Exception {
    super.beforeTest();
    final Schema schema = session.getMetadata().getSlowMutableSchema();
    schema.createClass("User", schema.getClass("V"));
  }

  @Test
  public void testDeleteVertexLimit() throws Exception {
    // for issue #4148

    for (var i = 0; i < 10; i++) {
      session.begin();
      session.execute("create vertex User set name = 'foo" + i + "'").close();
      session.commit();
    }

    session.begin();
    session.execute("delete vertex User limit 4").close();
    session.commit();

    session.begin();
    var result = session.query("select from User");
    Assert.assertEquals(result.stream().count(), 6);
    session.commit();
  }

  @Test
  public void testDeleteVertexBatch() throws Exception {
    // for issue #4622

    for (var i = 0; i < 100; i++) {
      session.begin();
      session.execute("create vertex User set name = 'foo" + i + "'").close();
      session.commit();
    }

    session.begin();
    session.execute("delete vertex User batch 5").close();
    session.commit();

    var result = session.query("select from User");
    Assert.assertEquals(result.stream().count(), 0);
  }

  @Test(expected = CommandExecutionException.class)
  public void testDeleteVertexWithEdgeRid() throws Exception {

    session.begin();
    session.execute("create vertex User set name = 'foo1'").close();
    session.execute("create vertex User set name = 'foo2'").close();
    session.execute(
            "create edge E from (select from user where name = 'foo1') to (select from user where"
                + " name = 'foo2')")
        .close();
    session.commit();

    session.begin();
    try (var edges = session.query("select from e limit 1")) {
      session.execute("delete vertex [" + edges.next().getIdentity() + "]").close();
      Assert.fail("Error on deleting a vertex with a rid of an edge");
    }
    session.rollback();
  }

  @Test
  public void testDeleteVertexFromSubquery() throws Exception {
    // for issue #4523

    for (var i = 0; i < 100; i++) {
      session.begin();
      session.execute("create vertex User set name = 'foo" + i + "'").close();
      session.commit();
    }

    session.begin();
    session.execute("delete vertex from (select from User)").close();
    session.commit();

    var result = session.query("select from User");
    Assert.assertEquals(result.stream().count(), 0);
  }

  @Test
  public void testDeleteVertexFromSubquery2() throws Exception {
    // for issue #4523

    for (var i = 0; i < 100; i++) {
      session.begin();
      session.execute("create vertex User set name = 'foo" + i + "'").close();
      session.commit();
    }

    session.begin();
    session.execute("delete vertex from (select from User where name = 'foo10')").close();
    session.commit();

    session.begin();
    var result = session.query("select from User");
    Assert.assertEquals(result.stream().count(), 99);
    session.commit();
  }
}
