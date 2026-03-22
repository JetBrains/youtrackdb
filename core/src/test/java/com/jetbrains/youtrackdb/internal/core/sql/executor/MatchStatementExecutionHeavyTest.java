package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.query.BasicResultSet;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPrefetchStep;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
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

  /// Deterministic reproducer for the MVCC snapshot visibility bug that caused
  /// ConcurrentModificationException in CI (nightly integration tests, YTDB-545).
  ///
  /// The bug: {@code isEntryVisible()} used {@code maxActiveOperationTs} as the upper
  /// visibility bound. A long-running IN_PROGRESS operation (e.g., GC) lowered this bound
  /// below recently committed record versions, making them invisible to subsequent readers.
  /// At commit, the OCC check compared the current position map version (committed) with
  /// the MVCC-filtered version (older, from historical fallback) — mismatch → CME.
  ///
  /// This test deterministically reproduces the race by holding an atomic operation open
  /// (via a latch) while frontend transactions commit and then read+update records.
  /// Without the fix ({@code snapshotTs} upper bound), this test reliably fails with CME.
  @Test
  public void testNoSpuriousCMEWithConcurrentInProgressOperation() throws Exception {
    // Step 1: Create a vertex that we'll later read and update.
    session.executeInTx(tx -> {
      var v = (EntityImpl) session.newVertex("V");
      v.setProperty("name", "target");
    });

    // Step 2: Hold an atomic operation IN_PROGRESS to simulate a long-running background
    // op (GC, checkpoint). This gives it a commitTs that becomes minActiveOperationTs
    // in subsequent snapshots.
    var storage = (AbstractStorage) session.getStorage();
    var atomicOpsManager = storage.getAtomicOperationsManager();

    var bgStarted = new CountDownLatch(1);
    var bgCanFinish = new CountDownLatch(1);
    var bgError = new AtomicReference<Throwable>();

    var bgThread = new Thread(() -> {
      try {
        atomicOpsManager.executeInsideAtomicOperation(op -> {
          // Signal that the operation is now IN_PROGRESS in the table.
          bgStarted.countDown();
          // Block until the main thread signals us to finish.
          bgCanFinish.await();
        });
      } catch (Exception e) {
        bgError.set(e);
      }
    }, "background-op-holder");
    bgThread.start();
    bgStarted.await();

    try {
      // Step 3: While the background op is IN_PROGRESS, update the vertex in a new
      // frontend transaction. This stamps the vertex with a version ABOVE the
      // background op's commitTs.
      session.executeInTx(tx -> {
        var result = session.query("SELECT FROM V WHERE name = 'target'");
        var vertex = result.next().asEntity();
        result.close();
        vertex.setProperty("updated", true);
      });

      // Step 4: In a NEW frontend transaction, read the vertex and update it again.
      // The snapshot includes the background op as IN_PROGRESS, so
      // maxActiveOperationTs is the background op's commitTs (lower than the
      // vertex's current version).
      //
      // Without the fix: the vertex's version (from step 3) is >= maxActiveOperationTs,
      // so isEntryVisible() returns false. The MVCC fallback returns an older version.
      // At commit, OCC sees position map version != read version → CME.
      //
      // With the fix: the vertex's version is > maxActiveOperationTs but <= snapshotTs,
      // so isEntryVisible() correctly returns true. The read sees the current version.
      // At commit, OCC succeeds.
      session.executeInTx(tx -> {
        var result = session.query("SELECT FROM V WHERE name = 'target'");
        var vertex = result.next().asEntity();
        result.close();
        vertex.setProperty("updatedAgain", true);
      });
    } finally {
      // Step 5: Clean up — let the background operation finish.
      bgCanFinish.countDown();
      bgThread.join(10_000);
    }

    Assert.assertNull("Background operation failed: " + bgError.get(), bgError.get());

    // Step 6: Verify the final state is correct.
    session.begin();
    var result = session.query("SELECT FROM V WHERE name = 'target'");
    Assert.assertTrue(result.hasNext());
    var finalVertex = result.next();
    Assert.assertEquals(true, finalVertex.getProperty("updated"));
    Assert.assertEquals(true, finalVertex.getProperty("updatedAgain"));
    result.close();
    session.commit();
  }

  private void printExecutionPlan(BasicResultSet result) {
    // Intentionally empty — enable the body below for local debugging.
    // result.getExecutionPlan().ifPresent(x -> System.out.println(x.prettyPrint(0, 3)));
  }
}
