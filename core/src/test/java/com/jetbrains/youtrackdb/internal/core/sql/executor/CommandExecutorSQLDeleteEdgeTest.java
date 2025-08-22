package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.schema.Schema;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 *
 */
@RunWith(JUnit4.class)
public class CommandExecutorSQLDeleteEdgeTest extends DbTestBase {

  private static RID folderId1;
  private static RID userId1;
  private List<Identifiable> edges;

  public void beforeTest() throws Exception {
    super.beforeTest();
    final Schema schema = session.getMetadata().getSchema();
    schema.createClass("User", schema.getClass("V"));
    schema.createClass("Folder", schema.getClass("V"));
    schema.createClass("CanAccess", schema.getClass("E"));

    session.begin();
    var doc = ((EntityImpl) session.newVertex("User"));
    doc.setProperty("username", "gongolo");

    userId1 = doc.getIdentity();
    doc = ((EntityImpl) session.newVertex("Folder"));
    doc.setProperty("keyId", "01234567893");

    folderId1 = doc.getIdentity();
    session.commit();

    session.begin();
    edges =
        session.execute("create edge CanAccess from " + userId1 + " to " + folderId1).stream()
            .map(Result::getIdentity)
            .collect(Collectors.toList());
    session.commit();
  }

  @Test
  public void testFromSelect() {
    session.begin();
    var res =
        session.execute(
            "delete edge CanAccess from (select from User where username = 'gongolo') to "
                + folderId1);
    Assert.assertEquals(1, (long) res.next().getProperty("count"));
    Assert.assertFalse(session.query("select expand(out(CanAccess)) from " + userId1).hasNext());
    session.commit();
  }

  @Test
  public void testFromSelectToSelect() {
    session.begin();
    var res =
        session.execute(
            "delete edge CanAccess from ( select from User where username = 'gongolo' ) to ( select"
                + " from Folder where keyId = '01234567893' )");
    assertEquals(1, (long) res.next().getProperty("count"));
    assertFalse(session.query("select expand(out(CanAccess)) from " + userId1).hasNext());
    session.commit();
  }

  @Test
  public void testDeleteByRID() {
    session.begin();
    var result = session.execute("delete edge [" + edges.get(0).getIdentity() + "]");
    session.commit();
    assertEquals(1L, (long) result.next().getProperty("count"));
  }

  @Test
  public void testDeleteEdgeWithVertexRid() {
    session.begin();
    var vertexes = session.execute("select from v limit 1");
    try {
      session.execute("delete edge [" + vertexes.next().getIdentity() + "]").close();
      session.commit();
      Assert.fail("Error on deleting an edge with a rid of a vertex");
    } catch (Exception e) {
      // OK
    }
  }

  @Test
  public void testDeleteEdgeBatch() {
    // for issue #4622

    for (var i = 0; i < 100; i++) {
      session.begin();
      session.execute("create vertex User set name = 'foo" + i + "'").close();
      session.execute(
              "create edge CanAccess from (select from User where name = 'foo"
                  + i
                  + "') to "
                  + folderId1)
          .close();
      session.commit();
    }

    session.begin();
    session.execute("delete edge CanAccess batch 5").close();
    session.commit();

    session.begin();
    var result = session.query("select expand( in('CanAccess') ) from " + folderId1);
    assertEquals(0, result.stream().count());
    session.commit();
  }
}
