package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import java.util.HashSet;
import java.util.Set;
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
   * One Author with five outgoing wrote edges. DELETE EDGE FROM/TO targeting the Message with
   * date=3 must remove exactly that edge and leave destinations {0,1,2,4}.
   */
  @Test
  public void testDeleteEdgeFromToRemovesOnlyMatchingDestination() {
    var authorRid = createAuthorWithDatedMessages("DelExact", 5);

    session.begin();
    var deleted =
        session.execute(
            "DELETE EDGE DelExactWrote FROM "
                + authorRid
                + " TO (SELECT FROM DelExactMessage WHERE date = 3)");
    Assert.assertEquals(1L, (long) deleted.next().getProperty("count"));
    deleted.close();
    session.commit();

    Assert.assertEquals(Set.of(0, 1, 2, 4), remainingDestinationDates(authorRid, "DelExactWrote"));
  }

  /**
   * TO selecting several Messages must delete every matching edge and keep the rest. Author has
   * edges to dates 0..4; TO keeps dates 1 and 3 → those two gone, {0,2,4} remain.
   */
  @Test
  public void testDeleteEdgeFromToRemovesAllMatchingDestinations() {
    var authorRid = createAuthorWithDatedMessages("DelMulti", 5);

    session.begin();
    var deleted =
        session.execute(
            "DELETE EDGE DelMultiWrote FROM "
                + authorRid
                + " TO (SELECT FROM DelMultiMessage WHERE date = 1 OR date = 3)");
    Assert.assertEquals(2L, (long) deleted.next().getProperty("count"));
    deleted.close();
    session.commit();

    Assert.assertEquals(Set.of(0, 2, 4), remainingDestinationDates(authorRid, "DelMultiWrote"));
  }

  /**
   * Empty TO deletes nothing. Wrong edge class with FROM+TO must not remove wrote edges either
   * (including those whose destination is outside TO — the pre-fix path used to delete those).
   */
  @Test
  public void testDeleteEdgeFromToEmptyTargetAndWrongClass() {
    var authorRid = createAuthorWithDatedMessages("DelGuard", 5);
    session.createEdgeClass("DelGuardOther");

    session.begin();
    var emptyTo =
        session.execute(
            "DELETE EDGE DelGuardWrote FROM "
                + authorRid
                + " TO (SELECT FROM DelGuardMessage WHERE date = 99)");
    Assert.assertEquals(0L, (long) emptyTo.next().getProperty("count"));
    emptyTo.close();
    session.commit();
    Assert.assertEquals(Set.of(0, 1, 2, 3, 4),
        remainingDestinationDates(authorRid, "DelGuardWrote"));

    session.begin();
    var wrongClass =
        session.execute(
            "DELETE EDGE DelGuardOther FROM "
                + authorRid
                + " TO (SELECT FROM DelGuardMessage WHERE date = 3)");
    Assert.assertEquals(0L, (long) wrongClass.next().getProperty("count"));
    wrongClass.close();
    session.commit();
    Assert.assertEquals(Set.of(0, 1, 2, 3, 4),
        remainingDestinationDates(authorRid, "DelGuardWrote"));
  }

  private RID createAuthorWithDatedMessages(String prefix, int messageCount) {
    session.createVertexClass(prefix + "Author");
    session.createVertexClass(prefix + "Message");
    session.createEdgeClass(prefix + "Wrote");

    session.begin();
    var author = session.newVertex(prefix + "Author");
    author.setProperty("name", "a1");
    for (var i = 0; i < messageCount; i++) {
      var message = session.newVertex(prefix + "Message");
      message.setProperty("date", i);
      author.addEdge(message, prefix + "Wrote");
    }
    var authorRid = author.getIdentity();
    session.commit();
    return authorRid;
  }

  private Set<Integer> remainingDestinationDates(RID authorRid, String edgeClass) {
    session.begin();
    var dates = new HashSet<Integer>();
    try (var rs =
        session.query("SELECT expand(out('" + edgeClass + "')) FROM " + authorRid)) {
      while (rs.hasNext()) {
        dates.add(rs.next().getProperty("date"));
      }
    }
    session.commit();
    return dates;
  }
}
