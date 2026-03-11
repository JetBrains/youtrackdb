package com.jetbrains.youtrackdb.internal.core.db.graph;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
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
 * Multi-threaded JMH benchmark measuring MATCH query throughput under contention.
 *
 * <p>Uses a tiny graph (1 center + 2 outer vertices, 2 edges) so that
 * the actual traversal time is minimal and lock-acquisition overhead
 * dominates.
 *
 * <p>Two benchmark groups:
 * <ul>
 *   <li>{@code readOnly} — all 8 threads execute MATCH queries concurrently.
 *       Measures read-lock scalability on {@code stateLock}.
 *   <li>{@code readersPlusDDL} — 7 threads execute MATCH queries while
 *       1 thread performs DDL (create + drop a vertex class) in a loop.
 *       In the BEFORE version DDL acquires {@code stateLock.writeLock()},
 *       blocking all readers. In the AFTER version DDL uses
 *       {@code stateLock.readLock() + ddlLock}, so readers proceed
 *       without blocking.
 * </ul>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(1)
public class MatchContendedBenchmark {

  // ─── Shared state: one DB instance, many sessions ──────────────────

  @State(Scope.Benchmark)
  public static class SharedDb {

    private static final String DB_NAME = "matchContendedBenchDb";

    YouTrackDBImpl youTrackDB;
    String matchSql;

    // Counter for unique DDL class names across threads/iterations
    final AtomicInteger ddlCounter = new AtomicInteger();

    @Setup(Level.Trial)
    public void setUp() {
      youTrackDB = (YouTrackDBImpl) YourTracks.instance(
          DbTestBase.getBaseDirectoryPath(MatchContendedBenchmark.class));
      if (youTrackDB.exists(DB_NAME)) {
        youTrackDB.drop(DB_NAME);
      }
      youTrackDB.create(DB_NAME, DatabaseType.MEMORY,
          new LocalUserCredential("admin", "adminpwd", PredefinedLocalRole.ADMIN));

      var session = youTrackDB.open(DB_NAME, "admin", "adminpwd");
      session.getSchema().createVertexClass("Person");
      session.getSchema().createEdgeClass("Knows");

      var tx = session.begin();
      var center = tx.newVertex("Person");
      center.setProperty("name", "Alice");
      var bob = tx.newVertex("Person");
      bob.setProperty("name", "Bob");
      var carol = tx.newVertex("Person");
      carol.setProperty("name", "Carol");
      center.addStateFulEdge(bob, "Knows");
      center.addStateFulEdge(carol, "Knows");
      tx.commit();
      session.close();

      matchSql = "MATCH {class: Person, as: p, where: (name = 'Alice')}"
          + ".out('Knows'){as: f} RETURN f.name as friendName";
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
      session = db.youTrackDB.open(SharedDb.DB_NAME, "admin", "adminpwd");
    }

    @TearDown(Level.Trial)
    public void tearDown() {
      session.close();
    }
  }

  // ─── Benchmark 1: pure read-only, all threads doing MATCH ──────────

  @Benchmark
  @Group("readOnly")
  @GroupThreads(8)
  public void readOnlyMatch(SharedDb db, ThreadSession ts, Blackhole bh) {
    var tx = ts.session.begin();
    var rs = ts.session.query(db.matchSql);
    while (rs.hasNext()) {
      bh.consume(rs.next());
    }
    rs.close();
    tx.commit();
  }

  // ─── Benchmark 2: 7 reader threads + 1 DDL thread ─────────────────

  @Benchmark
  @Group("readersPlusDDL")
  @GroupThreads(7)
  public void readerMatch(SharedDb db, ThreadSession ts, Blackhole bh) {
    var tx = ts.session.begin();
    var rs = ts.session.query(db.matchSql);
    while (rs.hasNext()) {
      bh.consume(rs.next());
    }
    rs.close();
    tx.commit();
  }

  @Benchmark
  @Group("readersPlusDDL")
  @GroupThreads(1)
  public void ddlWorker(SharedDb db, ThreadSession ts, Blackhole bh) {
    // Create and immediately drop a vertex class to exercise the DDL lock path.
    // Each invocation uses a unique name to avoid "already exists" races.
    var className = "Tmp" + db.ddlCounter.incrementAndGet();
    ts.session.getSchema().createVertexClass(className);
    ts.session.getSchema().dropClass(className);
    bh.consume(className);
  }

  public static void main(String[] args) throws Exception {
    var opt = new OptionsBuilder()
        .include(MatchContendedBenchmark.class.getSimpleName())
        .build();
    new Runner(opt).run();
  }
}
