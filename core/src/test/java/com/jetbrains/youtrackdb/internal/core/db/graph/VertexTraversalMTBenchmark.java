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
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * JMH benchmark measuring vertex traversal throughput in both single-threaded
 * and multi-threaded modes.
 *
 * <p>Setup: star graph with 1 center vertex connected to 1000 outer vertices
 * via heavyweight "HeavyEdge" class, in a DISK database to exercise the page
 * cache read path (LockFreeReadCache).
 *
 * <p>Each thread gets its own {@link DatabaseSessionEmbedded} session to avoid
 * thread-safety issues. The shared DB state is set up once per trial.
 *
 * <p>Benchmark methods:
 * <ul>
 *   <li>{@code st_*} — single-threaded (1 thread)
 *   <li>{@code mt_*} — multi-threaded ({@code Threads.MAX} = all available CPUs)
 * </ul>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(1)
public class VertexTraversalMTBenchmark {

  private static final String DB_NAME = "vertexTraversalMTBenchDb";
  private static final int OUTER_VERTEX_COUNT = 1000;

  // ─── Shared state: one DB instance ─────────────────────────────────

  @State(Scope.Benchmark)
  public static class SharedDb {

    YouTrackDBImpl youTrackDB;
    RID centerRid;

    @Setup(Level.Trial)
    public void setUp() {
      youTrackDB = (YouTrackDBImpl) YourTracks.instance(
          DbTestBase.getBaseDirectoryPath(VertexTraversalMTBenchmark.class));
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
      for (var i = 0; i < OUTER_VERTEX_COUNT; i++) {
        var outer = tx.newVertex("OuterVertex");
        center.addStateFulEdge(outer, "HeavyEdge");
      }
      tx.commit();

      centerRid = center.getIdentity();
      session.close();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
      youTrackDB.close();
    }
  }

  // ─── Per-thread state: each thread gets its own session ────────────

  @State(Scope.Thread)
  public static class ThreadSession {

    DatabaseSessionEmbedded session;

    @Setup(Level.Trial)
    public void setUp(SharedDb db) {
      session = db.youTrackDB.open(DB_NAME, "admin", "adminpwd");
    }

    @TearDown(Level.Trial)
    public void tearDown() {
      session.close();
    }
  }

  // ─── Single-threaded benchmarks ────────────────────────────────────

  @Benchmark
  @Threads(1)
  public void st_getVertices(SharedDb db, ThreadSession ts, Blackhole bh) {
    var tx = ts.session.begin();
    var center = tx.load(db.centerRid).asVertex();
    for (Vertex v : center.getVertices(Direction.OUT, "HeavyEdge")) {
      bh.consume(v);
    }
    tx.commit();
  }

  @Benchmark
  @Threads(1)
  public void st_getEdgesThenVertex(SharedDb db, ThreadSession ts, Blackhole bh) {
    var tx = ts.session.begin();
    var center = tx.load(db.centerRid).asVertex();
    for (var edge : center.getEdges(Direction.OUT, "HeavyEdge")) {
      bh.consume(edge.getTo());
    }
    tx.commit();
  }

  @Benchmark
  @Threads(1)
  public void st_matchQuery(SharedDb db, ThreadSession ts, Blackhole bh) {
    var tx = ts.session.begin();
    var rs = tx.query(
        "MATCH {class:CenterVertex, as:a, where:(@rid = ?)}"
            + ".out('HeavyEdge'){as:b} RETURN b",
        db.centerRid);
    while (rs.hasNext()) {
      bh.consume(rs.next());
    }
    rs.close();
    tx.commit();
  }

  // ─── Multi-threaded benchmarks (Threads.MAX = all CPUs) ────────────

  @Benchmark
  @Threads(Threads.MAX)
  public void mt_getVertices(SharedDb db, ThreadSession ts, Blackhole bh) {
    var tx = ts.session.begin();
    var center = tx.load(db.centerRid).asVertex();
    for (Vertex v : center.getVertices(Direction.OUT, "HeavyEdge")) {
      bh.consume(v);
    }
    tx.commit();
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void mt_getEdgesThenVertex(SharedDb db, ThreadSession ts, Blackhole bh) {
    var tx = ts.session.begin();
    var center = tx.load(db.centerRid).asVertex();
    for (var edge : center.getEdges(Direction.OUT, "HeavyEdge")) {
      bh.consume(edge.getTo());
    }
    tx.commit();
  }

  @Benchmark
  @Threads(Threads.MAX)
  public void mt_matchQuery(SharedDb db, ThreadSession ts, Blackhole bh) {
    var tx = ts.session.begin();
    var rs = tx.query(
        "MATCH {class:CenterVertex, as:a, where:(@rid = ?)}"
            + ".out('HeavyEdge'){as:b} RETURN b",
        db.centerRid);
    while (rs.hasNext()) {
      bh.consume(rs.next());
    }
    rs.close();
    tx.commit();
  }

  public static void main(String[] args) throws Exception {
    var opt = new OptionsBuilder()
        .include(VertexTraversalMTBenchmark.class.getSimpleName())
        .build();
    new Runner(opt).run();
  }
}
