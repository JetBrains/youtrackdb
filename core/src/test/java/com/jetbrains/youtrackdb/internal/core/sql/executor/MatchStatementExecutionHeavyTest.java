package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.query.BasicResultSet;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPrefetchStep;
import org.junit.Assert;
import org.junit.Test;

/**
 * Heavyweight MATCH statement tests that create 1000 vertices with indexed edges.
 *
 * <p>These tests were extracted from {@link MatchStatementExecutionNewTest} because
 * the heavy {@link #initEdgeIndexTest()} setup (1000 vertices, 100 edge batches)
 * causes PIT minion flakiness under instrumentation. Isolating them in a dedicated
 * class allows PIT to exclude only the heavy tests while keeping the rest of the
 * MATCH test suite in the mutation analysis.
 */
public class MatchStatementExecutionHeavyTest extends DbTestBase {

  private void initEdgeIndexTest() {
    session.execute("CREATE class IndexedVertex extends V").close();
    session.execute("CREATE property IndexedVertex.uid INTEGER").close();
    session.execute("CREATE index IndexedVertex_uid on IndexedVertex (uid) NOTUNIQUE").close();

    session.execute("CREATE class IndexedEdge extends E").close();
    session.execute("CREATE property IndexedEdge.out LINK").close();
    session.execute("CREATE property IndexedEdge.in LINK").close();
    session.execute("CREATE index IndexedEdge_out_in on IndexedEdge (out, in) NOTUNIQUE").close();

    var nodes = 1000;

    session.executeInTx(
        transaction -> {
          for (var i = 0; i < nodes; i++) {
            var doc = (EntityImpl) session.newVertex("IndexedVertex");
            doc.setProperty("uid", i);
          }
        });

    for (var i = 0; i < 100; i++) {
      var cmd =
          "CREATE EDGE IndexedEDGE FROM (SELECT FROM IndexedVertex WHERE uid = 0) TO (SELECT FROM"
              + " IndexedVertex WHERE uid > "
              + (i * nodes / 100)
              + " and uid <"
              + ((i + 1) * nodes / 100)
              + ")";

      session.begin();
      session.execute(cmd).close();
      session.commit();
    }
  }

  @Test
  public void testNoPrefetch() {
    initEdgeIndexTest();
    var query = "match " + "{class:IndexedVertex, as: one}" + "return $patterns";

    session.begin();
    var result = session.query(query);
    printExecutionPlan(result);

    result
        .getExecutionPlan().getSteps().stream()
        .filter(y -> y instanceof MatchPrefetchStep)
        .forEach(prefetchStepFound -> Assert.fail());

    for (var i = 0; i < 1000; i++) {
      Assert.assertTrue(result.hasNext());
      result.next();
    }
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testIndexedEdge() {
    initEdgeIndexTest();
    var query =
        "match "
            + "{class:IndexedVertex, as: one, where: (uid = 0)}"
            + ".out('IndexedEdge'){class:IndexedVertex, as: two, where: (uid = 1)}"
            + "return one, two";

    session.begin();
    var result = session.query(query);
    printExecutionPlan(result);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testIndexedEdgeArrows() {
    initEdgeIndexTest();
    var query =
        "match "
            + "{class:IndexedVertex, as: one, where: (uid = 0)}"
            + "-IndexedEdge->{class:IndexedVertex, as: two, where: (uid = 1)}"
            + "return one, two";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testJson() {
    initEdgeIndexTest();
    var query =
        "match "
            + "{class:IndexedVertex, as: one, where: (uid = 0)} "
            + "return {'name':'foo', 'uuid':one.uid}";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testJson2() {
    initEdgeIndexTest();
    var query =
        "match "
            + "{class:IndexedVertex, as: one, where: (uid = 0)} "
            + "return {'name':'foo', 'sub': {'uuid':one.uid}}";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void testJson3() {
    initEdgeIndexTest();
    var query =
        "match "
            + "{class:IndexedVertex, as: one, where: (uid = 0)} "
            + "return {'name':'foo', 'sub': [{'uuid':one.uid}]}";

    session.begin();
    var result = session.query(query);
    Assert.assertTrue(result.hasNext());
    result.next();
    Assert.assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  private void printExecutionPlan(BasicResultSet result) {
    // Intentionally empty — enable the body below for local debugging.
    // result.getExecutionPlan().ifPresent(x -> System.out.println(x.prettyPrint(0, 3)));
  }
}
