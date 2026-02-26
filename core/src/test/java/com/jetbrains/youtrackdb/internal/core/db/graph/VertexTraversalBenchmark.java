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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * JMH benchmark comparing the optimized getVertices() path (which reads vertices
 * directly from LinkBag secondary RIDs) against the baseline getEdges()+getTo()
 * path (which loads edge records first, then follows to the opposite vertex).
 *
 * <p>Setup: star graph with 1 center vertex connected to 1000 outer vertices
 * via heavyweight "HeavyEdge" class, in a MEMORY database (no disk I/O noise).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class VertexTraversalBenchmark {

  private static final String DB_NAME = "benchmarkDb";
  private static final int OUTER_VERTEX_COUNT = 1000;

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded session;
  private RID centerRid;

  @Setup(Level.Trial)
  public void setUp() {
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPath(VertexTraversalBenchmark.class));
    if (youTrackDB.exists(DB_NAME)) {
      youTrackDB.drop(DB_NAME);
    }
    youTrackDB.create(DB_NAME, DatabaseType.MEMORY,
        new LocalUserCredential("admin", "adminpwd", PredefinedLocalRole.ADMIN));
    session = youTrackDB.open(DB_NAME, "admin", "adminpwd");

    session.getSchema().createVertexClass("CenterVertex");
    session.getSchema().createVertexClass("OuterVertex");
    session.getSchema().createEdgeClass("HeavyEdge");

    // Build star graph: center -> 1000 outer vertices
    var tx = session.begin();
    var center = tx.newVertex("CenterVertex");
    for (var i = 0; i < OUTER_VERTEX_COUNT; i++) {
      var outer = tx.newVertex("OuterVertex");
      center.addStateFulEdge(outer, "HeavyEdge");
    }
    tx.commit();

    centerRid = center.getIdentity();
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    session.close();
    youTrackDB.close();
  }

  /**
   * Optimized path: getVertices(OUT, "HeavyEdge") reads vertices directly from
   * LinkBag secondary RIDs, skipping edge record loads.
   */
  @Benchmark
  public void getVertices_optimized(Blackhole bh) {
    var tx = session.begin();
    var center = tx.load(centerRid).asVertex();

    for (Vertex v : center.getVertices(Direction.OUT, "HeavyEdge")) {
      bh.consume(v);
    }
    tx.commit();
  }

  /**
   * Baseline path: getEdges(OUT, "HeavyEdge") loads each edge record, then
   * calls getTo() to reach the opposite vertex.
   */
  @Benchmark
  public void getEdges_thenVertex_baseline(Blackhole bh) {
    var tx = session.begin();
    var center = tx.load(centerRid).asVertex();

    for (var edge : center.getEdges(Direction.OUT, "HeavyEdge")) {
      bh.consume(edge.getTo());
    }
    tx.commit();
  }

  /**
   * MATCH query baseline: uses the SQL MATCH clause to traverse edges.
   */
  @Benchmark
  public void matchQuery(Blackhole bh) {
    var tx = session.begin();

    var resultSet = tx.query(
        "MATCH {class:CenterVertex, as:a, where:(@rid = ?)}"
            + ".out('HeavyEdge'){as:b} RETURN b",
        centerRid);
    while (resultSet.hasNext()) {
      bh.consume(resultSet.next());
    }
    resultSet.close();

    tx.commit();
  }

  public static void main(String[] args) throws Exception {
    var opt = new OptionsBuilder()
        .include(VertexTraversalBenchmark.class.getSimpleName())
        .build();
    new Runner(opt).run();
  }
}
