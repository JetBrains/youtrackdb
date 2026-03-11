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

/**
 * Simple harness for profiling with async-profiler.
 * Runs the vertex traversal in a tight loop so we can capture CPU profiles.
 *
 * Usage:
 *   java [JVM flags] -cp ... LockProfilingHarness [warmup_iters] [measure_iters]
 *
 * The harness prints timing and the PID so async-profiler can attach.
 */
public class LockProfilingHarness {

  private static final String DB_NAME = "profilingDb";
  private static final int OUTER_VERTEX_COUNT = 1000;

  public static void main(String[] args) throws Exception {
    int warmupIters = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
    int measureIters = args.length > 1 ? Integer.parseInt(args[1]) : 20000;

    System.out.println("PID: " + ProcessHandle.current().pid());
    System.out.println("Setting up DB...");

    var youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPath(LockProfilingHarness.class));
    if (youTrackDB.exists(DB_NAME)) {
      youTrackDB.drop(DB_NAME);
    }
    youTrackDB.create(DB_NAME, DatabaseType.DISK,
        new LocalUserCredential("admin", "adminpwd", PredefinedLocalRole.ADMIN));

    var session = youTrackDB.open(DB_NAME, "admin", "adminpwd");
    session.getSchema().createVertexClass("CenterVertex");
    session.getSchema().createVertexClass("OuterVertex");
    session.getSchema().createEdgeClass("HeavyEdge");

    var tx = session.begin();
    var center = tx.newVertex("CenterVertex");
    for (int i = 0; i < OUTER_VERTEX_COUNT; i++) {
      var outer = tx.newVertex("OuterVertex");
      center.addStateFulEdge(outer, "HeavyEdge");
    }
    tx.commit();
    RID centerRid = center.getIdentity();

    // ---- Warmup ----
    System.out.println("Warming up (" + warmupIters + " iterations)...");
    for (int i = 0; i < warmupIters; i++) {
      doGetVertices(session, centerRid);
    }

    // Signal ready for profiler attachment
    System.out.println("READY - attach profiler now if needed. Starting measurement...");

    // ---- Measure ----
    long start = System.nanoTime();
    for (int i = 0; i < measureIters; i++) {
      doGetVertices(session, centerRid);
    }
    long elapsed = System.nanoTime() - start;

    double avgUs = (elapsed / 1000.0) / measureIters;
    System.out.printf("Completed %d iterations in %.1f ms (avg %.2f us/op)%n",
        measureIters, elapsed / 1_000_000.0, avgUs);

    // ---- Match query benchmark ----
    System.out.println("Running MATCH query benchmark...");
    String matchSql = "MATCH {class:CenterVertex, as:a, where:(@rid = ?)}"
        + ".out('HeavyEdge'){as:b} RETURN b";

    // Warmup match
    for (int i = 0; i < warmupIters; i++) {
      doMatchQuery(session, matchSql, centerRid);
    }

    start = System.nanoTime();
    for (int i = 0; i < measureIters; i++) {
      doMatchQuery(session, matchSql, centerRid);
    }
    elapsed = System.nanoTime() - start;
    avgUs = (elapsed / 1000.0) / measureIters;
    System.out.printf("MATCH: %d iterations in %.1f ms (avg %.2f us/op)%n",
        measureIters, elapsed / 1_000_000.0, avgUs);

    session.close();
    youTrackDB.close();
  }

  private static void doGetVertices(DatabaseSessionEmbedded session, RID centerRid) {
    var tx = session.begin();
    var center = tx.load(centerRid).asVertex();
    int count = 0;
    for (Vertex v : center.getVertices(Direction.OUT, "HeavyEdge")) {
      count++;
    }
    if (count != OUTER_VERTEX_COUNT) {
      throw new AssertionError("Expected " + OUTER_VERTEX_COUNT + " but got " + count);
    }
    tx.commit();
  }

  private static void doMatchQuery(DatabaseSessionEmbedded session, String sql, RID centerRid) {
    var tx = session.begin();
    var rs = tx.query(sql, centerRid);
    int count = 0;
    while (rs.hasNext()) {
      rs.next();
      count++;
    }
    rs.close();
    if (count != OUTER_VERTEX_COUNT) {
      throw new AssertionError("Expected " + OUTER_VERTEX_COUNT + " but got " + count);
    }
    tx.commit();
  }
}
