package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests execution of DELETE EDGE SQL statements.
 */
public class DeleteEdgeStatementExecutionTest extends DbTestBase {

  @Test
  public void testDeleteSingleEdge() {
    var vertexClassName = "testDeleteSingleEdgeV";
    session.createVertexClass(vertexClassName);

    var edgeClassName = "testDeleteSingleEdgeE";
    session.createEdgeClass(edgeClassName);

    Vertex prev = null;
    for (var i = 0; i < 10; i++) {
      session.begin();
      var v1 = session.newVertex(vertexClassName);
      v1.setProperty("name", "a" + i);
      if (prev != null) {
        var activeTx = session.getActiveTransaction();
        prev = activeTx.load(prev);
        prev.addEdge(v1, edgeClassName);
      }
      prev = v1;
      session.commit();
    }

    session.begin();
    var rs = session.query("SELECT expand(out()) FROM " + vertexClassName);
    Assert.assertEquals(9, rs.stream().count());
    rs.close();

    rs = session.query("SELECT expand(in()) FROM " + vertexClassName);
    Assert.assertEquals(9, rs.stream().count());
    rs.close();

    session.execute(
        "DELETE EDGE "
            + edgeClassName
            + " from (SELECT FROM "
            + vertexClassName
            + " where name = 'a1') to (SELECT FROM "
            + vertexClassName
            + " where name = 'a2')")
        .close();
    session.commit();

    session.begin();
    rs = session.query("SELECT FROM " + edgeClassName);
    Assert.assertEquals(8, rs.stream().count());
    rs.close();

    rs = session.query("SELECT expand(out()) FROM " + vertexClassName + " where name = 'a1'");
    Assert.assertEquals(0, rs.stream().count());
    rs.close();

    rs = session.query("SELECT expand(in()) FROM " + vertexClassName + " where name = 'a2'");
    Assert.assertEquals(0, rs.stream().count());
    rs.close();
    session.commit();
  }

  @Test
  public void testDeleteAll() {
    var vertexClassName = "testDeleteAllV";
    session.createVertexClass(vertexClassName);

    var edgeClassName = "testDeleteAllE";
    session.createEdgeClass(edgeClassName);

    Vertex prev = null;
    for (var i = 0; i < 10; i++) {
      session.begin();
      var v1 = session.newVertex(vertexClassName);
      v1.setProperty("name", "a" + i);
      if (prev != null) {
        var activeTx = session.getActiveTransaction();
        prev = activeTx.load(prev);
        prev.addEdge(v1, edgeClassName);
      }
      prev = v1;
      session.commit();
    }

    session.begin();
    var rs = session.query("SELECT expand(out()) FROM " + vertexClassName);
    Assert.assertEquals(9, rs.stream().count());
    rs.close();

    rs = session.query("SELECT expand(in()) FROM " + vertexClassName);
    Assert.assertEquals(9, rs.stream().count());
    rs.close();

    session.execute("DELETE EDGE " + edgeClassName).close();
    session.commit();

    session.begin();
    rs = session.query("SELECT FROM " + edgeClassName);
    Assert.assertEquals(0, rs.stream().count());
    rs.close();

    rs = session.query("SELECT expand(out()) FROM " + vertexClassName);
    Assert.assertEquals(0, rs.stream().count());
    rs.close();

    rs = session.query("SELECT expand(in()) FROM " + vertexClassName);
    Assert.assertEquals(0, rs.stream().count());
    rs.close();
    session.commit();
  }

