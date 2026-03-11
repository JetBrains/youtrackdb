package com.jetbrains.youtrackdb.internal.core.db.graph;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Profiling harness for async-profiler analysis.
 *
 * <p>Usage: java ... ProfilingHarness &lt;workload&gt; &lt;threads&gt; [warmup] [measure]
 *
 * <p>Workloads: getVertices, getEdges, matchQuery
 */
public class ProfilingHarness {

  private static final String DB_NAME = "profilingDb";
  private static final int OUTER_VERTEX_COUNT = 1000;

  public static void main(String[] args) throws Exception {
    String workload = args.length > 0 ? args[0] : "getVertices";
    int threadCount = args.length > 1 ? Integer.parseInt(args[1]) : 1;
    int warmupIters = args.length > 2 ? Integer.parseInt(args[2]) : 3000;
    int measureIters = args.length > 3 ? Integer.parseInt(args[3]) : 10000;

    System.out.printf("Workload: %s, Threads: %d, Warmup: %d, Measure: %d%n",
        workload, threadCount, warmupIters, measureIters);

    var youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPath(ProfilingHarness.class));
    if (youTrackDB.exists(DB_NAME)) {
      youTrackDB.drop(DB_NAME);
    }
    youTrackDB.create(DB_NAME, DatabaseType.DISK,
        new LocalUserCredential("admin", "adminpwd", PredefinedLocalRole.ADMIN));

    // Setup DB
    var setupSession = youTrackDB.open(DB_NAME, "admin", "adminpwd");
    setupSession.getSchema().createVertexClass("CenterVertex");
    setupSession.getSchema().createVertexClass("OuterVertex");
    setupSession.getSchema().createEdgeClass("HeavyEdge");
    var tx = setupSession.begin();
    var center = tx.newVertex("CenterVertex");
    for (int i = 0; i < OUTER_VERTEX_COUNT; i++) {
      var outer = tx.newVertex("OuterVertex");
      center.addStateFulEdge(outer, "HeavyEdge");
    }
    tx.commit();
    RID centerRid = center.getIdentity();
    setupSession.close();

    String matchSql = "MATCH {class:CenterVertex, as:a, where:(@rid = ?)}"
        + ".out('HeavyEdge'){as:b} RETURN b";

    // Warmup with single thread
    System.out.println("Warming up...");
    var warmupSession = youTrackDB.open(DB_NAME, "admin", "adminpwd");
    for (int i = 0; i < warmupIters; i++) {
      runWorkload(warmupSession, workload, centerRid, matchSql);
    }
    warmupSession.close();

    // Measure with N threads
    System.out.printf("Measuring with %d thread(s)...%n", threadCount);
    var barrier = new CyclicBarrier(threadCount + 1);
    var totalOps = new AtomicLong();
    var threads = new Thread[threadCount];

    for (int t = 0; t < threadCount; t++) {
      threads[t] = new Thread(() -> {
        var session = youTrackDB.open(DB_NAME, "admin", "adminpwd");
        try {
          barrier.await(); // sync start
          for (int i = 0; i < measureIters; i++) {
            runWorkload(session, workload, centerRid, matchSql);
          }
          totalOps.addAndGet(measureIters);
          barrier.await(); // sync end
        } catch (Exception e) {
          throw new RuntimeException(e);
        } finally {
          session.close();
        }
      }, "worker-" + t);
      threads[t].start();
    }

    barrier.await(); // release all threads
    long start = System.nanoTime();
    barrier.await(); // wait for all to finish
    long elapsed = System.nanoTime() - start;

    double totalOpsVal = totalOps.get();
    double avgUsPerOp = (elapsed / 1000.0) / totalOpsVal;
    double opsPerSec = totalOpsVal / (elapsed / 1_000_000_000.0);
    System.out.printf("Total: %.0f ops in %.1f ms (%.2f us/op, %.0f ops/s)%n",
        totalOpsVal, elapsed / 1_000_000.0, avgUsPerOp, opsPerSec);

    for (var thread : threads) {
      thread.join();
    }

    youTrackDB.close();
    System.out.println("Done.");
  }

  private static void runWorkload(DatabaseSessionEmbedded session, String workload,
      RID centerRid, String matchSql) {
    switch (workload) {
      case "getVertices" -> {
        var t = session.begin();
        var c = t.load(centerRid).asVertex();
        for (Vertex v : c.getVertices(Direction.OUT, "HeavyEdge")) {
          // consume
        }
        t.commit();
      }
      case "getEdges" -> {
        var t = session.begin();
        var c = t.load(centerRid).asVertex();
        for (var edge : c.getEdges(Direction.OUT, "HeavyEdge")) {
          edge.getTo();
        }
        t.commit();
      }
      case "matchQuery" -> {
        var t = session.begin();
        var rs = t.query(matchSql, centerRid);
        while (rs.hasNext()) {
          rs.next();
        }
        rs.close();
        t.commit();
      }
      default -> throw new IllegalArgumentException("Unknown workload: " + workload);
    }
  }
}
