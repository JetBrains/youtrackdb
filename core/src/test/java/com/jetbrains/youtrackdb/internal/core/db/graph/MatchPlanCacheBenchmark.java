package com.jetbrains.youtrackdb.internal.core.db.graph;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
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
 * JMH benchmark isolating the MATCH execution plan caching overhead.
 *
 * <p>Uses a tiny graph (1 center + 2 outer vertices, 2 edges) so that
 * the actual traversal time is minimal and the planning overhead is
 * a large fraction of total execution time.
 *
 * <p>Compares three modes:
 * <ul>
 *   <li>{@code matchWithCache} — normal query path (plan cache enabled)
 *   <li>{@code matchNoCache} — bypasses the plan cache, forces re-planning every call
 *   <li>{@code matchNoCacheNoParams} — same query without parameters (no cache)
 * </ul>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(1)
public class MatchPlanCacheBenchmark {

  private static final String DB_NAME = "matchCacheBenchDb";

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded session;
  private RID centerRid;

  // Pre-parsed SQL statement for the no-cache benchmark
  private String matchSql;

  @Setup(Level.Trial)
  public void setUp() {
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPath(MatchPlanCacheBenchmark.class));
    if (youTrackDB.exists(DB_NAME)) {
      youTrackDB.drop(DB_NAME);
    }
    youTrackDB.create(DB_NAME, DatabaseType.MEMORY,
        new LocalUserCredential("admin", "adminpwd", PredefinedLocalRole.ADMIN));
    session = youTrackDB.open(DB_NAME, "admin", "adminpwd");

    session.getSchema().createVertexClass("Person");
    session.getSchema().createEdgeClass("Knows");

    // Tiny graph: center -> 2 outer vertices
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

    centerRid = center.getIdentity();
    matchSql = "MATCH {class: Person, as: p, where: (name = 'Alice')}"
        + ".out('Knows'){as: f} RETURN f.name as friendName";

    // Warm up the plan cache by running the query once
    var warmTx = session.begin();
    session.query(matchSql).close();
    warmTx.commit();
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    session.close();
    youTrackDB.close();
  }

  /**
   * Normal path: plan cache enabled. After the first call the plan is
   * retrieved from cache via a lightweight copy instead of re-planning.
   */
  @Benchmark
  public void matchWithCache(Blackhole bh) {
    var tx = session.begin();
    var rs = session.query(matchSql);
    while (rs.hasNext()) {
      bh.consume(rs.next());
    }
    rs.close();
    tx.commit();
  }

  /**
   * Bypass plan cache: forces a full re-plan on every invocation by
   * calling execute with usePlanCache=false.
   */
  @Benchmark
  public void matchNoCache(Blackhole bh) {
    var tx = session.begin();
    var stm = SQLEngine.parse(matchSql, session);
    var rs = stm.execute(session, (Object[]) null, false);
    while (rs.hasNext()) {
      bh.consume(rs.next());
    }
    rs.close();
    tx.commit();
  }

  public static void main(String[] args) throws Exception {
    var opt = new OptionsBuilder()
        .include(MatchPlanCacheBenchmark.class.getSimpleName())
        .build();
    new Runner(opt).run();
  }
}