  /**
   * DELETE EDGE with FROM and TO must delete only edges whose destination is in TO. One Author
   * with five outgoing wrote edges; TO selects a single Message. Expect count 1 and four remaining
   * edges after commit. Covers subquery/subquery, subquery/RID, RID/subquery, and RID/RID forms.
   */
  @Test
  public void testDeleteEdgeFromToKeepsEdgesToOtherVertices() {
    session.createVertexClass("YTDB1653Author");
    session.createVertexClass("YTDB1653Message");
    session.createEdgeClass("YTDB1653Wrote");

    session.begin();
    var author = session.newVertex("YTDB1653Author");
    author.setProperty("name", "a1");
    var messages = new RID[5];
    for (var i = 0; i < 5; i++) {
      var message = session.newVertex("YTDB1653Message");
      message.setProperty("date", i);
      author.addEdge(message, "YTDB1653Wrote");
      messages[i] = message.getIdentity();
    }
    var authorRid = author.getIdentity();
    session.commit();

    assertEdgeCount("YTDB1653Wrote", 5);

    deleteAndAssertOneEdge(
        "DELETE EDGE YTDB1653Wrote FROM (SELECT FROM YTDB1653Author WHERE name = 'a1')"
            + " TO (SELECT FROM YTDB1653Message WHERE date = 3)",
        authorRid,
        4);

    rebuildFiveWroteEdges(authorRid, messages);

    deleteAndAssertOneEdge(
        "DELETE EDGE YTDB1653Wrote FROM (SELECT FROM YTDB1653Author WHERE name = 'a1')"
            + " TO "
            + messages[3],
        authorRid,
        4);

    rebuildFiveWroteEdges(authorRid, messages);

    deleteAndAssertOneEdge(
        "DELETE EDGE YTDB1653Wrote FROM "
            + authorRid
            + " TO (SELECT FROM YTDB1653Message WHERE date = 3)",
        authorRid,
        4);

    rebuildFiveWroteEdges(authorRid, messages);

    deleteAndAssertOneEdge(
        "DELETE EDGE YTDB1653Wrote FROM " + authorRid + " TO " + messages[3],
        authorRid,
        4);
  }

  /**
   * Empty TO set must delete nothing (not every outgoing edge of FROM). Wrong edge class with
   * FROM+TO must leave the wrote edges intact while still respecting the TO filter on that class
   * (zero matches here).
   */
  @Test
  public void testDeleteEdgeFromToEmptyTargetAndWrongClass() {
    session.createVertexClass("YTDB1653BAuthor");
    session.createVertexClass("YTDB1653BMessage");
    session.createEdgeClass("YTDB1653BWrote");
    session.createEdgeClass("YTDB1653BOther");

    session.begin();
    var author = session.newVertex("YTDB1653BAuthor");
    author.setProperty("name", "a1");
    for (var i = 0; i < 5; i++) {
      var message = session.newVertex("YTDB1653BMessage");
      message.setProperty("date", i);
      author.addEdge(message, "YTDB1653BWrote");
    }
    var authorRid = author.getIdentity();
    session.commit();

    assertEdgeCount("YTDB1653BWrote", 5);

    session.begin();
    var emptyTo =
        session.execute(
            "DELETE EDGE YTDB1653BWrote FROM "
                + authorRid
                + " TO (SELECT FROM YTDB1653BMessage WHERE date = 99)");
    Assert.assertEquals(0L, (long) emptyTo.next().getProperty("count"));
    emptyTo.close();
    session.commit();
    assertEdgeCount("YTDB1653BWrote", 5);

    session.begin();
    var wrongClass =
        session.execute(
            "DELETE EDGE YTDB1653BOther FROM "
                + authorRid
                + " TO (SELECT FROM YTDB1653BMessage WHERE date = 3)");
    Assert.assertEquals(0L, (long) wrongClass.next().getProperty("count"));
    wrongClass.close();
    session.commit();
    assertEdgeCount("YTDB1653BWrote", 5);

    session.begin();
    var rs =
        session.query("SELECT expand(out('YTDB1653BWrote')) FROM " + authorRid);
    Assert.assertEquals(5, rs.stream().count());
    rs.close();
    session.commit();
  }

  private void rebuildFiveWroteEdges(RID authorRid, RID[] messages) {
    session.begin();
    session.execute("DELETE EDGE YTDB1653Wrote FROM " + authorRid).close();
    session.commit();

    session.begin();
    var author = session.getActiveTransaction().load(authorRid).asVertex();
    for (var messageRid : messages) {
      var message = session.getActiveTransaction().load(messageRid).asVertex();
      author.addEdge(message, "YTDB1653Wrote");
    }
    session.commit();
    assertEdgeCount("YTDB1653Wrote", 5);
  }

  private void deleteAndAssertOneEdge(String deleteSql, RID authorRid, int remaining) {
    session.begin();
    var deleted = session.execute(deleteSql);
    Assert.assertEquals(1L, (long) deleted.next().getProperty("count"));
    deleted.close();
    session.commit();

    assertEdgeCount("YTDB1653Wrote", remaining);

    session.begin();
    var out =
        session.query("SELECT expand(out('YTDB1653Wrote')) FROM " + authorRid);
    Assert.assertEquals(remaining, out.stream().count());
    out.close();
    session.commit();
  }

  private void assertEdgeCount(String edgeClass, long expected) {
    session.begin();
    var rs = session.query("SELECT FROM " + edgeClass);
    Assert.assertEquals(expected, rs.stream().count());
    rs.close();
    session.commit();
  }
}
